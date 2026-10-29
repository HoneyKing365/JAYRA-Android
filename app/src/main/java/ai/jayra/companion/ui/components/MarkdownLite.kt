package ai.jayra.companion.ui.components

import ai.jayra.companion.theme.JayraCyanPrimary
import ai.jayra.companion.theme.TextWhitePrimary
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** Tiny renderer for the daily flashcard markdown: hash headings, dash/star bullets, bold and inline code. */
@Composable
fun MarkdownLite(markdown: String, modifier: Modifier = Modifier, color: Color = TextWhitePrimary) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        markdown.lines().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Unit
                line.startsWith("#") -> Text(
                    inline(line.trimStart('#').trim()),
                    style = MaterialTheme.typography.titleSmall,
                    color = JayraCyanPrimary,
                    modifier = Modifier.padding(top = 6.dp)
                )
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> Row {
                    Text("▸ ", color = JayraCyanPrimary, style = MaterialTheme.typography.bodyMedium)
                    Text(inline(line.trimStart().drop(2)), color = color, style = MaterialTheme.typography.bodyMedium)
                }
                else -> Text(inline(line), color = color, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun inline(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        when {
            s.startsWith("**", i) -> {
                val end = s.indexOf("**", i + 2)
                if (end > 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = JayraCyanPrimary)) { append(s.substring(i + 2, end)) }
                    i = end + 2
                } else { append(s[i]); i++ }
            }
            s[i] == '`' -> {
                val end = s.indexOf('`', i + 1)
                if (end > 0) {
                    withStyle(SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = JayraCyanPrimary)) {
                        append(s.substring(i + 1, end))
                    }
                    i = end + 1
                } else { append(s[i]); i++ }
            }
            else -> { append(s[i]); i++ }
        }
    }
}
