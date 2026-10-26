package ai.jayra.companion.telemetry

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import androidx.core.content.ContextCompat
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Builds the status_update payload (spec 3.4). Unavailable values are omitted rather than faked.
 * SSID is private: only included when the user opted in AND Android granted location access.
 */
class StatusCollector(context: Context) {
    private val ctx = context.applicationContext

    fun collect(shareSsid: Boolean): JSONObject {
        val o = JSONObject()

        // Battery
        val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (bat != null) {
            val level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) o.put("batteryPercent", (level * 100f / scale).roundToInt())
            val st = bat.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            if (st != -1) {
                o.put("batteryCharging", st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL)
            }
        }

        // Storage
        runCatching {
            val fs = StatFs(Environment.getDataDirectory().path)
            o.put("storageFreeBytes", fs.availableBytes)
            o.put("storageTotalBytes", fs.totalBytes)
        }

        // Wi-Fi
        val wifi = ctx.getSystemService(WifiManager::class.java)
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        o.put(
            "wifiState",
            when {
                onWifi -> "connected"
                wifi?.isWifiEnabled == false -> "disabled"
                else -> "disconnected"
            },
        )
        if (onWifi && shareSsid && has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            @Suppress("DEPRECATION")
            val ssid = wifi?.connectionInfo?.ssid?.removeSurrounding("\"")
                ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
            if (ssid != null) o.put("wifiSsid", ssid)
        }

        // Permission/availability booleans only - never live sensor samples.
        o.put(
            "sensors",
            JSONObject()
                .put("camera", has(Manifest.permission.CAMERA))
                .put("microphone", has(Manifest.permission.RECORD_AUDIO))
                .put("location", has(Manifest.permission.ACCESS_FINE_LOCATION)),
        )
        return o
    }

    private fun has(permission: String) =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED
}
