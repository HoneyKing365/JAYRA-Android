package ai.jayra.companion.net

import ai.jayra.companion.data.SecureStore
import ai.jayra.companion.util.NetUtil
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** Thin REST client for the desktop Express gateway (the mobile API under /api/mobile plus feature endpoints). */
class HostApi(private val client: OkHttpClient, private val store: SecureStore) {

    /** WebSocket client has readTimeout=0; plain REST calls get sane timeouts. */
    private val rest: OkHttpClient = client.newBuilder().readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()

    data class ApiResult(val ok: Boolean, val code: Int, val body: String, val json: JSONObject?) {
        val error: String?
            get() = json?.let { it.optString("error").ifBlank { it.optString("message") } }?.takeIf { it.isNotBlank() }
                ?: if (ok) null else "HTTP $code"
    }

    private fun url(host: String, port: Int, path: String): HttpUrl? =
        HttpUrl.Builder().scheme("http").host(host).port(port).encodedPath(path).build()

    private fun saved(path: String): HttpUrl? {
        val h = store.host ?: return null
        if (!NetUtil.isPrivateLan(h)) return null
        return url(h, store.port, path)
    }

    private fun Request.Builder.auth(): Request.Builder {
        store.token?.let {
            header("X-JAYRA-Token", it)
            header("Authorization", "Bearer $it")
        }
        return this
    }

    private fun parse(resp: Response): ApiResult {
        val body = resp.body?.string().orEmpty()
        val json = try {
            if (body.trimStart().startsWith("{")) JSONObject(body) else null
        } catch (e: Exception) {
            null
        }
        return ApiResult(resp.isSuccessful, resp.code, body, json)
    }

    suspend fun get(path: String): ApiResult = withContext(Dispatchers.IO) {
        val u = saved(path) ?: return@withContext ApiResult(false, 0, "", JSONObject().put("error", "No host configured"))
        try {
            rest.newCall(Request.Builder().url(u).auth().get().build()).execute().use(::parse)
        } catch (e: IOException) {
            ApiResult(false, 0, "", JSONObject().put("error", e.message ?: "Network error"))
        }
    }

    suspend fun post(path: String, body: JSONObject = JSONObject()): ApiResult = withContext(Dispatchers.IO) {
        val u = saved(path) ?: return@withContext ApiResult(false, 0, "", JSONObject().put("error", "No host configured"))
        try {
            val rb = body.toString().toRequestBody("application/json".toMediaTypeOrNull())
            rest.newCall(Request.Builder().url(u).auth().post(rb).build()).execute().use(::parse)
        } catch (e: IOException) {
            ApiResult(false, 0, "", JSONObject().put("error", e.message ?: "Network error"))
        }
    }

    /** Exchanges the 8-digit code for a bridge bearer token. */
    suspend fun pair(host: String, port: Int, code: String): Result<String> = withContext(Dispatchers.IO) {
        if (!NetUtil.isPrivateLan(host)) return@withContext Result.failure(IOException("Refusing to pair with a non-LAN host"))
        try {
            val rb = JSONObject().put("code", code).toString().toRequestBody("application/json".toMediaTypeOrNull())
            val req = Request.Builder().url(url(host, port, "/api/mobile/pair")!!).post(rb).build()
            rest.newCall(req).execute().use { resp ->
                val r = parse(resp)
                val token = r.json?.let { extractToken(it) }
                if (r.ok && !token.isNullOrBlank()) Result.success(token)
                else Result.failure(IOException(r.error ?: "Pairing failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractToken(j: JSONObject): String? {
        for (k in listOf("token", "bearerToken", "bridgeToken", "accessToken")) {
            j.optString(k).takeIf { it.isNotBlank() }?.let { return it }
        }
        j.optJSONObject("data")?.let { return extractToken(it) }
        return null
    }

    /** Round-trip time of a tiny authenticated request, in ms. */
    suspend fun probeLatencyMs(): Int? = withContext(Dispatchers.IO) {
        val u = saved("/api/mobile/bridge-status") ?: return@withContext null
        val start = SystemClock.elapsedRealtime()
        try {
            client.newBuilder().callTimeout(3, TimeUnit.SECONDS).build()
                .newCall(Request.Builder().url(u).auth().get().build()).execute().use {
                    (SystemClock.elapsedRealtime() - start).toInt()
                }
        } catch (e: IOException) {
            null
        }
    }

    /** Streams a raw octet-stream upload with byte-level progress. */
    suspend fun upload(
        filename: String,
        mimeType: String,
        length: Long,
        open: () -> InputStream,
        onProgress: (Long) -> Unit
    ): ApiResult = withContext(Dispatchers.IO) {
        val u = saved("/api/mobile/upload") ?: return@withContext ApiResult(false, 0, "", JSONObject().put("error", "No host configured"))
        val body = object : RequestBody() {
            override fun contentType() = mimeType.toMediaTypeOrNull()
            override fun contentLength() = length
            override fun writeTo(sink: BufferedSink) {
                open().use { input ->
                    val buf = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        total += n
                        onProgress(total)
                    }
                }
            }
        }
        val req = Request.Builder().url(u).auth()
            .header("X-Filename", NetUtil.sanitizeFilename(filename))
            .post(body).build()
        try {
            client.newBuilder().writeTimeout(0, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
                .newCall(req).execute().use(::parse)
        } catch (e: IOException) {
            ApiResult(false, 0, "", JSONObject().put("error", e.message ?: "Upload failed"))
        }
    }

    /** Downloads `/api/mobile/download/:file` into [target]. */
    suspend fun download(filename: String, target: File, onProgress: (Long, Long) -> Unit): Result<File> =
        withContext(Dispatchers.IO) {
            val base = saved("/api/mobile/download/") ?: return@withContext Result.failure(IOException("No host configured"))
            val u = base.newBuilder().addPathSegment(filename).build()
            try {
                client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
                    .newCall(Request.Builder().url(u).auth().get().build()).execute().use { resp ->
                        if (!resp.isSuccessful) return@use Result.failure<File>(IOException("HTTP ${resp.code}"))
                        val body = resp.body ?: return@use Result.failure<File>(IOException("Empty body"))
                        val total = body.contentLength()
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out ->
                            body.byteStream().use { ins ->
                                val buf = ByteArray(32 * 1024)
                                var done = 0L
                                while (true) {
                                    val n = ins.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    onProgress(done, total)
                                }
                            }
                        }
                        Result.success(target)
                    }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
