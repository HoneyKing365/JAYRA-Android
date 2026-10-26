package ai.jayra.companion.accessibility

import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

data class A11yOutcome(val success: Boolean, val error: String? = null, val result: JSONObject? = null)

/** Process-wide handoff between the system-owned AccessibilityService and the bridge. */
object AccessibilityBridge {
    @Volatile var service: JayraAccessibilityService? = null
    @Volatile var eventSink: ((JSONObject) -> Unit)? = null

    /** True while Android has the service bound (user enabled it in system settings). */
    val connected = MutableStateFlow(false)

    /** Owner pause: stops both event reporting and remote actions without leaving system settings. */
    val paused = MutableStateFlow(false)

    /** Per-session opt-in; resets when the bridge disconnects or the process restarts. */
    val textSharing = MutableStateFlow(false)
}
