package com.jarvis.edge.mind

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistence contract for Jarvis's self-evolving memory, audit journal, and model versions.
 */
interface MindRepository {
    fun addEpisode(episode: Episode): Episode

    /** Write-ahead: persists the episode (empty reply, not ok, not completed) before Jarvis speaks. Returns its id. */
    fun beginEpisode(timestampMs: Long, live: String, accurate: String, chosen: String): Long

    /** Fills in the reply once spoken and marks the episode completed; outcome is ok unless echoed (or overridden). */
    fun completeEpisode(
        id: Long,
        reply: String,
        toolName: String?,
        toolArgsJson: String?,
        echoed: Boolean,
        outcomeOk: Boolean = !echoed,
    )

    /** Episodes begun but never completed at or after [sinceMs]. */
    fun listInterruptedEpisodes(sinceMs: Long): List<Episode>
    fun listUnreflectedEpisodes(limit: Int = 100): List<Episode>
    fun listRecentEpisodes(limit: Int = 100): List<Episode>
    fun markEpisodeSatisfactory(id: Long, outcomeOk: Boolean)
    fun markEpisodesReflected(ids: List<Long>)

    fun learnFact(
        text: String,
        embedding: FloatArray,
        sourceEpisodeId: Long? = null,
        confidence: Float = 1.0f,
    ): Fact

    fun learnVocab(phrase: String, source: String): VocabEntry
    fun learnCorrection(heard: String, meant: String): AsrCorrection

    fun promoteShortcut(
        canonicalUtterance: String,
        toolName: String,
        argsJson: String,
        embedding: FloatArray,
    ): LearnedShortcut

    fun recordModelVersion(
        baseModel: String,
        adapterPath: String?,
        evalScore: Float,
        echoRate: Float,
        toolAccuracy: Float,
        active: Boolean = false,
    ): ModelVersion

    fun addReminder(text: String, triggerAtMs: Long): Reminder
    fun recordInitiativeJournal(
        summary: String,
        targetTable: String = "initiative",
        targetId: Long = 0L,
    ): JournalEntry

    fun recordReflectionJournal(summary: String): JournalEntry

    fun recordRecoveryJournal(summary: String): JournalEntry

    fun undoJournalEntry(journalId: Long): Boolean
    fun undoLastLearning(): JournalEntry?
    fun forgetMatchingFact(query: String, queryEmbedding: FloatArray): Fact?

    fun deleteFact(id: Long): Boolean
    fun deleteVocab(id: Long): Boolean
    fun deleteCorrection(id: Long): Boolean
    fun deleteShortcut(id: Long): Boolean

    fun retrieveRelevantFacts(
        queryEmbedding: FloatArray,
        topK: Int = 4,
        minSim: Float = 0.20f,
    ): List<Fact>

    fun retrieveRelevantEpisode(
        queryEmbedding: FloatArray,
        minSim: Float = 0.30f,
    ): Episode?

    fun matchShortcut(
        queryEmbedding: FloatArray,
        minSim: Float = 0.85f,
    ): LearnedShortcut?

    fun listActiveFacts(limit: Int = 200): List<Fact>
    fun listActiveCorrectionsMap(): Map<String, String>
    fun listActiveCorrections(): List<AsrCorrection>
    fun listActiveVocab(): List<VocabEntry>
    fun listActiveShortcuts(): List<LearnedShortcut>
    fun listJournal(limit: Int = 100): List<JournalEntry>
    fun listModelVersions(): List<ModelVersion>
    fun activateModelVersion(id: Long): Boolean
    fun rollbackModelVersion(): ModelVersion?
    fun dueReminders(nowMs: Long = System.currentTimeMillis()): List<Reminder>
    fun markReminderFired(id: Long): Boolean

    fun isLearningPaused(): Boolean
    fun setLearningPaused(paused: Boolean)
    fun getMeta(key: String): String?
    fun setMeta(key: String, value: String)

    fun countActiveFacts(): Int
    fun countActiveVocab(): Int
    fun countActiveCorrections(): Int
    fun countActiveShortcuts(): Int
    fun countEpisodes(): Int

    fun exportToJson(file: File): Boolean
}

internal object MindJsonExporter {
    private val WORD_SPLIT = Regex("[^a-z0-9]+")

    fun export(repo: MindRepository, file: File): Boolean {
        return runCatching {
            file.parentFile?.mkdirs()
            val root = JSONObject().apply {
                put("learning_paused", repo.isLearningPaused())
                put("episode_count", repo.countEpisodes())
                put("facts", JSONArray().apply {
                    for (f in repo.listActiveFacts(500)) {
                        put(JSONObject().apply {
                            put("id", f.id)
                            put("text", f.text)
                            put("confidence", f.confidence.toDouble())
                            put("status", f.status)
                            put("created_at_ms", f.createdAtMs)
                        })
                    }
                })
                put("vocabulary", JSONArray().apply {
                    for (v in repo.listActiveVocab()) {
                        put(JSONObject().apply {
                            put("id", v.id)
                            put("phrase", v.phrase)
                            put("source", v.source)
                            put("status", v.status)
                            put("created_at_ms", v.createdAtMs)
                        })
                    }
                })
                put("corrections", JSONArray().apply {
                    for (c in repo.listActiveCorrections()) {
                        put(JSONObject().apply {
                            put("id", c.id)
                            put("heard", c.heard)
                            put("meant", c.meant)
                            put("hit_count", c.hitCount)
                            put("status", c.status)
                            put("created_at_ms", c.createdAtMs)
                        })
                    }
                })
                put("shortcuts", JSONArray().apply {
                    for (s in repo.listActiveShortcuts()) {
                        put(JSONObject().apply {
                            put("id", s.id)
                            put("canonical_utterance", s.canonicalUtterance)
                            put("tool_name", s.toolName)
                            put("args_json", s.argsJson)
                            put("hit_count", s.hitCount)
                            put("status", s.status)
                            put("created_at_ms", s.createdAtMs)
                        })
                    }
                })
                put("journal", JSONArray().apply {
                    for (j in repo.listJournal(500)) {
                        put(JSONObject().apply {
                            put("id", j.id)
                            put("type", j.type)
                            put("summary", j.summary)
                            put("target_table", j.targetTable)
                            put("target_id", j.targetId)
                            put("created_at_ms", j.createdAtMs)
                            put("undone", j.undone)
                        })
                    }
                })
                put("model_versions", JSONArray().apply {
                    for (m in repo.listModelVersions()) {
                        put(JSONObject().apply {
                            put("id", m.id)
                            put("base_model", m.baseModel)
                            put("adapter_path", m.adapterPath ?: JSONObject.NULL)
                            put("eval_score", m.evalScore.toDouble())
                            put("echo_rate", m.echoRate.toDouble())
                            put("tool_accuracy", m.toolAccuracy.toDouble())
                            put("active", m.active)
                            put("created_at_ms", m.createdAtMs)
                        })
                    }
                })
            }
            val tmp = File(file.parentFile ?: File("."), ".${file.name}.tmp")
            val text = root.toString(2)
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(text, Charsets.UTF_8)
                tmp.delete()
            }
            true
        }.getOrDefault(false)
    }

    fun keywordScore(query: String, candidate: String): Int {
        val words = query.lowercase().split(WORD_SPLIT).filter { it.length >= 2 }.distinct()
        if (words.isEmpty()) return 0
        val lower = candidate.lowercase()
        return words.count { it in lower }
    }
}

/**
 * Pure-Kotlin thread-safe in-memory implementation of [MindRepository] for fast JVM unit tests.
 */
class InMemoryMindStore(
    private val clockMs: () -> Long = System::currentTimeMillis,
) : MindRepository {

    private val lock = Any()
    private var nextId = 1L

    private val episodes = mutableListOf<Episode>()
    private val facts = mutableListOf<Fact>()
    private val vocab = mutableListOf<VocabEntry>()
    private val corrections = mutableListOf<AsrCorrection>()
    private val shortcuts = mutableListOf<LearnedShortcut>()
    private val journal = mutableListOf<JournalEntry>()
    private val modelVersions = mutableListOf<ModelVersion>()
    private val reminders = mutableListOf<Reminder>()
    private val meta = mutableMapOf<String, String>()

    override fun addEpisode(episode: Episode): Episode = synchronized(lock) {
        val assigned = episode.copy(id = nextId++)
        episodes.add(assigned)
        assigned
    }

    override fun beginEpisode(timestampMs: Long, live: String, accurate: String, chosen: String): Long =
        synchronized(lock) {
            val id = nextId++
            episodes.add(
                Episode(
                    id = id,
                    timestampMs = timestampMs,
                    liveTranscript = live,
                    accurateTranscript = accurate,
                    chosenTranscript = chosen,
                    reply = "",
                    outcomeOk = false,
                    reflected = false,
                    completed = false,
                )
            )
            id
        }

    override fun completeEpisode(
        id: Long,
        reply: String,
        toolName: String?,
        toolArgsJson: String?,
        echoed: Boolean,
        outcomeOk: Boolean,
    ) = synchronized(lock) {
        val idx = episodes.indexOfFirst { it.id == id }
        if (idx >= 0) {
            episodes[idx] = episodes[idx].copy(
                reply = reply,
                toolName = toolName,
                toolArgsJson = toolArgsJson,
                echoed = echoed,
                outcomeOk = outcomeOk,
                completed = true,
            )
        }
    }

    override fun listInterruptedEpisodes(sinceMs: Long): List<Episode> = synchronized(lock) {
        episodes.filter { !it.completed && it.timestampMs >= sinceMs }.sortedBy { it.timestampMs }
    }

    override fun listUnreflectedEpisodes(limit: Int): List<Episode> = synchronized(lock) {
        episodes.filter { !it.reflected }.sortedBy { it.timestampMs }.take(limit)
    }

    override fun listRecentEpisodes(limit: Int): List<Episode> = synchronized(lock) {
        episodes.sortedByDescending { it.timestampMs }.take(limit)
    }

    override fun markEpisodeSatisfactory(id: Long, outcomeOk: Boolean) = synchronized(lock) {
        val idx = episodes.indexOfFirst { it.id == id }
        if (idx >= 0) {
            episodes[idx] = episodes[idx].copy(outcomeOk = outcomeOk)
        }
    }

    override fun markEpisodesReflected(ids: List<Long>) = synchronized(lock) {
        val idSet = ids.toHashSet()
        for (i in episodes.indices) {
            if (episodes[i].id in idSet) {
                episodes[i] = episodes[i].copy(reflected = true)
            }
        }
    }

    override fun learnFact(
        text: String,
        embedding: FloatArray,
        sourceEpisodeId: Long?,
        confidence: Float,
    ): Fact = synchronized(lock) {
        val cleaned = text.trim()
        val existingIdx = facts.indexOfFirst {
            it.status == "active" && it.text.equals(cleaned, ignoreCase = true)
        }
        if (existingIdx >= 0) {
            val updated = facts[existingIdx].copy(
                confidence = maxOf(facts[existingIdx].confidence, confidence),
                embedding = if (embedding.isNotEmpty()) embedding.copyOf() else facts[existingIdx].embedding,
            )
            facts[existingIdx] = updated
            return updated
        }
        val now = clockMs()
        val item = Fact(
            id = nextId++,
            text = cleaned,
            embedding = embedding.copyOf(),
            sourceEpisodeId = sourceEpisodeId,
            confidence = confidence,
            status = "active",
            createdAtMs = now,
        )
        facts.add(item)
        journal.add(
            JournalEntry(
                id = nextId++,
                type = "fact",
                summary = "Learned fact: $cleaned",
                targetTable = "facts",
                targetId = item.id,
                createdAtMs = now,
            )
        )
        item
    }

    override fun learnVocab(phrase: String, source: String): VocabEntry = synchronized(lock) {
        val cleaned = phrase.trim()
        val existing = vocab.firstOrNull {
            it.status == "active" && it.phrase.equals(cleaned, ignoreCase = true)
        }
        if (existing != null) return existing
        val now = clockMs()
        val item = VocabEntry(
            id = nextId++,
            phrase = cleaned,
            source = source,
            status = "active",
            createdAtMs = now,
        )
        vocab.add(item)
        journal.add(
            JournalEntry(
                id = nextId++,
                type = "vocab",
                summary = "Learned word: $cleaned",
                targetTable = "vocabulary",
                targetId = item.id,
                createdAtMs = now,
            )
        )
        item
    }

    override fun learnCorrection(heard: String, meant: String): AsrCorrection = synchronized(lock) {
        val h = heard.trim()
        val m = meant.trim()
        val existingIdx = corrections.indexOfFirst {
            it.status == "active" &&
                it.heard.equals(h, ignoreCase = true) &&
                it.meant.equals(m, ignoreCase = true)
        }
        if (existingIdx >= 0) {
            val updated = corrections[existingIdx].copy(hitCount = corrections[existingIdx].hitCount + 1)
            corrections[existingIdx] = updated
            return updated
        }
        val now = clockMs()
        val item = AsrCorrection(
            id = nextId++,
            heard = h,
            meant = m,
            hitCount = 1,
            status = "active",
            createdAtMs = now,
        )
        corrections.add(item)
        journal.add(
            JournalEntry(
                id = nextId++,
                type = "correction",
                summary = "ASR correction: '$h' -> '$m'",
                targetTable = "corrections",
                targetId = item.id,
                createdAtMs = now,
            )
        )
        item
    }

    override fun promoteShortcut(
        canonicalUtterance: String,
        toolName: String,
        argsJson: String,
        embedding: FloatArray,
    ): LearnedShortcut = synchronized(lock) {
        val cleaned = canonicalUtterance.trim()
        val existingIdx = shortcuts.indexOfFirst {
            it.status == "active" &&
                it.toolName == toolName &&
                it.argsJson == argsJson &&
                it.canonicalUtterance.equals(cleaned, ignoreCase = true)
        }
        if (existingIdx >= 0) {
            val updated = shortcuts[existingIdx].copy(hitCount = shortcuts[existingIdx].hitCount + 1)
            shortcuts[existingIdx] = updated
            return updated
        }
        val now = clockMs()
        val item = LearnedShortcut(
            id = nextId++,
            canonicalUtterance = cleaned,
            toolName = toolName,
            argsJson = argsJson,
            embedding = embedding.copyOf(),
            hitCount = 1,
            status = "active",
            createdAtMs = now,
        )
        shortcuts.add(item)
        journal.add(
            JournalEntry(
                id = nextId++,
                type = "shortcut",
                summary = "Promoted shortcut: '$cleaned' -> $toolName",
                targetTable = "shortcuts",
                targetId = item.id,
                createdAtMs = now,
            )
        )
        item
    }

    override fun recordModelVersion(
        baseModel: String,
        adapterPath: String?,
        evalScore: Float,
        echoRate: Float,
        toolAccuracy: Float,
        active: Boolean,
    ): ModelVersion = synchronized(lock) {
        val now = clockMs()
        if (active) {
            for (i in modelVersions.indices) {
                if (modelVersions[i].active) {
                    modelVersions[i] = modelVersions[i].copy(active = false)
                }
            }
        }
        val item = ModelVersion(
            id = nextId++,
            baseModel = baseModel,
            adapterPath = adapterPath,
            evalScore = evalScore,
            echoRate = echoRate,
            toolAccuracy = toolAccuracy,
            active = active,
            createdAtMs = now,
        )
        modelVersions.add(item)
        journal.add(
            JournalEntry(
                id = nextId++,
                type = "model_version",
                summary = "Recorded model version: $baseModel (${adapterPath ?: "base"})",
                targetTable = "model_versions",
                targetId = item.id,
                createdAtMs = now,
            )
        )
        item
    }

    override fun addReminder(text: String, triggerAtMs: Long): Reminder = synchronized(lock) {
        val now = clockMs()
        val item = Reminder(
            id = nextId++,
            text = text.trim(),
            triggerAtMs = triggerAtMs,
            fired = false,
            createdAtMs = now,
        )
        reminders.add(item)
        journal.add(
            JournalEntry(
                id = nextId++,
                type = "reminder",
                summary = "Scheduled reminder: ${item.text}",
                targetTable = "reminders",
                targetId = item.id,
                createdAtMs = now,
            )
        )
        item
    }

    override fun recordInitiativeJournal(
        summary: String,
        targetTable: String,
        targetId: Long,
    ): JournalEntry = synchronized(lock) {
        val entry = JournalEntry(
            id = nextId++,
            type = "initiative",
            summary = summary,
            targetTable = targetTable,
            targetId = targetId,
            createdAtMs = clockMs(),
        )
        journal.add(entry)
        entry
    }

    override fun recordReflectionJournal(summary: String): JournalEntry = synchronized(lock) {
        val entry = JournalEntry(
            id = nextId++,
            type = "reflection",
            summary = summary,
            targetTable = "episodes",
            targetId = 0L,
            createdAtMs = clockMs(),
        )
        journal.add(entry)
        entry
    }

    override fun recordRecoveryJournal(summary: String): JournalEntry = synchronized(lock) {
        val entry = JournalEntry(
            id = nextId++,
            type = "recovery",
            summary = summary,
            targetTable = "meta",
            targetId = 0L,
            createdAtMs = clockMs(),
        )
        journal.add(entry)
        entry
    }

    override fun undoJournalEntry(journalId: Long): Boolean = synchronized(lock) {
        val idx = journal.indexOfFirst { it.id == journalId }
        if (idx < 0 || journal[idx].undone) return false
        val entry = journal[idx]
        when (entry.targetTable) {
            "facts" -> updateFactStatus(entry.targetId, "undone")
            "vocabulary" -> updateVocabStatus(entry.targetId, "undone")
            "corrections" -> updateCorrectionStatus(entry.targetId, "undone")
            "shortcuts" -> updateShortcutStatus(entry.targetId, "undone")
            "model_versions" -> {
                val mvIdx = modelVersions.indexOfFirst { it.id == entry.targetId }
                if (mvIdx >= 0) {
                    val wasActive = modelVersions[mvIdx].active
                    modelVersions[mvIdx] = modelVersions[mvIdx].copy(active = false)
                    if (wasActive) {
                        val fallbackIdx = modelVersions.indices.reversed().firstOrNull { it != mvIdx }
                        if (fallbackIdx != null) {
                            modelVersions[fallbackIdx] = modelVersions[fallbackIdx].copy(active = true)
                        }
                    }
                }
            }
            "reminders" -> {
                val rIdx = reminders.indexOfFirst { it.id == entry.targetId }
                if (rIdx >= 0) {
                    reminders[rIdx] = reminders[rIdx].copy(fired = true)
                }
            }
        }
        journal[idx] = entry.copy(undone = true)
        journal.add(
            JournalEntry(
                id = nextId++,
                type = "undo",
                summary = "Undid ${entry.type}: ${entry.summary}",
                targetTable = entry.targetTable,
                targetId = entry.targetId,
                createdAtMs = clockMs(),
            )
        )
        true
    }

    override fun undoLastLearning(): JournalEntry? = synchronized(lock) {
        val learningTypes = setOf("fact", "vocab", "correction", "shortcut", "model_version", "migration")
        val target = journal.asReversed().firstOrNull { !it.undone && it.type in learningTypes }
            ?: return null
        if (!undoJournalEntry(target.id)) return null
        target.copy(undone = true)
    }

    override fun forgetMatchingFact(query: String, queryEmbedding: FloatArray): Fact? = synchronized(lock) {
        val active = facts.filter { it.status == "active" }
        if (active.isEmpty()) return null
        val best = active.map { fact ->
            val sim = VectorMath.cosineSimilarity(queryEmbedding, fact.embedding)
            val kw = MindJsonExporter.keywordScore(query, fact.text)
            Triple(fact, sim, kw)
        }.filter { (_, sim, kw) -> sim >= 0.25f || kw > 0 }
            .maxByOrNull { (_, sim, kw) -> sim + kw * 0.25f }
            ?.first ?: return null

        updateFactStatus(best.id, "forgotten")
        journal.add(
            JournalEntry(
                id = nextId++,
                type = "undo",
                summary = "Forgot fact: ${best.text}",
                targetTable = "facts",
                targetId = best.id,
                createdAtMs = clockMs(),
            )
        )
        best.copy(status = "forgotten")
    }

    override fun deleteFact(id: Long): Boolean = synchronized(lock) {
        updateFactStatus(id, "deleted")
    }

    override fun deleteVocab(id: Long): Boolean = synchronized(lock) {
        updateVocabStatus(id, "deleted")
    }

    override fun deleteCorrection(id: Long): Boolean = synchronized(lock) {
        updateCorrectionStatus(id, "deleted")
    }

    override fun deleteShortcut(id: Long): Boolean = synchronized(lock) {
        updateShortcutStatus(id, "deleted")
    }

    override fun retrieveRelevantFacts(
        queryEmbedding: FloatArray,
        topK: Int,
        minSim: Float,
    ): List<Fact> = synchronized(lock) {
        facts.asSequence()
            .filter { it.status == "active" && it.embedding.isNotEmpty() }
            .map { it to VectorMath.cosineSimilarity(queryEmbedding, it.embedding) }
            .filter { it.second >= minSim }
            .sortedByDescending { it.second * it.first.confidence }
            .take(topK)
            .map { it.first }
            .toList()
    }

    override fun retrieveRelevantEpisode(queryEmbedding: FloatArray, minSim: Float): Episode? =
        synchronized(lock) {
            if (queryEmbedding.isEmpty()) return null
            episodes.asSequence()
                .map { ep ->
                    val epVec = VectorMath.simpleHashEmbed(
                        "${ep.chosenTranscript} ${ep.reply}",
                        queryEmbedding.size,
                    )
                    ep to VectorMath.cosineSimilarity(queryEmbedding, epVec)
                }
                .filter { it.second >= minSim }
                .maxByOrNull { it.second }
                ?.first
        }

    override fun matchShortcut(queryEmbedding: FloatArray, minSim: Float): LearnedShortcut? =
        synchronized(lock) {
            shortcuts.asSequence()
                .filter { it.status == "active" && it.embedding.isNotEmpty() }
                .map { it to VectorMath.cosineSimilarity(queryEmbedding, it.embedding) }
                .filter { it.second >= minSim }
                .maxByOrNull { it.second }
                ?.first
        }

    override fun listActiveFacts(limit: Int): List<Fact> = synchronized(lock) {
        facts.filter { it.status == "active" }.sortedByDescending { it.createdAtMs }.take(limit)
    }

    override fun listActiveCorrectionsMap(): Map<String, String> = synchronized(lock) {
        corrections.filter { it.status == "active" }.associate { it.heard to it.meant }
    }

    override fun listActiveCorrections(): List<AsrCorrection> = synchronized(lock) {
        corrections.filter { it.status == "active" }.sortedByDescending { it.hitCount }
    }

    override fun listActiveVocab(): List<VocabEntry> = synchronized(lock) {
        vocab.filter { it.status == "active" }.sortedByDescending { it.createdAtMs }
    }

    override fun listActiveShortcuts(): List<LearnedShortcut> = synchronized(lock) {
        shortcuts.filter { it.status == "active" }.sortedByDescending { it.hitCount }
    }

    override fun listJournal(limit: Int): List<JournalEntry> = synchronized(lock) {
        journal.sortedByDescending { it.id }.take(limit)
    }

    override fun listModelVersions(): List<ModelVersion> = synchronized(lock) {
        modelVersions.sortedByDescending { it.id }
    }

    override fun activateModelVersion(id: Long): Boolean = synchronized(lock) {
        val targetIdx = modelVersions.indexOfFirst { it.id == id }
        if (targetIdx < 0) return false
        for (i in modelVersions.indices) {
            modelVersions[i] = modelVersions[i].copy(active = (i == targetIdx))
        }
        true
    }

    override fun rollbackModelVersion(): ModelVersion? = synchronized(lock) {
        val activeIdx = modelVersions.indexOfFirst { it.active }
        val prev = if (activeIdx > 0) {
            modelVersions[activeIdx - 1]
        } else {
            modelVersions.asReversed().firstOrNull { !it.active }
        } ?: return null
        activateModelVersion(prev.id)
        prev.copy(active = true)
    }

    override fun dueReminders(nowMs: Long): List<Reminder> = synchronized(lock) {
        reminders.filter { !it.fired && it.triggerAtMs <= nowMs }.sortedBy { it.triggerAtMs }
    }

    override fun markReminderFired(id: Long): Boolean = synchronized(lock) {
        val idx = reminders.indexOfFirst { it.id == id }
        if (idx < 0) return false
        reminders[idx] = reminders[idx].copy(fired = true)
        true
    }

    override fun isLearningPaused(): Boolean = synchronized(lock) {
        meta["learning_paused"] == "true"
    }

    override fun setLearningPaused(paused: Boolean) = synchronized(lock) {
        meta["learning_paused"] = paused.toString()
    }

    override fun getMeta(key: String): String? = synchronized(lock) { meta[key] }

    override fun setMeta(key: String, value: String) = synchronized(lock) {
        meta[key] = value
    }

    override fun countActiveFacts(): Int = synchronized(lock) {
        facts.count { it.status == "active" }
    }

    override fun countActiveVocab(): Int = synchronized(lock) {
        vocab.count { it.status == "active" }
    }

    override fun countActiveCorrections(): Int = synchronized(lock) {
        corrections.count { it.status == "active" }
    }

    override fun countActiveShortcuts(): Int = synchronized(lock) {
        shortcuts.count { it.status == "active" }
    }

    override fun countEpisodes(): Int = synchronized(lock) { episodes.size }

    override fun exportToJson(file: File): Boolean = MindJsonExporter.export(this, file)

    /**
     * Migrates legacy `(text, createdAtMs)` rows (such as from `jarvis_notes.db`).
     */
    fun migrateLegacyNotes(rows: List<Pair<String, Long>>): Int = synchronized(lock) {
        var migrated = 0
        for ((text, ts) in rows) {
            val cleaned = text.trim()
            if (cleaned.isEmpty()) continue
            if (facts.any { it.status == "active" && it.text.equals(cleaned, ignoreCase = true) }) continue
            val item = Fact(
                id = nextId++,
                text = cleaned,
                embedding = VectorMath.simpleHashEmbed(cleaned),
                sourceEpisodeId = null,
                confidence = 1.0f,
                status = "active",
                createdAtMs = ts,
            )
            facts.add(item)
            journal.add(
                JournalEntry(
                    id = nextId++,
                    type = "migration",
                    summary = "Migrated note: $cleaned",
                    targetTable = "facts",
                    targetId = item.id,
                    createdAtMs = clockMs(),
                )
            )
            migrated++
        }
        migrated
    }

    private fun updateFactStatus(id: Long, status: String): Boolean {
        val idx = facts.indexOfFirst { it.id == id }
        if (idx < 0) return false
        facts[idx] = facts[idx].copy(status = status)
        return true
    }

    private fun updateVocabStatus(id: Long, status: String): Boolean {
        val idx = vocab.indexOfFirst { it.id == id }
        if (idx < 0) return false
        vocab[idx] = vocab[idx].copy(status = status)
        return true
    }

    private fun updateCorrectionStatus(id: Long, status: String): Boolean {
        val idx = corrections.indexOfFirst { it.id == id }
        if (idx < 0) return false
        corrections[idx] = corrections[idx].copy(status = status)
        return true
    }

    private fun updateShortcutStatus(id: Long, status: String): Boolean {
        val idx = shortcuts.indexOfFirst { it.id == id }
        if (idx < 0) return false
        shortcuts[idx] = shortcuts[idx].copy(status = status)
        return true
    }
}

/**
 * Android SQLite-backed implementation of [MindRepository] on `"jarvis_mind.db"`.
 */
class SqliteMindStore(
    private val context: Context,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION), MindRepository {

    private var migratedChecked = false

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE episodes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp_ms INTEGER NOT NULL,
                live_transcript TEXT NOT NULL,
                accurate_transcript TEXT NOT NULL,
                chosen_transcript TEXT NOT NULL,
                reply TEXT NOT NULL,
                tool_name TEXT,
                tool_args_json TEXT,
                echoed INTEGER NOT NULL DEFAULT 0,
                outcome_ok INTEGER NOT NULL DEFAULT 1,
                reflected INTEGER NOT NULL DEFAULT 0,
                completed INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE facts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                text TEXT NOT NULL,
                embedding BLOB NOT NULL,
                source_episode_id INTEGER,
                confidence REAL NOT NULL DEFAULT 1.0,
                status TEXT NOT NULL DEFAULT 'active',
                created_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE vocabulary (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                phrase TEXT NOT NULL,
                source TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'active',
                created_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE corrections (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                heard TEXT NOT NULL,
                meant TEXT NOT NULL,
                hit_count INTEGER NOT NULL DEFAULT 1,
                status TEXT NOT NULL DEFAULT 'active',
                created_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE shortcuts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                canonical_utterance TEXT NOT NULL,
                tool_name TEXT NOT NULL,
                args_json TEXT NOT NULL,
                embedding BLOB NOT NULL,
                hit_count INTEGER NOT NULL DEFAULT 1,
                status TEXT NOT NULL DEFAULT 'active',
                created_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE journal (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                type TEXT NOT NULL,
                summary TEXT NOT NULL,
                target_table TEXT NOT NULL,
                target_id INTEGER NOT NULL,
                created_at_ms INTEGER NOT NULL,
                undone INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE model_versions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                base_model TEXT NOT NULL,
                adapter_path TEXT,
                eval_score REAL NOT NULL,
                echo_rate REAL NOT NULL,
                tool_accuracy REAL NOT NULL,
                active INTEGER NOT NULL DEFAULT 0,
                created_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE reminders (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                text TEXT NOT NULL,
                trigger_at_ms INTEGER NOT NULL,
                fired INTEGER NOT NULL DEFAULT 0,
                created_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE meta (
                k TEXT PRIMARY KEY,
                v TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
        db.execSQL("PRAGMA synchronous=NORMAL")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Existing rows count as completed.
            db.execSQL("ALTER TABLE episodes ADD COLUMN completed INTEGER NOT NULL DEFAULT 1")
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (!migratedChecked && !db.isReadOnly) {
            migratedChecked = true
            migrateLegacyNotesDb(db)
        }
    }

    private fun migrateLegacyNotesDb(db: SQLiteDatabase) {
        runCatching {
            val done = db.rawQuery(
                "SELECT v FROM meta WHERE k = 'migrated_jarvis_notes'",
                null,
            ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            if (done == "1") return

            val legacyFile = context.getDatabasePath(LEGACY_NOTES_DB)
            if (legacyFile != null && legacyFile.isFile) {
                val rows = mutableListOf<Pair<String, Long>>()
                SQLiteDatabase.openDatabase(
                    legacyFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                ).use { legacyDb ->
                    legacyDb.rawQuery(
                        "SELECT text, created_at FROM notes ORDER BY id ASC",
                        null,
                    ).use { c ->
                        while (c.moveToNext()) {
                            rows.add(c.getString(0) to c.getLong(1))
                        }
                    }
                }
                if (rows.isNotEmpty()) {
                    db.beginTransaction()
                    try {
                        for ((text, ts) in rows) {
                            val cleaned = text.trim()
                            if (cleaned.isEmpty()) continue
                            val cv = ContentValues().apply {
                                put("text", cleaned)
                                put("embedding", VectorMath.toBytes(VectorMath.simpleHashEmbed(cleaned)))
                                putNull("source_episode_id")
                                put("confidence", 1.0f)
                                put("status", "active")
                                put("created_at_ms", ts)
                            }
                            val factId = db.insert("facts", null, cv)
                            if (factId > 0) {
                                insertJournalTx(
                                    db,
                                    type = "migration",
                                    summary = "Migrated note: $cleaned",
                                    targetTable = "facts",
                                    targetId = factId,
                                    createdAtMs = clockMs(),
                                )
                            }
                        }
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                }
            }
            val metaCv = ContentValues().apply {
                put("k", "migrated_jarvis_notes")
                put("v", "1")
            }
            db.insertWithOnConflict("meta", null, metaCv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    override fun addEpisode(episode: Episode): Episode {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("timestamp_ms", episode.timestampMs)
            put("live_transcript", episode.liveTranscript)
            put("accurate_transcript", episode.accurateTranscript)
            put("chosen_transcript", episode.chosenTranscript)
            put("reply", episode.reply)
            if (episode.toolName != null) put("tool_name", episode.toolName) else putNull("tool_name")
            if (episode.toolArgsJson != null) put("tool_args_json", episode.toolArgsJson) else putNull("tool_args_json")
            put("echoed", if (episode.echoed) 1 else 0)
            put("outcome_ok", if (episode.outcomeOk) 1 else 0)
            put("reflected", if (episode.reflected) 1 else 0)
            put("completed", 1)
        }
        val id = db.insert("episodes", null, cv)
        return episode.copy(id = id)
    }

    override fun beginEpisode(timestampMs: Long, live: String, accurate: String, chosen: String): Long {
        val cv = ContentValues().apply {
            put("timestamp_ms", timestampMs)
            put("live_transcript", live)
            put("accurate_transcript", accurate)
            put("chosen_transcript", chosen)
            put("reply", "")
            putNull("tool_name")
            putNull("tool_args_json")
            put("echoed", 0)
            put("outcome_ok", 0)
            put("reflected", 0)
            put("completed", 0)
        }
        return writableDatabase.insert("episodes", null, cv)
    }

    override fun completeEpisode(
        id: Long,
        reply: String,
        toolName: String?,
        toolArgsJson: String?,
        echoed: Boolean,
        outcomeOk: Boolean,
    ) {
        val cv = ContentValues().apply {
            put("reply", reply)
            if (toolName != null) put("tool_name", toolName) else putNull("tool_name")
            if (toolArgsJson != null) put("tool_args_json", toolArgsJson) else putNull("tool_args_json")
            put("echoed", if (echoed) 1 else 0)
            put("outcome_ok", if (outcomeOk) 1 else 0)
            put("completed", 1)
        }
        writableDatabase.update("episodes", cv, "id = ?", arrayOf(id.toString()))
    }

    override fun listInterruptedEpisodes(sinceMs: Long): List<Episode> {
        return readableDatabase.rawQuery(
            "SELECT id, timestamp_ms, live_transcript, accurate_transcript, chosen_transcript, reply, tool_name, tool_args_json, echoed, outcome_ok, reflected, completed " +
                "FROM episodes WHERE completed = 0 AND timestamp_ms >= ? ORDER BY timestamp_ms ASC",
            arrayOf(sinceMs.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(readEpisode(c))
            }
        }
    }

    override fun listUnreflectedEpisodes(limit: Int): List<Episode> {
        return readableDatabase.rawQuery(
            "SELECT id, timestamp_ms, live_transcript, accurate_transcript, chosen_transcript, reply, tool_name, tool_args_json, echoed, outcome_ok, reflected, completed " +
                "FROM episodes WHERE reflected = 0 ORDER BY timestamp_ms ASC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(readEpisode(c))
            }
        }
    }

    override fun listRecentEpisodes(limit: Int): List<Episode> {
        return readableDatabase.rawQuery(
            "SELECT id, timestamp_ms, live_transcript, accurate_transcript, chosen_transcript, reply, tool_name, tool_args_json, echoed, outcome_ok, reflected, completed " +
                "FROM episodes ORDER BY timestamp_ms DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(readEpisode(c))
            }
        }
    }

    override fun markEpisodeSatisfactory(id: Long, outcomeOk: Boolean) {
        val cv = ContentValues().apply { put("outcome_ok", if (outcomeOk) 1 else 0) }
        writableDatabase.update("episodes", cv, "id = ?", arrayOf(id.toString()))
    }

    override fun markEpisodesReflected(ids: List<Long>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cv = ContentValues().apply { put("reflected", 1) }
            for (id in ids) {
                db.update("episodes", cv, "id = ?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun learnFact(
        text: String,
        embedding: FloatArray,
        sourceEpisodeId: Long?,
        confidence: Float,
    ): Fact {
        val cleaned = text.trim()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = db.rawQuery(
                "SELECT id, text, embedding, source_episode_id, confidence, status, created_at_ms " +
                    "FROM facts WHERE status = 'active' AND LOWER(text) = LOWER(?) LIMIT 1",
                arrayOf(cleaned),
            ).use { c -> if (c.moveToFirst()) readFact(c) else null }

            if (existing != null) {
                val newConf = maxOf(existing.confidence, confidence)
                val newEmb = if (embedding.isNotEmpty()) embedding else existing.embedding
                val cv = ContentValues().apply {
                    put("confidence", newConf)
                    put("embedding", VectorMath.toBytes(newEmb))
                }
                db.update("facts", cv, "id = ?", arrayOf(existing.id.toString()))
                db.setTransactionSuccessful()
                return existing.copy(confidence = newConf, embedding = newEmb)
            }

            val now = clockMs()
            val cv = ContentValues().apply {
                put("text", cleaned)
                put("embedding", VectorMath.toBytes(embedding))
                if (sourceEpisodeId != null) put("source_episode_id", sourceEpisodeId) else putNull("source_episode_id")
                put("confidence", confidence)
                put("status", "active")
                put("created_at_ms", now)
            }
            val id = db.insert("facts", null, cv)
            insertJournalTx(db, "fact", "Learned fact: $cleaned", "facts", id, now)
            db.setTransactionSuccessful()
            return Fact(id, cleaned, embedding.copyOf(), sourceEpisodeId, confidence, "active", now)
        } finally {
            db.endTransaction()
        }
    }

    override fun learnVocab(phrase: String, source: String): VocabEntry {
        val cleaned = phrase.trim()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = db.rawQuery(
                "SELECT id, phrase, source, status, created_at_ms FROM vocabulary " +
                    "WHERE status = 'active' AND LOWER(phrase) = LOWER(?) LIMIT 1",
                arrayOf(cleaned),
            ).use { c ->
                if (c.moveToFirst()) VocabEntry(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4))
                else null
            }
            if (existing != null) {
                db.setTransactionSuccessful()
                return existing
            }
            val now = clockMs()
            val cv = ContentValues().apply {
                put("phrase", cleaned)
                put("source", source)
                put("status", "active")
                put("created_at_ms", now)
            }
            val id = db.insert("vocabulary", null, cv)
            insertJournalTx(db, "vocab", "Learned word: $cleaned", "vocabulary", id, now)
            db.setTransactionSuccessful()
            return VocabEntry(id, cleaned, source, "active", now)
        } finally {
            db.endTransaction()
        }
    }

    override fun learnCorrection(heard: String, meant: String): AsrCorrection {
        val h = heard.trim()
        val m = meant.trim()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = db.rawQuery(
                "SELECT id, heard, meant, hit_count, status, created_at_ms FROM corrections " +
                    "WHERE status = 'active' AND LOWER(heard) = LOWER(?) AND LOWER(meant) = LOWER(?) LIMIT 1",
                arrayOf(h, m),
            ).use { c ->
                if (c.moveToFirst()) {
                    AsrCorrection(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), c.getString(4), c.getLong(5))
                } else null
            }
            if (existing != null) {
                val updatedHits = existing.hitCount + 1
                val cv = ContentValues().apply { put("hit_count", updatedHits) }
                db.update("corrections", cv, "id = ?", arrayOf(existing.id.toString()))
                db.setTransactionSuccessful()
                return existing.copy(hitCount = updatedHits)
            }
            val now = clockMs()
            val cv = ContentValues().apply {
                put("heard", h)
                put("meant", m)
                put("hit_count", 1)
                put("status", "active")
                put("created_at_ms", now)
            }
            val id = db.insert("corrections", null, cv)
            insertJournalTx(db, "correction", "ASR correction: '$h' -> '$m'", "corrections", id, now)
            db.setTransactionSuccessful()
            return AsrCorrection(id, h, m, 1, "active", now)
        } finally {
            db.endTransaction()
        }
    }

    override fun promoteShortcut(
        canonicalUtterance: String,
        toolName: String,
        argsJson: String,
        embedding: FloatArray,
    ): LearnedShortcut {
        val cleaned = canonicalUtterance.trim()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = db.rawQuery(
                "SELECT id, canonical_utterance, tool_name, args_json, embedding, hit_count, status, created_at_ms " +
                    "FROM shortcuts WHERE status = 'active' AND tool_name = ? AND args_json = ? AND LOWER(canonical_utterance) = LOWER(?) LIMIT 1",
                arrayOf(toolName, argsJson, cleaned),
            ).use { c -> if (c.moveToFirst()) readShortcut(c) else null }

            if (existing != null) {
                val updatedHits = existing.hitCount + 1
                val cv = ContentValues().apply { put("hit_count", updatedHits) }
                db.update("shortcuts", cv, "id = ?", arrayOf(existing.id.toString()))
                db.setTransactionSuccessful()
                return existing.copy(hitCount = updatedHits)
            }

            val now = clockMs()
            val cv = ContentValues().apply {
                put("canonical_utterance", cleaned)
                put("tool_name", toolName)
                put("args_json", argsJson)
                put("embedding", VectorMath.toBytes(embedding))
                put("hit_count", 1)
                put("status", "active")
                put("created_at_ms", now)
            }
            val id = db.insert("shortcuts", null, cv)
            insertJournalTx(db, "shortcut", "Promoted shortcut: '$cleaned' -> $toolName", "shortcuts", id, now)
            db.setTransactionSuccessful()
            return LearnedShortcut(id, cleaned, toolName, argsJson, embedding.copyOf(), 1, "active", now)
        } finally {
            db.endTransaction()
        }
    }

    override fun recordModelVersion(
        baseModel: String,
        adapterPath: String?,
        evalScore: Float,
        echoRate: Float,
        toolAccuracy: Float,
        active: Boolean,
    ): ModelVersion {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (active) {
                val off = ContentValues().apply { put("active", 0) }
                db.update("model_versions", off, "active = 1", null)
            }
            val now = clockMs()
            val cv = ContentValues().apply {
                put("base_model", baseModel)
                if (adapterPath != null) put("adapter_path", adapterPath) else putNull("adapter_path")
                put("eval_score", evalScore)
                put("echo_rate", echoRate)
                put("tool_accuracy", toolAccuracy)
                put("active", if (active) 1 else 0)
                put("created_at_ms", now)
            }
            val id = db.insert("model_versions", null, cv)
            insertJournalTx(
                db,
                "model_version",
                "Recorded model version: $baseModel (${adapterPath ?: "base"})",
                "model_versions",
                id,
                now,
            )
            db.setTransactionSuccessful()
            return ModelVersion(id, baseModel, adapterPath, evalScore, echoRate, toolAccuracy, active, now)
        } finally {
            db.endTransaction()
        }
    }

    override fun addReminder(text: String, triggerAtMs: Long): Reminder {
        val cleaned = text.trim()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = clockMs()
            val cv = ContentValues().apply {
                put("text", cleaned)
                put("trigger_at_ms", triggerAtMs)
                put("fired", 0)
                put("created_at_ms", now)
            }
            val id = db.insert("reminders", null, cv)
            insertJournalTx(db, "reminder", "Scheduled reminder: $cleaned", "reminders", id, now)
            db.setTransactionSuccessful()
            return Reminder(id, cleaned, triggerAtMs, false, now)
        } finally {
            db.endTransaction()
        }
    }

    override fun recordInitiativeJournal(
        summary: String,
        targetTable: String,
        targetId: Long,
    ): JournalEntry {
        val db = writableDatabase
        val now = clockMs()
        val id = insertJournalTx(db, "initiative", summary, targetTable, targetId, now)
        return JournalEntry(id, "initiative", summary, targetTable, targetId, now, false)
    }

    override fun recordReflectionJournal(summary: String): JournalEntry {
        val db = writableDatabase
        val now = clockMs()
        val id = insertJournalTx(db, "reflection", summary, "episodes", 0L, now)
        return JournalEntry(id, "reflection", summary, "episodes", 0L, now, false)
    }

    override fun recordRecoveryJournal(summary: String): JournalEntry {
        val db = writableDatabase
        val now = clockMs()
        val id = insertJournalTx(db, "recovery", summary, "meta", 0L, now)
        return JournalEntry(id, "recovery", summary, "meta", 0L, now, false)
    }

    override fun undoJournalEntry(journalId: Long): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val entry = db.rawQuery(
                "SELECT id, type, summary, target_table, target_id, created_at_ms, undone FROM journal WHERE id = ?",
                arrayOf(journalId.toString()),
            ).use { c -> if (c.moveToFirst()) readJournal(c) else null }
            if (entry == null || entry.undone) return false

            when (entry.targetTable) {
                "facts", "vocabulary", "corrections", "shortcuts" -> {
                    val cv = ContentValues().apply { put("status", "undone") }
                    db.update(entry.targetTable, cv, "id = ?", arrayOf(entry.targetId.toString()))
                }
                "model_versions" -> {
                    val wasActive = db.rawQuery(
                        "SELECT active FROM model_versions WHERE id = ?",
                        arrayOf(entry.targetId.toString()),
                    ).use { c -> c.moveToFirst() && c.getInt(0) == 1 }
                    val cv = ContentValues().apply { put("active", 0) }
                    db.update("model_versions", cv, "id = ?", arrayOf(entry.targetId.toString()))
                    if (wasActive) {
                        val fallbackId = db.rawQuery(
                            "SELECT id FROM model_versions WHERE id != ? ORDER BY id DESC LIMIT 1",
                            arrayOf(entry.targetId.toString()),
                        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
                        if (fallbackId != null) {
                            val act = ContentValues().apply { put("active", 1) }
                            db.update("model_versions", act, "id = ?", arrayOf(fallbackId.toString()))
                        }
                    }
                }
                "reminders" -> {
                    val cv = ContentValues().apply { put("fired", 1) }
                    db.update("reminders", cv, "id = ?", arrayOf(entry.targetId.toString()))
                }
            }

            val jCv = ContentValues().apply { put("undone", 1) }
            db.update("journal", jCv, "id = ?", arrayOf(journalId.toString()))
            insertJournalTx(
                db,
                "undo",
                "Undid ${entry.type}: ${entry.summary}",
                entry.targetTable,
                entry.targetId,
                clockMs(),
            )
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    override fun undoLastLearning(): JournalEntry? {
        val target = readableDatabase.rawQuery(
            "SELECT id, type, summary, target_table, target_id, created_at_ms, undone FROM journal " +
                "WHERE undone = 0 AND type IN ('fact', 'vocab', 'correction', 'shortcut', 'model_version', 'migration') " +
                "ORDER BY id DESC LIMIT 1",
            null,
        ).use { c -> if (c.moveToFirst()) readJournal(c) else null } ?: return null
        return if (undoJournalEntry(target.id)) target.copy(undone = true) else null
    }

    override fun forgetMatchingFact(query: String, queryEmbedding: FloatArray): Fact? {
        val active = listActiveFacts(500)
        val best = active.map { fact ->
            val sim = VectorMath.cosineSimilarity(queryEmbedding, fact.embedding)
            val kw = MindJsonExporter.keywordScore(query, fact.text)
            Triple(fact, sim, kw)
        }.filter { (_, sim, kw) -> sim >= 0.25f || kw > 0 }
            .maxByOrNull { (_, sim, kw) -> sim + kw * 0.25f }
            ?.first ?: return null

        val db = writableDatabase
        db.beginTransaction()
        try {
            val cv = ContentValues().apply { put("status", "forgotten") }
            db.update("facts", cv, "id = ?", arrayOf(best.id.toString()))
            insertJournalTx(db, "undo", "Forgot fact: ${best.text}", "facts", best.id, clockMs())
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return best.copy(status = "forgotten")
    }

    override fun deleteFact(id: Long): Boolean = markStatus("facts", id, "deleted")
    override fun deleteVocab(id: Long): Boolean = markStatus("vocabulary", id, "deleted")
    override fun deleteCorrection(id: Long): Boolean = markStatus("corrections", id, "deleted")
    override fun deleteShortcut(id: Long): Boolean = markStatus("shortcuts", id, "deleted")

    override fun retrieveRelevantFacts(
        queryEmbedding: FloatArray,
        topK: Int,
        minSim: Float,
    ): List<Fact> {
        return listActiveFacts(500).asSequence()
            .filter { it.embedding.isNotEmpty() }
            .map { it to VectorMath.cosineSimilarity(queryEmbedding, it.embedding) }
            .filter { it.second >= minSim }
            .sortedByDescending { it.second * it.first.confidence }
            .take(topK)
            .map { it.first }
            .toList()
    }

    override fun retrieveRelevantEpisode(queryEmbedding: FloatArray, minSim: Float): Episode? {
        if (queryEmbedding.isEmpty()) return null
        return listRecentEpisodes(200).asSequence()
            .map { ep ->
                val epVec = VectorMath.simpleHashEmbed("${ep.chosenTranscript} ${ep.reply}", queryEmbedding.size)
                ep to VectorMath.cosineSimilarity(queryEmbedding, epVec)
            }
            .filter { it.second >= minSim }
            .maxByOrNull { it.second }
            ?.first
    }

    override fun matchShortcut(queryEmbedding: FloatArray, minSim: Float): LearnedShortcut? {
        return listActiveShortcuts().asSequence()
            .filter { it.embedding.isNotEmpty() }
            .map { it to VectorMath.cosineSimilarity(queryEmbedding, it.embedding) }
            .filter { it.second >= minSim }
            .maxByOrNull { it.second }
            ?.first
    }

    override fun listActiveFacts(limit: Int): List<Fact> {
        return readableDatabase.rawQuery(
            "SELECT id, text, embedding, source_episode_id, confidence, status, created_at_ms " +
                "FROM facts WHERE status = 'active' ORDER BY created_at_ms DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(readFact(c)) } }
    }

    override fun listActiveCorrectionsMap(): Map<String, String> =
        listActiveCorrections().associate { it.heard to it.meant }

    override fun listActiveCorrections(): List<AsrCorrection> {
        return readableDatabase.rawQuery(
            "SELECT id, heard, meant, hit_count, status, created_at_ms FROM corrections " +
                "WHERE status = 'active' ORDER BY hit_count DESC",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(AsrCorrection(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), c.getString(4), c.getLong(5)))
                }
            }
        }
    }

    override fun listActiveVocab(): List<VocabEntry> {
        return readableDatabase.rawQuery(
            "SELECT id, phrase, source, status, created_at_ms FROM vocabulary " +
                "WHERE status = 'active' ORDER BY created_at_ms DESC",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(VocabEntry(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4)))
                }
            }
        }
    }

    override fun listActiveShortcuts(): List<LearnedShortcut> {
        return readableDatabase.rawQuery(
            "SELECT id, canonical_utterance, tool_name, args_json, embedding, hit_count, status, created_at_ms " +
                "FROM shortcuts WHERE status = 'active' ORDER BY hit_count DESC",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(readShortcut(c)) } }
    }

    override fun listJournal(limit: Int): List<JournalEntry> {
        return readableDatabase.rawQuery(
            "SELECT id, type, summary, target_table, target_id, created_at_ms, undone " +
                "FROM journal ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(readJournal(c)) } }
    }

    override fun listModelVersions(): List<ModelVersion> {
        return readableDatabase.rawQuery(
            "SELECT id, base_model, adapter_path, eval_score, echo_rate, tool_accuracy, active, created_at_ms " +
                "FROM model_versions ORDER BY id DESC",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        ModelVersion(
                            id = c.getLong(0),
                            baseModel = c.getString(1),
                            adapterPath = if (c.isNull(2)) null else c.getString(2),
                            evalScore = c.getFloat(3),
                            echoRate = c.getFloat(4),
                            toolAccuracy = c.getFloat(5),
                            active = c.getInt(6) == 1,
                            createdAtMs = c.getLong(7),
                        )
                    )
                }
            }
        }
    }

    override fun activateModelVersion(id: Long): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val exists = db.rawQuery(
                "SELECT 1 FROM model_versions WHERE id = ?",
                arrayOf(id.toString()),
            ).use { it.moveToFirst() }
            if (!exists) return false
            val off = ContentValues().apply { put("active", 0) }
            db.update("model_versions", off, "active = 1", null)
            val on = ContentValues().apply { put("active", 1) }
            db.update("model_versions", on, "id = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    override fun rollbackModelVersion(): ModelVersion? {
        val versions = listModelVersions()
        val activeIdx = versions.indexOfFirst { it.active }
        val target = if (activeIdx >= 0 && activeIdx + 1 < versions.size) {
            versions[activeIdx + 1]
        } else {
            versions.firstOrNull { !it.active }
        } ?: return null
        if (!activateModelVersion(target.id)) return null
        return target.copy(active = true)
    }

    override fun dueReminders(nowMs: Long): List<Reminder> {
        return readableDatabase.rawQuery(
            "SELECT id, text, trigger_at_ms, fired, created_at_ms FROM reminders " +
                "WHERE fired = 0 AND trigger_at_ms <= ? ORDER BY trigger_at_ms ASC",
            arrayOf(nowMs.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(Reminder(c.getLong(0), c.getString(1), c.getLong(2), c.getInt(3) == 1, c.getLong(4)))
                }
            }
        }
    }

    override fun markReminderFired(id: Long): Boolean {
        val cv = ContentValues().apply { put("fired", 1) }
        return writableDatabase.update("reminders", cv, "id = ?", arrayOf(id.toString())) > 0
    }

    override fun isLearningPaused(): Boolean = getMeta("learning_paused") == "true"

    override fun setLearningPaused(paused: Boolean) {
        setMeta("learning_paused", paused.toString())
    }

    override fun getMeta(key: String): String? {
        return readableDatabase.rawQuery(
            "SELECT v FROM meta WHERE k = ? LIMIT 1",
            arrayOf(key),
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }

    override fun setMeta(key: String, value: String) {
        val cv = ContentValues().apply {
            put("k", key)
            put("v", value)
        }
        writableDatabase.insertWithOnConflict("meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun countActiveFacts(): Int = countWhere("facts", "status = 'active'")
    override fun countActiveVocab(): Int = countWhere("vocabulary", "status = 'active'")
    override fun countActiveCorrections(): Int = countWhere("corrections", "status = 'active'")
    override fun countActiveShortcuts(): Int = countWhere("shortcuts", "status = 'active'")
    override fun countEpisodes(): Int = countWhere("episodes", "1 = 1")

    override fun exportToJson(file: File): Boolean = MindJsonExporter.export(this, file)

    private fun markStatus(table: String, id: Long, status: String): Boolean {
        val cv = ContentValues().apply { put("status", status) }
        return writableDatabase.update(table, cv, "id = ?", arrayOf(id.toString())) > 0
    }

    private fun countWhere(table: String, where: String): Int {
        return readableDatabase.rawQuery("SELECT COUNT(*) FROM $table WHERE $where", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    private fun insertJournalTx(
        db: SQLiteDatabase,
        type: String,
        summary: String,
        targetTable: String,
        targetId: Long,
        createdAtMs: Long,
    ): Long {
        val cv = ContentValues().apply {
            put("type", type)
            put("summary", summary)
            put("target_table", targetTable)
            put("target_id", targetId)
            put("created_at_ms", createdAtMs)
            put("undone", 0)
        }
        return db.insert("journal", null, cv)
    }

    private fun readEpisode(c: android.database.Cursor): Episode = Episode(
        id = c.getLong(0),
        timestampMs = c.getLong(1),
        liveTranscript = c.getString(2),
        accurateTranscript = c.getString(3),
        chosenTranscript = c.getString(4),
        reply = c.getString(5),
        toolName = if (c.isNull(6)) null else c.getString(6),
        toolArgsJson = if (c.isNull(7)) null else c.getString(7),
        echoed = c.getInt(8) == 1,
        outcomeOk = c.getInt(9) == 1,
        reflected = c.getInt(10) == 1,
        completed = c.getInt(11) == 1,
    )

    private fun readFact(c: android.database.Cursor): Fact = Fact(
        id = c.getLong(0),
        text = c.getString(1),
        embedding = VectorMath.fromBytes(c.getBlob(2)),
        sourceEpisodeId = if (c.isNull(3)) null else c.getLong(3),
        confidence = c.getFloat(4),
        status = c.getString(5),
        createdAtMs = c.getLong(6),
    )

    private fun readShortcut(c: android.database.Cursor): LearnedShortcut = LearnedShortcut(
        id = c.getLong(0),
        canonicalUtterance = c.getString(1),
        toolName = c.getString(2),
        argsJson = c.getString(3),
        embedding = VectorMath.fromBytes(c.getBlob(4)),
        hitCount = c.getInt(5),
        status = c.getString(6),
        createdAtMs = c.getLong(7),
    )

    private fun readJournal(c: android.database.Cursor): JournalEntry = JournalEntry(
        id = c.getLong(0),
        type = c.getString(1),
        summary = c.getString(2),
        targetTable = c.getString(3),
        targetId = c.getLong(4),
        createdAtMs = c.getLong(5),
        undone = c.getInt(6) == 1,
    )

    companion object {
        const val DB_NAME = "jarvis_mind.db"
        const val LEGACY_NOTES_DB = "jarvis_notes.db"
        const val DB_VERSION = 2
    }
}
