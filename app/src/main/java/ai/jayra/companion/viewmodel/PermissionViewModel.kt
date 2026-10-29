package ai.jayra.companion.viewmodel

import ai.jayra.companion.data.SecureStore
import ai.jayra.companion.model.AppPermission
import ai.jayra.companion.service.JayraAccessibilityService
import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PermissionUiState(
    val granted: Map<AppPermission, Boolean> = emptyMap(),
    val onboardingDone: Boolean = true,
    val step: Int = 0
)

class PermissionViewModel(private val app: Application, private val store: SecureStore) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(PermissionUiState(onboardingDone = store.onboardingCompleted))
    val state: StateFlow<PermissionUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    private fun runtime(permission: String): Boolean =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    private fun accessibilityEnabled(): Boolean {
        if (JayraAccessibilityService.isRunning) return true
        val enabled = Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { it.startsWith("${app.packageName}/") }
    }

    fun isGranted(p: AppPermission): Boolean = when (p) {
        AppPermission.MICROPHONE -> runtime(Manifest.permission.RECORD_AUDIO)
        AppPermission.NOTIFICATIONS ->
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || runtime(Manifest.permission.POST_NOTIFICATIONS)
        AppPermission.CAMERA -> runtime(Manifest.permission.CAMERA)
        AppPermission.ACCESSIBILITY -> accessibilityEnabled()
        AppPermission.ACTIVITY_RECOGNITION -> runtime(Manifest.permission.ACTIVITY_RECOGNITION)
        AppPermission.DND_ACCESS -> app.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
    }

    fun refresh() {
        _state.value = _state.value.copy(granted = AppPermission.entries.associateWith { isGranted(it) })
    }

    fun nextStep() {
        val next = _state.value.step + 1
        if (next >= AppPermission.entries.size) completeOnboarding() else _state.value = _state.value.copy(step = next)
    }

    fun completeOnboarding() {
        store.onboardingCompleted = true
        _state.value = _state.value.copy(onboardingDone = true)
    }

    fun reopenOnboarding() {
        _state.value = _state.value.copy(onboardingDone = false, step = 0)
        refresh()
    }
}
