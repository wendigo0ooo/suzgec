package com.wendigo.suzgec.core

class DnsEngine(
    private val zeroAddressMode: Boolean,
    private val upstream: Upstream,
    private val rulesProvider: () -> FilterRules,
    private val onResult: (host: String?, blocked: Boolean) -> Unit = { _, _ -> },
    private val onError: (String) -> Unit = {}
) {

    fun handle(query: ByteArray, length: Int = query.size): ByteArray? {
        if (length < DnsWire.HEADER_SIZE) return null
        if (DnsWire.isResponse(query, length)) return null

        val questionEnd = DnsWire.questionEnd(query, length)
        if (questionEnd == null) {
            onError("Bozuk DNS sorgusu")
            return DnsWire.buildError(query, length, 1)
        }
        val host = DnsWire.questionName(query, length)
        if (host == null) {
            onError("Sorgu adı okunamadı")
            return DnsWire.buildError(query, length, 1)
        }

        val rules = try {
            rulesProvider()
        } catch (_: Exception) {
            FilterRules.EMPTY
        }
        val verdict = rules.verdict(host)
        if (verdict == FilterRules.Verdict.BLOCK) {
            onResult(host, true)
            return blockResponse(query, length)
        }

        val packet = if (query.size == length) query else query.copyOf(length)
        val answer = try {
            upstream.query(packet)
        } catch (_: Exception) {
            null
        }
        if (answer == null || answer.size < DnsWire.HEADER_SIZE) {
            return DnsWire.buildError(query, length, 2)
        }
        if (DnsWire.transactionId(answer) != DnsWire.transactionId(packet)) {
            return DnsWire.buildError(query, length, 2)
        }

        if (verdict != FilterRules.Verdict.ALLOW) {
            val hidden = try {
                DnsWire.cnameTargets(answer).any {
                    rules.verdict(it) == FilterRules.Verdict.BLOCK
                }
            } catch (_: Exception) {
                false
            }
            if (hidden) {
                onResult(host, true)
                return blockResponse(query, length)
            }
        }

        onResult(host, false)
        return answer
    }

    fun close() {
        try {
            upstream.close()
        } catch (_: Exception) {
        }
    }

    private fun blockResponse(query: ByteArray, length: Int): ByteArray {
        return if (zeroAddressMode) {
            DnsWire.buildAddressAnswer(query, length, byteArrayOf(0, 0, 0, 0))
                ?: DnsWire.buildError(query, length, DnsWire.RCODE_NXDOMAIN)
        } else {
            DnsWire.buildError(query, length, DnsWire.RCODE_NXDOMAIN)
        }
    }
}
