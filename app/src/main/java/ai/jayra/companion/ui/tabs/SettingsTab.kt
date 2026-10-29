package ai.jayra.companion.ui.tabs

import ai.jayra.companion.BuildConfig
import ai.jayra.companion.model.AppPermission
import ai.jayra.companion.model.AudioRoutingMode
import ai.jayra.companion.model.ConnectionState
import ai.jayra.companion.model.SassyLanguage
import ai.jayra.companion.theme.JayraCardSurface
import ai.jayra.companion.theme.JayraCrimsonDanger
import ai.jayra.companion.theme.JayraCyanGlow
import ai.jayra.companion.theme.JayraCyanPrimary
import ai.jayra.companion.theme.JayraEmeraldSuccess
import ai.jayra.companion.theme.JayraOledBlack
import ai.jayra.companion.theme.TextMuted
import ai.jayra.companion.viewmodel.CompanionViewModel
import ai.jayra.companion.viewmodel.PermissionViewModel
import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(JayraCardSurface, RoundedCornerShape(18.dp))
            .border(1.dp, JayraCyanGlow, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = JayraCyanPrimary)
        content()
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean?, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }
        Switch(checked = checked == true, onCheckedChange = onChange, enabled = enabled && checked != null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab(vm: CompanionViewModel, permissionVm: PermissionViewModel, onScanQr: () -> Unit, modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as Application
    val connection by vm.connection.collectAsStateWithLifecycle()
    val endpoint by vm.endpoint.collectAsStateWithLifecycle()
    val paired by vm.paired.collectAsStateWithLifecycle()
    val routing by vm.audioRouting.collectAsStateWithLifecycle()
    val discovered by vm.discovered.collectAsStateWithLifecycle()
    val discovering by vm.discovering.collectAsStateWithLifecycle()
    val pairing by vm.pairing.collectAsStateWithLifecycle()
    val pairError by vm.pairError.collectAsStateWithLifecycle()
    val clipboard by vm.clipboardSync.collectAsStateWithLifecycle()
    val guard by vm.biometricGuard.collectAsStateWithLifecycle()
    val lang by vm.sassyLanguage.collectAsStateWithLifecycle()
    val proxEnabled by vm.proximityEnabled.collectAsStateWithLifecycle()
    val vibeEnabled by vm.vibeEnabled.collectAsStateWithLifecycle()
    val perms by permissionVm.state.collectAsStateWithLifecycle()

    var host by rememberSaveable(endpoint) { mutableStateOf(endpoint?.host.orEmpty()) }
    var port by rememberSaveable(endpoint) { mutableStateOf((endpoint?.port ?: 3000).toString()) }
    var code by rememberSaveable { mutableStateOf("") }
    val connected = connection == ConnectionState.CONNECTED

    LaunchedEffect(Unit) { permissionVm.refresh() }
    LaunchedEffect(connected) { if (connected) vm.refreshFeatureStatus() }

    Column(
        modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("SETTINGS", style = MaterialTheme.typography.headlineMedium, color = JayraCyanPrimary)

        Section("Connection manager") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    host, { host = it.trim() }, Modifier.weight(1f), label = { Text("PC address") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                OutlinedTextField(
                    port, { port = it.filter(Char::isDigit).take(5) }, Modifier.weight(0.45f), label = { Text("Port") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::discover, enabled = !discovering, modifier = Modifier.weight(1f)) {
                    if (discovering) CircularProgressIndicator(Modifier.padding(end = 6.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Search, null)
                    Text(if (discovering) "  Scanning…" else "  Discover")
                }
                OutlinedButton(onClick = onScanQr, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.QrCodeScanner, null)
                    Text("  Scan QR")
                }
            }
            discovered.forEach { d ->
                Row(
                    Modifier.fillMaxWidth().clickable { d.primary?.let { host = it }; port = d.port.toString() }
                        .background(JayraOledBlack, RoundedCornerShape(10.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🖥 ${d.name}", Modifier.weight(1f))
                    Text("${d.primary}:${d.port}", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                }
            }
            if (!discovering && discovered.isEmpty()) {
                Text("Tap Discover to find JAYRA on this Wi-Fi, or enter the address shown on your PC.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }

            OutlinedTextField(
                code, { code = it.filter(Char::isDigit).take(8) }, Modifier.fillMaxWidth(), label = { Text("8-digit pairing code") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                supportingText = { pairError?.let { Text(it, color = JayraCrimsonDanger) } }
            )
            Button(
                onClick = { vm.pair(app, host, port.toIntOrNull() ?: 3000, code) },
                enabled = host.isNotBlank() && code.length == 8 && !pairing,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = JayraCyanPrimary, contentColor = JayraOledBlack)
            ) { Text(if (pairing) "PAIRING…" else "PAIR & CONNECT") }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (connection == ConnectionState.DISCONNECTED) {
                    OutlinedButton(
                        onClick = { vm.connectManual(app, host, port.toIntOrNull() ?: 3000) },
                        enabled = paired && host.isNotBlank(), modifier = Modifier.weight(1f)
                    ) { Text("Connect") }
                } else {
                    OutlinedButton(onClick = vm::disconnect, modifier = Modifier.weight(1f)) { Text("Disconnect") }
                }
                TextButton(onClick = vm::forgetHost, enabled = paired) { Text("Forget PC", color = JayraCrimsonDanger) }
            }
            Text(
                if (paired) "🔒 Bridge token stored in the encrypted keystore" else "Not paired yet",
                style = MaterialTheme.typography.bodySmall, color = if (paired) JayraEmeraldSuccess else TextMuted
            )
        }

        Section("Audio routing") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val modes = AudioRoutingMode.entries
                modes.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = routing == m,
                        onClick = { vm.setAudioRouting(m) },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size)
                    ) { Text(m.badge, style = MaterialTheme.typography.labelMedium) }
                }
            }
            Text(routing.label, style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }

        Section("Sync & security") {
            ToggleRow("Universal clipboard", "Mirror copied text between phone and PC (phone→PC while the app is focused).", clipboard, vm::setClipboardSync)
            ToggleRow("Biometric guard", "Require fingerprint / face / PIN for high-risk PC actions. Off = tap-to-approve.", guard, vm::setBiometricGuard)
        }

        Section("Ambient intelligence (PC side)") {
            ToggleRow("Spatial proximity hand-off", "Move JAYRA's voice to the phone when you walk away.", proxEnabled, { vm.toggleProximity() }, connected)
            ToggleRow("Ambient vibe shifting", "Auto dim HUD / Deep Focus based on time and activity.", vibeEnabled, { vm.toggleVibe() }, connected)
            Text("Sassy notice language", style = MaterialTheme.typography.labelMedium, color = TextMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SassyLanguage.entries.forEach {
                    FilterChip(selected = lang == it, onClick = { vm.setSassyLanguage(it) }, label = { Text(it.label) })
                }
            }
        }

        Section("Permissions") {
            AppPermission.entries.forEach { p ->
                val ok = perms.granted[p] == true
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (ok) "✔" else "✖", color = if (ok) JayraEmeraldSuccess else if (p.required) JayraCrimsonDanger else TextMuted)
                    Text("  ${p.title}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
            }
            OutlinedButton(onClick = permissionVm::reopenOnboarding, modifier = Modifier.fillMaxWidth()) { Text("Open setup wizard") }
        }

        Text(
            "JAYRA Deck ${BuildConfig.VERSION_NAME} · Cybernetic Deck Edition",
            style = MaterialTheme.typography.labelSmall, color = TextMuted, modifier = Modifier.padding(vertical = 8.dp)
        )
    }
}
