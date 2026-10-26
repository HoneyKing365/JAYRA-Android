package ai.jayra.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsTab(vm: MainViewModel, a: UiActions) {
    val perms by vm.permissions.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val paused by vm.a11yPaused.collectAsStateWithLifecycle()
    val textShare by vm.a11yText.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionLabel("Permissions status")
        NeonCard(Modifier.fillMaxWidth()) {
            PermRow("Notifications", perms.notifications, "Alerts from your PC and the link status") { a.requestNotifications() }
            PermRow("Phone control (accessibility)", perms.accessibility, "Optional: Back/Home and tapping specific controls") { a.openAccessibilitySettings() }
            PermRow("Microphone", perms.microphone, "Voice commands while you hold the mic") { a.requestMic() }
            PermRow("Unrestricted battery", perms.batteryExempt, "Helps the link survive with the screen off") { a.openBatterySettings() }
        }

        SectionLabel("What your PC may do")
        NeonCard(Modifier.fillMaxWidth()) {
            ToggleRow("Show notifications", "TRIGGER_NOTIFICATION", settings.allowNotifications) { on -> vm.updateSettings { it.copy(allowNotifications = on) } }
            ToggleRow("Open apps & WhatsApp drafts", "LAUNCH_APP, WHATSAPP_DRAFT (you tap Send)", settings.allowLaunch) { on -> vm.updateSettings { it.copy(allowLaunch = on) } }
            ToggleRow("Control media & volume", "MEDIA_CONTROL", settings.allowMedia) { on -> vm.updateSettings { it.copy(allowMedia = on) } }
            ToggleRow("Find my phone", "TRIGGER_RINGTONE_FINDER, respects Do Not Disturb", settings.allowFinder) { on -> vm.updateSettings { it.copy(allowFinder = on) } }
            ToggleRow("Share Wi-Fi name in status", "Needs location permission", settings.shareWifiSsid) { on ->
                if (on) a.onRequestLocation() else vm.setShareSsid(false)
            }
        }

        SectionLabel("Phone control safeguards")
        NeonCard(Modifier.fillMaxWidth(), accent = Neon.Violet) {
            ToggleRow("Pause phone control", "Stops accessibility events and actions instantly", paused) { vm.setA11yPaused(it) }
            ToggleRow("Share limited text this session", "256 chars max, secrets redacted, resets on disconnect", textShare) { vm.setA11yText(it) }
        }

        SectionLabel("Setup")
        NeonButton("Replay setup wizard", { vm.replayOnboarding() }, Modifier.fillMaxWidth(), accent = Neon.Dim)
        Text(
            "Activity history keeps action names and outcomes only. Message text, Wi-Fi names and screen content are never stored.",
            color = Neon.Dim, style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun PermRow(title: String, granted: Boolean, sub: String, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        JIcon(if (granted) JI.CHECK else JI.CROSS, if (granted) Neon.Green else Neon.Red, Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Neon.Dim, style = MaterialTheme.typography.bodySmall)
        }
        if (granted) {
            Text("ON", color = Neon.Green, fontWeight = FontWeight.Bold)
        } else {
            NeonButton("Fix", onFix, accent = Neon.Red)
        }
    }
}

@Composable
internal fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Neon.Dim)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = neonSwitchColors())
    }
}
