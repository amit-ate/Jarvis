package com.jarvis.edge.mind

import org.json.JSONObject
import java.io.File

/**
 * Periodic background reflection worker that consolidates unreflected [Episode]s into
 * ASR corrections, custom hotwords vocabulary, fast-path shortcuts, and LLM-extracted facts.
 */
class ReflectionWorker(
    private val store: MindRepository,
    private val embed: (String) -> FloatArray = { VectorMath.simpleHashEmbed(it) },
    private val hotwordsWriter: HotwordsWriter? = null,
    private val hotwordsOutFile: File? = null,
    private val llmExtract: ((String) -> String?)? = null,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {

    data class ReflectionReport(
        val episodesProcessed: Int,
        val factsLearned: Int,
        val vocabLearned: Int,
        val correctionsLearned: Int,
        val shortcutsPromoted: Int,
        val aborted: Boolean,
    )

    fun reflectOnce(
        canRun: Boolean = true,
        isInterrupted: () -> Boolean = { false },
    ): ReflectionReport {
        if (!canRun || store.isLearningPaused()) {
            return ReflectionReport(0, 0, 0, 0, 0, aborted = false)
        }
        if (isInterrupted()) {
            return ReflectionReport(0, 0, 0, 0, 0, aborted = true)
        }

        val unreflected = store.listUnreflectedEpisodes(limit = 100).sortedBy { it.timestampMs }
        if (unreflected.isEmpty()) {
            return ReflectionReport(0, 0, 0, 0, 0, aborted = false)
        }

        var factsLearned = 0
        var vocabLearned = 0
        var correctionsLearned = 0
        var shortcutsPromoted = 0

        // 1. Heuristic pass: repeated utterances within 30s and explicit user corrections.
        for (i in unreflected.indices) {
            if (isInterrupted()) {
                return ReflectionReport(
                    i,
                    factsLearned,
                    vocabLearned,
                    correctionsLearned,
                    shortcutsPromoted,
                    aborted = true,
                )
            }
            val curr = unreflected[i]
            val prev = if (i > 0) unreflected[i - 1] else null

            if (prev != null && (curr.timestampMs - prev.timestampMs) in 0L..REPEAT_WINDOW_MS) {
                if (isRepeatedUtterance(prev.chosenTranscript, curr.chosenTranscript)) {
                    store.markEpisodeSatisfactory(prev.id, outcomeOk = false)
                }
            }

            val correctionPair = extractExplicitCorrection(curr.chosenTranscript, prev)
            if (correctionPair != null) {
                val (heard, meant) = correctionPair
                if (heard.isNotBlank() && meant.isNotBlank() && !heard.equals(meant, ignoreCase = true)) {
                    store.learnCorrection(heard, meant)
                    correctionsLearned++
                    store.learnVocab(meant, source = "correction")
                    vocabLearned++
                }
            }
        }

        // 2. Shortcut promotion: >= 3 successful episodes with same (toolName, toolArgsJson).
        val allCandidates = LinkedHashMap<Long, Episode>()
        for (ep in store.listRecentEpisodes(limit = 200)) {
            allCandidates[ep.id] = ep
        }
        for (ep in unreflected) {
            allCandidates[ep.id] = ep
        }
        val existingShortcuts = store.listActiveShortcuts()
        val grouped = allCandidates.values
            .filter { ep ->
                ep.outcomeOk &&
                    !ep.echoed &&
                    !ep.toolName.isNullOrBlank() &&
                    ep.chosenTranscript.isNotBlank()
            }
            .groupBy { ep -> ep.toolName!! to (ep.toolArgsJson ?: "{}") }

        for ((key, eps) in grouped) {
            if (eps.size >= SHORTCUT_MIN_HITS) {
                val (toolName, argsJson) = key
                val alreadyActive = existingShortcuts.any {
                    it.toolName == toolName && it.argsJson == argsJson
                }
                if (!alreadyActive) {
                    val canonical = eps.last().chosenTranscript.trim()
                    store.promoteShortcut(canonical, toolName, argsJson, embed(canonical))
                    shortcutsPromoted++
                }
            }
        }

        // 3. Optional LLM fact & vocabulary extraction.
        val extractor = llmExtract
        if (extractor != null) {
            if (isInterrupted()) {
                return ReflectionReport(
                    unreflected.size,
                    factsLearned,
                    vocabLearned,
                    correctionsLearned,
                    shortcutsPromoted,
                    aborted = true,
                )
            }
            val promptBatch = buildString {
                // Interrupted episodes (empty reply) are never mined for facts.
                for (ep in unreflected.filter { it.reply.isNotEmpty() }) {
                    append("User: ").append(ep.chosenTranscript).append('\n')
                    if (ep.reply.isNotBlank()) {
                        append("Jarvis: ").append(ep.reply).append('\n')
                    }
                }
            }.trim()

            val rawJson = if (promptBatch.isEmpty()) null else runCatching { extractor.invoke(promptBatch) }.getOrNull()
            if (isInterrupted()) {
                return ReflectionReport(
                    unreflected.size,
                    factsLearned,
                    vocabLearned,
                    correctionsLearned,
                    shortcutsPromoted,
                    aborted = true,
                )
            }

            if (!rawJson.isNullOrBlank()) {
                val lastId = unreflected.last().id
                val parsed = parseLlmExtraction(rawJson)
                for (factText in parsed.facts) {
                    store.learnFact(factText, embed(factText), sourceEpisodeId = lastId, confidence = 0.85f)
                    factsLearned++
                }
                for (vocabPhrase in parsed.vocab) {
                    store.learnVocab(vocabPhrase, source = "llm_reflection")
                    vocabLearned++
                }
            }
        }

        // 4. Regenerate Zipformer hotwords file when vocab or corrections changed.
        if (hotwordsWriter != null && hotwordsOutFile != null && (vocabLearned > 0 || correctionsLearned > 0)) {
            val phrases = buildList {
                addAll(store.listActiveVocab().map { it.phrase })
                addAll(store.listActiveCorrections().map { it.meant })
            }.distinct()
            hotwordsWriter.writeHotwords(phrases, hotwordsOutFile)
        }

        // 5. Record reflection summary & mark episodes reflected.
        val totalLearned = factsLearned + vocabLearned + correctionsLearned + shortcutsPromoted
        if (totalLearned > 0) {
            store.recordReflectionJournal(
                "Reflected ${unreflected.size} episodes at ${clockMs()}: " +
                    "+$factsLearned facts, +$vocabLearned vocab, +$correctionsLearned corrections, +$shortcutsPromoted shortcuts"
            )
        }
        store.markEpisodesReflected(unreflected.map { it.id })

        return ReflectionReport(
            episodesProcessed = unreflected.size,
            factsLearned = factsLearned,
            vocabLearned = vocabLearned,
            correctionsLearned = correctionsLearned,
            shortcutsPromoted = shortcutsPromoted,
            aborted = false,
        )
    }

    private data class LlmExtraction(val facts: List<String>, val vocab: List<String>)

    private fun parseLlmExtraction(raw: String): LlmExtraction {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return LlmExtraction(emptyList(), emptyList())
        return runCatching {
            val obj = JSONObject(raw.substring(start, end + 1))
            val facts = buildList {
                val arr = obj.optJSONArray("facts")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val s = arr.optString(i, "").trim()
                        if (s.isNotEmpty()) add(s)
                    }
                }
            }
            val vocab = buildList {
                val arr = obj.optJSONArray("vocab")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val s = arr.optString(i, "").trim()
                        if (s.isNotEmpty()) add(s)
                    }
                }
            }
            LlmExtraction(facts, vocab)
        }.getOrDefault(LlmExtraction(emptyList(), emptyList()))
    }

    private fun extractExplicitCorrection(text: String, prev: Episode?): Pair<String, String>? {
        val trimmed = text.trim()
        NOT_X_COMMA_Y.matchEntire(trimmed)?.let { m ->
            val heard = m.groupValues[1].trim().trimEnd('.', '!', '?')
            val meant = m.groupValues[2].trim().trimEnd('.', '!', '?')
            if (heard.isNotEmpty() && meant.isNotEmpty()) return heard to meant
        }
        EXPLICIT_SAID_MEANT.matchEntire(trimmed)?.let { m ->
            val meant = m.groupValues[1].trim().trimEnd('.', '!', '?')
            val heard = prev?.let {
                it.chosenTranscript.ifBlank { it.liveTranscript }.trim()
            }.orEmpty()
            if (heard.isNotEmpty() && meant.isNotEmpty()) return heard to meant
        }
        return null
    }

    private fun isRepeatedUtterance(a: String, b: String): Boolean {
        val normA = normalizeWords(a)
        val normB = normalizeWords(b)
        if (normA.isEmpty() || normB.isEmpty()) return false
        if (normA == normB) return true
        val setA = normA.toHashSet()
        val setB = normB.toHashSet()
        val intersection = setA.count { it in setB }
        val union = setA.size + setB.size - intersection
        return union > 0 && (intersection.toFloat() / union.toFloat()) >= 0.70f
    }

    private fun normalizeWords(s: String): List<String> =
        s.lowercase().split(WORD_SPLIT).filter { it.isNotEmpty() }

    companion object {
        private const val REPEAT_WINDOW_MS = 30_000L
        private const val SHORTCUT_MIN_HITS = 3
        private val WORD_SPLIT = Regex("[^a-z0-9]+")
        private val EXPLICIT_SAID_MEANT = Regex(
            "^(?:no|nope|wrong|actually)[,.]?\\s+(?:i\\s+said|i\\s+meant)\\s+(.+)$",
            RegexOption.IGNORE_CASE,
        )
        private val NOT_X_COMMA_Y = Regex(
            "^not\\s+(.+?)\\s*,\\s*(.+)$",
            RegexOption.IGNORE_CASE,
        )
    }
}
