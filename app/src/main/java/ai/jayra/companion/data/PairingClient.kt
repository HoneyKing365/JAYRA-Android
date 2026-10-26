package ai.jayra.companion.data

import ai.jayra.companion.security.NetworkPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Manual-code fallback: POST /api/mobile/pair {"code":"01234567"} -> {"paired":true,"bridgeToken":"..."}.
 * Only ever talks to private-LAN hosts over plain HTTP. The returned token is never logged.
 */
class PairingClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun exchange(host: String, port: Int, code: String): Pairing = withContext(Dispatchers.IO) {
        val h = host.trim().removePrefix("[").removeSuffix("]")
        if (h.isEmpty() || port !in 1..65535) throw PairingException("Enter the laptop's address and port.")
        if (!Regex("""^\d{8}$""").matches(code)) throw PairingException("The pairing code is 8 digits.")
        if (!NetworkPolicy.isPrivateHost(h)) {
            throw PairingException("Code pairing only works with a private LAN address (for example 192.168.x.x).")
        }
        val hostForUrl = if (h.contains(':')) "[$h]" else h
        val request = Request.Builder()
            .url("http://$hostForUrl:$port/api/mobile/pair")
            .header("Origin", "http://$hostForUrl:$port")
            .post(JSONObject().put("code", code).toString().toRequestBody("application/json".toMediaType()))
            .build()
        try {
            http.newCall(request).execute().use { resp ->
                when (resp.code) {
                    200 -> {
                        val token = runCatching { JSONObject(resp.body?.string().orEmpty()).optString("bridgeToken") }
                            .getOrNull().orEmpty()
                        if (!PairingParser.isValidToken(token)) throw PairingException("The host sent an unexpected reply.")
                        Pairing(h, port, false, token, null)
                    }
                    401 -> throw PairingException("That code wasn't accepted. Check the 8 digits shown in JAYRA.")
                    403 -> throw PairingException("The host only accepts pairing from private LAN addresses.")
                    409 -> throw PairingException("Network Access is off in JAYRA. Turn it on, then try again.")
                    429 -> throw PairingException("Too many attempts. Wait a minute and try again.")
                    else -> throw PairingException("The host answered with HTTP ${resp.code}.")
                }
            }
        } catch (e: IOException) {
            throw PairingException("Couldn't reach $h:$port. Same Wi-Fi? Network Access on?")
        }
    }
}
