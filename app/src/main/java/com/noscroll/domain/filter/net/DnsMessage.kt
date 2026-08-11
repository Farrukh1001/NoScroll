package com.noscroll.domain.filter.net

/**
 * Just enough DNS to read the name being asked for and to answer "no such name".
 *
 * NoScroll never rewrites answers or points a name at a server of its own — a blocked lookup gets an
 * NXDOMAIN and nothing else, which is what makes the filter fail in the direction of "the site does
 * not resolve" rather than "the site resolves somewhere we control".
 *
 * The parser is written to be hostile-input safe: every read is bounds-checked, compression pointers
 * in the question section are refused, and anything malformed returns null so the packet is simply
 * forwarded untouched instead of crashing the tunnel.
 */
object DnsMessage {

    const val HEADER_BYTES = 12
    private const val MAX_NAME_LENGTH = 255
    private const val MAX_LABEL_LENGTH = 63

    const val RCODE_NXDOMAIN = 3

    data class Query(
        val transactionId: Int,
        val flags: Int,
        val name: String,
        val qType: Int,
        val qClass: Int,
        /** Offset just past the question section, used when echoing the question back. */
        val questionEndOffset: Int,
    )

    /**
     * Reads the first question out of a DNS payload. Returns null for anything that is not a
     * single-question standard query, which the caller forwards unmodified.
     */
    fun parseQuery(payload: ByteArray, offset: Int = 0, length: Int = payload.size - offset): Query? {
        if (length < HEADER_BYTES) return null
        val end = offset + length

        val transactionId = readUShort(payload, offset) ?: return null
        val flags = readUShort(payload, offset + 2) ?: return null

        // QR bit must be 0 (a query) and we only handle a single question.
        if (flags and 0x8000 != 0) return null
        val qdCount = readUShort(payload, offset + 4) ?: return null
        if (qdCount != 1) return null

        var cursor = offset + HEADER_BYTES
        val labels = mutableListOf<String>()
        var totalLength = 0

        while (true) {
            if (cursor >= end) return null
            val labelLength = payload[cursor].toInt() and 0xFF
            if (labelLength == 0) {
                cursor += 1
                break
            }
            // A compression pointer has no business in a question; refuse rather than chase it.
            if (labelLength and 0xC0 != 0) return null
            if (labelLength > MAX_LABEL_LENGTH) return null
            cursor += 1
            if (cursor + labelLength > end) return null
            totalLength += labelLength + 1
            if (totalLength > MAX_NAME_LENGTH) return null
            labels += String(payload, cursor, labelLength, Charsets.US_ASCII)
            cursor += labelLength
        }

        if (labels.isEmpty()) return null

        val qType = readUShort(payload, cursor) ?: return null
        val qClass = readUShort(payload, cursor + 2) ?: return null
        cursor += 4
        if (cursor > end) return null

        return Query(
            transactionId = transactionId,
            flags = flags,
            name = labels.joinToString("."),
            qType = qType,
            qClass = qClass,
            questionEndOffset = cursor - offset,
        )
    }

    /**
     * Builds an NXDOMAIN reply that echoes the original question.
     *
     * RD is copied from the query and RA is set, so a resolver library sees an ordinary
     * authoritative-looking negative answer rather than a malformed packet.
     */
    fun buildNxDomainResponse(payload: ByteArray, offset: Int, query: Query): ByteArray {
        val questionBytes = query.questionEndOffset - HEADER_BYTES
        val response = ByteArray(HEADER_BYTES + questionBytes)

        writeUShort(response, 0, query.transactionId)

        var flags = 0x8000                      // QR = response
        flags = flags or (query.flags and 0x7800) // preserve opcode
        flags = flags or (query.flags and 0x0100) // preserve RD
        flags = flags or 0x0080                 // RA = recursion available
        flags = flags or RCODE_NXDOMAIN
        writeUShort(response, 2, flags)

        writeUShort(response, 4, 1) // QDCOUNT
        writeUShort(response, 6, 0) // ANCOUNT
        writeUShort(response, 8, 0) // NSCOUNT
        writeUShort(response, 10, 0) // ARCOUNT

        System.arraycopy(payload, offset + HEADER_BYTES, response, HEADER_BYTES, questionBytes)
        return response
    }

    private fun readUShort(buffer: ByteArray, index: Int): Int? {
        if (index < 0 || index + 1 >= buffer.size) return null
        return ((buffer[index].toInt() and 0xFF) shl 8) or (buffer[index + 1].toInt() and 0xFF)
    }

    private fun writeUShort(buffer: ByteArray, index: Int, value: Int) {
        buffer[index] = ((value ushr 8) and 0xFF).toByte()
        buffer[index + 1] = (value and 0xFF).toByte()
    }
}
