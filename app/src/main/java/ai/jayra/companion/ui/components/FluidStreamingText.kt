package ai.jayra.companion.ui.components

import ai.jayra.companion.theme.JayraCyanPrimary
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle

/** Text with a blinking terminal cursor while [streaming] (text itself is paced by FluidTextStreamingBuffer). */
@Composable
fun FluidStreamingText(
    text: String,
    streaming: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.colorScheme.onBackground,
    maxLines: Int = Int.MAX_VALUE
) {
    val blink = rememberInfiniteTransition(label = "cursor")
    val alpha by blink.animateFloat(0.15f, 1f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "cursorAlpha")
    val annotated = buildAnnotatedString {
        append(text)
        if (streaming) withStyle(SpanStyle(color = JayraCyanPrimary.copy(alpha = alpha))) { append(" ▍") }
    }
    Text(annotated, modifier, color = color, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}
