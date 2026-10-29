package ai.jayra.companion.ui.components

import ai.jayra.companion.model.AuthRequest
import ai.jayra.companion.theme.JayraAmberWarning
import ai.jayra.companion.theme.JayraCrimsonDanger
import ai.jayra.companion.theme.JayraCyanPrimary
import ai.jayra.companion.theme.JayraElevatedCard
import ai.jayra.companion.theme.JayraNavySurface
import ai.jayra.companion.theme.JayraOledBlack
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Bottom sheet that gates high-risk PC actions behind biometric/PIN approval with a live countdown. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BiometricAuthSheet(
    request: AuthRequest,
    biometricGuard: Boolean,
    onAuthorize: () -> Unit,
    onDeny: () -> Unit
) {
    var remainingMs by remember(request.authId) { mutableLongStateOf(request.timeoutMs) }
    LaunchedEffect(request.authId) {
        while (remainingMs > 0) {
            remainingMs = (request.timeoutMs - (System.currentTimeMillis() - request.receivedAtMs)).coerceAtLeast(0)
            delay(200)
        }
    }
    val risk = if (request.isCritical) JayraCrimsonDanger else JayraAmberWarning
    ModalBottomSheet(
        onDismissRequest = onDeny,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = JayraNavySurface
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 22.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Filled.Warning, null, tint = risk, modifier = Modifier.size(26.dp))
                Text("AUTHORIZATION REQUIRED", style = MaterialTheme.typography.titleSmall, color = risk)
                Text(
                    request.riskLevel.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = JayraOledBlack,
                    modifier = Modifier.background(risk, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
            Text(request.actionDescription, style = MaterialTheme.typography.titleMedium)
            if (request.commandSnippet.isNotBlank()) {
                Text(
                    request.commandSnippet.take(600),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = JayraCyanPrimary,
                    modifier = Modifier.fillMaxWidth().background(JayraOledBlack, RoundedCornerShape(10.dp)).padding(12.dp)
                )
            }
            LinearProgressIndicator(
                progress = { (remainingMs.toFloat() / request.timeoutMs).coerceIn(0f, 1f) },
                color = risk,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Auto-denies in ${(remainingMs / 1000) + 1}s",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline
            )
            Button(
                onClick = onAuthorize,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = JayraCyanPrimary, contentColor = JayraOledBlack)
            ) {
                Icon(Icons.Filled.Fingerprint, null)
                Text(if (biometricGuard) "  AUTHORIZE WITH BIOMETRICS / PIN" else "  APPROVE", style = MaterialTheme.typography.labelLarge)
            }
            OutlinedButton(onClick = onDeny, modifier = Modifier.fillMaxWidth()) {
                Text("DENY", color = JayraCrimsonDanger, style = MaterialTheme.typography.labelLarge)
            }
            Row(Modifier.padding(bottom = 12.dp)) {}
        }
    }
}
