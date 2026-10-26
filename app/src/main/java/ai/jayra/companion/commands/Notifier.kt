package ai.jayra.companion.commands

import ai.jayra.companion.MainActivity
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Channel ids, notification ids and shared notification helpers. */
object Notifier {
    const val CH_SERVICE = "jayra_bridge"
    const val CH_LOW = "jayra_alerts_low"
    const val CH_NORMAL = "jayra_alerts_normal"
    const val CH_HIGH = "jayra_alerts_high"
    const val CH_FINDER = "jayra_finder"
    const val CH_CONFIRM = "jayra_confirm"

    const val ID_SERVICE = 1
    const val ID_FINDER = 2
    const val ID_TAP_TO_OPEN = 3
    const val ID_CONFIRM_BASE = 1000      // + 0..4095
    const val ID_ALERT_BASE = 10_000      // rolling

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Bridge connection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while JAYRA Companion is linked to your laptop."
            },
        )
        nm.createNotificationChannel(NotificationChannel(CH_LOW, "JAYRA alerts (low)", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_NORMAL, "JAYRA alerts", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_HIGH, "JAYRA alerts (urgent)", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(
            NotificationChannel(CH_FINDER, "Find my phone", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)       // the finder plays its own sound; the notification stays silent
                enableVibration(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_CONFIRM, "Approval requests", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Asks you before JAYRA performs a sensitive action on this phone."
            },
        )
    }

    fun canPost(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    }

    fun openAppIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
