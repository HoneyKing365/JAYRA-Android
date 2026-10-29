package ai.jayra.companion.di

import ai.jayra.companion.data.SecureStore
import ai.jayra.companion.net.FileTransferManager
import ai.jayra.companion.net.HostApi
import ai.jayra.companion.net.JayraBridgeClient
import ai.jayra.companion.net.LanDiscoveryManager
import ai.jayra.companion.runtime.BridgeRuntime
import ai.jayra.companion.service.AmbientAudioPlayer
import ai.jayra.companion.service.AppLaunchManager
import ai.jayra.companion.service.AudioRecordStreamer
import ai.jayra.companion.service.BiometricAuthManager
import ai.jayra.companion.service.DeviceControlManager
import ai.jayra.companion.service.JayraClipboardListener
import ai.jayra.companion.service.NotificationHelper
import ai.jayra.companion.service.PcmAudioPlayer
import ai.jayra.companion.service.ProximityEngine
import ai.jayra.companion.service.VibeTelemetryManager
import ai.jayra.companion.service.WifiSignalMonitor
import ai.jayra.companion.viewmodel.CompanionViewModel
import ai.jayra.companion.viewmodel.PermissionViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

val appModule = module {
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // WebSockets: liveness is handled by pings
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
    single { SecureStore(androidContext()) }
    single { HostApi(get(), get()) }
    single { JayraBridgeClient(get(), get()) }
    single { NotificationHelper(androidContext()) }
    single { FileTransferManager(androidContext(), get(), get(), get()) }
    single { LanDiscoveryManager(androidContext()) }
    single { AppLaunchManager(androidContext()) }
    single { DeviceControlManager(androidContext(), get(), get()) }
    single { JayraClipboardListener(androidContext()) }
    single { AudioRecordStreamer(androidContext(), get()) }
    single { PcmAudioPlayer(get()) }
    single { AmbientAudioPlayer(androidContext()) }
    single { ProximityEngine() }
    single { WifiSignalMonitor(androidContext()) }
    single { VibeTelemetryManager(androidContext()) }
    single { BiometricAuthManager(androidContext()) }
    single {
        BridgeRuntime(
            context = androidContext(),
            scope = get(),
            store = get(),
            client = get(),
            api = get(),
            files = get(),
            discovery = get(),
            apps = get(),
            device = get(),
            notifier = get(),
            clipboard = get(),
            mic = get(),
            player = get(),
            ambient = get(),
            proximityEngine = get(),
            wifi = get(),
            vibeSensors = get(),
            biometric = get()
        )
    }
    viewModel { CompanionViewModel(get()) }
    viewModel { PermissionViewModel(androidApplication(), get()) }
}
