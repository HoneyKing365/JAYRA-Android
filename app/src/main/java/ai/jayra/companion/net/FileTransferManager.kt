package ai.jayra.companion.net

import ai.jayra.companion.model.ChannelKind
import ai.jayra.companion.model.FileTransferState
import ai.jayra.companion.model.TransferDirection
import ai.jayra.companion.model.TransferStatus
import ai.jayra.companion.service.NotificationHelper
import ai.jayra.companion.util.NetUtil
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.max

data class HostFile(val name: String, val size: Long, val modified: String)

/** Bi-directional file transfer: chunked WebSocket (in-chat), REST upload/download, and host→phone chunks. */
class FileTransferManager(
    private val context: Context,
    private val client: JayraBridgeClient,
    private val api: HostApi,
    private val notifier: NotificationHelper
) {
    private val _transfers = MutableStateFlow<Map<String, FileTransferState>>(emptyMap())
    val transfers: StateFlow<Map<String, FileTransferState>> = _transfers.asStateFlow()

    private val acks = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()

    val downloadDir: File
        get() = (context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir).also { it.mkdirs() }

    private fun put(s: FileTransferState) = _transfers.update { it + (s.transferId to s) }
    private fun mod(id: String, f: (FileTransferState) -> FileTransferState) =
        _transfers.update { m -> m[id]?.let { m + (id to f(it)) } ?: m }

    fun dismiss(id: String) = _transfers.update { it - id }
    fun clearFinished() = _transfers.update { m -> m.filterValues { it.status == TransferStatus.ACTIVE || it.status == TransferStatus.PENDING } }

    // ───────────────────────── phone → PC ─────────────────────────

    private class Source(val name: String, val mime: String, val size: Long, val open: () -> InputStream, val cleanup: () -> Unit)

    private fun openSource(uri: Uri): Source {
        var name = "file_${System.currentTimeMillis()}"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        if (size >= 0) {
            return Source(name, mime, size, {
                context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $name")
            }, {})
        }
        // Provider did not report a size: stage into cache so we know the length up-front.
        val tmp = File(context.cacheDir, "upload_${System.nanoTime()}")
        (context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $name")).use { ins ->
            tmp.outputStream().use { out -> ins.copyTo(out) }
        }
        return Source(name, mime, tmp.length(), { tmp.inputStream() }, { tmp.delete() })
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    /** In-chat transfer: spec §2.1 `file_transfer_start` → 64 KB `file_transfer_chunk`s → host `file_transfer_complete`. */
    suspend fun uploadViaBridge(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        val src = try {
            openSource(uri)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        val id = "tr_" + UUID.randomUUID().toString().take(6)
        val total = src.size
        val totalChunks = max(1, ceil(total.toDouble() / CHUNK_BYTES).toInt())
        put(FileTransferState(id, src.name, total, 0, TransferDirection.UPLOAD, TransferStatus.PENDING))
        val ack = CompletableDeferred<JSONObject>()
        acks[id] = ack
        try {
            if (!client.isUp(ChannelKind.BRIDGE)) throw IOException("Bridge not connected")
            val start = JSONObject()
                .put("type", "file_transfer_start").put("transferId", id).put("filename", NetUtil.sanitizeFilename(src.name))
                .put("fileSize", total).put("totalChunks", totalChunks).put("mimeType", src.mime)
            if (!client.send(ChannelKind.BRIDGE, start)) throw IOException("Connection lost")
            mod(id) { it.copy(status = TransferStatus.ACTIVE) }

            var sentChunks = 0
            var sentBytes = 0L
            fun chunkFrame(idx: Int, data: String) = JSONObject()
                .put("type", "file_transfer_chunk").put("transferId", id)
                .put("chunkIndex", idx).put("totalChunks", totalChunks).put("dataBase64", data)

            src.open().use { input ->
                val buf = ByteArray(CHUNK_BYTES)
                while (true) {
                    val n = readFully(input, buf)
                    if (n <= 0) break
                    while (client.queueSize(ChannelKind.BRIDGE) > MAX_QUEUED_BYTES) {
                        if (!client.isUp(ChannelKind.BRIDGE)) throw IOException("Connection lost")
                        delay(25)
                    }
                    val b64 = Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)
                    if (!client.send(ChannelKind.BRIDGE, chunkFrame(sentChunks, b64))) throw IOException("Connection lost")
                    sentChunks++
                    sentBytes += n
                    mod(id) { it.copy(transferredBytes = sentBytes) }
                }
            }
            if (sentChunks == 0 && !client.send(ChannelKind.BRIDGE, chunkFrame(0, ""))) throw IOException("Connection lost")

            val result = withTimeoutOrNull(ACK_TIMEOUT_MS) { ack.await() } ?: throw IOException("Host did not acknowledge the file")
            if (!result.optBoolean("success", false)) throw IOException(result.optString("error", "Host rejected the file"))
            mod(id) { it.copy(status = TransferStatus.COMPLETE, transferredBytes = total) }
            notifier.postTransfer("Sent to PC", src.name)
            Result.success(result.optString("savedPath", src.name))
        } catch (e: Exception) {
            mod(id) { it.copy(status = TransferStatus.FAILED, detail = e.message) }
            Result.failure(e)
        } finally {
            acks.remove(id)
            src.cleanup()
        }
    }

    /** High-throughput REST upload (used by the “Send File” quick action). */
    suspend fun uploadViaRest(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        val src = try {
            openSource(uri)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        val id = "tr_" + UUID.randomUUID().toString().take(6)
        put(FileTransferState(id, src.name, src.size, 0, TransferDirection.UPLOAD, TransferStatus.ACTIVE))
        try {
            val res = api.upload(src.name, src.mime, src.size, src.open) { sent ->
                mod(id) { it.copy(transferredBytes = sent) }
            }
            if (!res.ok) throw IOException(res.error ?: "Upload failed")
            mod(id) { it.copy(status = TransferStatus.COMPLETE, transferredBytes = src.size) }
            notifier.postTransfer("Sent to PC", src.name)
            Result.success(res.json?.optString("filename")?.takeIf { it.isNotBlank() } ?: src.name)
        } catch (e: Exception) {
            mod(id) { it.copy(status = TransferStatus.FAILED, detail = e.message) }
            Result.failure(e)
        } finally {
            src.cleanup()
        }
    }

    // ───────────────────────── PC → phone ─────────────────────────

    suspend fun listHostFiles(): List<HostFile> {
        val r = api.get("/api/mobile/files")
        if (!r.ok) return emptyList()
        val arr: JSONArray = r.json?.optJSONArray("files")
            ?: runCatching { JSONArray(r.body) }.getOrNull()
            ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull {
            val name = it.optString("name", it.optString("filename"))
            if (name.isBlank()) null else HostFile(name, it.optLong("size"), it.optString("modifiedAt", it.optString("modified")))
        }
    }

    suspend fun downloadFromHost(filename: String): Result<File> {
        val id = "tr_" + UUID.randomUUID().toString().take(6)
        val target = uniqueFile(downloadDir, NetUtil.sanitizeFilename(filename))
        put(FileTransferState(id, filename, -1, 0, TransferDirection.DOWNLOAD, TransferStatus.ACTIVE))
        val r = api.download(filename, target) { done, total ->
            mod(id) { it.copy(transferredBytes = done, totalBytes = total) }
        }
        r.onSuccess {
            mod(id) { s -> s.copy(status = TransferStatus.COMPLETE, totalBytes = it.length(), transferredBytes = it.length()) }
            notifier.postTransfer("Saved from PC", it.name)
        }.onFailure { e -> mod(id) { it.copy(status = TransferStatus.FAILED, detail = e.message) } }
        return r
    }

    private class Incoming(val id: String, val name: String, val tmp: File, val out: FileOutputStream, var expected: Int = 0, var written: Long = 0)

    private val incoming = ConcurrentHashMap<String, Incoming>()

    fun onHostStart(cmd: JSONObject): JSONObject {
        val id = cmd.optString("transferId")
        if (id.isBlank()) return fail("transferId missing")
        val name = NetUtil.sanitizeFilename(cmd.optString("filename", "from_pc_${System.currentTimeMillis()}"))
        val size = cmd.optLong("fileSize", -1)
        return try {
            val tmp = File(downloadDir, ".$id.part")
            incoming[id] = Incoming(id, name, tmp, FileOutputStream(tmp))
            put(FileTransferState(id, name, size, 0, TransferDirection.DOWNLOAD, TransferStatus.ACTIVE))
            JSONObject().put("success", true).put("transferId", id)
        } catch (e: Exception) {
            fail(e.message ?: "Cannot create file")
        }
    }

    fun onHostChunk(cmd: JSONObject): JSONObject {
        val id = cmd.optString("transferId")
        val inc = incoming[id] ?: return fail("Unknown transfer $id")
        return try {
            val idx = cmd.optInt("chunkIndex", inc.expected)
            if (idx != inc.expected) throw IOException("Out-of-order chunk $idx (expected ${inc.expected})")
            val bytes = Base64.decode(cmd.optString("dataBase64"), Base64.DEFAULT)
            inc.out.write(bytes)
            inc.expected++
            inc.written += bytes.size
            mod(id) { it.copy(transferredBytes = inc.written) }
            JSONObject().put("success", true)
        } catch (e: Exception) {
            abort(id, e.message)
            fail(e.message ?: "Chunk failed")
        }
    }

    fun onHostComplete(cmd: JSONObject): JSONObject {
        val id = cmd.optString("transferId")
        val inc = incoming.remove(id) ?: return fail("Unknown transfer $id")
        return try {
            inc.out.flush()
            inc.out.close()
            if (!cmd.optBoolean("success", true)) {
                inc.tmp.delete()
                mod(id) { it.copy(status = TransferStatus.FAILED, detail = "Aborted by host") }
                return fail("Aborted by host")
            }
            val dest = uniqueFile(downloadDir, inc.name)
            if (!inc.tmp.renameTo(dest)) throw IOException("Cannot finalize file")
            mod(id) { it.copy(status = TransferStatus.COMPLETE, totalBytes = dest.length(), transferredBytes = dest.length()) }
            notifier.postTransfer("Received from PC", dest.name)
            JSONObject().put("success", true).put("savedPath", dest.absolutePath).put("size", dest.length())
        } catch (e: Exception) {
            mod(id) { it.copy(status = TransferStatus.FAILED, detail = e.message) }
            fail(e.message ?: "Finalize failed")
        }
    }

    private fun abort(id: String, why: String?) {
        incoming.remove(id)?.let {
            runCatching { it.out.close() }
            it.tmp.delete()
        }
        mod(id) { it.copy(status = TransferStatus.FAILED, detail = why) }
    }

    /** Host acknowledgement of a phone→PC bridge upload (`file_transfer_complete` top-level frame). */
    fun onUploadAck(frame: JSONObject) {
        acks[frame.optString("transferId")]?.complete(frame)
    }

    private fun fail(msg: String) = JSONObject().put("success", false).put("error", msg)

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (f.exists()) f = File(dir, "$stem ($i)$ext").also { i++ }
        return f
    }

    companion object {
        const val CHUNK_BYTES = 64 * 1024
        private const val MAX_QUEUED_BYTES = 2L * 1024 * 1024
        private const val ACK_TIMEOUT_MS = 30_000L
    }
}
