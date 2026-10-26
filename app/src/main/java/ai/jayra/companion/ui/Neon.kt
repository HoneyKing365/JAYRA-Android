package ai.jayra.companion.ui

import ai.jayra.companion.net.LinkState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asFrameworkPaint
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

object Neon {
    val Bg = Color(0xFF07090D)
    val Surface = Color(0xFF0D131B)
    val Card = Color(0xFF121A25)
    val Cyan = Color(0xFF00E5FF)
    val Violet = Color(0xFFB26BFF)
    val Green = Color(0xFF39FF88)
    val Red = Color(0xFFFF4D6D)
    val RedDeep = Color(0xFF3A1019)
    val Amber = Color(0xFFFFC857)
    val Dim = Color(0xFF8A99AD)
    val Text = Color(0xFFE6EDF3)
    val OnNeon = Color(0xFF001018)
}

/** True when the user has turned system animations off; all decorative motion is disabled. */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** Soft neon halo behind a rounded rectangle. Needs API 28+ hardware shadow layers (minSdk is 28). */
fun Modifier.neonGlow(color: Color, radius: Dp = 14.dp, corner: Dp = 16.dp, alpha: Float = 0.5f): Modifier =
    drawBehind {
        drawIntoCanvas { canvas ->
            val paint = Paint()
            val fw = paint.asFrameworkPaint()
            fw.isAntiAlias = true
            fw.color = color.copy(alpha = 0f).toArgb()
            fw.setShadowLayer(radius.toPx(), 0f, 0f, color.copy(alpha = alpha).toArgb())
            canvas.drawRoundRect(0f, 0f, size.width, size.height, corner.toPx(), corner.toPx(), paint)
        }
    }

@Composable
fun NeonCard(
    modifier: Modifier = Modifier,
    accent: Color = Neon.Cyan,
    glow: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    var m = modifier
    if (glow) m = m.neonGlow(accent, 10.dp, 16.dp, 0.25f)
    m = m.clip(shape).background(Neon.Card).border(1.dp, accent.copy(alpha = 0.45f), shape)
    if (onClick != null) m = m.clickable(role = Role.Button, onClick = onClick)
    Column(m.padding(14.dp), content = content)
}

@Composable
fun NeonButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Neon.Cyan,
    enabled: Boolean = true,
    filled: Boolean = false,
) {
    val shape = RoundedCornerShape(12.dp)
    val c = if (enabled) accent else Neon.Dim
    var m = modifier.heightIn(min = 48.dp)
    if (enabled && filled) m = m.neonGlow(c, 10.dp, 12.dp, 0.45f)
    m = m.clip(shape)
        .background(if (filled) c.copy(alpha = if (enabled) 0.92f else 0.25f) else c.copy(alpha = 0.08f))
        .border(1.dp, c.copy(alpha = 0.7f), shape)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 10.dp)
    Box(m, contentAlignment = Alignment.Center) {
        Text(
            text.uppercase(),
            color = if (filled) Neon.OnNeon else c,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), modifier = modifier.padding(top = 6.dp, bottom = 2.dp),
        color = Neon.Cyan, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
fun neonFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Neon.Cyan,
    unfocusedBorderColor = Neon.Dim.copy(alpha = 0.5f),
    focusedLabelColor = Neon.Cyan,
    cursorColor = Neon.Cyan,
)

@Composable
fun neonSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Neon.OnNeon,
    checkedTrackColor = Neon.Cyan,
    uncheckedThumbColor = Neon.Dim,
    uncheckedTrackColor = Neon.Surface,
    uncheckedBorderColor = Neon.Dim,
)

// ---- Connection state presentation (color AND shape AND text, never color alone) ----------------

enum class Conn(val label: String, val color: Color) {
    CONNECTED("Connected", Neon.Green),
    RECONNECTING("Reconnecting", Neon.Red),
    OFFLINE("Offline", Neon.Red),
}

fun connOf(s: LinkState): Conn = when (s) {
    LinkState.Open -> Conn.CONNECTED
    LinkState.Connecting, is LinkState.Waiting -> Conn.RECONNECTING
    LinkState.Idle, is LinkState.Rejected -> Conn.OFFLINE
}

@Composable
fun StatusDot(conn: Conn, modifier: Modifier = Modifier.size(14.dp)) {
    val reduced = LocalReducedMotion.current
    val t = rememberInfiniteTransition(label = "dot")
    val pulse by t.animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dotPulse",
    )
    val a = if (conn == Conn.RECONNECTING && !reduced) pulse else 1f
    Canvas(modifier) {
        val r = size.minDimension / 2f
        val c = conn.color.copy(alpha = a)
        when (conn) {
            Conn.CONNECTED -> drawCircle(c, r * 0.9f)
            Conn.OFFLINE -> drawCircle(c, r * 0.8f, style = Stroke(width = r * 0.35f))
            Conn.RECONNECTING -> {
                drawCircle(c, r * 0.8f, style = Stroke(width = r * 0.35f))
                drawArc(c, -90f, 180f, true, Offset(r * 0.2f, r * 0.2f), Size(r * 1.6f, r * 1.6f))
            }
        }
    }
}

// ---- Vector icons drawn on Canvas (no icon-library dependency) --------------------------------

enum class JI { HOME, CONTROLS, CHAT, SETTINGS, MENU, MIC, CHECK, CROSS, CAMERA, PLAY, BATTERY, RADAR }

@Composable
fun JIcon(kind: JI, tint: Color, modifier: Modifier = Modifier.size(24.dp)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.085f
        val st = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            JI.HOME -> drawPath(
                Path().apply {
                    moveTo(w * .15f, h * .5f); lineTo(w * .5f, h * .18f); lineTo(w * .85f, h * .5f)
                    moveTo(w * .25f, h * .45f); lineTo(w * .25f, h * .82f); lineTo(w * .75f, h * .82f); lineTo(w * .75f, h * .45f)
                },
                tint, style = st,
            )
            JI.CONTROLS -> for (i in 0..2) {
                val y = h * (.25f + .25f * i)
                drawLine(tint, Offset(w * .15f, y), Offset(w * .85f, y), sw, StrokeCap.Round)
                drawCircle(tint, w * .09f, Offset(w * (.3f + .2f * ((i * 2 + 1) % 3)), y))
            }
            JI.CHAT -> {
                drawRoundRect(tint, Offset(w * .12f, h * .18f), Size(w * .76f, h * .5f), CornerRadius(w * .12f), style = st)
                drawPath(
                    Path().apply { moveTo(w * .3f, h * .68f); lineTo(w * .28f, h * .86f); lineTo(w * .48f, h * .68f) },
                    tint, style = st,
                )
            }
            JI.SETTINGS -> {
                drawCircle(tint, w * .16f, Offset(w / 2, h / 2), style = st)
                for (i in 0 until 8) {
                    val a = Math.toRadians(45.0 * i)
                    val c = cos(a).toFloat(); val s = sin(a).toFloat()
                    drawLine(
                        tint, Offset(w / 2 + c * w * .28f, h / 2 + s * h * .28f),
                        Offset(w / 2 + c * w * .4f, h / 2 + s * h * .4f), sw, StrokeCap.Round,
                    )
                }
            }
            JI.MENU -> for (i in 0..2) {
                val y = h * (.3f + .2f * i)
                drawLine(tint, Offset(w * .18f, y), Offset(w * .82f, y), sw, StrokeCap.Round)
            }
            JI.MIC -> {
                drawRoundRect(tint, Offset(w * .38f, h * .1f), Size(w * .24f, h * .46f), CornerRadius(w * .12f), style = st)
                drawArc(tint, 0f, 180f, false, Offset(w * .25f, h * .28f), Size(w * .5f, h * .45f), style = st)
                drawLine(tint, Offset(w * .5f, h * .73f), Offset(w * .5f, h * .88f), sw, StrokeCap.Round)
            }
            JI.CHECK -> drawPath(
                Path().apply { moveTo(w * .2f, h * .55f); lineTo(w * .42f, h * .75f); lineTo(w * .8f, h * .3f) },
                tint, style = st,
            )
            JI.CROSS -> {
                drawLine(tint, Offset(w * .25f, h * .25f), Offset(w * .75f, h * .75f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * .75f, h * .25f), Offset(w * .25f, h * .75f), sw, StrokeCap.Round)
            }
            JI.CAMERA -> {
                drawRoundRect(tint, Offset(w * .12f, h * .27f), Size(w * .76f, h * .5f), CornerRadius(w * .1f), style = st)
                drawCircle(tint, w * .13f, Offset(w / 2, h * .52f), style = st)
                drawLine(tint, Offset(w * .35f, h * .2f), Offset(w * .6f, h * .2f), sw, StrokeCap.Round)
            }
            JI.PLAY -> drawPath(
                Path().apply { moveTo(w * .3f, h * .2f); lineTo(w * .78f, h * .5f); lineTo(w * .3f, h * .8f); close() },
                tint, style = st,
            )
            JI.BATTERY -> {
                drawRoundRect(tint, Offset(w * .1f, h * .3f), Size(w * .72f, h * .4f), CornerRadius(w * .06f), style = st)
                drawLine(tint, Offset(w * .88f, h * .42f), Offset(w * .88f, h * .58f), sw, StrokeCap.Round)
                drawRect(tint, Offset(w * .2f, h * .4f), Size(w * .35f, h * .2f))
            }
            JI.RADAR -> {
                drawCircle(tint, w * .36f, Offset(w / 2, h / 2), style = st)
                drawCircle(tint, w * .18f, Offset(w / 2, h / 2), style = st)
                drawCircle(tint, w * .05f, Offset(w / 2, h / 2))
            }
        }
    }
}

// ---- Hex logo + animated companion core ----------------------------------------------------------

private fun hexPath(cx: Float, cy: Float, r: Float, rotDeg: Float = 0f): Path = Path().apply {
    for (i in 0..5) {
        val a = Math.toRadians(60.0 * i + rotDeg - 90.0)
        val x = cx + (r * cos(a)).toFloat()
        val y = cy + (r * sin(a)).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

@Composable
fun HexLogo(color: Color, modifier: Modifier = Modifier.size(40.dp)) {
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent), c, size.width * 0.6f), size.width * 0.6f, c)
        drawPath(hexPath(c.x, c.y, size.width * 0.42f), color, style = Stroke(size.width * 0.07f, join = StrokeJoin.Round))
        drawPath(hexPath(c.x, c.y, size.width * 0.24f, 30f), color.copy(alpha = 0.7f), style = Stroke(size.width * 0.05f, join = StrokeJoin.Round))
        drawCircle(color, size.width * 0.06f, c)
    }
}

/** Miniature "desktop companion" visualisation: rings spin while linked, pulse while JAYRA speaks. */
@Composable
fun CompanionCore(online: Boolean, speaking: Boolean, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val t = rememberInfiniteTransition(label = "core")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(24_000, easing = LinearEasing)), label = "spin")
    val pulse by t.animateFloat(
        0.85f, 1.12f,
        infiniteRepeatable(tween(if (speaking) 450 else 1_800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val s = if (reduced || !online) 0f else spin
    val p = if (reduced || !online) 1f else pulse
    val main = if (online) Neon.Cyan else Neon.Dim
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val w = size.width
        drawCircle(
            Brush.radialGradient(listOf(main.copy(alpha = if (online) 0.30f else 0.10f), Color.Transparent), c, w * 0.55f),
            w * 0.55f, c,
        )
        rotate(s, c) { drawPath(hexPath(c.x, c.y, w * 0.46f), main.copy(alpha = 0.8f), style = Stroke(w * 0.015f)) }
        rotate(-s * 1.5f, c) {
            drawPath(hexPath(c.x, c.y, w * 0.36f, 30f), (if (online) Neon.Violet else Neon.Dim).copy(alpha = 0.8f), style = Stroke(w * 0.02f))
        }
        rotate(s * 2f, c) { drawPath(hexPath(c.x, c.y, w * 0.26f), main, style = Stroke(w * 0.025f)) }
        drawCircle(
            Brush.radialGradient(listOf(main, main.copy(alpha = 0.15f)), c, w * 0.16f * p),
            w * 0.14f * p, c,
        )
        if (speaking && online) drawCircle(Neon.Violet.copy(alpha = 0.5f), w * 0.2f * p, c, style = Stroke(w * 0.012f))
    }
}
