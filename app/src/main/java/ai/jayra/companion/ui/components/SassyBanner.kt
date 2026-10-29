package ai.jayra.companion.ui.components

import ai.jayra.companion.theme.JayraCardSurface
import ai.jayra.companion.theme.JayraCyanGlow
import ai.jayra.companion.theme.JayraCyanPrimary
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp

/** Glowing top banner for JAYRA's sassy one-liners (auto-dismissed by the caller after a few seconds). */
@Composable
fun SassyBanner(text: String?, rtl: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = text != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier
    ) {
        Row(
            Modifier
                .statusBarsPadding()
                .padding(12.dp)
                .fillMaxWidth()
                .background(JayraCardSurface, RoundedCornerShape(16.dp))
                .border(1.dp, JayraCyanPrimary.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                .clickable(onClick = onDismiss)
                .padding(16.dp)
        ) {
            Text(
                text.orEmpty(),
                color = MaterialTheme.colorScheme.onBackground,
                style = TextStyle(
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                    lineHeight = MaterialTheme.typography.bodyMedium.lineHeight,
                    textDirection = if (rtl) TextDirection.Rtl else TextDirection.Content
                )
            )
        }
    }
}
