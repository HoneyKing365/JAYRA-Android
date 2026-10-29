package ai.jayra.companion.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * Primary-clip listener for the Universal Shared Clipboard.
 * Android 10+ only lets the focused app (or the default IME) read the clipboard, so changes made while
 * JAYRA is in the background are picked up via [readNow] as soon as the app regains window focus.
 */
class JayraClipboardListener(private val context: Context) {
    private val cm = context.getSystemService(ClipboardManager::class.java)
    private val listener = ClipboardManager.OnPrimaryClipChangedListener { readNow() }

    @Volatile private var running = false
    @Volatile private var lastText: String? = null

    var onLocalCopy: ((String) -> Unit)? = null

    fun start() {
        if (running) return
        cm.addPrimaryClipChangedListener(listener)
        running = true
    }

    fun stop() {
        if (!running) return
        cm.removePrimaryClipChangedListener(listener)
        running = false
    }

    fun readNow() {
        if (!running) return
        val text = try {
            val clip = cm.primaryClip ?: return
            if (clip.itemCount == 0) return
            // Honour the "sensitive content" flag (password managers, OTP copies on Android 13+).
            if (clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true) return
            clip.getItemAt(0).coerceToText(context)?.toString()
        } catch (e: Exception) {
            null
        }
        if (text.isNullOrEmpty() || text == lastText || text.length > MAX_CHARS) return
        lastText = text
        onLocalCopy?.invoke(text)
    }

    /** Applies text copied on the PC; remembered so it is not echoed straight back. */
    fun applyRemote(text: String) {
        lastText = text
        try {
            cm.setPrimaryClip(ClipData.newPlainText("JAYRA", text))
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val MAX_CHARS = 100_000
    }
}
