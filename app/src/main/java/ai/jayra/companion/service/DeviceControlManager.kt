package ai.jayra.companion.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Ringer finder, phone status, media keys, haptics and Do-Not-Disturb control. */
class DeviceControlManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val notifier: NotificationHelper
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    private val _finderActive = MutableStateFlow(false)
    val finderActive: StateFlow<Boolean> = _finderActive.asStateFlow()

    private var finderPlayer: MediaPlayer? = null
    private var finderJob: Job? = null
    private var savedAlarmVolume = -1
    private var savedFilter: Int? = null

    /**
     * Rings the phone on the ALARM stream so it sounds even when the ringer is on silent/vibrate.
     * Must be called on the main thread.
     */
    fun startFinder(durationSec: Int): JSONObject {
        stopFinder()
        val dur = durationSec.coerceIn(5, 180)
        return try {
            savedAlarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) }
            val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(context, uri)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            finderPlayer = mp
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 900, 500), 0))
            _finderActive.value = true
            notifier.postFinder()
            finderJob = scope.launch {
                delay(dur * 1000L)
                stopFinder()
            }
            JSONObject().put("success", true).put("durationSec", dur)
        } catch (e: Exception) {
            stopFinder()
            JSONObject().put("success", false).put("error", e.message ?: "Cannot ring phone")
        }
    }

    fun stopFinder() {
        finderJob?.cancel()
        finderJob = null
        finderPlayer?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        finderPlayer = null
        runCatching { vibrator.cancel() }
        if (savedAlarmVolume >= 0) {
            runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0) }
            savedAlarmVolume = -1
        }
        if (_finderActive.value) {
            _finderActive.value = false
            notifier.cancelFinder()
        }
    }

    fun queryStatus(): JSONObject {
        val bat = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = bat?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val stat = StatFs(Environment.getDataDirectory().path)
        val wifi = JSONObject()
        runCatching {
            @Suppress("DEPRECATION")
            val info = context.applicationContext.getSystemService(WifiManager::class.java).connectionInfo
            wifi.put("rssi", info.rssi).put("linkSpeedMbps", info.linkSpeed)
        }
        val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return JSONObject()
            .put("success", true)
            .put("battery", JSONObject().put("percent", if (level >= 0) level * 100 / scale else -1).put("charging", charging))
            .put("storage", JSONObject().put("freeBytes", stat.availableBytes).put("totalBytes", stat.totalBytes))
            .put("wifi", wifi)
            .put("ringerMode", when (audio.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "normal"
            })
            .put("mediaVolumePercent", audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / maxVol)
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("androidSdk", Build.VERSION.SDK_INT)
    }

    fun mediaControl(operation: String): JSONObject {
        val key = when (operation.lowercase()) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return JSONObject().put("success", false).put("error", "Unknown operation '$operation'")
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        return JSONObject().put("success", true).put("operation", operation)
    }

    fun haptic(ms: Long = 30) {
        runCatching { vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) }
    }

    fun hasDndAccess(): Boolean = nm.isNotificationPolicyAccessGranted

    /** "Deep" DND: silences everything except alarms. Returns false if the access has not been granted. */
    fun setDeepDnd(enabled: Boolean): Boolean {
        if (!hasDndAccess()) return false
        return try {
            if (enabled) {
                if (savedFilter == null) {
                    savedFilter = nm.currentInterruptionFilter.takeIf { it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN }
                        ?: NotificationManager.INTERRUPTION_FILTER_ALL
                }
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
            } else {
                savedFilter?.let { nm.setInterruptionFilter(it) }
                savedFilter = null
            }
            true
        } catch (e: SecurityException) {
            false
        }
    }
}
