package ai.jayra.companion.service

import ai.jayra.companion.MainActivity
import ai.jayra.companion.R
import ai.jayra.companion.model.AuthRequest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.atomic.AtomicInteger

class NotificationHelper(private val context: Context) {
    private val nextId = AtomicInteger(5000)

    init {
        val nm = context.getSystemService(NotificationManager::class.java)
        fun channel(id: String, name: String, importance: Int, desc: String) =
            NotificationChannel(id, name, importance).apply { description = desc }
        nm.createNotificationChannels(
            listOf(
                channel(CH_SERVICE, "JAYRA connection", NotificationManager.IMPORTANCE_LOW, "Persistent bridge status"),
                channel(CH_ALERTS, "JAYRA alerts", NotificationManager.IMPORTANCE_HIGH, "Task alerts and messages from your PC"),
                channel(CH_AUTH, "Authorization requests", NotificationManager.IMPORTANCE_HIGH, "Biometric approval for high-risk PC actions"),
                channel(CH_TRANSFER, "File transfers", NotificationManager.IMPORTANCE_DEFAULT, "Transfer completion banners"),
                channel(CH_INFO, "JAYRA info", NotificationManager.IMPORTANCE_DEFAULT, "General notifications from JAYRA"),
                channel(CH_FINDER, "Find my phone", NotificationManager.IMPORTANCE_HIGH, "Phone locator alarm")
            )
        )
    }

    fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun serviceNotification(status: String, micActive: Boolean): Notification {
        val disconnect = PendingIntent.getService(
            context, 1, JayraForegroundService.intent(context, JayraForegroundService.ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_jayra)
            .setContentTitle(if (micActive) "JAYRA · microphone live" else "JAYRA Deck")
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openAppIntent())
            .addAction(0, "Disconnect", disconnect)
            .build()
    }

    @SuppressLint("MissingPermission")
    fun post(id: Int, channel: String, title: String, body: String, highPriority: Boolean = false) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        nm.notify(
            id,
            NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_stat_jayra)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setPriority(if (highPriority) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(openAppIntent())
                .build()
        )
    }

    fun postTransfer(title: String, body: String) = post(nextId.incrementAndGet(), CH_TRANSFER, title, body)

    fun postRemote(title: String, body: String, priority: String?) {
        val high = priority?.lowercase() in setOf("high", "urgent", "max")
        post(nextId.incrementAndGet(), if (high) CH_ALERTS else CH_INFO, title, body, high)
    }

    fun postAlert(title: String, body: String) = post(nextId.incrementAndGet(), CH_ALERTS, title, body, true)

    fun postAuth(req: AuthRequest) =
        post(ID_AUTH, CH_AUTH, "Authorization required · ${req.riskLevel}", req.actionDescription, true)

    fun cancelAuth() = NotificationManagerCompat.from(context).cancel(ID_AUTH)

    @SuppressLint("MissingPermission")
    fun postFinder() {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val stop = PendingIntent.getService(
            context, 2, JayraForegroundService.intent(context, JayraForegroundService.ACTION_STOP_FINDER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        nm.notify(
            ID_FINDER,
            NotificationCompat.Builder(context, CH_FINDER)
                .setSmallIcon(R.drawable.ic_stat_jayra)
                .setContentTitle("JAYRA is ringing your phone")
                .setContentText("Tap Stop to silence")
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentIntent(openAppIntent())
                .addAction(0, "Stop", stop)
                .build()
        )
    }

    fun cancelFinder() = NotificationManagerCompat.from(context).cancel(ID_FINDER)

    companion object {
        const val CH_SERVICE = "jayra_service"
        const val CH_ALERTS = "jayra_alerts"
        const val CH_AUTH = "jayra_auth"
        const val CH_TRANSFER = "jayra_transfer"
        const val CH_INFO = "jayra_info"
        const val CH_FINDER = "jayra_finder"
        const val ID_SERVICE = 1001
        const val ID_AUTH = 1002
        const val ID_FINDER = 1003
    }
}
