package ai.jayra.companion.net

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

data class PairingPayload(val host: String?, val port: Int?, val code: String?, val token: String?)

/**
 * Tolerant parser for whatever the desktop QR encodes:
 *  - a bare 8-digit code
 *  - JSON: {"host":"192.168.1.5","port":3000,"code":"84920153"}
 *  - a URL: jayra://pair?host=…&port=…&code=…  or  http://192.168.1.5:3000/…?code=…
 */
object PairingParser {
    private val digits8 = Regex("^\\d{8}$")

    fun parse(raw: String): PairingPayload? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        if (digits8.matches(t)) return PairingPayload(null, null, t, null)
        if (t.startsWith("{")) return parseJson(t)
        return parseUrl(t)
    }

    private fun parseJson(t: String): PairingPayload? = try {
        val j = JSONObject(t)
        val host = firstString(j, "host", "ip", "address", "hostname")
        val port = if (j.has("port")) j.optInt("port").takeIf { it in 1..65535 } else null
        val code = firstString(j, "code", "pairingCode", "pairCode")
        val token = firstString(j, "token", "bearerToken")
        if (host == null && code == null && token == null) null else PairingPayload(host, port, code, token)
    } catch (e: Exception) {
        null
    }

    private fun firstString(j: JSONObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k -> if (j.has(k) && !j.isNull(k)) j.optString(k).takeIf { it.isNotBlank() } else null }

    private fun parseUrl(t: String): PairingPayload? = try {
        val uri = URI(t)
        val q = (uri.rawQuery ?: "").split('&').filter { it.contains('=') }.associate {
            val (k, v) = it.split('=', limit = 2)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }
        val hostFromAuthority = uri.host?.takeIf { uri.scheme in setOf("http", "https", "ws", "wss") }
        val host = q["host"] ?: q["ip"] ?: hostFromAuthority
        val port = (q["port"]?.toIntOrNull() ?: uri.port.takeIf { it > 0 })?.takeIf { it in 1..65535 }
        val code = q["code"] ?: q["pairingCode"]
        val token = q["token"]
        if (host == null && code == null && token == null) null else PairingPayload(host, port, code, token)
    } catch (e: Exception) {
        null
    }
}
