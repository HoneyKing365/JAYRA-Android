package ai.jayra.companion.security

import java.net.InetAddress

/** Plain ws:// is only acceptable on a trusted private LAN (spec section 7 / 3.4). */
object NetworkPolicy {
    private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    fun isPrivateHost(host: String): Boolean {
        val h = host.trim().removePrefix("[").removeSuffix("]").lowercase()
        if (h.isEmpty()) return false
        if (h.endsWith(".local")) return true
        IPV4.matchEntire(h)?.let { m ->
            val o = m.groupValues.drop(1).map { it.toInt() }
            if (o.any { it !in 0..255 }) return false
            return o[0] == 10 ||
                (o[0] == 172 && o[1] in 16..31) ||
                (o[0] == 192 && o[1] == 168) ||
                (o[0] == 169 && o[1] == 254) ||
                o[0] == 127
        }
        if (h.contains(':')) {
            // Contains ':' so this is parsed as an IPv6 literal - no DNS lookup happens.
            val addr = runCatching { InetAddress.getByName(h) }.getOrNull() ?: return false
            if (addr.isLoopbackAddress || addr.isLinkLocalAddress) return true
            val b = addr.address
            return b.size == 16 && (b[0].toInt() and 0xFE) == 0xFC   // fc00::/7 unique-local
        }
        return false
    }
}
