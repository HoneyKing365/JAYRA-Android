package ai.jayra.companion.commands

import ai.jayra.companion.data.SettingsStore
import ai.jayra.companion.protocol.PhoneCommand
import ai.jayra.companion.telemetry.StatusCollector

/** Maps validated commands to handlers, honoring the phone owner's per-capability switches. */
class CommandRouter(
    private val settings: SettingsStore,
    private val status: StatusCollector,
    private val notifications: NotificationHandler,
    private val launcher: AppLauncher,
    private val media: MediaHandler,
    private val finder: PhoneFinder,
) {
    /**
     * [respectToggles] is true for host commands. The phone owner's own taps in the app pass false:
     * the switches exist to limit what the laptop may do, not what the owner may do.
     */
    suspend fun execute(cmd: PhoneCommand, respectToggles: Boolean = true): CommandOutcome {
        val s = settings.settings.value
        fun allowed(flag: Boolean) = !respectToggles || flag
        return when (cmd) {
            is PhoneCommand.TriggerNotification ->
                if (allowed(s.allowNotifications)) notifications.show(cmd) else disabled("Notifications")
            is PhoneCommand.LaunchApp ->
                if (allowed(s.allowLaunch)) launcher.launch(cmd.appId) else disabled("App launching")
            is PhoneCommand.WhatsappDraft ->
                if (allowed(s.allowLaunch)) launcher.openWhatsAppDraft(cmd.phoneNumber, cmd.message) else disabled("App launching")
            is PhoneCommand.MediaControl ->
                if (allowed(s.allowMedia)) media.execute(cmd) else disabled("Media control")
            PhoneCommand.QueryPhoneStatus ->
                CommandOutcome.Success(status.collect(s.shareWifiSsid))
            is PhoneCommand.RingtoneFinder ->
                if (allowed(s.allowFinder)) finder.start(cmd) else disabled("Find my phone")
        }
    }

    private fun disabled(what: String) =
        CommandOutcome.Failure("$what is switched off in JAYRA Companion on the phone.")
}
