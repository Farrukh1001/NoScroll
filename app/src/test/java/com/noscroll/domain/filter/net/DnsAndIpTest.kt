package com.noscroll.domain.filter.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The packet code sits on the path every DNS lookup takes, so it is tested against hand-assembled
 * bytes — including deliberately broken ones. Anything malformed must return null so the packet is
 * forwarded untouched, never crash the tunnel.
 */
class DnsAndIpTest {

    // ---- DNS query parsing ------------------------------------------------------------------

    private fun dnsQuery(name: String, transactionId: Int = 0x1234, qType: Int = 1): ByteArray {
        val labels = name.split('.')
        val size = 12 + labels.sumOf { it.length + 1 } + 1 + 4
        val buffer = ByteArray(size)
        buffer[0] = (transactionId ushr 8).toByte()
        buffer[1] = (transactionId and 0xFF).toByte()
        buffer[2] = 0x01 // standard query, RD set
        buffer[3] = 0x00
        buffer[5] = 1    // QDCOUNT
        var offset = 12
        labels.forEach { label ->
            buffer[offset++] = label.length.toByte()
            label.forEach { buffer[offset++] = it.code.toByte() }
        }
        buffer[offset++] = 0
        buffer[offset++] = (qType ushr 8).toByte()
        buffer[offset++] = (qType and 0xFF).toByte()
        buffer[offset++] = 0
        buffer[offset] = 1
        return buffer
    }

    @Test
    fun `a query name is parsed`() {
        val query = DnsMessage.parseQuery(dnsQuery("adult.example"))
        assertNotNull(query)
        assertEquals("adult.example", query!!.name)
        assertEquals(0x1234, query.transactionId)
        assertEquals(1, query.qType)
    }

    @Test
    fun `a deep name is parsed`() {
        val query = DnsMessage.parseQuery(dnsQuery("a.b.c.d.adult.example"))
        assertEquals("a.b.c.d.adult.example", query!!.name)
    }

    @Test
    fun `truncated and malformed payloads return null`() {
        val valid = dnsQuery("adult.example")

        assertNull("too short", DnsMessage.parseQuery(ByteArray(4)))
        assertNull("truncated mid-name", DnsMessage.parseQuery(valid.copyOfRange(0, 15)))

        // A response, not a query.
        val response = valid.copyOf().also { it[2] = 0x81.toByte() }
        assertNull("QR set", DnsMessage.parseQuery(response))

        // Two questions: we only handle one.
        val twoQuestions = valid.copyOf().also { it[5] = 2 }
        assertNull("QDCOUNT 2", DnsMessage.parseQuery(twoQuestions))

        // A compression pointer in the question section is refused rather than chased.
        val pointer = valid.copyOf().also { it[12] = 0xC0.toByte() }
        assertNull("compression pointer", DnsMessage.parseQuery(pointer))

        // An over-long label.
        val longLabel = valid.copyOf().also { it[12] = 100 }
        assertNull("label longer than the payload", DnsMessage.parseQuery(longLabel))
    }

    @Test
    fun `the nxdomain response echoes the question and sets the right flags`() {
        val payload = dnsQuery("adult.example")
        val query = DnsMessage.parseQuery(payload)!!
        val response = DnsMessage.buildNxDomainResponse(payload, 0, query)

        // Transaction id preserved, so the resolver matches it to its request.
        assertEquals(payload[0], response[0])
        assertEquals(payload[1], response[1])

        val flags = ((response[2].toInt() and 0xFF) shl 8) or (response[3].toInt() and 0xFF)
        assertTrue("QR set", flags and 0x8000 != 0)
        assertTrue("RD preserved", flags and 0x0100 != 0)
        assertTrue("RA set", flags and 0x0080 != 0)
        assertEquals("NXDOMAIN", DnsMessage.RCODE_NXDOMAIN, flags and 0x000F)

        assertEquals("QDCOUNT 1", 1, ((response[4].toInt() and 0xFF) shl 8) or (response[5].toInt() and 0xFF))
        assertEquals("ANCOUNT 0", 0, ((response[6].toInt() and 0xFF) shl 8) or (response[7].toInt() and 0xFF))

        // The question is echoed byte for byte.
        val questionLength = query.questionEndOffset - DnsMessage.HEADER_BYTES
        repeat(questionLength) { index ->
            assertEquals(payload[12 + index], response[12 + index])
        }
    }

    // ---- IPv4 -----------------------------------------------------------------------------------

    private fun ipv4UdpPacket(payload: ByteArray, sourcePort: Int = 40000, destPort: Int = 53): ByteArray {
        val total = 20 + 8 + payload.size
        val packet = ByteArray(total)
        packet[0] = 0x45
        packet[2] = (total ushr 8).toByte()
        packet[3] = (total and 0xFF).toByte()
        packet[8] = 64
        packet[9] = 17 // UDP
        // 10.0.0.2 -> 10.111.222.2
        packet[12] = 10; packet[13] = 0; packet[14] = 0; packet[15] = 2
        packet[16] = 10; packet[17] = 111; packet[18] = 222.toByte(); packet[19] = 2
        packet[20] = (sourcePort ushr 8).toByte(); packet[21] = (sourcePort and 0xFF).toByte()
        packet[22] = (destPort ushr 8).toByte(); packet[23] = (destPort and 0xFF).toByte()
        val udpLength = 8 + payload.size
        packet[24] = (udpLength ushr 8).toByte(); packet[25] = (udpLength and 0xFF).toByte()
        payload.copyInto(packet, 28)
        return packet
    }

    @Test
    fun `an ipv4 udp datagram is parsed`() {
        val payload = dnsQuery("adult.example")
        val packet = ipv4UdpPacket(payload)
        val datagram = IpPacket.parseUdp(packet, packet.size)

        assertNotNull(datagram)
        assertEquals(IpPacket.VERSION_4, datagram!!.version)
        assertEquals(53, datagram.destinationPort)
        assertEquals(40000, datagram.sourcePort)
        assertEquals(payload.size, datagram.payloadLength)
        assertEquals("adult.example", DnsMessage.parseQuery(packet, datagram.payloadOffset, datagram.payloadLength)!!.name)
    }

    @Test
    fun `a fragment is refused so nothing is guessed from a partial datagram`() {
        val packet = ipv4UdpPacket(dnsQuery("adult.example"))
        packet[6] = 0x20 // more-fragments flag
        assertNull(IpPacket.parseUdp(packet, packet.size))
    }

    @Test
    fun `a non udp protocol is ignored`() {
        val packet = ipv4UdpPacket(dnsQuery("adult.example"))
        packet[9] = IpPacket.PROTOCOL_TCP.toByte()
        assertNull(IpPacket.parseUdp(packet, packet.size))
    }

    @Test
    fun `a truncated packet is refused`() {
        val packet = ipv4UdpPacket(dnsQuery("adult.example"))
        assertNull(IpPacket.parseUdp(packet, 12))
        assertNull(IpPacket.parseUdp(ByteArray(0), 0))
    }

    @Test
    fun `the ipv4 response swaps addresses and ports and checksums correctly`() {
        val request = IpPacket.parseUdp(ipv4UdpPacket(dnsQuery("adult.example")), ipv4UdpPacket(dnsQuery("adult.example")).size)!!
        val body = byteArrayOf(1, 2, 3, 4)
        val response = IpPacket.buildUdpResponse(request, body)

        assertEquals(20 + 8 + body.size, response.size)
        // Source of the reply is the address the query went to.
        assertEquals(10, response[12].toInt())
        assertEquals(111, response[13].toInt())
        // Destination is where the query came from.
        assertEquals(10, response[16].toInt())
        assertEquals(0, response[17].toInt())
        // Ports swapped.
        assertEquals(53, ((response[20].toInt() and 0xFF) shl 8) or (response[21].toInt() and 0xFF))
        assertEquals(40000, ((response[22].toInt() and 0xFF) shl 8) or (response[23].toInt() and 0xFF))

        // A correct header checksum makes the whole header sum to zero.
        assertEquals("ipv4 header checksum", 0, IpPacket.checksum(response, 0, 20))
    }

    // ---- IPv6 -----------------------------------------------------------------------------------

    private fun ipv6UdpPacket(payload: ByteArray): ByteArray {
        val udpLength = 8 + payload.size
        val packet = ByteArray(40 + udpLength)
        packet[0] = 0x60
        packet[4] = (udpLength ushr 8).toByte()
        packet[5] = (udpLength and 0xFF).toByte()
        packet[6] = 17
        packet[7] = 64
        packet[8] = 0xfd.toByte(); packet[23] = 1  // source ...::1
        packet[24] = 0xfd.toByte(); packet[39] = 2 // destination ...::2
        packet[40] = 0x9c.toByte(); packet[41] = 0x40 // source port 40000
        packet[42] = 0; packet[43] = 53
        packet[44] = (udpLength ushr 8).toByte(); packet[45] = (udpLength and 0xFF).toByte()
        payload.copyInto(packet, 48)
        return packet
    }

    @Test
    fun `an ipv6 udp datagram is parsed`() {
        val payload = dnsQuery("adult.example")
        val packet = ipv6UdpPacket(payload)
        val datagram = IpPacket.parseUdp(packet, packet.size)

        assertNotNull(datagram)
        assertEquals(IpPacket.VERSION_6, datagram!!.version)
        assertEquals(53, datagram.destinationPort)
        assertEquals(payload.size, datagram.payloadLength)
        assertEquals(16, datagram.sourceAddress.size)
    }

    @Test
    fun `the ipv6 response carries a mandatory non-zero checksum`() {
        val packet = ipv6UdpPacket(dnsQuery("adult.example"))
        val request = IpPacket.parseUdp(packet, packet.size)!!
        val response = IpPacket.buildUdpResponse(request, byteArrayOf(9, 9))

        assertEquals(0x60, response[0].toInt() and 0xF0 or 0x60 and 0xFF)
        val checksum = ((response[46].toInt() and 0xFF) shl 8) or (response[47].toInt() and 0xFF)
        // IPv6 has no header checksum, so a zero UDP checksum would be illegal.
        assertTrue("udp checksum must not be zero over ipv6", checksum != 0)
    }

    @Test
    fun `a truncated ipv6 packet is refused`() {
        val packet = ipv6UdpPacket(dnsQuery("adult.example"))
        assertNull(IpPacket.parseUdp(packet, 30))
    }

    @Test
    fun `an unknown ip version is ignored`() {
        val packet = ByteArray(60).also { it[0] = 0x70 }
        assertNull(IpPacket.parseUdp(packet, packet.size))
    }
}
