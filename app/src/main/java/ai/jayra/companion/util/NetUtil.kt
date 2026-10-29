package ai.jayra.companion.util

import java.util.Locale

object NetUtil {
    private val ipv4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    /**
     * The desktop gateway only ever lives on the local network. The app refuses to send the bearer
     * token to any host that is not a private / link-local address or a bare LAN hostname.
     */
    fun isPrivateLan(host: String): Boolean {
        val h = host.trim().lowercase(Locale.ROOT).removePrefix("[").removeSuffix("]")
        if (h.isEmpty()) return false
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home")) return true
        val m = ipv4.matchEntire(h)
        if (m != null) {
            val o = m.groupValues.drop(1).map { it.toInt() }
            if (o.any { it > 255 }) return false
            return o[0] == 10 || o[0] == 127 ||
                (o[0] == 172 && o[1] in 16..31) ||
                (o[0] == 192 && o[1] == 168) ||
                (o[0] == 169 && o[1] == 254)
        }
        if (h.contains(':')) return h == "::1" || h.startsWith("fe80") || h.startsWith("fc") || h.startsWith("fd")
        return !h.contains('.') // bare NetBIOS-style hostname such as DESKTOP-JAYRA
    }

    /** ASCII-only, path-safe file name (used for X-Filename headers). */
    fun sanitizeFilename(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = base.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_').take(120)
        return cleaned.ifBlank { "file_${System.currentTimeMillis()}" }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.2f GB".format(mb / 1024.0)
    }
}
