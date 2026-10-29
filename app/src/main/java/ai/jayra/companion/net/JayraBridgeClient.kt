package ai.jayra.companion.net

import ai.jayra.companion.model.ChannelKind
import ai.jayra.companion.model.ConnectionState
import ai.jayra.companion.model.HostEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Dual-WebSocket client:
 *  - `/live`            – character state mirroring, FFT levels, TTS audio downlink, mic uplink
 *  - `/mobile-bridge`   – authenticated bidirectional command bridge
 * Both channels auto-reconnect with exponential backoff while a connection is desired.
 */
class JayraBridgeClient(
    private val scope: CoroutineScope,
    private val okHttp: OkHttpClient
) {
    data class Frame(val channel: ChannelKind, val json: JSONObject)

    private val _frames = MutableSharedFlow<Frame>(extraBufferCapacity = 512)
    val frames: SharedFlow<Frame> = _frames.asSharedFlow()

    private val _bridgeState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val bridgeState: StateFlow<ConnectionState> = _bridgeState.asStateFlow()

    private val _liveState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val liveState: StateFlow<ConnectionState> = _liveState.asStateFlow()

    private val sockets = ConcurrentHashMap<ChannelKind, WebSocket>()
    private val attempts = ConcurrentHashMap<ChannelKind, Int>()
    private val lock = Any()

    @Volatile private var endpoint: HostEndpoint? = null
    @Volatile private var token: String? = null
    @Volatile private var generation = 0

    fun connect(ep: HostEndpoint, bearer: String) {
        val gen: Int
        synchronized(lock) {
            closeAll()
            endpoint = ep
            token = bearer
            gen = ++generation
            attempts.clear()
        }
        ChannelKind.entries.forEach { open(it, gen) }
    }

    fun disconnect() {
        synchronized(lock) {
            generation++
            endpoint = null
            token = null
            closeAll()
        }
        _bridgeState.value = ConnectionState.DISCONNECTED
        _liveState.value = ConnectionState.DISCONNECTED
    }

    private fun closeAll() {
        sockets.values.forEach { runCatching { it.close(1000, "client closing") } }
        sockets.clear()
    }

    private fun stateFlow(kind: ChannelKind) = if (kind == ChannelKind.LIVE) _liveState else _bridgeState

    private fun buildUrl(ep: HostEndpoint, kind: ChannelKind): HttpUrl =
        HttpUrl.Builder().scheme("http").host(ep.host).port(ep.port).addPathSegment(kind.path).build()

    private fun open(kind: ChannelKind, gen: Int) {
        val ep = endpoint ?: return
        val bearer = token ?: return
        if (gen != generation) return
        stateFlow(kind).value = if ((attempts[kind] ?: 0) > 0) ConnectionState.RECONNECTING else ConnectionState.CONNECTING

        val req = Request.Builder()
            .url(buildUrl(ep, kind))
            .header("Authorization", "Bearer $bearer")
            .header("X-JAYRA-Token", bearer)
            .build()

        val ws = okHttp.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != generation) {
                    webSocket.close(1000, "stale")
                    return
                }
                attempts[kind] = 0
                stateFlow(kind).value = ConnectionState.CONNECTED
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != generation) return
                val json = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    return
                }
                _frames.tryEmit(Frame(kind, json))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(kind, gen, webSocket)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(kind, gen, webSocket)
        })
        sockets[kind] = ws
    }

    private fun dropped(kind: ChannelKind, gen: Int, ws: WebSocket) {
        if (gen != generation) return
        sockets.remove(kind, ws)
        val n = (attempts[kind] ?: 0) + 1
        attempts[kind] = n
        stateFlow(kind).value = ConnectionState.RECONNECTING
        scope.launch {
            delay(BACKOFF_MS[(n - 1).coerceAtMost(BACKOFF_MS.lastIndex)])
            if (gen == generation) open(kind, gen)
        }
    }

    fun send(kind: ChannelKind, json: JSONObject): Boolean = sockets[kind]?.send(json.toString()) ?: false

    /** Sends on [preferred] if it is up, otherwise on the other channel. */
    fun sendAny(preferred: ChannelKind, json: JSONObject): Boolean {
        if (stateFlow(preferred).value == ConnectionState.CONNECTED && send(preferred, json)) return true
        val other = if (preferred == ChannelKind.LIVE) ChannelKind.BRIDGE else ChannelKind.LIVE
        return send(other, json)
    }

    /** Bytes queued but not yet flushed – used for file-transfer back-pressure. */
    fun queueSize(kind: ChannelKind): Long = sockets[kind]?.queueSize() ?: 0L

    fun isUp(kind: ChannelKind): Boolean = stateFlow(kind).value == ConnectionState.CONNECTED

    private companion object {
        val BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000)
    }
}
