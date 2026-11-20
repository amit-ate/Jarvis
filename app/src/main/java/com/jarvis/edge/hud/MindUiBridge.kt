package com.jarvis.edge.hud

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class JournalItemUi(
    val id: Long,
    val type: String,
    val summary: String,
    val timestampMs: Long,
    val undone: Boolean,
)

data class FactItemUi(
    val id: Long,
    val text: String,
    val confidence: Float,
    val createdAtMs: Long,
)

data class VocabItemUi(
    val id: Long,
    val phrase: String,
    val source: String,
)

data class CorrectionItemUi(
    val id: Long,
    val idKey: Long,
    val heard: String,
    val meant: String,
    val hitCount: Int,
)

data class ShortcutItemUi(
    val id: Long,
    val utterance: String,
    val toolName: String,
    val argsSummary: String,
    val hitCount: Int,
)

data class BrainVersionUi(
    val id: Long,
    val label: String,
    val adapterPath: String?,
    val evalScore: Float,
    val echoRate: Float,
    val toolAccuracy: Float,
    val active: Boolean,
)

data class ResourceStatusUi(
    val batteryPct: Int = 0,
    val charging: Boolean = false,
    val batteryTempC: Float = 0f,
    val maxCpuTempC: Float = 0f,
    val healthLevel: String = "NORMAL",
    val availRamMb: Long = 0,
    val totalRamMb: Long = 0,
    val littleCpuMhz: Int = 0,
    val bigCpuMhz: Int = 0,
    val activeModel: String = "qwen2.5-0.5b-instruct-q4_0",
    val activeExperts: List<String> = emptyList(),
    val lux: Float = 0f,
    val nearProximity: Boolean = false,
    val lastReflectionMs: Long = 0L,
    val episodeCount: Int = 0,
    val statusNotice: String = "",
)

data class MindUiState(
    val learningPaused: Boolean = false,
    val journal: List<JournalItemUi> = emptyList(),
    val facts: List<FactItemUi> = emptyList(),
    val vocab: List<VocabItemUi> = emptyList(),
    val corrections: List<CorrectionItemUi> = emptyList(),
    val shortcuts: List<ShortcutItemUi> = emptyList(),
    val versions: List<BrainVersionUi> = emptyList(),
    val resources: ResourceStatusUi = ResourceStatusUi(),
)

interface MindUiActions {
    fun undoJournal(id: Long)
    fun deleteFact(id: Long)
    fun deleteVocab(id: Long)
    fun deleteCorrection(id: Long)
    fun deleteShortcut(id: Long)
    fun activateVersion(id: Long)
    fun setLearningPaused(paused: Boolean)
    fun exportJson()
    fun triggerReflection()
    fun refresh()
}

/**
 * Process-local reactive bridge connecting JarvisService's memory/evolution engine
 * with the Compose Mind inspector UI without introducing direct service coupling.
 */
object MindUiBridge {
    private val _state = MutableStateFlow(MindUiState())
    val state: StateFlow<MindUiState> = _state.asStateFlow()

    @Volatile
    var actions: MindUiActions? = null

    fun publish(newState: MindUiState) {
        _state.value = newState
    }

    fun undoJournal(id: Long) {
        actions?.undoJournal(id)
    }

    fun deleteFact(id: Long) {
        actions?.deleteFact(id)
    }

    fun deleteVocab(id: Long) {
        actions?.deleteVocab(id)
    }

    fun deleteCorrection(id: Long) {
        actions?.deleteCorrection(id)
    }

    fun deleteShortcut(id: Long) {
        actions?.deleteShortcut(id)
    }

    fun activateVersion(id: Long) {
        actions?.activateVersion(id)
    }

    fun setLearningPaused(paused: Boolean) {
        actions?.setLearningPaused(paused)
    }

    fun exportJson() {
        actions?.exportJson()
    }

    fun triggerReflection() {
        actions?.triggerReflection()
    }

    fun refresh() {
        actions?.refresh()
    }

    fun resetForTest() {
        actions = null
        _state.value = MindUiState()
    }
}
