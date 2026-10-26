package ai.jayra.companion.ui

import ai.jayra.companion.BuildConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
fun JayraRoot(vm: MainViewModel, actions: UiActions) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val reduced = remember {
        android.provider.Settings.Global.getFloat(
            ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        ) == 0f
    }
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.message.value = null
        }
    }
    CompositionLocalProvider(LocalReducedMotion provides reduced) {
        Box(Modifier.fillMaxSize().background(Neon.Bg)) {
            if (!settings.onboardingDone) OnboardingWizard(vm, actions) else MainShell(vm, actions)
            SnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp),
            )
        }
    }
}

@Composable
private fun MainShell(vm: MainViewModel, a: UiActions) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf<Sheet?>(null) }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            DrawerContent(
                vm, a,
                onOpenSheet = { sheet = it; scope.launch { drawer.close() } },
                onClose = { scope.launch { drawer.close() } },
            )
        },
    ) {
        Scaffold(
            containerColor = Neon.Bg,
            topBar = { TopBar(vm, onMenu = { scope.launch { drawer.open() } }) },
            bottomBar = { BottomBar(tab) { tab = it } },
            floatingActionButton = { VoiceFab(vm, a) },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (tab) {
                    0 -> HomeTab(vm, a) { sheet = it }
                    1 -> ControlsTab(vm) { sheet = it }
                    2 -> ChatLogsTab(vm)
                    else -> SettingsTab(vm, a)
                }
            }
        }
    }
    sheet?.let { SheetHost(it, vm, a) { sheet = null } }
}

@Composable
private fun TopBar(vm: MainViewModel, onMenu: () -> Unit) {
    val state by vm.bridgeState.collectAsStateWithLifecycle()
    val conn = connOf(state)
    Row(
        Modifier.fillMaxWidth().background(Neon.Surface).statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .semantics { contentDescription = "Open menu" }
                .clickable(role = Role.Button, onClick = onMenu),
            contentAlignment = Alignment.Center,
        ) { JIcon(JI.MENU, Neon.Text) }
        HexLogo(conn.color, Modifier.size(40.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("JAYRA", fontWeight = FontWeight.Bold, letterSpacing = 4.sp, style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(conn)
                Spacer(Modifier.width(6.dp))
                Text(
                    conn.label.uppercase(), color = conn.color, letterSpacing = 1.sp,
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                )
            }
        }
        NeonButton(
            "Stop", onClick = { vm.killSwitch() },
            Modifier.semantics { contentDescription = "Emergency stop. Halts JAYRA on the PC." },
            accent = Neon.Red, filled = true,
        )
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
private fun BottomBar(tab: Int, onTab: (Int) -> Unit) {
    NavigationBar(containerColor = Neon.Surface) {
        listOf(
            Triple("Home", JI.HOME, 0),
            Triple("Controls", JI.CONTROLS, 1),
            Triple("Chat", JI.CHAT, 2),
            Triple("Settings", JI.SETTINGS, 3),
        ).forEach { (label, icon, i) ->
            NavigationBarItem(
                selected = tab == i,
                onClick = { onTab(i) },
                icon = { JIcon(icon, if (tab == i) Neon.Cyan else Neon.Dim) },
                label = { Text(label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Neon.Cyan, selectedTextColor = Neon.Cyan,
                    indicatorColor = Neon.Cyan.copy(alpha = 0.15f),
                    unselectedIconColor = Neon.Dim, unselectedTextColor = Neon.Dim,
                ),
            )
        }
    }
}

/** Hold to talk. TalkBack users get a double-tap toggle instead of press-and-hold. */
@Composable
private fun VoiceFab(vm: MainViewModel, a: UiActions) {
    val talking by vm.live.talking.collectAsStateWithLifecycle()
    val color = if (talking) Neon.Violet else Neon.Cyan
    Box(
        Modifier
            .size(64.dp)
            .neonGlow(color, 18.dp, 32.dp, if (talking) 0.85f else 0.5f)
            .clip(CircleShape)
            .background(color)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    if (!a.hasMic()) {
                        a.requestMic()
                    } else if (vm.startTalking()) {
                        try { tryAwaitRelease() } finally { vm.stopTalking() }
                    }
                })
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = if (talking) "Listening. Double tap to stop." else "Talk to JAYRA. Hold, or double tap to start."
                onClick(label = "Toggle listening") {
                    if (talking) vm.stopTalking() else if (a.hasMic()) vm.startTalking() else a.requestMic()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) { JIcon(JI.MIC, Neon.OnNeon, Modifier.size(30.dp)) }
}

@Composable
private fun DrawerContent(vm: MainViewModel, a: UiActions, onOpenSheet: (Sheet) -> Unit, onClose: () -> Unit) {
    val pairing by vm.pairing.collectAsStateWithLifecycle()
    val state by vm.bridgeState.collectAsStateWithLifecycle()
    var host by remember(pairing) { mutableStateOf(pairing?.host.orEmpty()) }
    var port by remember(pairing) { mutableStateOf(pairing?.port?.toString().orEmpty()) }
    var confirmForget by remember { mutableStateOf(false) }
    val running = state is ai.jayra.companion.net.LinkState.Open ||
        state is ai.jayra.companion.net.LinkState.Connecting ||
        state is ai.jayra.companion.net.LinkState.Waiting

    ModalDrawerSheet(drawerContainerColor = Neon.Surface) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HexLogo(Neon.Cyan, Modifier.size(48.dp))
                Column {
                    Text("JAYRA MOBILE", fontWeight = FontWeight.Bold, letterSpacing = 3.sp, color = Neon.Cyan)
                    Text("v${BuildConfig.VERSION_NAME}", color = Neon.Dim, style = MaterialTheme.typography.labelSmall)
                }
            }

            SectionLabel("Connection")
            if (pairing == null) {
                Text("No PC linked yet.", color = Neon.Dim)
                NeonButton("Scan QR", { onClose(); a.onScan() }, Modifier.fillMaxWidth(), filled = true)
                NeonButton("Link with code", { onOpenSheet(Sheet.PAIR) }, Modifier.fillMaxWidth())
            } else {
                OutlinedTextField(
                    host, { host = it }, label = { Text("PC address") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = neonFieldColors(), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = neonFieldColors(), modifier = Modifier.fillMaxWidth(),
                )
                NeonButton("Save endpoint", { vm.updateEndpoint(host, port) }, Modifier.fillMaxWidth())
                if (running) NeonButton("Pause link", { vm.pauseLink() }, Modifier.fillMaxWidth(), accent = Neon.Amber)
                else NeonButton("Resume link", { onClose(); a.onStartBridge() }, Modifier.fillMaxWidth(), filled = true)
                NeonButton("Link again (QR or code)", { onOpenSheet(Sheet.PAIR) }, Modifier.fillMaxWidth())
                NeonButton("Disconnect & forget PC", { confirmForget = true }, Modifier.fillMaxWidth(), accent = Neon.Red)
            }

            SectionLabel("About")
            Text(
                "JAYRA Mobile talks only to your own PC over your local network. Activity history keeps action names and outcomes, " +
                    "never message text, Wi-Fi names or screen content.",
                color = Neon.Dim, style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            containerColor = Neon.Surface,
            title = { Text("Disconnect and forget this PC?") },
            text = { Text("This erases the stored pairing from the phone. You'll need to scan the QR or enter the code again.") },
            confirmButton = {
                TextButton(onClick = { confirmForget = false; vm.forgetPc(); onClose() }) { Text("Forget", color = Neon.Red) }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel", color = Neon.Cyan) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetHost(sheet: Sheet, vm: MainViewModel, a: UiActions, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Neon.Surface, contentColor = Neon.Text) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (sheet) {
                Sheet.PAIR -> PairSheet(vm, a, onDismiss)
                Sheet.FINDER -> FinderSheet(vm, onDismiss)
                Sheet.WHATSAPP -> WhatsAppSheet(vm, onDismiss)
                Sheet.DEVICE -> DeviceSheet(vm)
            }
        }
    }
}
