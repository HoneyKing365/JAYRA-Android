package ai.jayra.companion.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val JayraDarkColors = darkColorScheme(
    primary = JayraCyanPrimary,
    onPrimary = JayraOledBlack,
    primaryContainer = JayraElevatedCard,
    onPrimaryContainer = JayraCyanPrimary,
    secondary = TextCyanSecondary,
    onSecondary = JayraOledBlack,
    tertiary = JayraEmeraldSuccess,
    onTertiary = JayraOledBlack,
    background = JayraOledBlack,
    onBackground = TextWhitePrimary,
    surface = JayraNavySurface,
    onSurface = TextWhitePrimary,
    surfaceVariant = JayraCardSurface,
    onSurfaceVariant = TextCyanSecondary,
    surfaceContainer = JayraCardSurface,
    surfaceContainerHigh = JayraElevatedCard,
    surfaceContainerHighest = JayraElevatedCard,
    outline = TextMuted,
    outlineVariant = JayraCyanGlow,
    error = JayraCrimsonDanger,
    onError = TextWhitePrimary
)

@Composable
fun JayraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = JayraDarkColors, typography = JayraTypography, content = content)
}
