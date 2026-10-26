package ai.jayra.companion

import ai.jayra.companion.ui.JayraRoot
import ai.jayra.companion.ui.JayraTheme
import ai.jayra.companion.ui.MainViewModel
import ai.jayra.companion.ui.UiActions
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val raw = result.contents ?: return@registerForActivityResult   // user cancelled
        if (vm.pairFromText(raw)) startBridge()
    }

    /** Used when starting the bridge: the foreground notification should be visible. */
    private val notifForStart =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.startBridge() }

    /** Used by the wizard / settings "Grant" button. */
    private val notifForGrant = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.refreshPermissions()
        if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) openNotificationSettings()
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.refreshPermissions()
        if (granted) vm.message.value = "Microphone ready - hold the mic button to talk."
        else if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) openAppDetails()
    }

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.setShareSsid(granted)
        if (!granted) vm.message.value = "Location permission is required to read the Wi-Fi name."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val actions = UiActions(
            onScan = {
                scanLauncher.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("Scan the JAYRA pairing QR")
                        .setBeepEnabled(false)
                        .setOrientationLocked(false),
                )
            },
            onStartBridge = { startBridge() },
            onRequestLocation = { locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
            hasMic = { granted(Manifest.permission.RECORD_AUDIO) },
            requestMic = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
            requestNotifications = {
                if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                    notifForGrant.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    openNotificationSettings()   // permission is fine; notifications are switched off in system settings
                }
            },
            openAccessibilitySettings = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            openBatterySettings = { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
        )
        setContent { JayraTheme { JayraRoot(vm, actions) } }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshPermissions()   // the user may have changed things in system settings
    }

    /** Ask for notification permission first (Android 13+) so the status notification is visible. */
    private fun startBridge() {
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            notifForStart.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.startBridge()
        }
    }

    private fun openNotificationSettings() {
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
        )
    }

    private fun openAppDetails() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
        )
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
