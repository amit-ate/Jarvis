package com.jarvis.edge.mind

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import kotlin.math.sqrt

/**
 * Stage 4 overnight forward-only neural self-evolution orchestrator.
 * Trains a contrastive control vector from recent episodes and confirmed ASR corrections,
 * evaluates it against [OnDeviceEvalGate], writes `.jvec` on improvement/non-regression,
 * and rolls back cleanly on any regression, failure, or interruption.
 */
class BrainEvolver(
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val hourOfDayProvider: (Long) -> Int = { tsMs ->
        Calendar.getInstance().apply { timeInMillis = tsMs }.get(Calendar.HOUR_OF_DAY)
    },
) {

    data class EvolutionResult(
        val attempted: Boolean,
        val adopted: Boolean,
        val adapterFile: File?,
        val modelVersion: ModelVersion?,
        val baselineEval: OnDeviceEvalGate.EvalGateResult?,
        val candidateEval: OnDeviceEvalGate.EvalGateResult?,
        val reason: String,
    )

    fun isOvernightWindow(hourOfDay: Int): Boolean = hourOfDay in 1..5

    fun canRunOvernight(
        snapshot: SelfStateSnapshot,
        isIdle: Boolean,
        manualOverride: Boolean = false,
    ): Boolean {
        if (snapshot.learningPaused || !isIdle || snapshot.healthLevelName != "NORMAL") {
            return false
        }
        if (manualOverride) {
            return true
        }
        val hour = hourOfDayProvider(clockMs())
        return snapshot.charging && isOvernightWindow(hour)
    }

    fun buildContrastivePairs(
        episodes: List<Episode>,
        corrections: List<AsrCorrection>,
    ): Pair<List<String>, List<String>> {
        val positives = ArrayList<String>(MAX_CONTRAST_PAIRS)
        val negatives = ArrayList<String>(MAX_CONTRAST_PAIRS)
        val seenPos = HashSet<String>()

        fun addPair(pos: String, neg: String) {
            if (positives.size >= MAX_CONTRAST_PAIRS) return
            val p = pos.trim()
            val n = neg.trim()
            if (p.isEmpty() || n.isEmpty() || p == n) return
            if (seenPos.add(p)) {
                positives.add(p)
                negatives.add(n)
            }
        }

        val goodEpisodes = episodes.filter {
            it.outcomeOk && !it.echoed && it.chosenTranscript.isNotBlank() && it.reply.isNotBlank()
        }
        val badEpisodes = episodes.filter {
            (it.echoed || !it.outcomeOk) && it.chosenTranscript.isNotBlank()
        }

        for ((idx, good) in goodEpisodes.withIndex()) {
            if (positives.size >= MAX_CONTRAST_PAIRS) break
            val posPrompt = "User: ${good.chosenTranscript.trim()}\nAssistant: ${good.reply.trim()}"
            val bad = badEpisodes.getOrNull(idx)
            val negPrompt = if (bad != null && bad.reply.isNotBlank()) {
                "User: ${bad.chosenTranscript.trim()}\nAssistant: ${bad.reply.trim()}"
            } else {
                "User: ${good.chosenTranscript.trim()}\nAssistant: ${good.chosenTranscript.trim()}"
            }
            addPair(posPrompt, negPrompt)
        }

        for (corr in corrections) {
            if (positives.size >= MAX_CONTRAST_PAIRS) break
            if (corr.status != "active") continue
            val heard = corr.heard.trim()
            val meant = corr.meant.trim()
            if (heard.isNotEmpty() && meant.isNotEmpty() && !heard.equals(meant, ignoreCase = true)) {
                addPair(
                    pos = "User: $meant\nAssistant: Understood, I will handle $meant.",
                    neg = "User: $heard\nAssistant: $heard",
                )
            }
        }

        for ((builtinPos, builtinNeg) in DEFAULT_CONTRAST_PAIRS) {
            if (positives.size >= MIN_CONTRAST_PAIRS) break
            addPair(builtinPos, builtinNeg)
        }

        return positives to negatives
    }

    fun writeJvec(
        file: File,
        nEmbd: Int,
        nLayersMinus1: Int,
        ilStart: Int,
        ilEnd: Int,
        data: FloatArray,
    ) {
        require(nEmbd > 0) { "nEmbd must be positive: $nEmbd" }
        require(nLayersMinus1 > 0) { "nLayersMinus1 must be positive: $nLayersMinus1" }
        require(ilStart >= 1 && ilEnd >= ilStart && ilEnd <= nLayersMinus1) {
            "Invalid layer range [$ilStart, $ilEnd] for nLayersMinus1=$nLayersMinus1"
        }
        val expectedSize = nLayersMinus1 * nEmbd
        require(data.size == expectedSize) {
            "Expected $expectedSize floats, got ${data.size}"
        }

        val buffer = ByteBuffer.allocate(JVEC_HEADER_BYTES + expectedSize * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.put('J'.code.toByte())
        buffer.put('V'.code.toByte())
        buffer.put('E'.code.toByte())
        buffer.put('C'.code.toByte())
        buffer.putInt(JVEC_VERSION)
        buffer.putInt(nEmbd)
        buffer.putInt(nLayersMinus1)
        buffer.putInt(ilStart)
        buffer.putInt(ilEnd)
        for (value in data) {
            buffer.putFloat(value)
        }

        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile ?: File("."), ".${file.name}.tmp")
        tmp.writeBytes(buffer.array())
        if (!tmp.renameTo(file)) {
            file.writeBytes(buffer.array())
            tmp.delete()
        }
    }

    fun evolveOnce(
        store: MindRepository,
        adaptersDir: File,
        baseModelName: String,
        nEmbd: Int,
        nLayer: Int,
        previousAdapterData: FloatArray?,
        previousIlStart: Int,
        previousIlEnd: Int,
        trainVectorFn: (pos: List<String>, neg: List<String>, ilStart: Int, ilEnd: Int) -> FloatArray,
        applyVectorFn: (FloatArray?, Int, Int, Int) -> Unit,
        evalGate: OnDeviceEvalGate,
        generateFn: (OnDeviceEvalGate.EvalProbe) -> String,
        isInterrupted: () -> Boolean = { false },
    ): EvolutionResult {
        if (nEmbd <= 0 || nLayer <= 2) {
            return EvolutionResult(
                attempted = false,
                adopted = false,
                adapterFile = null,
                modelVersion = null,
                baselineEval = null,
                candidateEval = null,
                reason = "Invalid model geometry: nEmbd=$nEmbd, nLayer=$nLayer",
            )
        }

        val ilStart = (nLayer / 3).coerceAtLeast(1)
        val ilEnd = (nLayer - 2).coerceAtLeast(ilStart)
        val expectedFloats = (nLayer - 1) * nEmbd

        val recentEpisodes = store.listRecentEpisodes(100)
        val heldOutEpisodes = recentEpisodes
            .filter { it.outcomeOk && !it.echoed && it.chosenTranscript.isNotBlank() }
            .take(4)

        // Step 1: Evaluate baseline with currently active adapter.
        val baselineEval = evalGate.evaluate(
            heldOutEpisodes = heldOutEpisodes,
            baseline = null,
            isInterrupted = isInterrupted,
            generateFn = generateFn,
        )
        if (isInterrupted() || baselineEval.reason.startsWith("Interrupted")) {
            restorePrevious(applyVectorFn, previousAdapterData, nEmbd, previousIlStart, previousIlEnd)
            return EvolutionResult(
                attempted = true,
                adopted = false,
                adapterFile = null,
                modelVersion = null,
                baselineEval = baselineEval,
                candidateEval = null,
                reason = "Interrupted during baseline evaluation",
            )
        }

        // Step 2: Build contrastive pairs and train candidate vector.
        val (posPrompts, negPrompts) = buildContrastivePairs(recentEpisodes, store.listActiveCorrections())
        val candidateData = runCatching {
            trainVectorFn(posPrompts, negPrompts, ilStart, ilEnd)
        }.getOrElse { err ->
            restorePrevious(applyVectorFn, previousAdapterData, nEmbd, previousIlStart, previousIlEnd)
            val failedVersion = store.recordModelVersion(
                baseModel = baseModelName,
                adapterPath = null,
                evalScore = 0f,
                echoRate = 1f,
                toolAccuracy = 0f,
                active = false,
            )
            return EvolutionResult(
                attempted = true,
                adopted = false,
                adapterFile = null,
                modelVersion = failedVersion,
                baselineEval = baselineEval,
                candidateEval = null,
                reason = "Training failed: ${err.message ?: err::class.java.simpleName}",
            )
        }

        if (isInterrupted()) {
            restorePrevious(applyVectorFn, previousAdapterData, nEmbd, previousIlStart, previousIlEnd)
            return EvolutionResult(
                attempted = true,
                adopted = false,
                adapterFile = null,
                modelVersion = null,
                baselineEval = baselineEval,
                candidateEval = null,
                reason = "Interrupted after training",
            )
        }

        if (candidateData.size != expectedFloats || !hasValidNonZeroNorm(candidateData)) {
            restorePrevious(applyVectorFn, previousAdapterData, nEmbd, previousIlStart, previousIlEnd)
            val failedVersion = store.recordModelVersion(
                baseModel = baseModelName,
                adapterPath = null,
                evalScore = 0f,
                echoRate = 1f,
                toolAccuracy = 0f,
                active = false,
            )
            return EvolutionResult(
                attempted = true,
                adopted = false,
                adapterFile = null,
                modelVersion = failedVersion,
                baselineEval = baselineEval,
                candidateEval = null,
                reason = "Invalid candidate vector: size=${candidateData.size}, expected=$expectedFloats",
            )
        }

        // Step 3: Temporarily apply candidate and run evaluation gate against baseline.
        var candidateApplied = false
        val candidateEval = try {
            applyVectorFn(candidateData, nEmbd, ilStart, ilEnd)
            candidateApplied = true
            evalGate.evaluate(
                heldOutEpisodes = heldOutEpisodes,
                baseline = baselineEval,
                isInterrupted = isInterrupted,
                generateFn = generateFn,
            )
        } catch (err: Throwable) {
            if (candidateApplied) {
                restorePrevious(applyVectorFn, previousAdapterData, nEmbd, previousIlStart, previousIlEnd)
            }
            val failedVersion = store.recordModelVersion(
                baseModel = baseModelName,
                adapterPath = null,
                evalScore = 0f,
                echoRate = 1f,
                toolAccuracy = 0f,
                active = false,
            )
            return EvolutionResult(
                attempted = true,
                adopted = false,
                adapterFile = null,
                modelVersion = failedVersion,
                baselineEval = baselineEval,
                candidateEval = null,
                reason = "Candidate evaluation failed: ${err.message ?: err::class.java.simpleName}",
            )
        }

        // Step 4: Adopt if candidate passes and was not interrupted.
        if (candidateEval.passed && !isInterrupted()) {
            val outFile = File(adaptersDir, "brain_v${clockMs()}.jvec")
            writeJvec(
                file = outFile,
                nEmbd = nEmbd,
                nLayersMinus1 = nLayer - 1,
                ilStart = ilStart,
                ilEnd = ilEnd,
                data = candidateData,
            )
            val activeVersion = store.recordModelVersion(
                baseModel = baseModelName,
                adapterPath = outFile.absolutePath,
                evalScore = candidateEval.evalScore,
                echoRate = candidateEval.echoRate,
                toolAccuracy = candidateEval.toolAccuracy,
                active = true,
            )
            return EvolutionResult(
                attempted = true,
                adopted = true,
                adapterFile = outFile,
                modelVersion = activeVersion,
                baselineEval = baselineEval,
                candidateEval = candidateEval,
                reason = "Adopted: ${candidateEval.reason}",
            )
        }

        // Step 5: Roll back to previous adapter and record inactive attempt for auditability.
        restorePrevious(applyVectorFn, previousAdapterData, nEmbd, previousIlStart, previousIlEnd)
        val rejectedVersion = store.recordModelVersion(
            baseModel = baseModelName,
            adapterPath = null,
            evalScore = candidateEval.evalScore,
            echoRate = candidateEval.echoRate,
            toolAccuracy = candidateEval.toolAccuracy,
            active = false,
        )
        val reason = if (isInterrupted()) {
            "Interrupted before adoption; restored previous adapter"
        } else {
            "Rejected: ${candidateEval.reason}"
        }
        return EvolutionResult(
            attempted = true,
            adopted = false,
            adapterFile = null,
            modelVersion = rejectedVersion,
            baselineEval = baselineEval,
            candidateEval = candidateEval,
            reason = reason,
        )
    }

    private fun restorePrevious(
        applyVectorFn: (FloatArray?, Int, Int, Int) -> Unit,
        previousAdapterData: FloatArray?,
        nEmbd: Int,
        previousIlStart: Int,
        previousIlEnd: Int,
    ) {
        runCatching {
            applyVectorFn(previousAdapterData, nEmbd, previousIlStart, previousIlEnd)
        }
    }

    private fun hasValidNonZeroNorm(data: FloatArray): Boolean {
        var sumSq = 0.0
        for (v in data) {
            if (!v.isFinite()) return false
            sumSq += v.toDouble() * v.toDouble()
        }
        return sqrt(sumSq) > 1e-6
    }

    companion object {
        const val JVEC_HEADER_BYTES = 24
        const val JVEC_VERSION = 1
        private const val MIN_CONTRAST_PAIRS = 4
        private const val MAX_CONTRAST_PAIRS = 8

        private val DEFAULT_CONTRAST_PAIRS = listOf(
            "User: Explain photosynthesis briefly.\nAssistant: Plants convert sunlight, water, and carbon dioxide into glucose and oxygen." to
                "User: Explain photosynthesis briefly.\nAssistant: Explain photosynthesis briefly.",
            "User: Why is the sky blue?\nAssistant: Air molecules scatter shorter blue wavelengths of sunlight more than red." to
                "User: Why is the sky blue?\nAssistant: Why is the sky blue?",
            "User: What does HTTP stand for?\nAssistant: Hypertext Transfer Protocol." to
                "User: What does HTTP stand for?\nAssistant: What does HTTP stand for?",
            "User: Turn on the flashlight.\nAssistant: {\"tool\":\"torch\",\"args\":{\"state\":\"on\"}}" to
                "User: Turn on the flashlight.\nAssistant: Turn on the flashlight.",
            "User: Tell me a short fact about Saturn.\nAssistant: Saturn is a gas giant famous for its bright icy rings." to
                "User: Tell me a short fact about Saturn.\nAssistant: Tell me a short fact about Saturn.",
            "User: Search Wikipedia for Albert Einstein.\nAssistant: {\"tool\":\"web_search\",\"args\":{\"query\":\"Albert Einstein\"}}" to
                "User: Search Wikipedia for Albert Einstein.\nAssistant: I cannot search Wikipedia.",
        )
    }
}
