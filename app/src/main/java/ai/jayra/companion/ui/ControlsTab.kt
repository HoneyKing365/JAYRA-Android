package ai.jayra.companion.ui

import ai.jayra.companion.net.LinkState
import ai.jayra.companion.protocol.MediaOp
import ai.jayra.companion.protocol.PhoneCommand
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ControlsTab(vm: MainViewModel, onOpenSheet: (Sheet) -> Unit) {
    val deck by vm.liveState.collectAsStateWithLifecycle()
    val paired by vm.pairing.collectAsStateWithLifecycle()
    val deckOpen = deck is LinkState.Open

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionLabel("PC remote deck")
        NeonCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Deck link", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    Text(linkLabel(deck), color = Neon.Dim, style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = deck !is LinkState.Idle && deck !is LinkState.Rejected,
                    onCheckedChange = { vm.toggleLive() },
                    enabled = paired != null,
                    colors = neonSwitchColors(),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NeonButton("PC vol −", { vm.quickAction("volumeDown") }, Modifier.weight(1f), enabled = deckOpen)
                NeonButton("PC vol +", { vm.quickAction("volumeUp") }, Modifier.weight(1f), enabled = deckOpen)
                NeonButton("PC mute", { vm.quickAction("muteToggle") }, Modifier.weight(1f), enabled = deckOpen)
            }
        }
        NeonButton("Emergency stop", { vm.killSwitch() }, Modifier.fillMaxWidth(), accent = Neon.Red, filled = true)
        Text(
            "Stops JAYRA's running tasks and sound on the PC. If the link is down it reconnects and sends as soon as it can.",
            color = Neon.Dim, style = MaterialTheme.typography.bodySmall,
        )

        SectionLabel("Media on this phone")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NeonButton("Prev", { vm.runLocal("Previous", PhoneCommand.MediaControl(MediaOp.PREVIOUS, null)) }, Modifier.weight(1f))
            NeonButton("Play/Pause", { vm.runLocal("Media", PhoneCommand.MediaControl(MediaOp.TOGGLE, null)) }, Modifier.weight(1.4f), filled = true)
            NeonButton("Next", { vm.runLocal("Next", PhoneCommand.MediaControl(MediaOp.NEXT, null)) }, Modifier.weight(1f))
        }

        SectionLabel("Phone tools")
        NeonButton("Find my phone…", { onOpenSheet(Sheet.FINDER) }, Modifier.fillMaxWidth())
        NeonButton("WhatsApp draft…", { onOpenSheet(Sheet.WHATSAPP) }, Modifier.fillMaxWidth(), accent = Neon.Violet)
        NeonButton("Device status…", { onOpenSheet(Sheet.DEVICE) }, Modifier.fillMaxWidth(), accent = Neon.Dim)
    }
}
