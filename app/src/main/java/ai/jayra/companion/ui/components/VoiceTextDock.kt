package ai.jayra.companion.ui.components

import ai.jayra.companion.theme.JayraCardSurface
import ai.jayra.companion.theme.JayraCrimsonDanger
import ai.jayra.companion.theme.JayraCyanGlow
import ai.jayra.companion.theme.JayraCyanPrimary
import ai.jayra.companion.theme.JayraEmeraldSuccess
import ai.jayra.companion.theme.JayraOledBlack
import ai.jayra.companion.theme.TextMuted
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

@Suppress("DEPRECATION")
@Composable
fun VoiceTextDock(
    micActive: Boolean,
    micLevel: Float,
    enabled: Boolean,
    onSend: (String) -> Unit,
    onToggleMic: () -> Unit,
    modifier: Modifier = Modifier
) {
    var text by rememberSaveable { mutableStateOf("") }
    val ring by animateFloatAsState(if (micActive) 1.15f + micLevel * 0.55f else 1f, tween(80), label = "micRing")
    fun submit() {
        if (text.isNotBlank()) {
            onSend(text)
            text = ""
        }
    }
    Row(
        modifier
            .fillMaxWidth()
            .background(JayraCardSurface, RoundedCornerShape(32.dp))
            .border(1.dp, JayraCyanGlow, RoundedCornerShape(32.dp))
            .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text(if (micActive) "Listening… speak to JAYRA" else "Type a command for JAYRA", color = TextMuted) },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                disabledBorderColor = androidx.compose.ui.graphics.Color.Transparent
            )
        )
        if (text.isNotBlank()) {
            IconButton(onClick = { submit() }, enabled = enabled) {
                Icon(Icons.Filled.Send, "Send", tint = JayraCyanPrimary)
            }
        }
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(56.dp)) {
            if (micActive) Box(Modifier.size(48.dp).scale(ring).background(JayraEmeraldSuccess.copy(alpha = 0.25f), CircleShape))
            Box(
                Modifier
                    .size(48.dp)
                    .background(if (micActive) JayraCrimsonDanger else JayraCyanPrimary, CircleShape)
                    .clickable(enabled = enabled, onClick = onToggleMic),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (micActive) Icons.Filled.MicOff else Icons.Filled.Mic,
                    contentDescription = if (micActive) "Stop microphone" else "Start microphone",
                    tint = JayraOledBlack
                )
            }
        }
    }
}
