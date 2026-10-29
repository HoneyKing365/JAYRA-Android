package ai.jayra.companion.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// ───────────────────────── helpers ─────────────────────────

fun JSONObject.optStringOrNull(name: String): String? =
    if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotEmpty() } else null

fun JSONObject.optDoubleOrNull(name: String): Double? =
    if (has(name) && !isNull(name)) optDouble(name).takeIf { !it.isNaN() } else null

fun JSONArray.toStringList(): List<String> = (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotEmpty() } }

// ───────────────────────── connection ─────────────────────────

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

enum class ChannelKind(val path: String) {
    LIVE("live"),
    BRIDGE("mobile-bridge")
}

data class HostEndpoint(val host: String, val port: Int) {
    val display: String get() = "$host:$port"
}

// ───────────────────────── audio routing ─────────────────────────

enum class AudioRoutingMode(val label: String, val badge: String) {
    DESKTOP_ONLY("Desktop Only", "DESKTOP"),
    PHONE_ONLY("Phone Only (PCM Stream)", "PHONE"),
    BOTH("Dual Playback (Both)", "DUAL");

    val includesPhone: Boolean get() = this != DESKTOP_ONLY

    companion object {
        fun parse(value: String?): AudioRoutingMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: DESKTOP_ONLY
    }
}

// ───────────────────────── character state ─────────────────────────

data class CharacterVisualState(
    val state: String = "idle",
    val emotion: String = "neutral",
    val audioLevel: Float = 0f
) {
    companion object {
        fun fromJson(j: JSONObject) = CharacterVisualState(
            state = j.optString("state", "idle").lowercase(),
            emotion = j.optString("emotion", "neutral").lowercase(),
            audioLevel = j.optDouble("audioLevel", 0.0).toFloat().coerceIn(0f, 1f)
        )
    }
}

// ───────────────────────── ReAct tasks ─────────────────────────

enum class ReActStage {
    IDLE, PLANNING, EXECUTING, VERIFYING, COMPLETE, FAILED;

    val isTerminal: Boolean get() = this == IDLE || this == COMPLETE || this == FAILED

    companion object {
        fun parse(v: String?): ReActStage = entries.firstOrNull { it.name.equals(v, true) } ?: EXECUTING
    }
}

data class ReActStep(
    val stage: ReActStage,
    val stepNumber: Int,
    val totalSteps: Int,
    val thought: String,
    val tool: String?,
    val output: String?,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toLogLine(): String = buildString {
        append("[${stepNumber}/${totalSteps}] ${stage.name}")
        if (!tool.isNullOrBlank()) append(" · $tool")
        if (thought.isNotBlank()) append("\n  ▸ $thought")
        if (!output.isNullOrBlank()) append("\n  ◂ $output")
    }

    companion object {
        fun fromJson(j: JSONObject) = ReActStep(
            stage = ReActStage.parse(j.optString("stage")),
            stepNumber = j.optInt("stepNumber", 0),
            totalSteps = j.optInt("totalSteps", 0),
            thought = j.optString("thought", ""),
            tool = j.optStringOrNull("tool"),
            output = j.optStringOrNull("output")
        )
    }
}

data class ActiveTask(
    val goal: String,
    val stage: ReActStage,
    val stepNumber: Int,
    val totalSteps: Int
)

data class TaskLogLine(val text: String, val stage: ReActStage?, val timestamp: Long = System.currentTimeMillis())

// ───────────────────────── session handover ─────────────────────────

data class SessionHandover(
    val conversationId: String,
    val lastUserQuery: String,
    val lastAssistantResponse: String,
    val activeTask: ActiveTask?,
    val audioRoutingMode: AudioRoutingMode,
    val timestamp: Long
) {
    companion object {
        fun fromJson(s: JSONObject): SessionHandover {
            val task = s.optJSONObject("activeTask")?.let {
                ActiveTask(
                    goal = it.optString("goal"),
                    stage = ReActStage.parse(it.optString("stage")),
                    stepNumber = it.optInt("stepNumber"),
                    totalSteps = it.optInt("totalSteps")
                )
            }
            return SessionHandover(
                conversationId = s.optString("conversationId"),
                lastUserQuery = s.optString("lastUserQuery"),
                lastAssistantResponse = s.optString("lastAssistantResponse"),
                activeTask = task,
                audioRoutingMode = AudioRoutingMode.parse(s.optString("audioRoutingMode")),
                timestamp = s.optLong("timestamp", System.currentTimeMillis())
            )
        }
    }
}

// ───────────────────────── authorization ─────────────────────────

data class AuthRequest(
    val commandId: String,
    val authId: String,
    val riskLevel: String,
    val actionDescription: String,
    val commandSnippet: String,
    val timeoutMs: Long,
    val receivedAtMs: Long = System.currentTimeMillis()
) {
    val isCritical: Boolean get() = riskLevel.equals("CRITICAL", true) || riskLevel.equals("HIGH", true)
}

// ───────────────────────── file transfer ─────────────────────────

enum class TransferDirection { UPLOAD, DOWNLOAD }
enum class TransferStatus { PENDING, ACTIVE, COMPLETE, FAILED }

data class FileTransferState(
    val transferId: String,
    val filename: String,
    val totalBytes: Long,
    val transferredBytes: Long,
    val direction: TransferDirection,
    val status: TransferStatus,
    val detail: String? = null
) {
    val progress: Float
        get() = if (totalBytes <= 0) 0f else (transferredBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    val percent: Int get() = (progress * 100).toInt()
}

// ───────────────────────── chat ─────────────────────────

enum class ChatRole { USER, ASSISTANT, SYSTEM }

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: ChatRole,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val reasoning: String? = null,
    val attachmentName: String? = null,
    val flashcard: DailyFlashcard? = null
)

// ───────────────────────── sassy copy / language ─────────────────────────

enum class SassyLanguage(val label: String) {
    URDU("اردو"),
    HINGLISH("Roman Urdu"),
    ENGLISH("English");

    companion object {
        fun parse(v: String?): SassyLanguage = entries.firstOrNull { it.name == v } ?: HINGLISH
    }
}

data class SassyText(val urdu: String, val hinglish: String, val english: String) {
    fun pick(lang: SassyLanguage): String {
        val chosen = when (lang) {
            SassyLanguage.URDU -> urdu
            SassyLanguage.HINGLISH -> hinglish
            SassyLanguage.ENGLISH -> english
        }
        return chosen.ifBlank { hinglish.ifBlank { english.ifBlank { urdu } } }
    }

    companion object {
        fun fromJson(j: JSONObject?): SassyText = SassyText(
            urdu = j?.optString("urdu", j.optString("textUrdu", "")) ?: "",
            hinglish = j?.optString("hinglish", j.optString("textHinglish", "")) ?: "",
            english = j?.optString("english", j.optString("textEnglish", "")) ?: ""
        )
    }
}

// ───────────────────────── ambient vibe ─────────────────────────

enum class VibeMode(val label: String) {
    DAYTIME_NORMAL("Daytime"),
    DEEP_FOCUS_NIGHT("Deep Focus"),
    CREATIVE_FLOW("Creative Flow"),
    REST_RECOVERY("Rest & Recovery"),
    MANUAL_OVERRIDE("Manual Lock");

    companion object {
        fun parse(v: String?): VibeMode = entries.firstOrNull { it.name.equals(v, true) } ?: DAYTIME_NORMAL
    }
}

data class AmbientAudio(val playing: Boolean, val track: String, val volume: Float, val profile: String)

data class VibeShift(
    val shiftId: String,
    val vibe: VibeMode,
    val previous: VibeMode,
    val reason: String,
    val hudBrightness: Float,
    val dndEnabled: Boolean,
    val ambient: AmbientAudio?,
    val sassy: SassyText,
    val timestamp: Long
) {
    companion object {
        fun fromJson(j: JSONObject): VibeShift {
            val a = j.optJSONObject("ambientAudio")?.let {
                AmbientAudio(
                    playing = it.optBoolean("playing"),
                    track = it.optString("track"),
                    volume = it.optDouble("volume", 0.2).toFloat().coerceIn(0f, 1f),
                    profile = it.optString("profile")
                )
            }
            return VibeShift(
                shiftId = j.optString("shiftId"),
                vibe = VibeMode.parse(j.optString("vibe")),
                previous = VibeMode.parse(j.optString("previousVibe")),
                reason = j.optString("reason"),
                hudBrightness = j.optDouble("hudBrightness", 1.0).toFloat().coerceIn(0.1f, 1f),
                dndEnabled = j.optBoolean("dndEnabled"),
                ambient = a,
                sassy = SassyText.fromJson(j.optJSONObject("sassyNotice")),
                timestamp = j.optLong("timestamp", System.currentTimeMillis())
            )
        }
    }
}

/** Local UI-facing vibe state (what the HUD should currently look like). */
data class VibeUiState(
    val vibe: VibeMode = VibeMode.DAYTIME_NORMAL,
    val hudBrightness: Float = 1f,
    val dndEnabled: Boolean = false,
    val reason: String = ""
)

// ───────────────────────── daily flashcard ─────────────────────────

data class ProjectBreakdown(val project: String, val durationMinutes: Int, val activitiesCount: Int)
data class KeyReference(val title: String, val url: String)

data class DailyFlashcard(
    val cardId: String,
    val date: String,
    val title: String,
    val totalActivities: Int,
    val focusMinutes: Int,
    val focusHours: Double,
    val projects: List<ProjectBreakdown>,
    val topFiles: List<String>,
    val references: List<KeyReference>,
    val recommendedTodos: List<String>,
    val sassy: SassyText,
    val markdownCard: String,
    val generatedAt: Long
) {
    companion object {
        fun fromJson(j: JSONObject): DailyFlashcard {
            val projects = j.optJSONArray("projectBreakdown")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
                    ProjectBreakdown(it.optString("project"), it.optInt("durationMinutes"), it.optInt("activitiesCount"))
                }
            } ?: emptyList()
            val refs = j.optJSONArray("keyReferences")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                    .map { KeyReference(it.optString("title"), it.optString("url")) }
            } ?: emptyList()
            val card = DailyFlashcard(
                cardId = j.optString("cardId", UUID.randomUUID().toString()),
                date = j.optString("date"),
                title = j.optString("title", "Daily Dev Pulse"),
                totalActivities = j.optInt("totalActivities"),
                focusMinutes = j.optInt("focusMinutes"),
                focusHours = j.optDouble("focusHours", j.optInt("focusMinutes") / 60.0),
                projects = projects,
                topFiles = j.optJSONArray("topFiles")?.toStringList() ?: emptyList(),
                references = refs,
                recommendedTodos = j.optJSONArray("recommendedTodos")?.toStringList() ?: emptyList(),
                sassy = SassyText.fromJson(j.optJSONObject("sassySummary")),
                markdownCard = j.optString("markdownCard", ""),
                generatedAt = j.optLong("generatedAt", System.currentTimeMillis())
            )
            return if (card.markdownCard.isBlank()) card.copy(markdownCard = card.synthesizeMarkdown()) else card
        }
    }

    /** Fallback when the host sent a truncated / empty markdown card. */
    fun synthesizeMarkdown(): String = buildString {
        appendLine("### ⚡ JAYRA Daily Pulse — $date")
        appendLine("**Focus:** ${"%.1f".format(focusHours)} h · **Activities:** $totalActivities")
        if (projects.isNotEmpty()) {
            appendLine("### Projects")
            projects.forEach { appendLine("- **${it.project}** — ${it.durationMinutes} min, ${it.activitiesCount} actions") }
        }
        if (topFiles.isNotEmpty()) {
            appendLine("### Top files")
            topFiles.forEach { appendLine("- $it") }
        }
        if (recommendedTodos.isNotEmpty()) {
            appendLine("### Tomorrow")
            recommendedTodos.forEach { appendLine("- $it") }
        }
    }
}

// ───────────────────────── spatial proximity ─────────────────────────

data class HandoffEvent(
    val state: String,
    val deviceId: String,
    val previousAudioMode: AudioRoutingMode,
    val newAudioMode: AudioRoutingMode,
    val proximityScore: Int,
    val smoothedRssi: Double?,
    val smoothedLatencyMs: Double?,
    val message: String,
    val timestamp: Long
) {
    companion object {
        fun fromJson(j: JSONObject) = HandoffEvent(
            state = j.optString("state").uppercase(),
            deviceId = j.optString("deviceId"),
            previousAudioMode = AudioRoutingMode.parse(j.optString("previousAudioMode")),
            newAudioMode = AudioRoutingMode.parse(j.optString("newAudioMode")),
            proximityScore = j.optInt("proximityScore"),
            smoothedRssi = j.optDoubleOrNull("smoothedRssi"),
            smoothedLatencyMs = j.optDoubleOrNull("smoothedLatencyMs"),
            message = j.optString("message"),
            timestamp = j.optLong("timestamp", System.currentTimeMillis())
        )
    }
}

// ───────────────────────── one-shot UI events ─────────────────────────

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
    data class Info(val title: String, val body: String) : UiEvent
    data class Screenshot(val bytes: ByteArray) : UiEvent
    data class Sassy(val text: String, val kind: String) : UiEvent
    data class Navigate(val tabIndex: Int) : UiEvent
    data class AuthRequired(val request: AuthRequest) : UiEvent
}
