package ai.jayra.companion.ui

/** Callbacks that need an Activity (permission prompts, scanner, system settings screens). */
class UiActions(
    val onScan: () -> Unit,
    val onStartBridge: () -> Unit,
    val onRequestLocation: () -> Unit,
    val hasMic: () -> Boolean,
    val requestMic: () -> Unit,
    val requestNotifications: () -> Unit,
    val openAccessibilitySettings: () -> Unit,
    val openBatterySettings: () -> Unit,
)

enum class Sheet { PAIR, FINDER, WHATSAPP, DEVICE }
