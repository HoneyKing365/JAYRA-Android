package ai.jayra.companion

import ai.jayra.companion.commands.AppLauncher
import ai.jayra.companion.commands.CommandRouter
import ai.jayra.companion.commands.ConfirmationManager
import ai.jayra.companion.commands.MediaHandler
import ai.jayra.companion.commands.NotificationHandler
import ai.jayra.companion.commands.Notifier
import ai.jayra.companion.commands.PhoneFinder
import ai.jayra.companion.data.PairingClient
import ai.jayra.companion.data.PairingStore
import ai.jayra.companion.data.SettingsStore
import ai.jayra.companion.net.BridgeController
import ai.jayra.companion.net.HostDiscovery
import ai.jayra.companion.security.SecretStore
import ai.jayra.companion.telemetry.StatusCollector
import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class JayraApp : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
        graph = Graph(this)
    }
}

/** Hand-wired singletons; small enough that a DI framework would only add weight. */
class Graph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val secrets = SecretStore(app)
    val pairingStore = PairingStore(secrets)
    val settingsStore = SettingsStore(app)
    val statusCollector = StatusCollector(app)
    val confirmations = ConfirmationManager(app)
    val finder = PhoneFinder(app, scope)
    val discovery = HostDiscovery(app)
    val pairingClient = PairingClient()

    val router = CommandRouter(
        settings = settingsStore,
        status = statusCollector,
        notifications = NotificationHandler(app),
        launcher = AppLauncher(app) {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        },
        media = MediaHandler(app),
        finder = finder,
    )

    val controller = BridgeController(app, scope, pairingStore, settingsStore, statusCollector, router, finder)

    init {
        finder.onEvent = controller::addLog
    }
}
