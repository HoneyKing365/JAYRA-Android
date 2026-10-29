package ai.jayra.companion.ui.tabs

import ai.jayra.companion.model.ChatRole
import ai.jayra.companion.model.SessionHandover
import ai.jayra.companion.theme.JayraCardSurface
import ai.jayra.companion.theme.JayraCyanGlow
import ai.jayra.companion.theme.JayraCyanPrimary
import ai.jayra.companion.theme.JayraOledBlack
import ai.jayra.companion.theme.TextMuted
import ai.jayra.companion.ui.components.FileTransferBar
import ai.jayra.companion.ui.components.FluidStreamingText
import ai.jayra.companion.ui.components.HolographicAvatar
import ai.jayra.companion.ui.components.QuickAction
import ai.jayra.companion.ui.components.QuickActionChips
import ai.jayra.companion.ui.components.StatusPill
import ai.jayra.companion.ui.components.VoiceTextDock
import ai.jayra.companion.viewmodel.CompanionViewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Suppress("DEPRECATION")
@Composable
fun HomeTab(vm: CompanionViewModel, modifier: Modifier = Modifier) {
    val connection by vm.connection.collectAsStateWithLifecycle()
    val endpoint by vm.endpoint.collectAsStateWithLifecycle()
    val latency by vm.latencyMs.collectAsStateWithLifecycle()
    val routing by vm.audioRouting.collectAsStateWithLifecycle()
    val character by vm.character.collectAsStateWithLifecycle()
    val micActive by vm.micActive.collectAsStateWithLifecycle()
    val micLevel by vm.micLevel.collectAsStateWithLifecycle()
    val handover by vm.handover.collectAsStateWithLifecycle()
    val streaming by vm.streamingText.collectAsStateWithLifecycle()
    val chat by vm.chat.collectAsStateWithLifecycle()
    val transfers by vm.transfers.collectAsStateWithLifecycle()
    val finder by vm.finderActive.collectAsStateWithLifecycle()
    val proximity by vm.proximity.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(vm::sendFileRest) }

    val avatarState = if (micActive) "listening" else character.state
    val avatarLevel = if (micActive) micLevel else character.audioLevel
    val connected = connection == ai.jayra.companion.model.ConnectionState.CONNECTED
    val lastAssistant = chat.lastOrNull { it.role == ChatRole.ASSISTANT }?.text

    Column(
        modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatusPill(connection, endpoint, latency, routing)

        handover?.let { HandoverBanner(it, onResume = vm::resumeHandover, onDismiss = vm::dismissHandover) }

        if (finder) {
            Row(
                Modifier.fillMaxWidth().background(JayraCardSurface, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🔔 Phone is ringing", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = vm::stopFinder) { Text("STOP") }
            }
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            HolographicAvatar(avatarState, character.emotion, avatarLevel, Modifier.fillMaxWidth(0.72f))
            Text(
                "${avatarState.uppercase()} · PROXIMITY ${proximity.score}%",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                modifier = Modifier.padding(top = 6.dp)
            )
            Box(Modifier.padding(top = 14.dp, start = 8.dp, end = 8.dp)) {
                when {
                    streaming.isNotEmpty() -> FluidStreamingText(streaming, streaming = true, maxLines = 6)
                    !lastAssistant.isNullOrBlank() -> FluidStreamingText(lastAssistant, streaming = false, maxLines = 6)
                    else -> Text(
                        if (connected) "JAYRA is listening. Say something or type below." else "Connect to your PC in Settings to wake JAYRA.",
                        color = TextMuted,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        QuickActionChips(
            listOf(
                QuickAction("PC Telemetry", Icons.Filled.Memory, vm::pcTelemetry),
                QuickAction("PC Screenshot", Icons.Filled.Image, vm::requestScreenshot),
                QuickAction("Media Toggle", Icons.Filled.PlayArrow, vm::pcMediaToggle),
                QuickAction("Send File", Icons.Filled.AttachFile) { picker.launch("*/*") },
                QuickAction("Test Ringer", Icons.Filled.PhoneAndroid, vm::testFinder)
            )
        )

        FileTransferBar(transfers.values.toList().takeLast(3), vm::dismissTransfer)

        VoiceTextDock(
            micActive = micActive,
            micLevel = micLevel,
            enabled = connected,
            onSend = vm::sendUserInput,
            onToggleMic = vm::toggleMic,
            modifier = Modifier.imePadding()
        )
    }
}

@Composable
private fun HandoverBanner(h: SessionHandover, onResume: () -> Unit, onDismiss: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(JayraCardSurface, RoundedCornerShape(16.dp))
            .border(1.dp, JayraCyanPrimary.copy(alpha = 0.6f), RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("SESSION HANDOVER", style = MaterialTheme.typography.labelSmall, color = JayraCyanPrimary)
        Text(
            h.activeTask?.let { "“${it.goal}” — step ${it.stepNumber}/${it.totalSteps}" } ?: h.lastUserQuery.ifBlank { "A session is ready to continue" },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onResume, colors = ButtonDefaults.buttonColors(containerColor = JayraCyanPrimary, contentColor = JayraOledBlack)) {
                Text("RESUME ON DECK")
            }
            TextButton(onClick = onDismiss) { Text("Dismiss", color = TextMuted) }
        }
    }
}
