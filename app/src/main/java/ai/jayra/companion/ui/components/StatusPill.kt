package ai.jayra.companion.ui.components

import ai.jayra.companion.model.AudioRoutingMode
import ai.jayra.companion.model.ConnectionState
import ai.jayra.companion.model.HostEndpoint
import ai.jayra.companion.theme.JayraAmberWarning
import ai.jayra.companion.theme.JayraCardSurface
import ai.jayra.companion.theme.JayraCrimsonDanger
import ai.jayra.companion.theme.JayraCyanGlow
import ai.jayra.companion.theme.JayraCyanPrimary
import ai.jayra.companion.theme.JayraEmeraldSuccess
import ai.jayra.companion.theme.TextMuted
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun StatusPill(
    state: ConnectionState,
    endpoint: HostEndpoint?,
    latencyMs: Int?,
    routing: AudioRoutingMode,
    modifier: Modifier = Modifier
) {
    val dot = when (state) {
        ConnectionState.CONNECTED -> JayraEmeraldSuccess
        ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> JayraAmberWarning
        ConnectionState.DISCONNECTED -> JayraCrimsonDanger
    }
    val blink = rememberInfiniteTransition(label = "pill")
    val a by blink.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pillAlpha")
    val dotAlpha = if (state == ConnectionState.CONNECTED || state == ConnectionState.DISCONNECTED) 1f else a

    Row(
        modifier
            .fillMaxWidth()
            .border(1.dp, JayraCyanGlow, RoundedCornerShape(50))
            .background(JayraCardSurface, RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.size(10.dp).alpha(dotAlpha).background(dot, CircleShape))
        Text(
            when (state) {
                ConnectionState.CONNECTED -> endpoint?.display ?: "Connected"
                ConnectionState.CONNECTING -> "Connecting…"
                ConnectionState.RECONNECTING -> "Reconnecting…"
                ConnectionState.DISCONNECTED -> endpoint?.display?.let { "Offline · $it" } ?: "Not paired"
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (state == ConnectionState.CONNECTED && latencyMs != null) {
            Text("${latencyMs}ms", style = MaterialTheme.typography.labelMedium, color = if (latencyMs < 60) JayraEmeraldSuccess else JayraAmberWarning)
        }
        Text(
            routing.badge,
            style = MaterialTheme.typography.labelSmall,
            color = JayraCyanPrimary,
            modifier = Modifier
                .border(1.dp, JayraCyanPrimary.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
