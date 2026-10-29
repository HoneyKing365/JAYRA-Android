package ai.jayra.companion.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException

data class DiscoveredHost(val name: String, val port: Int, val addresses: List<String>) {
    val primary: String? get() = addresses.firstOrNull()
}

/** UDP multicast discovery: phone sends DISCOVER_JAYRA, the PC answers with JAYRA_OFFER (spec §5.1). */
class LanDiscoveryManager(context: Context) {
    private val appContext = context.applicationContext

    suspend fun discover(timeoutMs: Long = 3500): List<DiscoveredHost> = withContext(Dispatchers.IO) {
        val wifi = appContext.getSystemService(WifiManager::class.java)
        val multicastLock = wifi?.createMulticastLock("jayra-discovery")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        val found = LinkedHashMap<String, DiscoveredHost>()
        var socket: MulticastSocket? = null
        try {
            socket = MulticastSocket(null).apply {
                reuseAddress = true
                bindToWifi(this)
                bind(InetSocketAddress(0))
                soTimeout = 400
                timeToLive = 2
            }
            val payload = """{"type":"DISCOVER_JAYRA","version":1}""".toByteArray()
            val group = InetAddress.getByName(GROUP)
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            var nextSend = 0L
            val buf = ByteArray(4096)
            while (isActive && SystemClock.elapsedRealtime() < deadline) {
                val now = SystemClock.elapsedRealtime()
                if (now >= nextSend) {
                    socket.send(DatagramPacket(payload, payload.size, group, PORT))
                    nextSend = now + 800
                }
                try {
                    val p = DatagramPacket(buf, buf.size)
                    socket.receive(p)
                    parseOffer(String(p.data, 0, p.length), p.address?.hostAddress.orEmpty())?.let {
                        found[it.name + ":" + it.port] = it
                    }
                } catch (_: SocketTimeoutException) {
                    // keep listening until the deadline
                }
            }
        } catch (_: Exception) {
            // return whatever was collected
        } finally {
            runCatching { socket?.close() }
            if (multicastLock?.isHeld == true) multicastLock.release()
        }
        found.values.toList()
    }

    private fun bindToWifi(socket: MulticastSocket) {
        try {
            val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return
            cm.allNetworks
                .firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
                ?.bindSocket(socket)
        } catch (_: Exception) {
        }
    }

    private fun parseOffer(text: String, sourceAddress: String): DiscoveredHost? = try {
        val j = JSONObject(text)
        if (j.optString("type") != "JAYRA_OFFER") null
        else {
            val arr = j.optJSONArray("addresses")
            val addrs = (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optString(it)?.takeIf { s -> s.isNotBlank() } }
                .ifEmpty { listOfNotNull(sourceAddress.takeIf { it.isNotBlank() }) }
            DiscoveredHost(j.optString("host", "JAYRA"), j.optInt("port", 3000), addrs)
        }
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val GROUP = "239.255.42.99"
        const val PORT = 45842
    }
}
