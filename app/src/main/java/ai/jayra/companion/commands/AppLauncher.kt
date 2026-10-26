package ai.jayra.companion.commands

import ai.jayra.companion.R
import ai.jayra.companion.protocol.AppId
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.app.NotificationCompat
import org.json.JSONObject

/**
 * LAUNCH_APP / WHATSAPP_DRAFT with fixed, reviewed intents (never a package, URI or extras
 * supplied by the host beyond a validated E.164 number and draft text).
 *
 * Android 10+ blocks activity launches from the background and gives no error when it does.
 * So when JAYRA Companion isn't visible we don't pretend: we post a tap-to-open notification
 * and report failure with a clear reason.
 */
class AppLauncher(
    private val context: Context,
    private val isForeground: () -> Boolean,
) {
    fun launch(appId: AppId): CommandOutcome {
        val intent: Intent = when (appId) {
            AppId.WHATSAPP -> context.packageManager.getLaunchIntentForPackage(WHATSAPP_PACKAGE)
                ?: return CommandOutcome.Failure("WhatsApp is not installed on the phone.")
            AppId.CAMERA -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            AppId.SETTINGS -> Intent(Settings.ACTION_SETTINGS)
        }
        return start(intent, appId.ordinal + 100, "open ${appId.wire}", JSONObject().put("launched", appId.wire))
    }

    /** Opens a WhatsApp chat with the text pre-filled. The owner reviews it and taps Send. */
    fun openWhatsAppDraft(phoneNumber: String, message: String): CommandOutcome {
        if (context.packageManager.getLaunchIntentForPackage(WHATSAPP_PACKAGE) == null) {
            return CommandOutcome.Failure("WhatsApp is not installed on the phone.")
        }
        val uri = Uri.Builder()
            .scheme("https").authority("wa.me")
            .appendPath(phoneNumber.removePrefix("+"))
            .appendQueryParameter("text", message)
            .build()
        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(WHATSAPP_PACKAGE)
        return start(intent, 200, "review a WhatsApp draft", JSONObject().put("draftOpened", true))
    }

    private fun start(intent: Intent, requestCode: Int, what: String, ok: JSONObject): CommandOutcome {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!isForeground()) {
            postTapToOpen(requestCode, what, intent)
            return CommandOutcome.Failure(
                "Android blocks silent app launches while JAYRA Companion is in the background. " +
                    "A tap-to-open notification was posted on the phone instead.",
            )
        }
        return try {
            context.startActivity(intent)
            CommandOutcome.Success(ok)
        } catch (e: ActivityNotFoundException) {
            CommandOutcome.Failure("No app on the phone can handle that request.")
        } catch (e: SecurityException) {
            CommandOutcome.Failure("Android blocked the launch.")
        }
    }

    private fun postTapToOpen(requestCode: Int, what: String, intent: Intent) {
        if (!Notifier.canPost(context)) return
        val pi = PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, Notifier.CH_CONFIRM)
            .setSmallIcon(R.drawable.ic_stat_jayra)
            .setContentTitle("JAYRA wants to $what")
            .setContentText("Tap to continue.")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(Notifier.ID_TAP_TO_OPEN, n)
    }

    private companion object { const val WHATSAPP_PACKAGE = "com.whatsapp" }
}
