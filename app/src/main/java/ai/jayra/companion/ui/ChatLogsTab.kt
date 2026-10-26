package ai.jayra.companion.ui

import ai.jayra.companion.net.BridgeController.LogKind
import ai.jayra.companion.net.LinkState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date

@Composable
fun ChatLogsTab(vm: MainViewModel) {
    var mode by rememberSaveable { mutableIntStateOf(0) }
    val paired by vm.pairing.collectAsStateWithLifecycle()
    // Opening this tab links JAYRA's chat/voice deck so it just works.
    LaunchedEffect(paired) { vm.ensureLive() }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NeonButton("Chat", { mode = 0 }, Modifier.weight(1f), filled = mode == 0)
            NeonButton("Terminal", { mode = 1 }, Modifier.weight(1f), accent = Neon.Violet, filled = mode == 1)
        }
        Spacer(Modifier.height(10.dp))
        if (mode == 0) ChatPane(vm) else TerminalPane(vm)
    }
}

@Composable
private fun ColumnScope.ChatPane(vm: MainViewModel) {
    val lines by vm.live.lines.collectAsStateWithLifecycle()
    val deck by vm.liveState.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex) }

    if (deck !is LinkState.Open) {
        Text(
            "Deck " + linkLabel(deck).lowercase() + " - messages will send once it's connected.",
            color = Neon.Amber, style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(6.dp))
    }
    LazyColumn(
        Modifier.weight(1f).fillMaxWidth(), state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (lines.isEmpty()) {
            item { Text("Say something to JAYRA - type below or hold the mic.", color = Neon.Dim) }
        }
        items(lines) { l ->
            val you = l.role == "you"
            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (you) Arrangement.End else Arrangement.Start) {
                NeonCard(Modifier.widthIn(max = 300.dp), accent = if (you) Neon.Cyan else Neon.Violet, glow = false) {
                    Text(if (you) "YOU" else "JAYRA", color = Neon.Dim, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
                    Text(l.text)
                }
            }
        }
    }
    // end padding keeps the send button clear of the floating mic button
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp, end = 80.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            text, { text = it }, Modifier.weight(1f), placeholder = { Text("Tell JAYRA…") }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), colors = neonFieldColors(),
        )
        NeonButton("Send", { if (vm.sendText(text)) text = "" }, enabled = text.isNotBlank(), filled = true)
    }
}

@Composable
private fun ColumnScope.TerminalPane(vm: MainViewModel) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val shown = remember(entries) { entries.asReversed() }   // oldest first, newest at the bottom
    val fmt = remember { DateFormat.getTimeInstance(DateFormat.MEDIUM) }
    val listState = rememberLazyListState()
    LaunchedEffect(shown.size) { if (shown.isNotEmpty()) listState.scrollToItem(shown.lastIndex) }

    val shape = RoundedCornerShape(12.dp)
    LazyColumn(
        Modifier.weight(1f).fillMaxWidth().padding(bottom = 12.dp)
            .clip(shape).background(Color.Black).border(1.dp, Neon.Violet.copy(alpha = 0.5f), shape).padding(10.dp),
        state = listState, verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (shown.isEmpty()) item { Text("> awaiting commands…", color = Neon.Dim, fontFamily = FontFamily.Monospace) }
        items(shown) { e ->
            val (prefix, color) = when (e.kind) {
                LogKind.INBOUND -> ">>" to Neon.Cyan      // command from the PC
                LogKind.OUTBOUND -> "<<" to Neon.Green    // result sent back
                LogKind.ERROR -> "!!" to Neon.Red
                LogKind.LOCAL -> "::phone" to Neon.Violet // action you started on the phone
                LogKind.SYSTEM -> "::" to Neon.Dim
            }
            Text(
                "${fmt.format(Date(e.atMs))} $prefix ${e.text}",
                color = color, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            )
        }
    }
}
