package ai.jayra.companion.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Resolves launchable apps by package name or fuzzy title, and opens deep links. */
class AppLaunchManager(private val context: Context) {

    private data class Installed(val label: String, val pkg: String)

    private fun installed(): List<Installed> {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, 0)
            .map { Installed(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase(Locale.ROOT) }
    }

    fun listInstalled(): JSONObject {
        val arr = JSONArray()
        installed().forEach { arr.put(JSONObject().put("appName", it.label).put("packageName", it.pkg)) }
        return JSONObject().put("success", true).put("count", arr.length()).put("apps", arr)
    }

    private fun norm(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")

    private fun resolvePackage(packageName: String?, appName: String?): String? {
        val pm = context.packageManager
        if (!packageName.isNullOrBlank() && pm.getLaunchIntentForPackage(packageName) != null) return packageName
        if (appName.isNullOrBlank()) return null
        val key = norm(appName)
        if (key.isEmpty()) return null
        val apps = installed()
        return apps.firstOrNull { norm(it.label) == key }?.pkg
            ?: apps.firstOrNull { norm(it.label).startsWith(key) }?.pkg
            ?: apps.firstOrNull { norm(it.label).contains(key) }?.pkg
            ?: apps.firstOrNull { key.contains(norm(it.label)) && norm(it.label).length >= 4 }?.pkg
    }

    /** Must be called on the main thread. */
    fun launch(packageName: String?, appName: String?, uri: String?): JSONObject {
        val intent: Intent
        val label: String
        if (!uri.isNullOrBlank()) {
            intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            val pkg = resolvePackage(packageName, appName)
            if (pkg != null) intent.setPackage(pkg)
            label = uri
        } else {
            val pkg = resolvePackage(packageName, appName)
                ?: return fail("No installed app matches '${packageName ?: appName ?: ""}'")
            intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return fail("$pkg is not launchable")
            label = pkg
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            when {
                // An enabled accessibility service is exempt from the background-activity-start restriction.
                JayraAccessibilityService.instance != null -> {
                    JayraAccessibilityService.instance!!.startActivity(intent)
                    ok(label, false)
                }
                AppVisibility.foreground -> {
                    context.startActivity(intent)
                    ok(label, false)
                }
                else -> {
                    deferredViaNotification(intent, label)
                    ok(label, true)
                }
            }
        } catch (e: Exception) {
            fail(e.message ?: "Launch failed")
        }
    }

    private fun deferredViaNotification(intent: Intent, label: String) {
        val pi = android.app.PendingIntent.getActivity(
            context, label.hashCode(), intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = androidx.core.app.NotificationCompat.Builder(context, NotificationHelper.CH_ALERTS)
            .setSmallIcon(ai.jayra.companion.R.drawable.ic_stat_jayra)
            .setContentTitle("Open $label")
            .setContentText("Requested by JAYRA — tap to continue")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching {
            @Suppress("MissingPermission")
            androidx.core.app.NotificationManagerCompat.from(context).notify(label.hashCode(), n)
        }
    }

    private fun ok(label: String, deferred: Boolean) =
        JSONObject().put("success", true).put("launched", label).put("deferred", deferred)
            .apply { if (deferred) put("note", "Phone is locked/background: tap the notification to finish opening") }

    private fun fail(msg: String) = JSONObject().put("success", false).put("error", msg)
}
