package com.noscroll.domain.filter.net

/**
 * The small slice of IP/UDP handling the DNS filter needs.
 *
 * NoScroll routes only the tunnel's own DNS address into the TUN device, so the only packets that
 * ever reach this code are DNS datagrams. That is why there is no TCP state machine and no NAT
 * table here: everything else keeps using the normal network path untouched, which both keeps the
 * implementation honest and keeps ordinary connectivity intact.
 *
 * IPv4 and IPv6 are both handled because a modern mobile network will happily hand out either.
 */
object IpPacket {

    const val VERSION_4 = 4
    const val VERSION_6 = 6

    const val PROTOCOL_TCP = 6
    const val PROTOCOL_UDP = 17

    data class UdpDatagram(
        val version: Int,
        val sourceAddress: ByteArray,
        val destinationAddress: ByteArray,
        val sourcePort: Int,
        val destinationPort: Int,
        val payloadOffset: Int,
        val payloadLength: Int,
    ) {
        // Data classes with ByteArray members need these spelled out to compare by content.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is UdpDatagram) return false
            return version == other.version &&
                sourceAddress.contentEquals(other.sourceAddress) &&
                destinationAddress.contentEquals(other.destinationAddress) &&
                sourcePort == other.sourcePort &&
                destinationPort == other.destinationPort &&
                payloadOffset == other.payloadOffset &&
                payloadLength == other.payloadLength
        }

        override fun hashCode(): Int {
            var result = version
            result = 31 * result + sourceAddress.contentHashCode()
            result = 31 * result + destinationAddress.contentHashCode()
            result = 31 * result + sourcePort
            result = 31 * result + destinationPort
            result = 31 * result + payloadOffset
            result = 31 * result + payloadLength
            return result
        }
    }

    fun version(packet: ByteArray, length: Int): Int =
        if (length < 1) 0 else (packet[0].toInt() and 0xF0) ushr 4

    /**
     * Parses an IPv4 or IPv6 packet carrying UDP. Returns null for anything else — including
     * fragments and unexpected extension-header chains — so the caller forwards it untouched.
     */
    fun parseUdp(packet: ByteArray, length: Int): UdpDatagram? = when (version(packet, length)) {
        VERSION_4 -> parseIpv4Udp(packet, length)
        VERSION_6 -> parseIpv6Udp(packet, length)
        else -> null
    }

    private fun parseIpv4Udp(packet: ByteArray, length: Int): UdpDatagram? {
        if (length < 20) return null
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < 20 || ihl > length) return null
        if ((packet[9].toInt() and 0xFF) != PROTOCOL_UDP) return null

        // Refuse fragments: the DNS query might not be in this one.
        val flagsAndFragment = readUShort(packet, 6)
        val moreFragments = flagsAndFragment and 0x2000 != 0
        val fragmentOffset = flagsAndFragment and 0x1FFF
        if (moreFragments || fragmentOffset != 0) return null

        if (ihl + 8 > length) return null
        val udpLength = readUShort(packet, ihl + 4)
        if (udpLength < 8 || ihl + udpLength > length) return null

        return UdpDatagram(
            version = VERSION_4,
            sourceAddress = packet.copyOfRange(12, 16),
            destinationAddress = packet.copyOfRange(16, 20),
            sourcePort = readUShort(packet, ihl),
            destinationPort = readUShort(packet, ihl + 2),
            payloadOffset = ihl + 8,
            payloadLength = udpLength - 8,
        )
    }

    private fun parseIpv6Udp(packet: ByteArray, length: Int): UdpDatagram? {
        if (length < 40) return null
        // Only a bare UDP next-header is handled; extension chains are forwarded untouched.
        if ((packet[6].toInt() and 0xFF) != PROTOCOL_UDP) return null
        val payloadLength = readUShort(packet, 4)
        if (40 + payloadLength > length) return null
        if (payloadLength < 8) return null

        val udpLength = readUShort(packet, 44)
        if (udpLength < 8 || 40 + udpLength > length) return null

        return UdpDatagram(
            version = VERSION_6,
            sourceAddress = packet.copyOfRange(8, 24),
            destinationAddress = packet.copyOfRange(24, 40),
            sourcePort = readUShort(packet, 40),
            destinationPort = readUShort(packet, 42),
            payloadOffset = 48,
            payloadLength = udpLength - 8,
        )
    }

    /**
     * Wraps [payload] in a UDP datagram sent back to whoever sent [request], i.e. source and
     * destination swapped. Used to hand an NXDOMAIN back to the app that asked.
     */
    fun buildUdpResponse(request: UdpDatagram, payload: ByteArray): ByteArray =
        if (request.version == VERSION_4) buildIpv4(request, payload) else buildIpv6(request, payload)

    private fun buildIpv4(request: UdpDatagram, payload: ByteArray): ByteArray {
        val totalLength = 20 + 8 + payload.size
        val packet = ByteArray(totalLength)

        packet[0] = 0x45                       // version 4, IHL 5
        packet[1] = 0                          // DSCP/ECN
        writeUShort(packet, 2, totalLength)
        writeUShort(packet, 4, 0)              // identification
        writeUShort(packet, 6, 0x4000)         // don't fragment
        packet[8] = 64                         // TTL
        packet[9] = PROTOCOL_UDP.toByte()
        // Swap: the reply comes from the address the query was sent to.
        System.arraycopy(request.destinationAddress, 0, packet, 12, 4)
        System.arraycopy(request.sourceAddress, 0, packet, 16, 4)
        writeUShort(packet, 10, 0)
        writeUShort(packet, 10, checksum(packet, 0, 20))

        val udpOffset = 20
        writeUShort(packet, udpOffset, request.destinationPort)
        writeUShort(packet, udpOffset + 2, request.sourcePort)
        writeUShort(packet, udpOffset + 4, 8 + payload.size)
        writeUShort(packet, udpOffset + 6, 0)
        System.arraycopy(payload, 0, packet, udpOffset + 8, payload.size)

        val udpChecksum = udpChecksum(
            source = request.destinationAddress,
            destination = request.sourceAddress,
            packet = packet,
            udpOffset = udpOffset,
            udpLength = 8 + payload.size,
        )
        // A zero UDP checksum means "not computed" in IPv4, so 0 is transmitted as 0xFFFF.
        writeUShort(packet, udpOffset + 6, if (udpChecksum == 0) 0xFFFF else udpChecksum)
        return packet
    }

    private fun buildIpv6(request: UdpDatagram, payload: ByteArray): ByteArray {
        val udpLength = 8 + payload.size
        val packet = ByteArray(40 + udpLength)

        packet[0] = 0x60                       // version 6
        writeUShort(packet, 4, udpLength)      // payload length
        packet[6] = PROTOCOL_UDP.toByte()      // next header
        packet[7] = 64                         // hop limit
        System.arraycopy(request.destinationAddress, 0, packet, 8, 16)
        System.arraycopy(request.sourceAddress, 0, packet, 24, 16)

        val udpOffset = 40
        writeUShort(packet, udpOffset, request.destinationPort)
        writeUShort(packet, udpOffset + 2, request.sourcePort)
        writeUShort(packet, udpOffset + 4, udpLength)
        writeUShort(packet, udpOffset + 6, 0)
        System.arraycopy(payload, 0, packet, udpOffset + 8, payload.size)

        val udpChecksum = udpChecksum(
            source = request.destinationAddress,
            destination = request.sourceAddress,
            packet = packet,
            udpOffset = udpOffset,
            udpLength = udpLength,
        )
        // IPv6 has no header checksum, so the UDP checksum is mandatory and may never be zero.
        writeUShort(packet, udpOffset + 6, if (udpChecksum == 0) 0xFFFF else udpChecksum)
        return packet
    }

    /** Standard internet checksum (RFC 1071) over a byte range. */
    fun checksum(buffer: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var index = offset
        val end = offset + length
        while (index + 1 < end) {
            sum += ((buffer[index].toInt() and 0xFF) shl 8) or (buffer[index + 1].toInt() and 0xFF)
            index += 2
        }
        if (index < end) sum += (buffer[index].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    /** UDP checksum over the pseudo-header plus the datagram, for both address families. */
    private fun udpChecksum(
        source: ByteArray,
        destination: ByteArray,
        packet: ByteArray,
        udpOffset: Int,
        udpLength: Int,
    ): Int {
        var sum = 0L
        fun addBytes(bytes: ByteArray) {
            var i = 0
            while (i + 1 < bytes.size) {
                sum += ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
                i += 2
            }
            if (i < bytes.size) sum += (bytes[i].toInt() and 0xFF) shl 8
        }
        addBytes(source)
        addBytes(destination)
        sum += udpLength.toLong()
        sum += PROTOCOL_UDP.toLong()

        var index = udpOffset
        val end = udpOffset + udpLength
        while (index + 1 < end) {
            sum += ((packet[index].toInt() and 0xFF) shl 8) or (packet[index + 1].toInt() and 0xFF)
            index += 2
        }
        if (index < end) sum += (packet[index].toInt() and 0xFF) shl 8

        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun readUShort(buffer: ByteArray, index: Int): Int =
        ((buffer[index].toInt() and 0xFF) shl 8) or (buffer[index + 1].toInt() and 0xFF)

    private fun writeUShort(buffer: ByteArray, index: Int, value: Int) {
        buffer[index] = ((value ushr 8) and 0xFF).toByte()
        buffer[index + 1] = (value and 0xFF).toByte()
    }
}
