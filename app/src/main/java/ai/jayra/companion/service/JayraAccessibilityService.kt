package ai.jayra.companion.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Remote screen inspection + gesture injection.
 * Privacy: password / PIN fields are never serialized or typed into; text is capped at 256 chars;
 * the dump is bounded to [MAX_NODES] nodes.
 */
class JayraAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    // ───────────────────────── inspection ─────────────────────────

    fun inspect(maxDepth: Int): JSONObject {
        val root = rootInActiveWindow ?: return error("No active window (screen off or secure window)")
        return try {
            val counter = IntArray(1)
            val tree = dump(root, 0, maxDepth.coerceIn(1, 20), counter)
            JSONObject()
                .put("success", true)
                .put("package", root.packageName?.toString())
                .put("nodeCount", counter[0])
                .put("truncated", counter[0] >= MAX_NODES)
                .put("tree", tree)
        } finally {
            recycle(root)
        }
    }

    private fun dump(node: AccessibilityNodeInfo, depth: Int, maxDepth: Int, counter: IntArray): JSONObject? {
        if (counter[0] >= MAX_NODES) return null
        counter[0]++
        val o = JSONObject()
        o.put("class", node.className?.toString()?.substringAfterLast('.') ?: "View")
        if (node.isPassword) {
            o.put("password", true) // contents intentionally omitted
        } else {
            node.text?.toString()?.takeIf { it.isNotEmpty() }?.let { o.put("text", it.take(MAX_TEXT)) }
            node.contentDescription?.toString()?.takeIf { it.isNotEmpty() }?.let { o.put("desc", it.take(MAX_TEXT)) }
        }
        node.viewIdResourceName?.let { o.put("id", it) }
        val r = Rect()
        node.getBoundsInScreen(r)
        o.put("bounds", "[${r.left},${r.top}][${r.right},${r.bottom}]")
        if (node.isClickable) o.put("clickable", true)
        if (node.isScrollable) o.put("scrollable", true)
        if (node.isEditable) o.put("editable", true)
        if (node.isChecked) o.put("checked", true)
        if (node.isFocused) o.put("focused", true)
        if (depth < maxDepth && node.childCount > 0) {
            val kids = JSONArray()
            for (i in 0 until node.childCount) {
                val c = node.getChild(i) ?: continue
                dump(c, depth + 1, maxDepth, counter)?.let { kids.put(it) }
                recycle(c)
                if (counter[0] >= MAX_NODES) break
            }
            if (kids.length() > 0) o.put("children", kids)
        }
        return o
    }

    // ───────────────────────── actions ─────────────────────────

    suspend fun perform(cmd: JSONObject): JSONObject {
        val type = cmd.optString("actionType").uppercase()
        val target = cmd.opt("target")?.takeIf { it != JSONObject.NULL }
        return when (type) {
            "BACK" -> global(GLOBAL_ACTION_BACK, type)
            "HOME" -> global(GLOBAL_ACTION_HOME, type)
            "RECENTS" -> global(GLOBAL_ACTION_RECENTS, type)
            "NOTIFICATIONS" -> global(GLOBAL_ACTION_NOTIFICATIONS, type)
            "QUICK_SETTINGS" -> global(GLOBAL_ACTION_QUICK_SETTINGS, type)
            "TAP", "LONG_TAP" -> {
                val (x, y) = coords(cmd, target, "x", "y") ?: return error("TAP needs target {x,y}")
                val ok = gesture(Path().apply { moveTo(x, y) }, if (type == "LONG_TAP") 700 else 60)
                result(ok, type)
            }
            "SWIPE" -> {
                val t = target as? JSONObject ?: cmd
                val p = Path().apply {
                    moveTo(t.optDouble("x1").toFloat(), t.optDouble("y1").toFloat())
                    lineTo(t.optDouble("x2").toFloat(), t.optDouble("y2").toFloat())
                }
                result(gesture(p, t.optLong("durationMs", 350).coerceIn(50, 3000)), type)
            }
            "CLICK", "LONG_CLICK", "SET_TEXT", "FOCUS",
            "SCROLL_FORWARD", "SCROLL_BACKWARD", "SCROLL_UP", "SCROLL_DOWN" -> nodeAction(type, target, cmd)
            else -> error("Unsupported actionType '$type'")
        }
    }

    private fun nodeAction(type: String, target: Any?, cmd: JSONObject): JSONObject {
        val root = rootInActiveWindow ?: return error("No active window")
        try {
            val scrolling = type.startsWith("SCROLL")
            val node: AccessibilityNodeInfo = when {
                target == null && scrolling -> findFirst(root) { it.isScrollable }
                target == null -> return error("target required")
                else -> findTarget(root, target)
            } ?: return error("Target not found on screen")
            try {
                if (node.isPassword && type == "SET_TEXT") return error("Refusing to type into a password field")
                val ok = when (type) {
                    "CLICK" -> (ancestorOrSelf(node) { it.isClickable } ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    "LONG_CLICK" -> (ancestorOrSelf(node) { it.isLongClickable } ?: node).performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                    "FOCUS" -> node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    "SCROLL_FORWARD", "SCROLL_DOWN" -> (ancestorOrSelf(node) { it.isScrollable } ?: node).performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    "SCROLL_BACKWARD", "SCROLL_UP" -> (ancestorOrSelf(node) { it.isScrollable } ?: node).performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    "SET_TEXT" -> {
                        val args = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cmd.optString("text"))
                        }
                        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    }
                    else -> false
                }
                return result(ok, type)
            } finally {
                recycle(node)
            }
        } finally {
            recycle(root)
        }
    }

    private fun findTarget(root: AccessibilityNodeInfo, target: Any): AccessibilityNodeInfo? {
        val text: String?
        val desc: String?
        val viewId: String?
        when (target) {
            is String -> { text = target; desc = target; viewId = null }
            is JSONObject -> {
                text = target.optString("text").takeIf { it.isNotBlank() }
                desc = target.optString("contentDescription", target.optString("desc")).takeIf { it.isNotBlank() }
                viewId = target.optString("viewId", target.optString("id")).takeIf { it.isNotBlank() }
                if (text == null && desc == null && viewId == null) {
                    coords(JSONObject(), target, "x", "y")?.let { (x, y) ->
                        return findFirst(root) { n ->
                            val r = Rect()
                            n.getBoundsInScreen(r)
                            r.contains(x.toInt(), y.toInt()) && (n.isClickable || n.isEditable)
                        }
                    }
                }
            }
            else -> return null
        }
        viewId?.let { id -> root.findAccessibilityNodeInfosByViewId(id).firstOrNull()?.let { return it } }
        text?.let { t ->
            val matches = root.findAccessibilityNodeInfosByText(t)
            (matches.firstOrNull { it.text?.toString().equals(t, true) } ?: matches.firstOrNull())?.let { return it }
        }
        desc?.let { d -> return findFirst(root) { it.contentDescription?.toString()?.contains(d, true) == true } }
        return null
    }

    private fun findFirst(root: AccessibilityNodeInfo, budget: IntArray = intArrayOf(MAX_NODES), pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (budget[0]-- <= 0) return null
        if (pred(root)) return AccessibilityNodeInfo.obtain(root)
        for (i in 0 until root.childCount) {
            val c = root.getChild(i) ?: continue
            val hit = findFirst(c, budget, pred)
            recycle(c)
            if (hit != null) return hit
        }
        return null
    }

    private fun ancestorOrSelf(node: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && hops < 12) {
            if (pred(n)) return n
            n = n.parent
            hops++
        }
        return null
    }

    private fun coords(cmd: JSONObject, target: Any?, kx: String, ky: String): Pair<Float, Float>? {
        val src = (target as? JSONObject)?.takeIf { it.has(kx) && it.has(ky) } ?: cmd.takeIf { it.has(kx) && it.has(ky) } ?: return null
        return src.optDouble(kx).toFloat() to src.optDouble(ky).toFloat()
    }

    private fun global(action: Int, name: String) = result(performGlobalAction(action), name)

    private suspend fun gesture(path: Path, durationMs: Long): Boolean = suspendCancellableCoroutine { cont ->
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        val dispatched = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }, null)
        if (!dispatched && cont.isActive) cont.resume(false)
    }

    private fun result(ok: Boolean, action: String) = JSONObject().put("success", ok).put("action", action)
        .apply { if (!ok) put("error", "Action was not performed") }

    private fun error(msg: String) = JSONObject().put("success", false).put("error", msg)

    @Suppress("DEPRECATION")
    private fun recycle(n: AccessibilityNodeInfo?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) n?.recycle()
    }

    companion object {
        @Volatile var instance: JayraAccessibilityService? = null
        val isRunning: Boolean get() = instance != null
        const val MAX_NODES = 500
        const val MAX_TEXT = 256
    }
}
