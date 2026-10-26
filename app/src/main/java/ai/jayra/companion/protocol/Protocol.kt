package ai.jayra.companion.protocol

import org.json.JSONObject

object Protocol {
    const val VERSION = 1
    const val MAX_FRAME_CHARS = 64 * 1024
    const val COMMAND_TIMEOUT_MS = 10_000L   // host gives up after 12 s
    const val STATUS_INTERVAL_MS = 60_000L
}

enum class Priority { LOW, NORMAL, HIGH }
enum class AppId(val wire: String) { WHATSAPP("whatsapp"), CAMERA("camera"), SETTINGS("settings") }
enum class MediaOp { PLAY, PAUSE, TOGGLE, NEXT, PREVIOUS, VOLUME }
enum class FinderMode { RING, VIBRATE }

/** Version-1 allow-listed laptop-to-phone commands (spec section 3.5). */
sealed interface PhoneCommand {
    data class TriggerNotification(val title: String, val body: String, val priority: Priority) : PhoneCommand
    data class LaunchApp(val appId: AppId) : PhoneCommand
    /** Opens a WhatsApp draft for the owner to review. Never sends automatically. */
    data class WhatsappDraft(val phoneNumber: String, val message: String) : PhoneCommand
    data class MediaControl(val operation: MediaOp, val volume: Int?) : PhoneCommand
    data object QueryPhoneStatus : PhoneCommand
    data class RingtoneFinder(val mode: FinderMode, val durationSec: Int) : PhoneCommand
}

class ProtocolException(message: String) : Exception(message)

/** Independent on-device validation; the host validates too, but the phone never trusts it. */
object CommandParser {
    private val E164 = Regex("""^\+[1-9]\d{6,14}$""")

    fun parse(cmd: JSONObject): PhoneCommand {
        val action = cmd.opt("action") as? String ?: throw ProtocolException("Missing 'action'.")
        return when (action) {
            "TRIGGER_NOTIFICATION" -> PhoneCommand.TriggerNotification(
                title = cmd.text("title", 1, 120),
                body = cmd.text("body", 1, 1000),
                priority = when (cmd.optText("priority") ?: "normal") {
                    "low" -> Priority.LOW
                    "normal" -> Priority.NORMAL
                    "high" -> Priority.HIGH
                    else -> throw ProtocolException("Invalid 'priority'.")
                },
            )

            "LAUNCH_APP" -> PhoneCommand.LaunchApp(
                when (cmd.optText("appId")) {
                    "whatsapp" -> AppId.WHATSAPP
                    "camera" -> AppId.CAMERA
                    "settings" -> AppId.SETTINGS
                    else -> throw ProtocolException("'appId' must be whatsapp, camera or settings.")
                },
            )

            "WHATSAPP_DRAFT" -> {
                val number = cmd.text("phoneNumber", 8, 16)
                if (!E164.matches(number)) throw ProtocolException("'phoneNumber' must be E.164, e.g. +14155552671.")
                PhoneCommand.WhatsappDraft(number, cmd.text("message", 1, 2000))
            }

            "MEDIA_CONTROL" -> {
                val op = when (cmd.optText("operation")) {
                    "play" -> MediaOp.PLAY
                    "pause" -> MediaOp.PAUSE
                    "toggle" -> MediaOp.TOGGLE
                    "next" -> MediaOp.NEXT
                    "previous" -> MediaOp.PREVIOUS
                    "volume" -> MediaOp.VOLUME
                    else -> throw ProtocolException("Invalid media 'operation'.")
                }
                val volume = cmd.optInteger("volume", 0..100)
                if (op == MediaOp.VOLUME && volume == null) throw ProtocolException("'volume' (0-100) is required.")
                PhoneCommand.MediaControl(op, if (op == MediaOp.VOLUME) volume else null)
            }

            "QUERY_PHONE_STATUS" -> PhoneCommand.QueryPhoneStatus

            "TRIGGER_RINGTONE_FINDER" -> PhoneCommand.RingtoneFinder(
                mode = when (cmd.optText("mode") ?: "ring") {
                    "ring" -> FinderMode.RING
                    "vibrate" -> FinderMode.VIBRATE
                    else -> throw ProtocolException("Invalid finder 'mode'.")
                },
                durationSec = cmd.optInteger("durationSec", 5..60) ?: 30,
            )

            else -> throw ProtocolException("Unsupported action.")
        }
    }

    private fun JSONObject.optText(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return opt(key) as? String ?: throw ProtocolException("'$key' must be a string.")
    }

    private fun JSONObject.text(key: String, min: Int, max: Int): String {
        val v = optText(key) ?: throw ProtocolException("'$key' is required.")
        if (v.isBlank() || v.length !in min..max) throw ProtocolException("'$key' must be $min-$max characters.")
        return v
    }

    private fun JSONObject.optInteger(key: String, range: IntRange): Int? {
        if (!has(key) || isNull(key)) return null
        val n = opt(key) as? Number ?: throw ProtocolException("'$key' must be an integer.")
        if (n.toDouble() != n.toLong().toDouble()) throw ProtocolException("'$key' must be an integer.")
        val i = n.toLong()
        if (i < range.first || i > range.last) throw ProtocolException("'$key' must be ${range.first}-${range.last}.")
        return i.toInt()
    }
}
