package ai.jayra.companion.service

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max

/** Plays JAYRA's streamed 24 kHz mono PCM16 voice (PHONE_ONLY / BOTH routing). */
class PcmAudioPlayer(scope: CoroutineScope) {
    private class Chunk(val data: ByteArray, val rate: Int)

    private val queue = Channel<Chunk>(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    @Volatile private var track: AudioTrack? = null
    @Volatile private var trackRate = 0

    init {
        scope.launch(Dispatchers.IO) {
            while (true) {
                val chunk = withTimeoutOrNull(IDLE_RELEASE_MS) { queue.receive() }
                if (chunk == null) {
                    release() // idle: free the audio session
                    val next = queue.receive()
                    write(next)
                } else {
                    write(chunk)
                }
            }
        }
    }

    fun enqueue(pcm: ByteArray, sampleRate: Int = DEFAULT_RATE) {
        if (pcm.isEmpty()) return
        queue.trySend(Chunk(pcm, sampleRate))
    }

    /** Drops queued audio immediately (barge-in / routing change). */
    fun stop() {
        while (queue.tryReceive().isSuccess) { /* drain */ }
        track?.let {
            runCatching {
                it.pause()
                it.flush()
            }
        }
    }

    private fun write(chunk: Chunk) {
        val t = ensureTrack(chunk.rate) ?: return
        if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
        t.write(chunk.data, 0, chunk.data.size)
    }

    @Synchronized
    private fun ensureTrack(rate: Int): AudioTrack? {
        if (track != null && trackRate == rate) return track
        release()
        return try {
            val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(max(minBuf, rate / 2 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also {
                    track = it
                    trackRate = rate
                }
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    private fun release() {
        track?.let {
            runCatching {
                it.stop()
                it.release()
            }
        }
        track = null
        trackRate = 0
    }

    private companion object {
        const val DEFAULT_RATE = 24_000
        const val IDLE_RELEASE_MS = 5_000L
    }
}
