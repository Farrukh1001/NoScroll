package com.noscroll.service.vpn

import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.noscroll.NoScrollApplication
import com.noscroll.R
import com.noscroll.di.AppContainer
import com.noscroll.domain.filter.RuleSet
import com.noscroll.domain.filter.net.DnsMessage
import com.noscroll.domain.filter.net.IpPacket
import com.noscroll.domain.health.FilterStatus
import com.noscroll.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Explicit Content Shield's network layer: a real, working DNS filter.
 *
 * **What it actually does, stated plainly.** The tunnel does not carry the device's traffic. It
 * advertises a DNS server at a private address and routes *only that address* into the TUN device.
 * Everything else — every web page, video, and app connection — keeps using the normal network path
 * and is never touched. A DNS query for a blocked name is answered with NXDOMAIN; every other query
 * is forwarded to the network's own resolver and its reply handed back unchanged.
 *
 * **Why it is built this way.** Filtering by name is the honest place to filter. A full userspace
 * TCP/IP stack would mean re-implementing NAT and TCP in this process, adding a large third-party
 * networking dependency, and putting every byte the user sends through code that has to be perfect
 * or their phone stops working. Routing a single address gets the same content-blocking result with
 * a fraction of the blast radius, no third-party dependency, and no plausible way for a bug here to
 * break ordinary connectivity.
 *
 * **What it explicitly does not do.** No traffic is uploaded anywhere. No browsing history is kept —
 * not the domains that were allowed, and not the ones that were blocked. There is no TLS
 * interception, no custom CA, and no plaintext logging. A blocked lookup increments an anonymous
 * counter and nothing else.
 *
 * **Documented limitations** (also in README and THREAT_MODEL): a browser or app using DNS-over-HTTPS
 * or DNS-over-TLS bypasses this entirely, as does one that connects to a hardcoded IP address;
 * Encrypted Client Hello hides the hostname from any network-level filter. Those cases are why the
 * accessibility browser guard exists as a second layer, and why fail-closed mode blocks supported
 * browsers when the tunnel is down instead of pretending everything is covered.
 */
class NoScrollVpnService : VpnService() {

    private var tunnel: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private val running = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var container: AppContainer

    @Volatile
    private var ruleSet: RuleSet = RuleSet.EMPTY

    override fun onCreate() {
        super.onCreate()
        container = (application as NoScrollApplication).container
        scope.launch {
            ruleSet = container.ruleRepository.ruleSet()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NoScrollApplication.NOTIFICATION_ID_FILTER, buildStatusNotification())
        startTunnel()
        // START_STICKY so Android brings the filter back after a low-memory kill.
        return START_STICKY
    }

    private fun startTunnel() {
        if (running.get()) return

        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(MTU)
            // A private address for the tunnel itself, plus the resolver address we advertise.
            .addAddress(TUN_ADDRESS_V4, 32)
            .addDnsServer(DNS_ADDRESS_V4)
            // Route ONLY the resolver. Nothing else enters the tunnel.
            .addRoute(DNS_ADDRESS_V4, 32)

        runCatching {
            builder.addAddress(TUN_ADDRESS_V6, 128)
            builder.addDnsServer(DNS_ADDRESS_V6)
            builder.addRoute(DNS_ADDRESS_V6, 128)
        }

        // NoScroll's own traffic must not loop back through its own tunnel.
        runCatching { builder.addDisallowedApplication(packageName) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        builder.setConfigureIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )

        val descriptor = runCatching { builder.establish() }.getOrNull()
        if (descriptor == null) {
            // Consent revoked, another VPN took over, or the platform refused. Say so; do not
            // pretend the filter is running.
            container.protectionHealth.setFilterStatus(FilterStatus.DOWN)
            container.protectionHealth.recordError("vpn_establish_failed")
            stopSelf()
            return
        }

        tunnel = descriptor
        running.set(true)
        container.protectionHealth.setFilterStatus(FilterStatus.ACTIVE)

        worker = Thread({ pumpPackets(descriptor) }, "noscroll-dns").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * The packet loop.
     *
     * Only DNS reaches it, so the body is short: read a datagram, decide, either answer NXDOMAIN or
     * forward upstream and relay the reply.
     */
    private fun pumpPackets(descriptor: ParcelFileDescriptor) {
        val input = FileInputStream(descriptor.fileDescriptor)
        val output = FileOutputStream(descriptor.fileDescriptor)
        val buffer = ByteArray(MTU)

        DatagramSocket().use { upstream ->
            // Protecting the socket keeps our own forwarded queries out of the tunnel.
            if (!protect(upstream)) {
                container.protectionHealth.recordError("vpn_protect_failed")
            }
            upstream.soTimeout = UPSTREAM_TIMEOUT_MILLIS

            while (running.get()) {
                val length = runCatching { input.read(buffer) }.getOrElse { -1 }
                if (length <= 0) {
                    if (length < 0) break else continue
                }
                runCatching { handlePacket(buffer, length, upstream, output) }
                    .onFailure { container.protectionHealth.recordError("vpn_packet_error") }
            }
        }
    }

    private fun handlePacket(
        packet: ByteArray,
        length: Int,
        upstream: DatagramSocket,
        output: FileOutputStream,
    ) {
        val datagram = IpPacket.parseUdp(packet, length) ?: return
        if (datagram.destinationPort != DNS_PORT) return

        val query = DnsMessage.parseQuery(packet, datagram.payloadOffset, datagram.payloadLength)
        if (query == null) {
            // Not a query we understand. Forward it verbatim rather than guess.
            forward(packet, datagram, upstream, output)
            return
        }

        if (ruleSet.isBlocked(query.name)) {
            // Counted, never recorded. The name that was blocked is not stored anywhere.
            container.protectionHealth.recordBlockedAttempt()
            val response = DnsMessage.buildNxDomainResponse(packet, datagram.payloadOffset, query)
            output.write(IpPacket.buildUdpResponse(datagram, response))
            output.flush()
            return
        }

        forward(packet, datagram, upstream, output)
    }

    /** Relays a query to the network's own resolver and writes the reply back into the tunnel. */
    private fun forward(
        packet: ByteArray,
        datagram: IpPacket.UdpDatagram,
        upstream: DatagramSocket,
        output: FileOutputStream,
    ) {
        val resolver = upstreamResolver(datagram.version) ?: return
        val payload = packet.copyOfRange(datagram.payloadOffset, datagram.payloadOffset + datagram.payloadLength)

        runCatching {
            upstream.send(DatagramPacket(payload, payload.size, InetSocketAddress(resolver, DNS_PORT)))
            val replyBuffer = ByteArray(MTU)
            val reply = DatagramPacket(replyBuffer, replyBuffer.size)
            upstream.receive(reply)
            output.write(
                IpPacket.buildUdpResponse(datagram, replyBuffer.copyOfRange(0, reply.length))
            )
            output.flush()
        }.onFailure {
            // A timeout is normal on a flaky network; the resolver library retries. Nothing is
            // logged about which name failed.
            container.protectionHealth.recordError("dns_upstream_timeout")
        }
    }

    /**
     * The resolver to forward to: the underlying network's own DNS server.
     *
     * Using the network's resolver rather than a hardcoded public one means NoScroll does not
     * quietly redirect the user's DNS to a third party, and captive portals keep working. The
     * fallback only applies when the platform reports no resolver at all.
     */
    private fun upstreamResolver(ipVersion: Int): InetAddress? {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val servers = runCatching {
            val network = connectivity?.activeNetwork
            connectivity?.getLinkProperties(network)?.dnsServers.orEmpty()
        }.getOrDefault(emptyList())

        val match = servers.firstOrNull { address ->
            if (ipVersion == IpPacket.VERSION_4) address is Inet4Address else address is Inet6Address
        }
        if (match != null) return match

        return runCatching {
            if (ipVersion == IpPacket.VERSION_4) {
                InetAddress.getByName(FALLBACK_DNS_V4)
            } else {
                InetAddress.getByName(FALLBACK_DNS_V6)
            }
        }.getOrNull()
    }

    private fun buildStatusNotification() =
        NotificationCompat.Builder(this, NoScrollApplication.CHANNEL_STATUS)
            .setContentTitle(getString(R.string.filter_running_title))
            .setContentText(getString(R.string.filter_running_body))
            .setSmallIcon(R.drawable.ic_shield_status)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()

    /** Called by the platform when the user revokes VPN consent or another VPN takes over. */
    override fun onRevoke() {
        container.protectionHealth.setFilterStatus(FilterStatus.DOWN)
        container.protectionHealth.recordError("vpn_revoked")
        stopTunnel()
        super.onRevoke()
    }

    private fun stopTunnel() {
        running.set(false)
        runCatching { worker?.interrupt() }
        worker = null
        runCatching { tunnel?.close() }
        tunnel = null
        container.protectionHealth.setFilterStatus(FilterStatus.DOWN)
    }

    override fun onDestroy() {
        stopTunnel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.noscroll.action.STOP_FILTER"

        const val MTU = 1500
        const val DNS_PORT = 53
        const val UPSTREAM_TIMEOUT_MILLIS = 5_000

        // Link-local-ish private addresses used only inside the tunnel.
        const val TUN_ADDRESS_V4 = "10.111.222.1"
        const val DNS_ADDRESS_V4 = "10.111.222.2"
        const val TUN_ADDRESS_V6 = "fd00:6e6f:7363:1::1"
        const val DNS_ADDRESS_V6 = "fd00:6e6f:7363:1::2"

        /** Only used when the platform reports no resolver for the active network. */
        const val FALLBACK_DNS_V4 = "9.9.9.9"
        const val FALLBACK_DNS_V6 = "2620:fe::fe"

        fun start(context: android.content.Context) {
            // minSdk is 26, so the foreground-service path is always the right one.
            context.startForegroundService(Intent(context, NoScrollVpnService::class.java))
        }

        fun stop(context: android.content.Context) {
            context.startService(Intent(context, NoScrollVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
