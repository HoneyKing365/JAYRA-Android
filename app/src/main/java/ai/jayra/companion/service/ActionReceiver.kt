package ai.jayra.companion.service

import ai.jayra.companion.JayraApp
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles notification buttons: stop the phone finder, allow/deny a sensitive remote action. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as JayraApp).graph
        when (intent.action) {
            ACTION_STOP_FINDER -> graph.finder.stop("stopped by you")
            ACTION_CONFIRM -> {
                val token = intent.getStringExtra(EXTRA_TOKEN) ?: return
                graph.confirmations.resolve(token, intent.getBooleanExtra(EXTRA_ALLOW, false))
            }
        }
    }

    companion object {
        const val ACTION_STOP_FINDER = "ai.jayra.companion.action.STOP_FINDER"
        const val ACTION_CONFIRM = "ai.jayra.companion.action.CONFIRM"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_ALLOW = "allow"
    }
}
