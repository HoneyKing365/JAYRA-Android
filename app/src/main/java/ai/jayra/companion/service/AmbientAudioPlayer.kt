package ai.jayra.companion.service

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri

/** Low-volume ambient (Lo-Fi) loop streamed from the host during Deep Focus (only when routing includes the phone). */
class AmbientAudioPlayer(private val context: Context) {
    private var player: MediaPlayer? = null

    fun play(url: String, volume: Float, token: String?) {
        stop()
        try {
            val headers = token?.let { mapOf("Authorization" to "Bearer $it", "X-JAYRA-Token" to it) } ?: emptyMap()
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setDataSource(context, Uri.parse(url), headers)
            mp.isLooping = true
            mp.setVolume(volume, volume)
            mp.setOnPreparedListener { it.start() }
            mp.setOnErrorListener { p, _, _ ->
                runCatching { p.release() }
                if (player === p) player = null
                true
            }
            player = mp
            mp.prepareAsync()
        } catch (e: Exception) {
            stop()
        }
    }

    fun stop() {
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
    }
}
