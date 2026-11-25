package dev.ronguard.rules

/**
 * Blocklist domains with subdomain (suffix) matching: "ads.com" also covers "x.ads.com".
 * ponytail: a plain HashSet, ~100 bytes per entry (~20MB at 200k); a reverse-label trie if memory bites.
 */
class DomainSet(private val domains: Set<String>) {
    val size get() = domains.size

    fun matches(name: String): Boolean {
        var d = name
        while (true) {
            if (d in domains) return true
            val dot = d.indexOf('.')
            if (dot < 0) return false
            d = d.substring(dot + 1)
        }
    }
}

/** Reads hosts files, plain or wildcard domain lists, and the domain-only subset of adblock syntax. */
object BlocklistParser {
    private val HOST_IPS = setOf("0.0.0.0", "127.0.0.1", "::", "::1", "0:0:0:0:0:0:0:0")
    private val RESERVED = setOf("localhost.localdomain", "local.localdomain", "ip6-localhost.localdomain")

    /** Adds every domain found in [lines] to [into]; returns how many lines yielded a domain. */
    fun parse(lines: Sequence<String>, into: MutableSet<String>): Int {
        var added = 0
        for (line in lines) {
            val domain = domainOf(line) ?: continue
            into.add(domain)
            added++
        }
        return added
    }

    fun domainOf(raw: String): String? {
        val line = raw.substringBefore('#').trim()
        if (isNoise(line)) return null
        val domain = candidate(line)?.removePrefix("*.")?.removePrefix(".")?.lowercase() ?: return null
        return domain.takeIf { isValidDomain(it) && it !in RESERVED }
    }

    private fun isNoise(line: String) = line.isEmpty() || line[0] == '!' || line[0] == '[' || line.startsWith("@@")

    private fun candidate(line: String): String? = when {
        line.startsWith("||") -> line.removePrefix("||").takeIf { it.endsWith('^') && '$' !in it && '/' !in it }?.dropLast(1)
        ' ' in line || '\t' in line -> line.split(' ', '\t').filter { it.isNotEmpty() }.let { parts ->
            if (parts.size >= 2 && parts[0] in HOST_IPS) parts[1] else null
        }
        else -> line
    }

    /** Fast domain check without Regex — large lists were freezing on Regex.matches per line. */
    private fun isValidDomain(s: String): Boolean {
        if (s.length !in 3..253 || '.' !in s || ".." in s) return false
        val first = s.first()
        val last = s.last()
        if (first == '.' || first == '-') return false
        if (last == '.' || last == '-') return false
        return s.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
    }
}
