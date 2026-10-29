package ai.jayra.companion.ui.tabs

import ai.jayra.companion.model.ChatMessage
import ai.jayra.companion.model.ChatRole
import ai.jayra.companion.theme.JayraCardSurface
import ai.jayra.companion.theme.JayraCyanGlow
import ai.jayra.companion.theme.JayraCyanPrimary
import ai.jayra.companion.theme.JayraElevatedCard
import ai.jayra.companion.theme.JayraOledBlack
import ai.jayra.companion.theme.TextMuted
import ai.jayra.companion.ui.components.FileTransferBar
import ai.jayra.companion.ui.components.FluidStreamingText
import ai.jayra.companion.ui.components.MarkdownLite
import ai.jayra.companion.util.NetUtil
import ai.jayra.companion.viewmodel.CompanionViewModel
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Suppress("DEPRECATION")
@Composable
fun ChatTab(vm: CompanionViewModel, modifier: Modifier = Modifier) {
    val chat by vm.chat.collectAsStateWithLifecycle()
    val streaming by vm.streamingText.collectAsStateWithLifecycle()
    val transfers by vm.transfers.collectAsStateWithLifecycle()
    val hostFiles by vm.hostFiles.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var input by rememberSaveable { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { uri ->
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()
            vm.sendFileChat(uri, name)
        }
    }

    val rowCount = chat.size + if (streaming.isNotEmpty()) 1 else 0
    LaunchedEffect(rowCount, streaming.length / 24) {
        if (rowCount > 0) listState.animateScrollToItem(rowCount - 1)
    }

    fun send() {
        if (input.isNotBlank()) {
            vm.sendUserInput(input)
            input = ""
        }
    }

    Column(modifier.fillMaxSize().statusBarsPadding().imePadding().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("CONVERSATION", style = MaterialTheme.typography.titleSmall, color = JayraCyanPrimary, modifier = Modifier.weight(1f))
            IconButton(onClick = vm::refreshHostFiles) { Icon(Icons.Filled.Folder, "PC files", tint = JayraCyanPrimary) }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (chat.isEmpty() && streaming.isEmpty()) {
                item {
                    Text(
                        "No messages yet. Your conversation with JAYRA – voice or text – appears here.",
                        color = TextMuted, modifier = Modifier.padding(24.dp)
                    )
                }
            }
            items(chat, key = { it.id }) { Bubble(it) }
            if (streaming.isNotEmpty()) item("streaming") {
                Row(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier.fillMaxWidth(0.88f).background(JayraCardSurface, RoundedCornerShape(16.dp))
                            .border(1.dp, JayraCyanPrimary.copy(alpha = 0.4f), RoundedCornerShape(16.dp)).padding(12.dp)
                    ) { FluidStreamingText(streaming, streaming = true, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }

        FileTransferBar(transfers.values.toList().takeLast(3), vm::dismissTransfer, Modifier.padding(top = 8.dp))

        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { picker.launch("*/*") }) { Icon(Icons.Filled.AttachFile, "Attach", tint = JayraCyanPrimary) }
            OutlinedTextField(
                value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("Message JAYRA", color = TextMuted) }, maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() })
            )
            IconButton(onClick = ::send, enabled = input.isNotBlank()) { Icon(Icons.Filled.Send, "Send", tint = JayraCyanPrimary) }
        }
    }

    hostFiles?.let { files ->
        AlertDialog(
            onDismissRequest = vm::closeHostFiles,
            title = { Text("Files on your PC") },
            text = {
                if (files.isEmpty()) Text("No shared files found.", color = TextMuted)
                else LazyColumn {
                    items(files, key = { it.name }) { f ->
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.downloadFile(f.name); vm.closeHostFiles() }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(f.name, Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                            Text(NetUtil.formatBytes(f.size), style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::closeHostFiles) { Text("Close") } },
            containerColor = JayraElevatedCard
        )
    }
}

@Composable
private fun Bubble(m: ChatMessage) {
    val user = m.role == ChatRole.USER
    var showReasoning by rememberSaveable(m.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier
                .fillMaxWidth(0.88f)
                .background(if (user) JayraCyanGlow else JayraCardSurface, RoundedCornerShape(16.dp))
                .border(1.dp, if (user) JayraCyanPrimary.copy(alpha = 0.5f) else JayraCyanGlow, RoundedCornerShape(16.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (m.flashcard != null) MarkdownLite(m.text)
            else Text(m.text, style = MaterialTheme.typography.bodyMedium)
            m.attachmentName?.let {
                Text("📎 $it", style = MaterialTheme.typography.labelMedium, color = JayraCyanPrimary)
            }
            if (!m.reasoning.isNullOrBlank()) {
                Row(Modifier.clickable { showReasoning = !showReasoning }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (showReasoning) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = TextMuted)
                    Text("Reasoning trace", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                }
                if (showReasoning) {
                    Text(
                        m.reasoning,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        modifier = Modifier.fillMaxWidth().background(JayraOledBlack, RoundedCornerShape(8.dp)).padding(8.dp)
                    )
                }
            }
        }
    }
}
