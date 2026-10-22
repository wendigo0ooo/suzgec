package com.wendigo.suzgec.core

class FilterRules(
    private val block: Set<String>,
    private val allow: Set<String>,
    private val important: Set<String>
) {

    enum class Verdict { BLOCK, ALLOW, PASS }

    val size: Int get() = block.size + allow.size + important.size
    val blockCount: Int get() = block.size
    val allowCount: Int get() = allow.size

    fun verdict(host: String): Verdict {
        val h = normalize(host) ?: return Verdict.PASS
        if (matches(important, h)) return Verdict.BLOCK
        if (matches(allow, h)) return Verdict.ALLOW
        if (matches(block, h)) return Verdict.BLOCK
        return Verdict.PASS
    }

    fun isBlocked(host: String): Boolean = verdict(host) == Verdict.BLOCK

    private fun matches(set: Set<String>, host: String): Boolean {
        if (set.isEmpty()) return false
        var s = host
        while (true) {
            if (set.contains(s)) return true
            val dot = s.indexOf('.')
            if (dot < 0) return false
            s = s.substring(dot + 1)
        }
    }

    companion object {
        val EMPTY = FilterRules(emptySet(), emptySet(), emptySet())

        fun normalize(raw: String): String? {
            var s = raw.trim().lowercase()
            if (s.isEmpty()) return null
            if (s.endsWith(".")) s = s.dropLast(1)
            if (s.isEmpty() || s.length > 253) return null
            return s
        }

        fun build(
            block: MutableSet<String>,
            allow: MutableSet<String>,
            important: MutableSet<String>
        ): FilterRules = FilterRules(block, allow, important)
    }
}

object RuleParser {

    data class ParseResult(
        val rules: FilterRules,
        val kept: Int,
        val skipped: Int
    )

    fun parse(lines: Sequence<String>): ParseResult {
        val block = HashSet<String>(1 shl 14)
        val allow = HashSet<String>(1 shl 10)
        val important = HashSet<String>(256)
        var kept = 0
        var skipped = 0

        lines.forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) return@forEach
            if (line.startsWith("#") || line.startsWith(";")) return@forEach
            if (line.contains("##") || line.contains("#@#") || line.contains("#?#")) {
                skipped++
                return@forEach
            }

            if (line.startsWith("address=/", ignoreCase = true)) {
                val rest = line.substringAfter("address=/")
                val domain = toDomain(rest.substringBefore('/'))
                if (domain == null) {
                    skipped++
                } else {
                    block.add(domain)
                    kept++
                }
                return@forEach
            }

            var pattern = line
            var isAllow = false
            var isImportant = false

            if (pattern.startsWith("@@")) {
                isAllow = true
                pattern = pattern.substring(2)
            }

            val dollar = pattern.indexOf('$')
            if (dollar >= 0) {
                val modifiers = pattern.substring(dollar + 1).split(',')
                pattern = pattern.substring(0, dollar)
                for (m in modifiers) {
                    val name = m.trim().lowercase()
                    when {
                        name.isEmpty() -> Unit
                        name == "important" -> isImportant = true
                        isUnsupportedModifier(name) -> {
                            skipped++
                            return@forEach
                        }
                        else -> Unit
                    }
                }
            }

            val domain = toDomain(pattern)
            if (domain == null) {
                skipped++
                return@forEach
            }

            when {
                isAllow -> allow.add(domain)
                isImportant -> important.add(domain)
                else -> block.add(domain)
            }
            kept++
        }

        return ParseResult(FilterRules(block, allow, important), kept, skipped)
    }

    private fun isUnsupportedModifier(name: String): Boolean {
        val base = name.substringBefore('=').trim()
        return when (base) {
            "badfilter", "denyallow", "dnsrewrite", "app", "client", "ctag",
            "csp", "redirect", "redirect-rule", "removeparam", "replace", "cookie",
            "header", "method", "to", "popup", "elemhide", "generichide",
            "specifichide", "genericblock", "document" -> true
            else -> false
        }
    }

    fun toDomain(patternRaw: String): String? {
        var p = patternRaw.trim()
        if (p.isEmpty()) return null

        val hadDoublePipe = p.startsWith("||")
        if (hadDoublePipe) {
            p = p.substring(2)
        } else if (p.startsWith("|")) {
            p = p.substring(1)
        }

        p = p.trimEnd('|')

        val parts = p.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.size > 1) {
            val head = parts.first()
            val isHostsPrefix = head == "0.0.0.0" || head == "127.0.0.1" ||
                head == "::1" || head == "::" || head == "255.255.255.255"
            if (!isHostsPrefix) return null
            p = parts[1]
        } else if (parts.size == 1) {
            p = parts[0]
        } else {
            return null
        }

        if (p.contains('/')) return null
        if (p.contains(':')) return null

        p = p.removeSuffix("^")
        if (p.contains('*')) {
            if (hadDoublePipe) return null
            if (!p.startsWith("*.")) return null
            p = p.substring(2)
        }
        if (p.startsWith(".")) p = p.substring(1)
        p = p.trimEnd('.')

        val host = p.lowercase()
        if (host.isEmpty() || host.length > 253) return null
        if (!host.contains('.')) return null
        if (host.all { it.isDigit() || it == '.' }) return null

        val labels = host.split('.')
        for (label in labels) {
            if (label.isEmpty() || label.length > 63) return null
            if (label.first() == '-' || label.last() == '-') return null
            for (ch in label) {
                val ok = ch in 'a'..'z' || ch in '0'..'9' || ch == '-'
                if (!ok) return null
            }
        }
        return host
    }
}
