package ai.jayra.companion

import ai.jayra.companion.data.SecureStore
import ai.jayra.companion.runtime.BridgeRuntime
import ai.jayra.companion.service.AppVisibility
import ai.jayra.companion.service.JayraForegroundService
import ai.jayra.companion.theme.JayraTheme
import ai.jayra.companion.ui.MainScreen
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import org.koin.android.ext.android.inject

/** FragmentActivity (not plain ComponentActivity) because BiometricPrompt requires one. */
class MainActivity : FragmentActivity() {
    private val runtime: BridgeRuntime by inject()
    private val store: SecureStore by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { JayraTheme { MainScreen() } }
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.foreground = true
        if (!store.token.isNullOrBlank()) JayraForegroundService.start(this)
    }

    override fun onStop() {
        AppVisibility.foreground = false
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) runtime.onAppFocused()
    }
}
