package com.wendigo.suzgec.core

object DnsWire {

    const val HEADER_SIZE = 12
    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val RCODE_NXDOMAIN = 3

    fun isResponse(msg: ByteArray, len: Int = msg.size): Boolean =
        len >= 4 && (msg[2].toInt() and 0x80) != 0

    fun transactionId(msg: ByteArray): Int {
        if (msg.size < 2) return 0
        return ((msg[0].toInt() and 0xFF) shl 8) or (msg[1].toInt() and 0xFF)
    }

    fun readU16(msg: ByteArray, pos: Int): Int {
        if (pos + 1 >= msg.size) return 0
        return ((msg[pos].toInt() and 0xFF) shl 8) or (msg[pos + 1].toInt() and 0xFF)
    }

    fun setU16(msg: ByteArray, pos: Int, value: Int) {
        msg[pos] = ((value ushr 8) and 0xFF).toByte()
        msg[pos + 1] = (value and 0xFF).toByte()
    }

    fun questionEnd(msg: ByteArray, len: Int = msg.size): Int? {
        if (len < HEADER_SIZE + 5) return null
        var pos = HEADER_SIZE
        var labels = 0
        while (pos < len) {
            val l = msg[pos].toInt() and 0xFF
            if (l == 0) {
                val end = pos + 1 + 4
                return if (end <= len) end else null
            }
            if ((l and 0xC0) != 0) return null
            if (l > 63) return null
            pos += 1 + l
            labels++
            if (labels > 127) return null
        }
        return null
    }

    fun questionName(msg: ByteArray, len: Int = msg.size): String? {
        if (len < HEADER_SIZE) return null
        val qEnd = questionEnd(msg, len) ?: return null
        val sb = StringBuilder()
        var pos = HEADER_SIZE
        while (pos < qEnd - 4) {
            val l = msg[pos].toInt() and 0xFF
            if (l == 0) break
            if (pos + 1 + l > qEnd - 4) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 0 until l) {
                val c = msg[pos + 1 + i].toInt() and 0xFF
                if (c <= 0x20 || c >= 0x7F) return null
                sb.append(if (c in 65..90) (c + 32).toChar() else c.toChar())
            }
            pos += 1 + l
        }
        if (sb.isEmpty()) return null
        return sb.toString()
    }

    fun queryType(msg: ByteArray, questionEnd: Int): Int {
        if (questionEnd < 4 || questionEnd > msg.size) return 0
        return readU16(msg, questionEnd - 4)
    }

    fun wasTruncated(msg: ByteArray, len: Int): Boolean =
        len >= 4 && (msg[2].toInt() and 0x02) != 0

    fun buildError(query: ByteArray, len: Int, rcode: Int): ByteArray {
        val qEnd = questionEnd(query, len)
        if (qEnd == null) {
            val out = ByteArray(HEADER_SIZE)
            if (len >= 2) {
                out[0] = query[0]
                out[1] = query[1]
            }
            val rd = if (len >= 4) (query[2].toInt() and 0x01) else 0
            var flags = 0x8000 or 0x0080 or rd
            flags = flags or (rcode and 0x0F)
            setU16(out, 2, flags)
            setU16(out, 4, 0)
            setU16(out, 6, 0)
            setU16(out, 8, 0)
            setU16(out, 10, 0)
            return out
        }
        val out = ByteArray(qEnd)
        System.arraycopy(query, 0, out, 0, qEnd)
        val rd = query[2].toInt() and 0x01
        val opcode = query[2].toInt() and 0x78
        var flags = 0x8000 or 0x0400 or 0x0080 or rd or opcode
        flags = flags or (rcode and 0x0F)
        setU16(out, 2, flags)
        setU16(out, 4, 1)
        setU16(out, 6, 0)
        setU16(out, 8, 0)
        setU16(out, 10, 0)
        return out
    }

    fun buildAddressAnswer(query: ByteArray, len: Int, ipv4: ByteArray): ByteArray? {
        val qEnd = questionEnd(query, len) ?: return null
        val type = queryType(query, qEnd)
        val answerLen = if (type == TYPE_A) 16 else 0
        val out = ByteArray(qEnd + answerLen)
        System.arraycopy(query, 0, out, 0, qEnd)
        val rd = query[2].toInt() and 0x01
        var flags = 0x8000 or 0x0400 or 0x0080 or rd
        setU16(out, 2, flags)
        setU16(out, 4, 1)
        setU16(out, 6, if (answerLen > 0) 1 else 0)
        setU16(out, 8, 0)
        setU16(out, 10, 0)
        if (answerLen > 0) {
            var p = qEnd
            out[p++] = 0xC0.toByte()
            out[p++] = 0x0C
            setU16(out, p, TYPE_A); p += 2
            setU16(out, p, 1); p += 2
            setU16(out, p, 60); p += 2
            setU16(out, p, 4); p += 2
            System.arraycopy(ipv4, 0, out, p, 4)
        }
        return out
    }

    fun buildQuery(name: String, type: Int = TYPE_A, id: Int = 0x5375): ByteArray {
        val labels = name.trim().split('.').filter { it.isNotEmpty() }
        var size = HEADER_SIZE + 1 + 4
        for (label in labels) size += 1 + label.length
        val out = ByteArray(size)
        setU16(out, 0, id)
        setU16(out, 2, 0x0100)
        setU16(out, 4, 1)
        setU16(out, 6, 0)
        setU16(out, 8, 0)
        setU16(out, 10, 0)
        var p = HEADER_SIZE
        for (label in labels) {
            out[p++] = label.length.toByte()
            for (ch in label) out[p++] = ch.code.toByte()
        }
        out[p++] = 0
        setU16(out, p, type)
        p += 2
        setU16(out, p, 1)
        return out
    }

    fun rcode(msg: ByteArray): Int =
        if (msg.size < 4) -1 else msg[3].toInt() and 0x0F
}
