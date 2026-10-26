package ai.jayra.companion.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.random.Random

sealed interface LinkState {
    data object Idle : LinkState
    data object Connecting : LinkState
    data object Open : LinkState
    data class Waiting(val attempt: Int, val retryInMs: Long) : LinkState
    /** Auth/policy failure: retrying would be pointless, the user has to re-pair. */
    data class Rejected(val reason: String) : LinkState
}

object Backoff {
    /** 1s, 2s, 4s, 8s, 16s, then capped at 30s (+ small jitter). */
    fun delayMs(attempt: Int, jitter: Long = Random.nextLong(0, 250)): Long =
        minOf(30_000L, 1_000L shl attempt.coerceIn(0, 5)) + jitter
}

/**
 * OkHttp WebSocket with bounded exponential-backoff reconnect. Stops for good when the user
 * disconnects/unpairs or the host rejects credentials (HTTP 401/403 or close code 1008).
 */
class ReconnectingSocket(
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val requestFactory: () -> Request?,
    private val listener: Listener,
) {
    interface Listener {
        fun onOpen()
        fun onText(text: String)
        fun onClosed()
    }

    private val lock = Any()
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private var socket: WebSocket? = null
    private var retryJob: Job? = null
    private var generation = 0
    private var attempt = 0

    @Volatile
    var isWanted = false
        private set

    fun start() {
        synchronized(lock) {
            if (isWanted) return
            isWanted = true
            attempt = 0
        }
        connect()
    }

    fun stop() {
        var old: WebSocket? = null
        var wasOpen = false
        synchronized(lock) {
            isWanted = false
            generation += 1
            retryJob?.cancel()
            retryJob = null
            wasOpen = _state.value is LinkState.Open
            old = socket
            socket = null
            _state.value = LinkState.Idle
        }
        old?.close(1000, "client closing")
        if (wasOpen) listener.onClosed()
    }

    /** Permanently stop and surface a reason to the user (e.g. incompatible protocol version). */
    fun fail(reason: String) {
        stop()
        _state.value = LinkState.Rejected(reason)
    }

    /** Called when the device regains a network: skip the remaining backoff wait. */
    fun nudge() {
        synchronized(lock) {
            if (!isWanted || _state.value !is LinkState.Waiting) return
            attempt = 0
        }
        connect()
    }

    fun send(text: String): Boolean {
        val ws = synchronized(lock) { socket } ?: return false
        if (_state.value !is LinkState.Open) return false
        return ws.send(text)
    }

    fun queueSize(): Long = synchronized(lock) { socket }?.queueSize() ?: 0L

    private fun connect() {
        val (request, gen) = synchronized(lock) {
            if (!isWanted) return
            retryJob?.cancel()
            val r = requestFactory()
            if (r == null) {
                isWanted = false
                _state.value = LinkState.Rejected("Not paired.")
                return
            }
            generation += 1
            _state.value = LinkState.Connecting
            r to generation
        }
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(lock) {
                    if (gen != generation) {
                        webSocket.cancel()
                        return
                    }
                    socket = webSocket
                    attempt = 0
                    _state.value = LinkState.Open
                }
                listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen == generation) listener.onText(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                dropped(gen, code, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                dropped(gen, null, response?.code)
            }
        })
        synchronized(lock) { if (gen == generation && socket == null) socket = ws }
    }

    private fun dropped(gen: Int, closeCode: Int?, httpCode: Int?) {
        var rejection: String? = null
        var wait = 0L
        synchronized(lock) {
            if (gen != generation) return
            socket = null
            generation += 1          // invalidate any late callbacks from this socket
            rejection = when {
                httpCode == 401 || httpCode == 403 ->
                    "The host rejected this pairing (HTTP $httpCode). Scan the pairing QR again."
                closeCode == 1008 ->
                    "The host closed the link for a policy reason. Scan the pairing QR again."
                else -> null
            }
            if (rejection != null) {
                isWanted = false
                _state.value = LinkState.Rejected(rejection!!)
            } else {
                wait = Backoff.delayMs(attempt)
                attempt += 1
                _state.value = LinkState.Waiting(attempt, wait)
            }
        }
        listener.onClosed()
        if (rejection == null) {
            val job = scope.launch {
                delay(wait)
                connect()
            }
            synchronized(lock) { retryJob = job }
        }
    }
}
