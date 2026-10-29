package ai.jayra.companion.viewmodel

import ai.jayra.companion.model.AudioRoutingMode
import ai.jayra.companion.model.SassyLanguage
import ai.jayra.companion.net.DiscoveredHost
import ai.jayra.companion.net.HostFile
import ai.jayra.companion.runtime.BridgeRuntime
import ai.jayra.companion.service.JayraForegroundService
import android.app.Application
import android.net.Uri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Thin UI-facing façade over [BridgeRuntime] (which lives for the whole process). */
class CompanionViewModel(private val runtime: BridgeRuntime) : ViewModel() {

    // state
    val connection = runtime.connection
    val liveConnection = runtime.liveConnection
    val endpoint = runtime.endpoint
    val paired = runtime.paired
    val latencyMs = runtime.latencyMs
    val audioRouting = runtime.audioRouting
    val character = runtime.character
    val taskStage = runtime.taskStage
    val activeTask = runtime.activeTask
    val taskLog = runtime.taskLog
    val chat = runtime.chat
    val streamingText = runtime.streamingText
    val handover = runtime.handover
    val pendingAuth = runtime.pendingAuth
    val micActive = runtime.micActive
    val micLevel = runtime.micLevel
    val finderActive = runtime.finderActive
    val clipboardSync = runtime.clipboardSync
    val biometricGuard = runtime.biometricGuard
    val sassyLanguage = runtime.sassyLanguage
    val proximity = runtime.proximity
    val lastHandoff = runtime.lastHandoff
    val vibe = runtime.vibe
    val flashcard = runtime.flashcard
    val proximityEnabled = runtime.proximityEnabled
    val vibeEnabled = runtime.vibeEnabled
    val transfers = runtime.files.transfers
    val events = runtime.events

    // local UI state
    private val _discovered = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    val discovered: StateFlow<List<DiscoveredHost>> = _discovered.asStateFlow()
    private val _discovering = MutableStateFlow(false)
    val discovering: StateFlow<Boolean> = _discovering.asStateFlow()
    private val _pairing = MutableStateFlow(false)
    val pairing: StateFlow<Boolean> = _pairing.asStateFlow()
    private val _pairError = MutableStateFlow<String?>(null)
    val pairError: StateFlow<String?> = _pairError.asStateFlow()
    private val _hostFiles = MutableStateFlow<List<HostFile>?>(null)
    val hostFiles: StateFlow<List<HostFile>?> = _hostFiles.asStateFlow()

    // connection
    fun discover() {
        if (_discovering.value) return
        viewModelScope.launch {
            _discovering.value = true
            _discovered.value = runtime.discover()
            _discovering.value = false
        }
    }

    fun pair(app: Application, host: String, port: Int, code: String) {
        viewModelScope.launch {
            _pairing.value = true
            _pairError.value = null
            runtime.pairAndConnect(host, port, code)
                .onSuccess { JayraForegroundService.start(app) }
                .onFailure { _pairError.value = it.message ?: "Pairing failed" }
            _pairing.value = false
        }
    }

    fun connectManual(app: Application, host: String, port: Int) {
        runtime.updateEndpoint(host, port)
        runtime.connect()
        JayraForegroundService.start(app)
    }

    fun handleScan(app: Application, raw: String): String {
        val msg = runtime.handleScan(raw)
        JayraForegroundService.start(app)
        return msg
    }

    fun disconnect() = runtime.disconnect()
    fun forgetHost() = runtime.forgetHost()

    // chat / voice
    fun sendUserInput(text: String) = runtime.sendUserInput(text)
    fun toggleMic() = if (micActive.value) runtime.stopMic() else { runtime.startMic(); Unit }
    fun sendFileChat(uri: Uri, name: String?) = runtime.sendFileChat(uri, name)
    fun sendFileRest(uri: Uri) = runtime.sendFileRest(uri)
    fun dismissTransfer(id: String) = runtime.files.dismiss(id)
    fun clearFinishedTransfers() = runtime.files.clearFinished()

    fun refreshHostFiles() {
        viewModelScope.launch { _hostFiles.value = runtime.files.listHostFiles() }
    }

    fun closeHostFiles() {
        _hostFiles.value = null
    }

    fun downloadFile(name: String) = runtime.downloadFile(name)

    // quick actions
    fun pcTelemetry() = runtime.pcTelemetry()
    fun requestScreenshot() = runtime.requestScreenshot()
    fun pcMediaToggle() = runtime.pcMediaToggle()
    fun testFinder() = runtime.testFinder()
    fun stopFinder() = runtime.stopFinder()

    // tasks
    fun triggerTask(task: String) = runtime.triggerTask(task)
    fun emergencyStop() = runtime.emergencyStop()
    fun promoteTodos(todos: List<String>) = runtime.promoteTodos(todos)
    fun resumeHandover() = runtime.resumeHandover()
    fun dismissHandover() = runtime.dismissHandover()
    fun fetchSummary() = viewModelScope.launch { runtime.fetchDailySummary() }
    fun generateSummary() = viewModelScope.launch { runtime.generateDailySummary() }

    // security
    fun authorize(activity: FragmentActivity) {
        viewModelScope.launch { runtime.authorizeCurrent(activity) }
    }

    fun denyAuth() = runtime.respondAuth(false, "none", "Denied by user on phone")

    // settings
    fun setAudioRouting(m: AudioRoutingMode) = runtime.setAudioRouting(m)
    fun setClipboardSync(on: Boolean) = runtime.setClipboardSync(on)
    fun setBiometricGuard(on: Boolean) = runtime.setBiometricGuard(on)
    fun setSassyLanguage(l: SassyLanguage) = runtime.setSassyLanguage(l)
    fun toggleProximity() = viewModelScope.launch { runtime.toggleProximity() }
    fun toggleVibe() = viewModelScope.launch { runtime.toggleVibe() }
    fun refreshFeatureStatus() = viewModelScope.launch { runtime.refreshFeatureStatus() }
}
