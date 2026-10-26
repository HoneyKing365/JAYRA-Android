package ai.jayra.companion.commands

import ai.jayra.companion.R
import ai.jayra.companion.protocol.PhoneCommand
import ai.jayra.companion.protocol.Priority
import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/** TRIGGER_NOTIFICATION: posts an app-owned notification, or fails honestly. */
class NotificationHandler(private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val counter = AtomicInteger(0)

    fun show(cmd: PhoneCommand.TriggerNotification): CommandOutcome {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return CommandOutcome.Failure("Notification permission has not been granted on the phone.")
        if (!nm.areNotificationsEnabled()) {
            return CommandOutcome.Failure("Notifications are turned off for JAYRA Companion in Android settings.")
        }
        val (channel, compatPriority) = when (cmd.priority) {
            Priority.LOW -> Notifier.CH_LOW to NotificationCompat.PRIORITY_LOW
            Priority.NORMAL -> Notifier.CH_NORMAL to NotificationCompat.PRIORITY_DEFAULT
            Priority.HIGH -> Notifier.CH_HIGH to NotificationCompat.PRIORITY_HIGH
        }
        if (nm.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE) {
            return CommandOutcome.Failure("That notification channel is turned off on the phone.")
        }
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_jayra)
            .setContentTitle(cmd.title)
            .setContentText(cmd.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(cmd.body))
            .setPriority(compatPriority)
            .setAutoCancel(true)
            .setContentIntent(Notifier.openAppIntent(context))
            .build()
        nm.notify(Notifier.ID_ALERT_BASE + counter.incrementAndGet() % 500, n)
        return CommandOutcome.Success(JSONObject().put("displayed", true))
    }
}
