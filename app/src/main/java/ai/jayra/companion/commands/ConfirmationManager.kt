package ai.jayra.companion.commands

import ai.jayra.companion.R
import ai.jayra.companion.service.ActionReceiver
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Phone-owner approval (Allow / Deny notification) for sensitive remote actions. */
class ConfirmationManager(private val context: Context) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val nm = context.getSystemService(NotificationManager::class.java)

    /** True only if the owner explicitly taps Allow before the timeout. No notification => denied. */
    suspend fun ask(summary: String, timeoutMs: Long = 8_000): Boolean {
        if (!Notifier.canPost(context)) return false
        val token = UUID.randomUUID().toString()
        val decision = CompletableDeferred<Boolean>()
        pending[token] = decision
        val notifId = Notifier.ID_CONFIRM_BASE + (token.hashCode() and 0xFFF)
        val n = NotificationCompat.Builder(context, Notifier.CH_CONFIRM)
            .setSmallIcon(R.drawable.ic_stat_jayra)
            .setContentTitle("Allow JAYRA to continue?")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setTimeoutAfter(timeoutMs)
            .addAction(0, "Deny", action(token, false))
            .addAction(0, "Allow", action(token, true))
            .build()
        nm.notify(notifId, n)
        try {
            return withTimeoutOrNull(timeoutMs) { decision.await() } ?: false
        } finally {
            pending.remove(token)
            nm.cancel(notifId)
        }
    }

    fun resolve(token: String, allow: Boolean) {
        pending[token]?.complete(allow)
    }

    private fun action(token: String, allow: Boolean): PendingIntent {
        val intent = Intent(context, ActionReceiver::class.java)
            .setAction(ActionReceiver.ACTION_CONFIRM)
            .putExtra(ActionReceiver.EXTRA_TOKEN, token)
            .putExtra(ActionReceiver.EXTRA_ALLOW, allow)
        // PendingIntent identity ignores extras, so each token/choice needs its own request code.
        return PendingIntent.getBroadcast(
            context, token.hashCode() * 2 + if (allow) 1 else 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
