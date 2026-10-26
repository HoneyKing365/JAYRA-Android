package ai.jayra.companion.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val JayraColors = darkColorScheme(
    primary = Neon.Cyan,
    onPrimary = Neon.OnNeon,
    secondary = Neon.Violet,
    onSecondary = Neon.OnNeon,
    background = Neon.Bg,
    onBackground = Neon.Text,
    surface = Neon.Surface,
    onSurface = Neon.Text,
    surfaceVariant = Neon.Card,
    onSurfaceVariant = Neon.Dim,
    error = Neon.Red,
    errorContainer = Neon.RedDeep,
    onErrorContainer = Neon.Text,
    outline = Neon.Dim,
)

@Composable
fun JayraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = JayraColors, content = content)
}
