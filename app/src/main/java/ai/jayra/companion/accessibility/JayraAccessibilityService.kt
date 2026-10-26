package ai.jayra.companion.accessibility

import ai.jayra.companion.JayraApp
import ai.jayra.companion.security.Redactor
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant

/**
 * Optional, owner-enabled service. Reports normalized state events (no raw event dumps, text off
 * by default) and performs only the allow-listed actions from spec 6.4: no coordinates, gestures,
 * key injection or cross-app text entry.
 */
class JayraAccessibilityService : AccessibilityService() {
    private var lastContentEventAt = 0L

    private val confirmations get() = (application as JayraApp).graph.confirmations

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityBridge.service = this
        AccessibilityBridge.connected.value = true
        emit(stateEvent("ServiceEnabled"))
    }

    override fun onUnbind(intent: Intent?): Boolean {
        emit(stateEvent("ServiceDisabled"))
        AccessibilityBridge.service = null
        AccessibilityBridge.connected.value = false
        return super.onUnbind(intent)
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (AccessibilityBridge.paused.value) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return   // never report our own UI

        val name = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT_CHANGED"
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "VIEW_CLICKED"
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> "VIEW_FOCUSED"
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "TEXT_CHANGED"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "SCROLL"
            else -> return
        }
        if (name == "WINDOW_CONTENT_CHANGED") {   // very chatty: at most one per 1.5 s
            val now = SystemClock.elapsedRealtime()
            if (now - lastContentEventAt < 1_500) return
            lastContentEventAt = now
        }

        val text: Any = if (AccessibilityBridge.textSharing.value && !event.isPassword) {
            val raw = event.text.joinToString(" ") { it?.toString().orEmpty() }.trim()
            if (raw.isEmpty()) JSONObject.NULL else Redactor.redact(raw, 256)
        } else JSONObject.NULL

        emit(
            JSONObject()
                .put("type", "accessibility_event")
                .put("event", name)
                .put("at", Instant.now().toString())
                .put("packageName", pkg.take(200))
                .put("className", event.className?.toString()?.take(200) ?: JSONObject.NULL)
                .put("text", text),
        )
    }

    private fun stateEvent(label: String) = JSONObject()
        .put("type", "accessibility_event")
        .put("event", "SERVICE_STATE_CHANGED")
        .put("at", Instant.now().toString())
        .put("packageName", packageName)
        .put("className", label)
        .put("text", JSONObject.NULL)

    private fun emit(json: JSONObject) {
        AccessibilityBridge.eventSink?.invoke(json)
    }

    // ---- Host -> phone actions ------------------------------------------------------------

    suspend fun perform(action: String, viewId: String?): A11yOutcome = withContext(Dispatchers.Main) {
        when (action) {
            "GLOBAL_BACK" -> global(GLOBAL_ACTION_BACK)
            "GLOBAL_HOME" -> global(GLOBAL_ACTION_HOME)
            "GLOBAL_NOTIFICATIONS" -> global(GLOBAL_ACTION_NOTIFICATIONS)
            "GLOBAL_QUICK_SETTINGS" -> global(GLOBAL_ACTION_QUICK_SETTINGS)
            "CLICK", "FOCUS", "SCROLL_FORWARD", "SCROLL_BACKWARD" -> nodeAction(action, viewId)
            else -> A11yOutcome(false, "Unsupported accessibility action.")
        }
    }

    private fun global(code: Int): A11yOutcome =
        if (performGlobalAction(code)) A11yOutcome(true, result = JSONObject().put("performed", true))
        else A11yOutcome(false, "Android did not perform the action.")

    private suspend fun nodeAction(action: String, viewId: String?): A11yOutcome {
        if (viewId != null && !VIEW_ID.matches(viewId)) return A11yOutcome(false, "Malformed target id.")
        val root = rootInActiveWindow ?: return A11yOutcome(false, "No active window is available.")

        // Always resolve from a fresh tree; reject stale or ambiguous targets.
        val target: AccessibilityNodeInfo = when {
            viewId != null -> {
                val matches = root.findAccessibilityNodeInfosByViewId(viewId)
                when (matches.size) {
                    0 -> return A11yOutcome(false, "Target not found (stale or unknown id).")
                    1 -> matches[0]
                    else -> return A11yOutcome(false, "Target id is ambiguous on the current screen.")
                }
            }
            action.startsWith("SCROLL") -> uniqueScrollable(root)
                ?: return A11yOutcome(false, "No single scrollable target; specify a target id.")
            else -> return A11yOutcome(false, "A target id is required for this action.")
        }

        if (target.isPassword) return A11yOutcome(false, "Actions on password fields are not allowed.")

        if (action == "CLICK") {
            val label = labelOf(target)
            if (isSensitive(label)) {
                val pkg = target.packageName?.toString().orEmpty()
                val summary = "Tap \"${label.take(60)}\" in $pkg?"
                if (!confirmations.ask(summary)) {
                    return A11yOutcome(false, "The phone owner did not approve this action.")
                }
            }
        }

        val ok = when (action) {
            "CLICK" -> target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            "FOCUS" -> target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            "SCROLL_FORWARD" -> target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            else -> target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        }
        return if (ok) A11yOutcome(true, result = JSONObject().put("performed", true))
        else A11yOutcome(false, "Android reported the action was not performed.")
    }

    private fun uniqueScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        var found: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val n = queue.removeFirst()
            visited++
            if (n.isScrollable) {
                if (found != null) return null   // more than one: ambiguous
                found = n
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return found
    }

    private fun labelOf(node: AccessibilityNodeInfo): String {
        val parts = mutableListOf<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(node)
        var visited = 0
        while (queue.isNotEmpty() && visited < 20) {
            val n = queue.removeFirst()
            visited++
            n.text?.let { parts += it.toString() }
            n.contentDescription?.let { parts += it.toString() }
            n.viewIdResourceName?.let { parts += it }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return parts.joinToString(" ").take(200)
    }

    private fun isSensitive(label: String): Boolean {
        val normalized = label
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")   // sendButton -> send Button
            .replace(Regex("[^A-Za-z]+"), " ")            // btn_send -> btn send
        return SENSITIVE.containsMatchIn(normalized)
    }

    private companion object {
        const val MAX_NODES = 2_000
        val VIEW_ID = Regex("""^[\w.:/\-]{1,200}$""")
        val SENSITIVE = Regex(
            """\b(send|submit|buy|purchase|pay|payment|order|checkout|delete|remove|erase|transfer|""" +
                """confirm|sign ?out|log ?out|password|passcode|pin|security|account|subscribe|""" +
                """uninstall|reset|post|publish)\b""",
            RegexOption.IGNORE_CASE,
        )
    }
}
