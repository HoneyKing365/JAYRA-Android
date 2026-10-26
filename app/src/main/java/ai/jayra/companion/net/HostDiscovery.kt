package ai.jayra.companion.net

import ai.jayra.companion.protocol.Protocol
import ai.jayra.companion.security.NetworkPolicy
import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.util.UUID

/**
 * Optional UDP-multicast discovery (spec 2.3). Responses are unauthenticated hints only: they
 * carry no token, and the phone still needs QR/link pairing before it can open /mobile-bridge.
 */
class HostDiscovery(context: Context) {
    private val ctx = context.applicationContext

    data class Host(val hostId: String, val addresses: List<String>, val port: Int, val secure: Boolean)

    suspend fun discover(timeoutMs: Long = 3_000): List<Host> = withContext(Dispatchers.IO) {
        val wifi = ctx.getSystemService(WifiManager::class.java)
        val lock = wifi.createMulticastLock("jayra-discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
        try {
            val group = InetAddress.getByName(GROUP)
            val nonce = UUID.randomUUID().toString()
            val found = LinkedHashMap<String, Host>()
            val socket = MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
            }
            socket.use { s ->
                @Suppress("DEPRECATION")
                s.joinGroup(group)
                s.soTimeout = 400
                val hello = JSONObject()
                    .put("type", "jayra_discover")
                    .put("protocolVersion", Protocol.VERSION)
                    .put("nonce", nonce)
                    .toString().toByteArray()
                s.send(DatagramPacket(hello, hello.size, group, PORT))

                val deadline = SystemClock.elapsedRealtime() + timeoutMs
                val buf = ByteArray(2048)
                while (SystemClock.elapsedRealtime() < deadline) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    parse(String(packet.data, 0, packet.length, Charsets.UTF_8), nonce)
                        ?.let { found.putIfAbsent(it.hostId, it) }
                }
                @Suppress("DEPRECATION")
                runCatching { s.leaveGroup(group) }
            }
            found.values.toList()
        } finally {
            if (lock.isHeld) lock.release()
        }
    }

    private fun parse(text: String, nonce: String): Host? = try {
        val o = JSONObject(text)
        if (o.optString("type") != "jayra_host" ||
            o.optInt("protocolVersion", -1) != Protocol.VERSION ||
            o.optString("nonce") != nonce
        ) null
        else {
            val addrs = o.optJSONArray("addresses")
            val list = buildList {
                if (addrs != null) for (i in 0 until minOf(addrs.length(), 8)) {
                    val a = addrs.optString(i)
                    if (NetworkPolicy.isPrivateHost(a)) add(a)
                }
            }
            val port = o.optInt("port", -1)
            val id = o.optString("hostId").take(64)
            if (list.isEmpty() || port !in 1..65535 || id.isEmpty()) null
            else Host(id, list, port, o.optBoolean("secure", false))
        }
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val GROUP = "239.255.42.99"
        const val PORT = 45842
    }
}
