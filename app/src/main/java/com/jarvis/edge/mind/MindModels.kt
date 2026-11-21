package com.jarvis.edge.mind

/**
 * A single conversational turn recorded for reflection and episodic recall.
 */
data class Episode(
    val id: Long = 0,
    val timestampMs: Long,
    val liveTranscript: String,
    val accurateTranscript: String,
    val chosenTranscript: String,
    val reply: String,
    val toolName: String? = null,
    val toolArgsJson: String? = null,
    val echoed: Boolean = false,
    val outcomeOk: Boolean = true,
    val reflected: Boolean = false,
    val completed: Boolean = true,
)

/**
 * A durable semantic fact learned explicitly or via reflection.
 */
data class Fact(
    val id: Long = 0,
    val text: String,
    val embedding: FloatArray = FloatArray(0),
    val sourceEpisodeId: Long? = null,
    val confidence: Float = 1.0f,
    val status: String = "active",
    val createdAtMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Fact) return false
        return id == other.id &&
            text == other.text &&
            embedding.contentEquals(other.embedding) &&
            sourceEpisodeId == other.sourceEpisodeId &&
            confidence == other.confidence &&
            status == other.status &&
            createdAtMs == other.createdAtMs
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + (sourceEpisodeId?.hashCode() ?: 0)
        result = 31 * result + confidence.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + createdAtMs.hashCode()
        return result
    }
}

/**
 * A custom word or phrase promoted to Zipformer ASR hotwords.
 */
data class VocabEntry(
    val id: Long = 0,
    val phrase: String,
    val source: String,
    val status: String = "active",
    val createdAtMs: Long,
)

/**
 * A learned ASR correction rule mapping misheard text to intended text.
 */
data class AsrCorrection(
    val id: Long = 0,
    val heard: String,
    val meant: String,
    val hitCount: Int = 1,
    val status: String = "active",
    val createdAtMs: Long,
)

/**
 * A frequently repeated utterance-to-tool shortcut promoted for zero-LLM fast routing.
 */
data class LearnedShortcut(
    val id: Long = 0,
    val canonicalUtterance: String,
    val toolName: String,
    val argsJson: String,
    val embedding: FloatArray = FloatArray(0),
    val hitCount: Int = 1,
    val status: String = "active",
    val createdAtMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LearnedShortcut) return false
        return id == other.id &&
            canonicalUtterance == other.canonicalUtterance &&
            toolName == other.toolName &&
            argsJson == other.argsJson &&
            embedding.contentEquals(other.embedding) &&
            hitCount == other.hitCount &&
            status == other.status &&
            createdAtMs == other.createdAtMs
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + canonicalUtterance.hashCode()
        result = 31 * result + toolName.hashCode()
        result = 31 * result + argsJson.hashCode()
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + hitCount
        result = 31 * result + status.hashCode()
        result = 31 * result + createdAtMs.hashCode()
        return result
    }
}

/**
 * Auditable, undoable record of every memory or brain-version mutation.
 */
data class JournalEntry(
    val id: Long = 0,
    val type: String,
    val summary: String,
    val targetTable: String,
    val targetId: Long,
    val createdAtMs: Long,
    val undone: Boolean = false,
)

/**
 * Tracked base model + adapter version with evaluation gate metrics.
 */
data class ModelVersion(
    val id: Long = 0,
    val baseModel: String,
    val adapterPath: String?,
    val evalScore: Float,
    val echoRate: Float,
    val toolAccuracy: Float,
    val active: Boolean,
    val createdAtMs: Long,
)

/**
 * A scheduled reminder stored persistently in MindStore.
 */
data class Reminder(
    val id: Long = 0,
    val text: String,
    val triggerAtMs: Long,
    val fired: Boolean = false,
    val createdAtMs: Long,
)

/**
 * Point-in-time hardware, sensor, and cognitive self-awareness snapshot.
 */
data class SelfStateSnapshot(
    val batteryPct: Int = 100,
    val charging: Boolean = false,
    val batteryTempC: Float = 30.0f,
    val maxCpuTempC: Float = 35.0f,
    val healthLevelName: String = "NORMAL",
    val availRamMb: Long = 0L,
    val totalRamMb: Long = 0L,
    val littleFreqMhz: Int = 0,
    val bigFreqMhz: Int = 0,
    val activeModel: String = "google_gemma-3-1b-it-Q4_0",
    val activeExperts: List<String> = emptyList(),
    val factCount: Int = 0,
    val vocabCount: Int = 0,
    val correctionCount: Int = 0,
    val shortcutCount: Int = 0,
    val episodeCount: Int = 0,
    val lastReflectionMs: Long = 0L,
    val uptimeMs: Long = 0L,
    val lux: Float = 0f,
    val nearProximity: Boolean = false,
    val lastMotionMs: Long = 0L,
    val learningPaused: Boolean = false,
    val recoveredAtMs: Long? = null,
)
