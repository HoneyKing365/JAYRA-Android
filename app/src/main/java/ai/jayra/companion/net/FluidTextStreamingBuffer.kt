package ai.jayra.companion.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * FIFO character queue that re-emits streamed text at a natural typing cadence (18–35 ms per char)
 * with micro-pauses after sentence-ending punctuation. If the backlog grows large it speeds up so the
 * UI never falls far behind the host.
 *
 * Turn boundaries are queued *in order* with the text ([endTurn]), so a new turn can start streaming
 * while the previous one is still typing out without the two ever mixing.
 */
class FluidTextStreamingBuffer(
    scope: CoroutineScope,
    private val random: Random = Random.Default,
    private val onTurnEnd: (String) -> Unit = {}
) {
    private val queue = ConcurrentLinkedQueue<Char>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val pending = AtomicInteger(0)
    private val builder = StringBuilder()

    private val _text = MutableStateFlow("")
    /** Text of the turn currently being typed out. */
    val text: StateFlow<String> = _text.asStateFlow()

    private val _streaming = MutableStateFlow(false)
    /** True while characters are still queued or being emitted. */
    val isStreaming: StateFlow<Boolean> = _streaming.asStateFlow()

    init {
        scope.launch { consume() }
    }

    fun append(chunk: String) {
        if (chunk.isEmpty()) return
        pending.addAndGet(chunk.length)
        _streaming.value = true
        chunk.forEach { queue.add(it) }
        signal.trySend(Unit)
    }

    /** Marks the end of the current turn; [onTurnEnd] fires after its last character was emitted. */
    fun endTurn() {
        pending.incrementAndGet()
        _streaming.value = true
        queue.add(TURN_END)
        signal.trySend(Unit)
    }

    suspend fun awaitIdle() {
        isStreaming.first { !it }
    }

    /** Drops everything that is queued or half-typed. */
    fun reset() {
        queue.clear()
        pending.set(0)
        builder.clear()
        _text.value = ""
        _streaming.value = false
    }

    private suspend fun consume() {
        while (true) {
            val c = queue.poll()
            if (c == null) {
                _streaming.value = pending.get() > 0
                signal.receive()
                continue
            }
            pending.decrementAndGet()
            if (c == TURN_END) {
                val done = builder.toString()
                builder.clear()
                onTurnEnd(done)
                _text.value = ""
                _streaming.value = pending.get() > 0
                continue
            }
            builder.append(c)
            _text.value = builder.toString()
            val backlog = pending.get()
            if (backlog > FAST_BACKLOG) {
                delay(FAST_DELAY_MS)
                continue
            }
            delay(random.nextLong(MIN_DELAY_MS, MAX_DELAY_MS + 1))
            if (c == '.' || c == '!' || c == '?') {
                val next = queue.peek()
                if (next == null || next.isWhitespace() || next == TURN_END) {
                    delay(random.nextLong(PAUSE_MIN_MS, PAUSE_MAX_MS + 1))
                }
            }
        }
    }

    companion object {
        const val MIN_DELAY_MS = 18L
        const val MAX_DELAY_MS = 35L
        const val PAUSE_MIN_MS = 120L
        const val PAUSE_MAX_MS = 220L
        private const val FAST_BACKLOG = 400
        private const val FAST_DELAY_MS = 3L
        private const val TURN_END = '\u0000'
    }
}
