package ai.jayra.companion.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

/** Captures 16 kHz mono 16-bit little-endian PCM in 1024-sample (64 ms) frames. */
class AudioRecordStreamer(private val context: Context, private val scope: CoroutineScope) {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private var job: Job? = null

    fun hasPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(onFrame: (ByteArray) -> Unit): Boolean {
        if (_active.value) return true
        if (!hasPermission()) return false
        val frameBytes = FRAME_SAMPLES * 2
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, // enables echo cancellation when audio also plays on the phone
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf, frameBytes * 4)
            )
        } catch (e: Exception) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        return try {
            rec.startRecording()
            _active.value = true
            job = scope.launch(Dispatchers.IO) {
                val buf = ByteArray(frameBytes)
                try {
                    while (isActive) {
                        var read = 0
                        while (read < frameBytes && isActive) {
                            val n = rec.read(buf, read, frameBytes - read)
                            if (n <= 0) break
                            read += n
                        }
                        if (read < frameBytes) break
                        _level.value = rms(buf)
                        onFrame(buf.copyOf())
                    }
                } finally {
                    runCatching { rec.stop() }
                    rec.release()
                    _active.value = false
                    _level.value = 0f
                }
            }
            true
        } catch (e: Exception) {
            rec.release()
            false
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    private fun rms(pcm: ByteArray): Float {
        val sb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var sum = 0.0
        val n = sb.remaining()
        for (i in 0 until n) {
            val s = sb.get(i) / 32768.0
            sum += s * s
        }
        val r = sqrt(sum / max(1, n)).toFloat()
        return (r * 4f).coerceIn(0f, 1f) // gentle gain so speech fills the orb
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 1024
    }
}
