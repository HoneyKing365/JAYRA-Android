package ai.jayra.companion.ui

import ai.jayra.companion.model.SassyLanguage
import ai.jayra.companion.model.UiEvent
import ai.jayra.companion.model.ReActStage
import ai.jayra.companion.theme.JayraCyanPrimary
import ai.jayra.companion.theme.JayraElevatedCard
import ai.jayra.companion.theme.JayraOledBlack
import ai.jayra.companion.ui.components.BiometricAuthSheet
import ai.jayra.companion.ui.components.BottomNavBar
import ai.jayra.companion.ui.components.JayraTab
import ai.jayra.companion.ui.components.OnboardingPermissionDialog
import ai.jayra.companion.ui.components.QrScannerDialog
import ai.jayra.companion.ui.components.SassyBanner
import ai.jayra.companion.ui.tabs.ChatTab
import ai.jayra.companion.ui.tabs.HomeTab
import ai.jayra.companion.ui.tabs.SettingsTab
import ai.jayra.companion.ui.tabs.TasksTab
import ai.jayra.companion.viewmodel.CompanionViewModel
import ai.jayra.companion.viewmodel.PermissionViewModel
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

private data class SassyState(val text: String, val id: Long)

@Composable
fun MainScreen(
    vm: CompanionViewModel = koinViewModel(),
    permissionVm: PermissionViewModel = koinViewModel()
) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val activity = remember(context) { context.findFragmentActivity() }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    var info by remember { mutableStateOf<UiEvent.Info?>(null) }
    var screenshot by remember { mutableStateOf<ByteArray?>(null) }
    var sassy by remember { mutableStateOf<SassyState?>(null) }
    var showQr by remember { mutableStateOf(false) }

    val vibe by vm.vibe.collectAsStateWithLifecycle()
    val pendingAuth by vm.pendingAuth.collectAsStateWithLifecycle()
    val guard by vm.biometricGuard.collectAsStateWithLifecycle()
    val lang by vm.sassyLanguage.collectAsStateWithLifecycle()
    val stage by vm.taskStage.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is UiEvent.Message -> snackbar.showSnackbar(e.text)
                is UiEvent.Info -> info = e
                is UiEvent.Screenshot -> screenshot = e.bytes
                is UiEvent.Sassy -> sassy = SassyState(e.text, System.nanoTime())
                is UiEvent.Navigate -> tab = e.tabIndex
                is UiEvent.AuthRequired -> Unit // sheet is driven by pendingAuth
            }
        }
    }
    LaunchedEffect(sassy?.id) {
        if (sassy != null) {
            delay(6_500)
            sassy = null
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = JayraOledBlack,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = { BottomNavBar(tab, { tab = it }, taskBadge = stage != ReActStage.IDLE && !stage.isTerminal) }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (JayraTab.entries[tab]) {
                    JayraTab.HOME -> HomeTab(vm)
                    JayraTab.CHAT -> ChatTab(vm)
                    JayraTab.TASKS -> TasksTab(vm)
                    JayraTab.SETTINGS -> SettingsTab(vm, permissionVm, onScanQr = { showQr = true })
                }
            }
        }

        // Ambient vibe: HUD brightness dimming (does not intercept touches)
        val dim = (1f - vibe.hudBrightness).coerceIn(0f, 0.75f)
        if (dim > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))

        SassyBanner(
            text = sassy?.text,
            rtl = lang == SassyLanguage.URDU,
            onDismiss = { sassy = null },
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }

    pendingAuth?.let { req ->
        BiometricAuthSheet(
            request = req,
            biometricGuard = guard,
            onAuthorize = { activity?.let(vm::authorize) },
            onDeny = vm::denyAuth
        )
    }

    OnboardingPermissionDialog(permissionVm)

    if (showQr) {
        QrScannerDialog(
            onResult = { raw ->
                showQr = false
                val msg = vm.handleScan(app, raw)
                tab = JayraTab.SETTINGS.ordinal
                // surfaced via snackbar on next frame
                info = UiEvent.Info("QR scan", msg)
            },
            onDismiss = { showQr = false }
        )
    }

    info?.let { i ->
        AlertDialog(
            onDismissRequest = { info = null },
            title = { Text(i.title) },
            text = { Text(i.body, fontFamily = FontFamily.Monospace, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { info = null }) { Text("Close", color = JayraCyanPrimary) } },
            containerColor = JayraElevatedCard
        )
    }

    screenshot?.let { bytes ->
        val bmp = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
        AlertDialog(
            onDismissRequest = { screenshot = null },
            title = { Text("PC screenshot") },
            text = {
                if (bmp != null) Image(bmp, "PC screenshot", Modifier.fillMaxWidth())
                else Text("Could not decode the image")
            },
            confirmButton = { TextButton(onClick = { screenshot = null }) { Text("Close", color = JayraCyanPrimary) } },
            containerColor = JayraElevatedCard
        )
    }
}
