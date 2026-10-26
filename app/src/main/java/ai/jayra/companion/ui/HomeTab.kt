package ai.jayra.companion.ui

import ai.jayra.companion.net.LinkState
import ai.jayra.companion.protocol.AppId
import ai.jayra.companion.protocol.MediaOp
import ai.jayra.companion.protocol.PhoneCommand
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

internal fun linkLabel(s: LinkState): String = when (s) {
    LinkState.Idle -> "Offline"
    LinkState.Connecting -> "Connecting…"
    LinkState.Open -> "Connected"
    is LinkState.Waiting -> "Reconnecting (attempt ${s.attempt})…"
    is LinkState.Rejected -> s.reason
}

private fun ago(ms: Long?, now: Long): String {
    if (ms == null) return "never"
    val s = ((now - ms) / 1000).coerceAtLeast(0)
    return when {
        s < 5 -> "just now"
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60}m ago"
        else -> "${s / 3600}h ago"
    }
}

private class Tile(val label: String, val sub: String, val icon: JI, val onTap: () -> Unit)

@Composable
fun HomeTab(vm: MainViewModel, a: UiActions, onOpenSheet: (Sheet) -> Unit) {
    val pairing by vm.pairing.collectAsStateWithLifecycle()
    val state by vm.bridgeState.collectAsStateWithLifecycle()
    val speaking by vm.live.speaking.collectAsStateWithLifecycle()
    val clients by vm.live.activeClients.collectAsStateWithLifecycle()
    val react by vm.live.react.collectAsStateWithLifecycle()
    val device by vm.device.collectAsStateWithLifecycle()
    val lastSync by vm.lastSync.collectAsStateWithLifecycle()
    val perms by vm.permissions.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        var n = 0
        while (true) {
            now = System.currentTimeMillis()
            if (n % 30 == 0) vm.refreshDevice()
            n++
            delay(1_000)
        }
    }

    val conn = connOf(state)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Companion core
        NeonCard(Modifier.fillMaxWidth(), accent = conn.color) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CompanionCore(
                    online = conn == Conn.CONNECTED, speaking = speaking,
                    modifier = Modifier.size(112.dp).semantics { contentDescription = "JAYRA core, ${conn.label}" },
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        conn.label.uppercase(), color = conn.color, fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp, style = MaterialTheme.typography.titleMedium,
                    )
                    Text(pairing?.displayName ?: "No PC linked", color = Neon.Dim, style = MaterialTheme.typography.bodySmall)
                    clients?.let { Text("$it deck client(s) on PC", color = Neon.Dim, style = MaterialTheme.typography.bodySmall) }
                    react?.let { Text(it, color = Neon.Violet, style = MaterialTheme.typography.bodySmall, maxLines = 3) }
                    if (state is LinkState.Rejected) {
                        Text(linkLabel(state), color = Neon.Red, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // Link prompt
        if (pairing == null) {
            NeonCard(Modifier.fillMaxWidth(), accent = Neon.Violet) {
                Text("Link this phone to your PC", fontWeight = FontWeight.Bold)
                Text(
                    "Turn on Network Access in JAYRA, then scan the QR or enter the 8-digit code.",
                    color = Neon.Dim, style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NeonButton("Scan QR", a.onScan, Modifier.weight(1f), filled = true)
                    NeonButton("Enter code", { onOpenSheet(Sheet.PAIR) }, Modifier.weight(1f))
                }
            }
        } else if (conn != Conn.CONNECTED) {
            NeonButton(
                if (state is LinkState.Rejected) "Link again" else "Connect to PC",
                onClick = { if (state is LinkState.Rejected) onOpenSheet(Sheet.PAIR) else a.onStartBridge() },
                Modifier.fillMaxWidth(), filled = true,
            )
        }

        // Status strip
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val d = device
            StatCell("Battery", d?.batteryPercent?.let { "$it%" + if (d?.charging == true) " ⚡" else "" } ?: "--", Modifier.weight(1f))
            StatCell("Last sync", ago(lastSync, now), Modifier.weight(1f))
            StatCell("Permissions", "${perms.grantedCount}/3", Modifier.weight(1f))
        }

        SectionLabel("Quick actions")
        val tiles = listOf(
            Tile("Camera", "Open", JI.CAMERA) { vm.runLocal("Camera", PhoneCommand.LaunchApp(AppId.CAMERA)) },
            Tile("WhatsApp", "Open", JI.CHAT) { vm.runLocal("WhatsApp", PhoneCommand.LaunchApp(AppId.WHATSAPP)) },
            Tile("Play / Pause", "Media", JI.PLAY) { vm.runLocal("Media", PhoneCommand.MediaControl(MediaOp.TOGGLE, null)) },
            Tile("Battery", "Device status", JI.BATTERY) { onOpenSheet(Sheet.DEVICE) },
            Tile("Find phone", "Ring or vibrate", JI.RADAR) { onOpenSheet(Sheet.FINDER) },
            Tile("Settings", "Android", JI.SETTINGS) { vm.runLocal("Settings", PhoneCommand.LaunchApp(AppId.SETTINGS)) },
        )
        tiles.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { t ->
                    NeonCard(Modifier.weight(1f).heightIn(min = 96.dp), onClick = t.onTap) {
                        JIcon(t.icon, Neon.Cyan, Modifier.size(28.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(t.label, fontWeight = FontWeight.SemiBold)
                        Text(t.sub, color = Neon.Dim, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier) {
    NeonCard(modifier, glow = false) {
        Text(label.uppercase(), color = Neon.Dim, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
        Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
    }
}
