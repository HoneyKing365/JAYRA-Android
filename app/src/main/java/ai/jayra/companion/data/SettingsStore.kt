package ai.jayra.companion.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Per-capability switches the phone owner controls, independent of what the host asks for. */
data class Settings(
    val allowNotifications: Boolean = true,
    val allowLaunch: Boolean = true,
    val allowMedia: Boolean = true,
    val allowFinder: Boolean = true,
    val shareWifiSsid: Boolean = false,   // opt-in; also needs location permission
    val bridgeWanted: Boolean = false,    // user pressed Connect; restore after reboot
    val onboardingDone: Boolean = false,  // permission wizard finished (or skipped through)
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("jayra_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    fun update(transform: (Settings) -> Settings) {
        _settings.update(transform)
        val s = _settings.value
        prefs.edit()
            .putBoolean("allowNotifications", s.allowNotifications)
            .putBoolean("allowLaunch", s.allowLaunch)
            .putBoolean("allowMedia", s.allowMedia)
            .putBoolean("allowFinder", s.allowFinder)
            .putBoolean("shareWifiSsid", s.shareWifiSsid)
            .putBoolean("bridgeWanted", s.bridgeWanted)
            .putBoolean("onboardingDone", s.onboardingDone)
            .apply()
    }

    private fun read() = Settings(
        allowNotifications = prefs.getBoolean("allowNotifications", true),
        allowLaunch = prefs.getBoolean("allowLaunch", true),
        allowMedia = prefs.getBoolean("allowMedia", true),
        allowFinder = prefs.getBoolean("allowFinder", true),
        shareWifiSsid = prefs.getBoolean("shareWifiSsid", false),
        bridgeWanted = prefs.getBoolean("bridgeWanted", false),
        onboardingDone = prefs.getBoolean("onboardingDone", false),
    )
}
