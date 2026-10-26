package ai.jayra.companion.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.math.max

/** Plays JAYRA's 24 kHz mono PCM16 speech. Writes happen off the socket thread. */
class PcmPlayer(private val scope: CoroutineScope) {
    private val queue = Channel<ByteArray>(capacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var track: AudioTrack? = null
    private var writer: Job? = null

    fun enqueue(pcm: ByteArray) {
        val even = pcm.size and 1.inv()
        if (even == 0) return
        ensureStarted()
        queue.trySend(if (even == pcm.size) pcm else pcm.copyOf(even))
    }

    /** Barge-in / interruption: drop everything queued and silence the track immediately. */
    @Synchronized
    fun flush() {
        drain()
        track?.let {
            runCatching {
                it.pause()
                it.flush()
                it.play()
            }
        }
    }

    @Synchronized
    fun release() {
        writer?.cancel()
        writer = null
        runCatching { track?.stop() }
        track?.release()
        track = null
        drain()
    }

    @Synchronized
    private fun ensureStarted() {
        if (writer != null) return
        val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(max(minBuf * 2, RATE / 5 * 2))   // >= 200 ms
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play()
        track = t
        writer = scope.launch(Dispatchers.IO) {
            for (chunk in queue) t.write(chunk, 0, chunk.size)
        }
    }

    private fun drain() {
        while (queue.tryReceive().isSuccess) { /* discard */ }
    }

    private companion object { const val RATE = 24_000 }
}
