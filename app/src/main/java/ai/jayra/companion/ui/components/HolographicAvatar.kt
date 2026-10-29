package ai.jayra.companion.ui.components

import ai.jayra.companion.theme.JayraAmberWarning
import ai.jayra.companion.theme.JayraCrimsonDanger
import ai.jayra.companion.theme.JayraCyanPrimary
import ai.jayra.companion.theme.JayraEmeraldSuccess
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Procedural holographic orb. [state] tints it (idle/listening/thinking/speaking/error), [level] (0..1)
 * drives the FFT-style ring distortion and core size, [emotion] speeds the pulse for upbeat moods.
 */
@Composable
fun HolographicAvatar(state: String, emotion: String, level: Float, modifier: Modifier = Modifier) {
    val base = when (state) {
        "listening" -> JayraEmeraldSuccess
        "thinking" -> JayraAmberWarning
        "error" -> JayraCrimsonDanger
        "speaking" -> JayraCyanPrimary
        else -> JayraCyanPrimary.copy(alpha = 0.78f)
    }
    val smooth by animateFloatAsState(level.coerceIn(0f, 1f), tween(90), label = "avatarLevel")
    val upbeat = emotion in setOf("happy", "excited", "joy", "playful")
    val t = rememberInfiniteTransition(label = "avatar")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(14_000, easing = LinearEasing)), label = "spin")
    val spin2 by t.animateFloat(360f, 0f, infiniteRepeatable(tween(9_000, easing = LinearEasing)), label = "spin2")
    val pulse by t.animateFloat(
        0.93f, 1.07f,
        infiniteRepeatable(
            tween(if (state == "thinking") 650 else if (upbeat) 1100 else 1800, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val phase by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(2_200, easing = LinearEasing)), label = "phase")
    val scan by t.animateFloat(-1f, 1f, infiniteRepeatable(tween(3_200, easing = LinearEasing), RepeatMode.Reverse), label = "scan")

    Canvas(modifier.aspectRatio(1f)) {
        val c = center
        val r = size.minDimension / 2f

        // ambient glow
        drawCircle(
            brush = Brush.radialGradient(listOf(base.copy(alpha = 0.30f + 0.30f * smooth), Color.Transparent), center = c, radius = r),
            radius = r * pulse
        )

        // core orb
        val coreR = r * 0.42f * (1f + 0.28f * smooth) * pulse
        drawCircle(
            brush = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.92f), base, base.copy(alpha = 0.12f)), center = c, radius = coreR),
            radius = coreR
        )

        // FFT distortion ring
        val path = Path()
        val n = 120
        val baseR = r * 0.68f
        for (i in 0..n) {
            val a = (i.toFloat() / n) * 2f * PI.toFloat()
            val wob = smooth * r * 0.13f * (0.6f * sin(a * 6f + phase) + 0.4f * sin(a * 11f - phase * 1.5f))
            val rr = baseR + wob
            val x = c.x + cos(a) * rr
            val y = c.y + sin(a) * rr
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, base, style = Stroke(width = 3.dp.toPx()))

        // rotating dashed ring
        rotate(spin, c) {
            drawCircle(
                color = base.copy(alpha = 0.5f),
                radius = r * 0.82f,
                style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 26f)))
            )
        }

        // counter-rotating arcs
        rotate(spin2, c) {
            val tl = Offset(c.x - r * 0.92f, c.y - r * 0.92f)
            val sz = Size(r * 1.84f, r * 1.84f)
            drawArc(base, 0f, 70f, false, tl, sz, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
            drawArc(base.copy(alpha = 0.6f), 180f, 40f, false, tl, sz, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
        }

        // hologram scan line
        val sy = c.y + scan * r * 0.7f
        drawLine(base.copy(alpha = 0.35f), Offset(c.x - r * 0.7f, sy), Offset(c.x + r * 0.7f, sy), strokeWidth = 2.dp.toPx())
    }
}
