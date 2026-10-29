package ai.jayra.companion.ui.components

import ai.jayra.companion.model.AppPermission
import ai.jayra.companion.theme.JayraCardSurface
import ai.jayra.companion.theme.JayraCyanPrimary
import ai.jayra.companion.theme.JayraEmeraldSuccess
import ai.jayra.companion.theme.JayraOledBlack
import ai.jayra.companion.theme.TextMuted
import ai.jayra.companion.viewmodel.PermissionViewModel
import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** First-launch wizard: one permission per step; hidden forever once completed (re-openable from Settings). */
@Composable
fun OnboardingPermissionDialog(vm: PermissionViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    if (state.onboardingDone) return

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) vm.refresh() }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val steps = AppPermission.entries
    val permission = steps[state.step.coerceIn(0, steps.lastIndex)]
    val granted = state.granted[permission] == true

    val runtimeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }

    // Auto-advance as soon as a step is granted.
    LaunchedEffect(granted, state.step) { if (granted) vm.nextStep() }

    fun grant() {
        when (permission) {
            AppPermission.MICROPHONE -> runtimeLauncher.launch(Manifest.permission.RECORD_AUDIO)
            AppPermission.NOTIFICATIONS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) runtimeLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else vm.refresh()
            AppPermission.CAMERA -> runtimeLauncher.launch(Manifest.permission.CAMERA)
            AppPermission.ACTIVITY_RECOGNITION -> runtimeLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            AppPermission.ACCESSIBILITY -> context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            AppPermission.DND_ACCESS -> context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Column(
            Modifier.fillMaxWidth().background(JayraCardSurface, RoundedCornerShape(24.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("SYSTEM INITIALIZATION", style = MaterialTheme.typography.labelMedium, color = JayraCyanPrimary)
            LinearProgressIndicator(progress = { (state.step + 1f) / steps.size }, modifier = Modifier.fillMaxWidth(), color = JayraCyanPrimary)
            Text("Step ${state.step + 1} of ${steps.size}", style = MaterialTheme.typography.labelSmall, color = TextMuted)
            Text(permission.title, style = MaterialTheme.typography.titleLarge)
            Text(permission.rationale, style = MaterialTheme.typography.bodyMedium)
            if (permission == AppPermission.ACCESSIBILITY) {
                Text(
                    "Find “JAYRA Companion Control” and switch it on. On sideloaded builds Android 13+ may first require " +
                        "App info → ⋮ → Allow restricted settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
            Button(
                onClick = ::grant,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = JayraCyanPrimary, contentColor = JayraOledBlack)
            ) { Text(if (permission == AppPermission.ACCESSIBILITY || permission == AppPermission.DND_ACCESS) "OPEN SETTINGS" else "GRANT ACCESS") }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (granted) Text("✔ Granted", color = JayraEmeraldSuccess, style = MaterialTheme.typography.labelMedium)
                Row(Modifier.weight(1f)) {}
                TextButton(onClick = { vm.nextStep() }) { Text(if (permission.required) "Skip for now" else "Skip", color = TextMuted) }
            }
        }
    }
}
