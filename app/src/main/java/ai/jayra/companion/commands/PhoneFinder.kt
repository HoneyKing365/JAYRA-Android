package ai.jayra.companion.commands

import ai.jayra.companion.R
import ai.jayra.companion.protocol.FinderMode
import ai.jayra.companion.protocol.PhoneCommand
import ai.jayra.companion.service.ActionReceiver
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

data class FinderSession(val mode: FinderMode, val endsAtElapsedMs: Long)

/**
 * TRIGGER_RINGTONE_FINDER: visible, time-bounded, user-stoppable. It never changes ringer mode
 * or volume and refuses to run under Do Not Disturb or a silent ringer.
 */
class PhoneFinder(private val context: Context, private val scope: CoroutineScope) {
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private var ringtone: Ringtone? = null
    private var timeoutJob: Job? = null

    val active = MutableStateFlow<FinderSession?>(null)

    @Volatile
    var onEvent: (String) -> Unit = {}

    @Synchronized
    fun start(cmd: PhoneCommand.RingtoneFinder): CommandOutcome {
        val filter = nm.currentInterruptionFilter
        if (filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        ) return CommandOutcome.Failure("Do Not Disturb is on, so the finder was not started.")

        when (cmd.mode) {
            FinderMode.RING -> {
                if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
                    return CommandOutcome.Failure("The phone's ringer is not in normal mode; JAYRA will not override it.")
                }
                if (audio.getStreamVolume(AudioManager.STREAM_RING) == 0) {
                    return CommandOutcome.Failure("The ring volume is zero; JAYRA will not raise it silently.")
                }
            }
            FinderMode.VIBRATE -> if (vibrator()?.hasVibrator() != true) {
                return CommandOutcome.Failure("This phone has no vibrator.")
            }
        }

        stop("replaced")
        val started = when (cmd.mode) {
            FinderMode.RING -> startRing()
            FinderMode.VIBRATE -> startVibrate()
        }
        if (!started) return CommandOutcome.Failure("Could not start the ${cmd.mode.name.lowercase()} on this phone.")

        active.value = FinderSession(cmd.mode, SystemClock.elapsedRealtime() + cmd.durationSec * 1000L)
        postNotification(cmd.durationSec)
        timeoutJob = scope.launch {
            delay(cmd.durationSec * 1000L)
            stop("timeout")
        }
        onEvent("Finder started (${cmd.mode.name.lowercase()}, ${cmd.durationSec}s)")
        return CommandOutcome.Success(
            JSONObject().put("mode", cmd.mode.name.lowercase()).put("durationSec", cmd.durationSec),
        )
    }

    @Synchronized
    fun stop(reason: String) {
        val wasActive = active.value != null
        timeoutJob?.cancel()
        timeoutJob = null
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { vibrator()?.cancel() }
        nm.cancel(Notifier.ID_FINDER)
        active.value = null
        if (wasActive) onEvent("Finder stopped ($reason)")
    }

    private fun startRing(): Boolean {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val r = RingtoneManager.getRingtone(context, uri) ?: return false
        r.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        r.isLooping = true
        r.play()
        ringtone = r
        return true
    }

    @Suppress("DEPRECATION")
    private fun startVibrate(): Boolean {
        val v = vibrator() ?: return false
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 700, 400), 0)
        v.vibrate(
            effect,
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        return true
    }

    @Suppress("DEPRECATION")
    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private fun postNotification(seconds: Int) {
        if (!Notifier.canPost(context)) return
        val stopIntent = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, ActionReceiver::class.java).setAction(ActionReceiver.ACTION_STOP_FINDER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, Notifier.CH_FINDER)
            .setSmallIcon(R.drawable.ic_stat_jayra)
            .setContentTitle("JAYRA is helping you find this phone")
            .setContentText("Stops automatically after ${seconds}s.")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(seconds * 1000L)
            .setContentIntent(Notifier.openAppIntent(context))
            .addAction(0, "Stop", stopIntent)
            .build()
        nm.notify(Notifier.ID_FINDER, n)
    }
}
