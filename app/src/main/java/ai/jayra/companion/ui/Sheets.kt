package ai.jayra.companion.ui

import ai.jayra.companion.protocol.FinderMode
import ai.jayra.companion.protocol.PhoneCommand
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

@Composable
private fun SheetTitle(text: String) {
    Text(
        text.uppercase(), color = Neon.Cyan, fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp, style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
fun PairSheet(vm: MainViewModel, a: UiActions, onDone: () -> Unit) {
    val busy by vm.pairBusy.collectAsStateWithLifecycle()
    val found by vm.discovered.collectAsStateWithLifecycle()
    val discovering by vm.discovering.collectAsStateWithLifecycle()
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("3000") }
    var code by rememberSaveable { mutableStateOf("") }
    var link by rememberSaveable { mutableStateOf("") }

    SheetTitle("Link to PC")
    Text(
        "Turn on Network Access in JAYRA. Phone and PC must be on the same Wi-Fi. Then scan the QR, or type the 8-digit code JAYRA shows.",
        color = Neon.Dim, style = MaterialTheme.typography.bodySmall,
    )
    NeonButton("Scan QR code", { onDone(); a.onScan() }, Modifier.fillMaxWidth(), filled = true)

    SectionLabel("Or use the code")
    OutlinedTextField(
        host, { host = it }, Modifier.fillMaxWidth(), label = { Text("PC address (e.g. 192.168.1.105)") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), colors = neonFieldColors(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            port, { port = it.filter(Char::isDigit).take(5) }, Modifier.weight(1f), label = { Text("Port") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = neonFieldColors(),
        )
        OutlinedTextField(
            code, { code = it.filter(Char::isDigit).take(8) }, Modifier.weight(2f), label = { Text("8-digit code") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = neonFieldColors(),
        )
    }
    NeonButton(
        if (busy) "Pairing…" else "Pair with code",
        { vm.pairWithCode(host, port, code) { onDone(); a.onStartBridge() } },
        Modifier.fillMaxWidth(), enabled = !busy && code.length == 8 && host.isNotBlank(), filled = true,
    )

    NeonButton(
        if (discovering) "Scanning Wi-Fi…" else "Find JAYRA on Wi-Fi",
        { vm.discover() }, Modifier.fillMaxWidth(), enabled = !discovering, accent = Neon.Violet,
    )
    found.forEach { h ->
        val addr = h.addresses.first()
        NeonButton(
            "$addr : ${h.port}", { host = addr; port = h.port.toString() },
            Modifier.fillMaxWidth(), accent = Neon.Violet,
        )
    }

    SectionLabel("Or paste a pairing link")
    OutlinedTextField(
        link, { link = it }, Modifier.fillMaxWidth(), label = { Text("http://…#mobileBridgeToken=…") }, singleLine = true,
        colors = neonFieldColors(),
    )
    NeonButton(
        "Pair with link",
        { if (vm.pairFromText(link)) { onDone(); a.onStartBridge() } },
        Modifier.fillMaxWidth(), enabled = link.isNotBlank(),
    )
}

@Composable
fun FinderSheet(vm: MainViewModel, onDone: () -> Unit) {
    var ring by rememberSaveable { mutableStateOf(true) }
    var secs by rememberSaveable { mutableFloatStateOf(30f) }
    SheetTitle("Find my phone")
    Text(
        "Plays for a limited time and has a Stop button. It won't override Do Not Disturb or a silenced ringer.",
        color = Neon.Dim, style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NeonButton("Ring", { ring = true }, Modifier.weight(1f), filled = ring)
        NeonButton("Vibrate", { ring = false }, Modifier.weight(1f), accent = Neon.Violet, filled = !ring)
    }
    Text("Duration: ${secs.roundToInt()} s")
    Slider(
        value = secs, onValueChange = { secs = it }, valueRange = 5f..60f,
        colors = SliderDefaults.colors(thumbColor = Neon.Cyan, activeTrackColor = Neon.Cyan),
    )
    NeonButton(
        "Start", {
            vm.runLocal(
                "Find my phone",
                PhoneCommand.RingtoneFinder(if (ring) FinderMode.RING else FinderMode.VIBRATE, secs.roundToInt()),
            )
            onDone()
        },
        Modifier.fillMaxWidth(), filled = true,
    )
}

private val E164 = Regex("""^\+[1-9]\d{6,14}$""")

@Composable
fun WhatsAppSheet(vm: MainViewModel, onDone: () -> Unit) {
    var number by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }
    SheetTitle("WhatsApp draft")
    Text(
        "Opens WhatsApp with your message filled in. You review it and tap Send yourself - JAYRA never sends it.",
        color = Neon.Dim, style = MaterialTheme.typography.bodySmall,
    )
    OutlinedTextField(
        number, { number = it.filter { c -> c.isDigit() || c == '+' }.take(16) }, Modifier.fillMaxWidth(),
        label = { Text("Number, international format (+14155552671)") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), colors = neonFieldColors(),
        isError = number.isNotEmpty() && !E164.matches(number),
    )
    OutlinedTextField(
        message, { message = it.take(2000) }, Modifier.fillMaxWidth(), label = { Text("Message") }, minLines = 3,
        colors = neonFieldColors(),
    )
    NeonButton(
        "Open draft",
        { vm.runLocal("WhatsApp draft", PhoneCommand.WhatsappDraft(number, message)); onDone() },
        Modifier.fillMaxWidth(), enabled = E164.matches(number) && message.isNotBlank(), filled = true,
    )
}

@Composable
fun DeviceSheet(vm: MainViewModel) {
    val d by vm.device.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshDevice() }
    SheetTitle("Device status")
    val snap = d
    if (snap == null) {
        Text("Reading…", color = Neon.Dim)
        return
    }
    InfoRow("Battery", (snap.batteryPercent?.let { "$it%" } ?: "unknown") + if (snap.charging == true) " (charging)" else "")
    InfoRow(
        "Storage",
        if (snap.freeBytes != null && snap.totalBytes != null) "${gb(snap.freeBytes)} free of ${gb(snap.totalBytes)}" else "unknown",
    )
    InfoRow("Wi-Fi", snap.wifi)
    Text(
        "The Wi-Fi network name is only ever shared if you turn that on in Settings.",
        color = Neon.Dim, style = MaterialTheme.typography.bodySmall,
    )
}

private fun gb(bytes: Long) = String.format("%.1f GB", bytes / 1_000_000_000.0)

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), color = Neon.Dim, modifier = Modifier.width(96.dp), style = MaterialTheme.typography.labelMedium)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}
