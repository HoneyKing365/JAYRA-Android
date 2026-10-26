package ai.jayra.companion.commands

import ai.jayra.companion.protocol.MediaOp
import ai.jayra.companion.protocol.PhoneCommand
import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.math.roundToInt

/** MEDIA_CONTROL via the platform media-key and AudioManager APIs. */
class MediaHandler(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)

    suspend fun execute(cmd: PhoneCommand.MediaControl): CommandOutcome = try {
        when (cmd.operation) {
            MediaOp.PLAY -> key(KeyEvent.KEYCODE_MEDIA_PLAY, "play")
            MediaOp.PAUSE -> key(KeyEvent.KEYCODE_MEDIA_PAUSE, "pause")
            MediaOp.TOGGLE -> key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "toggle")
            MediaOp.NEXT -> key(KeyEvent.KEYCODE_MEDIA_NEXT, "next")
            MediaOp.PREVIOUS -> key(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "previous")
            MediaOp.VOLUME -> setVolume(cmd.volume ?: return CommandOutcome.Failure("'volume' is required."))
        }
    } catch (e: SecurityException) {
        CommandOutcome.Failure("Android denied the media request.")
    }

    private suspend fun key(code: Int, label: String): CommandOutcome {
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        delay(250)   // give the player a moment so we can report what actually happened
        return CommandOutcome.Success(
            JSONObject().put("operation", label).put("dispatched", true).put("musicActive", audio.isMusicActive),
        )
    }

    private fun setVolume(percent: Int): CommandOutcome {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return CommandOutcome.Failure("Media volume is not adjustable on this device.")
        val index = (percent * max / 100f).roundToInt().coerceIn(0, max)
        // FLAG_SHOW_UI keeps the change visible to the phone's owner.
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, AudioManager.FLAG_SHOW_UI)
        val actual = (audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max).roundToInt()
        return CommandOutcome.Success(JSONObject().put("operation", "volume").put("volume", actual))
    }
}
