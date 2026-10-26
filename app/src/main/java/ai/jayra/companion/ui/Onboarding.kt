package ai.jayra.companion.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private class WizardStep(
    val title: String,
    val glyph: JI,
    val optional: Boolean,
    val why: String,
    val shared: String,
    val grantLabel: String,
)

private val STEPS = listOf(
    WizardStep(
        "Notifications", JI.CHAT, false,
        "Lets JAYRA show alerts you send from your PC, and the \"linked\" status while the bridge is running.",
        "Only JAYRA's own notifications are shown. This app never reads notifications from other apps.",
        "Allow notifications",
    ),
    WizardStep(
        "Phone control", JI.CONTROLS, true,
        "Lets JAYRA, when you ask it to, press Back or Home, open quick settings, or tap one specific control.",
        "Shared: app name and screen-change events. Text is off by default. Passwords, clipboard and notification contents are never read. " +
            "Risky taps ask you first, and you can pause it any time in Settings. No overlay permission is requested.",
        "Open accessibility settings",
    ),
    WizardStep(
        "Microphone", JI.MIC, false,
        "Hold the mic button to speak to JAYRA. Audio streams to your PC only while you hold it.",
        "Nothing is recorded or stored on the phone.",
        "Allow microphone",
    ),
)

@Composable
fun OnboardingWizard(vm: MainViewModel, a: UiActions) {
    val perms by vm.permissions.collectAsStateWithLifecycle()
    var index by rememberSaveable { mutableIntStateOf(0) }

    fun grantedNow(i: Int) = when (i) {
        0 -> perms.notifications
        1 -> perms.accessibility
        else -> perms.microphone
    }

    // Already-granted steps are skipped; after the last one the wizard hides itself for good.
    LaunchedEffect(perms, index) {
        if (index >= STEPS.size) vm.completeOnboarding()
        else if (grantedNow(index)) index += 1
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0A1424), Neon.Bg)))
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        HexLogo(Neon.Cyan, Modifier.size(84.dp))
        Text(
            "JAYRA MOBILE", color = Neon.Cyan, fontWeight = FontWeight.Bold,
            letterSpacing = 4.sp, style = MaterialTheme.typography.titleLarge,
        )
        Text(
            "Three quick steps to link your phone.", color = Neon.Dim,
            textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            STEPS.indices.forEach { i ->
                Spacer(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (i <= index) Neon.Cyan else Neon.Dim.copy(alpha = 0.3f)),
                )
            }
        }

        AnimatedContent(targetState = index, label = "wizard") { i ->
            val step = STEPS.getOrNull(i)
            if (step != null) {
                NeonCard(Modifier.fillMaxWidth(), accent = if (step.optional) Neon.Violet else Neon.Cyan) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        JIcon(step.glyph, if (step.optional) Neon.Violet else Neon.Cyan, Modifier.size(32.dp))
                        Column {
                            Text("STEP ${i + 1} OF ${STEPS.size}", color = Neon.Dim, style = MaterialTheme.typography.labelSmall, letterSpacing = 2.sp)
                            Text(
                                step.title + if (step.optional) " (optional)" else "",
                                fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(step.why, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(step.shared, color = Neon.Dim, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (index < STEPS.size) {
            NeonButton(
                STEPS[index].grantLabel,
                onClick = {
                    when (index) {
                        0 -> a.requestNotifications()
                        1 -> a.openAccessibilitySettings()
                        else -> a.requestMic()
                    }
                },
                Modifier.fillMaxWidth(), filled = true,
            )
            NeonButton("Skip for now", onClick = { index += 1 }, Modifier.fillMaxWidth(), accent = Neon.Dim)
            Text(
                "You can change any of these later in Settings → Permissions status.",
                color = Neon.Dim, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
