package ai.jayra.companion.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/** Captures 16 kHz mono PCM16 in 1024-sample (~64 ms) chunks. Caller must hold RECORD_AUDIO. */
class MicStreamer(
    private val scope: CoroutineScope,
    private val onChunk: (ByteArray) -> Unit,
) {
    private var job: Job? = null

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(): Boolean {
        if (job != null) return true
        val chunkBytes = SAMPLES_PER_CHUNK * 2
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,   // enables echo cancellation where available
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            max(minBuf, chunkBytes * 4),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        rec.startRecording()
        job = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(chunkBytes)
            try {
                while (isActive) {
                    val n = rec.read(buf, 0, chunkBytes)   // blocking, returns within ~64 ms
                    if (n <= 0) break
                    onChunk(buf.copyOf(n))
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
        }
        return true
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val RATE = 16_000
        const val SAMPLES_PER_CHUNK = 1024
    }
}
