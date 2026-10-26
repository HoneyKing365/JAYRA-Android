package ai.jayra.companion.data

import ai.jayra.companion.security.NetworkPolicy
import ai.jayra.companion.security.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.URI

data class Pairing(
    val host: String,
    val port: Int,
    val secure: Boolean,
    val bridgeToken: String,
    val gatewayToken: String?,
) {
    private val hostForUrl get() = if (host.contains(':')) "[$host]" else host
    val origin: String get() = "${if (secure) "https" else "http"}://$hostForUrl:$port"
    val displayName: String get() = "$host:$port"
    fun wsUrl(path: String): String = "${if (secure) "wss" else "ws"}://$hostForUrl:$port$path"

    // Never print tokens, even by accident.
    override fun toString() = "Pairing($displayName, secure=$secure)"
}

class PairingException(message: String) : Exception(message)

/**
 * Parses the QR/URL bootstrap: http://<ip>:3000/#mobileBridgeToken=<secret>[&token=<gateway>]
 * The token lives in the URL fragment so it is only ever parsed locally.
 */
object PairingParser {
    private val TOKEN = Regex("""^[A-Za-z0-9._~+/=-]{16,512}$""")
    private val PERCENT = Regex("%([0-9A-Fa-f]{2})")
    private const val DEFAULT_PORT = 3000

    fun isValidToken(t: String) = TOKEN.matches(t)

    fun parse(raw: String): Pairing {
        val text = raw.trim()
        if (text.isEmpty() || text.length > 2048) throw PairingException("That doesn't look like a JAYRA pairing code.")
        val uri = try {
            URI(text)
        } catch (e: Exception) {
            throw PairingException("That doesn't look like a JAYRA pairing code.")
        }
        val secure = when (uri.scheme?.lowercase()) {
            "http", "ws" -> false
            "https", "wss" -> true
            else -> throw PairingException("That doesn't look like a JAYRA pairing code.")
        }
        val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.takeIf { it.isNotBlank() }
            ?: throw PairingException("The pairing code has no host address.")
        val port = if (uri.port in 1..65535) uri.port else DEFAULT_PORT

        val params = (uri.rawFragment ?: "").split('&').mapNotNull { kv ->
            val i = kv.indexOf('=')
            if (i <= 0) null else decode(kv.substring(0, i)) to decode(kv.substring(i + 1))
        }.toMap()

        val bridgeToken = params["mobileBridgeToken"]?.takeIf { TOKEN.matches(it) }
            ?: throw PairingException(
                "No valid bridge token in this code. Enable Network Access in JAYRA and scan the pairing QR again.",
            )
        val gatewayToken = params["token"]?.takeIf { TOKEN.matches(it) }

        if (!secure && !NetworkPolicy.isPrivateHost(host)) {
            throw PairingException("Unencrypted connections are only allowed to private LAN addresses. Use an https/wss host instead.")
        }
        return Pairing(host, port, secure, bridgeToken, gatewayToken)
    }

    private fun decode(s: String): String =
        PERCENT.replace(s) { it.groupValues[1].toInt(16).toChar().toString() }
}

class PairingStore(private val secrets: SecretStore) {
    private val _pairing = MutableStateFlow(load())
    val pairing: StateFlow<Pairing?> = _pairing.asStateFlow()

    fun save(p: Pairing) {
        val json = JSONObject()
            .put("host", p.host).put("port", p.port).put("secure", p.secure)
            .put("bridgeToken", p.bridgeToken)
        p.gatewayToken?.let { json.put("gatewayToken", it) }
        secrets.put(KEY, json.toString())
        _pairing.value = p
    }

    fun clear() {
        secrets.remove(KEY)
        _pairing.value = null
    }

    private fun load(): Pairing? {
        val s = secrets.get(KEY) ?: return null
        return try {
            val o = JSONObject(s)
            Pairing(
                o.getString("host"), o.getInt("port"), o.getBoolean("secure"),
                o.getString("bridgeToken"), o.optString("gatewayToken", "").ifEmpty { null },
            )
        } catch (e: Exception) {
            null
        }
    }

    private companion object { const val KEY = "pairing_v1" }
}
