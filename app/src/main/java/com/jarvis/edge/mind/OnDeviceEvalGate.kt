package com.jarvis.edge.mind

import com.jarvis.edge.brain.EchoGuard
import com.jarvis.edge.brain.ModelOutput
import com.jarvis.edge.brain.parseModelOutput

/**
 * On-device evaluation gate that scores the active or candidate brain across echo traps,
 * factual/greeting probes, tool calls, and up to 4 held-out positive personal episodes.
 */
class OnDeviceEvalGate(
    private val probes: List<EvalProbe> = defaultProbes(),
    private val clockMs: () -> Long = System::currentTimeMillis,
) {

    data class EvalProbe(
        val id: String,
        val prompt: String,
        val expectedType: String,
        val expectedTool: String? = null,
        val expectedKeywords: List<String> = emptyList(),
        val forbidEcho: Boolean = true,
    )

    data class EvalGateResult(
        val evalScore: Float,
        val echoRate: Float,
        val toolAccuracy: Float,
        val avgLatencyMs: Long,
        val totalPrompts: Int,
        val passed: Boolean,
        val reason: String,
    )

    fun evaluate(
        heldOutEpisodes: List<Episode> = emptyList(),
        baseline: EvalGateResult? = null,
        isInterrupted: () -> Boolean = { false },
        generateFn: (EvalProbe) -> String,
    ): EvalGateResult {
        val extraProbes = heldOutEpisodes
            .asSequence()
            .filter { it.outcomeOk && !it.echoed && it.chosenTranscript.isNotBlank() }
            .distinctBy { it.chosenTranscript.trim().lowercase() }
            .take(MAX_HELD_OUT_EPISODES)
            .map { ep ->
                val tool = ep.toolName?.takeIf { it.isNotBlank() }
                EvalProbe(
                    id = "ep_${ep.id}",
                    prompt = ep.chosenTranscript.trim(),
                    expectedType = if (tool != null) "tool" else "say",
                    expectedTool = tool,
                    expectedKeywords = emptyList(),
                    forbidEcho = true,
                )
            }
            .toList()

        val allProbes = probes + extraProbes
        if (allProbes.isEmpty()) {
            return EvalGateResult(
                evalScore = 0f,
                echoRate = 0f,
                toolAccuracy = 0f,
                avgLatencyMs = 0L,
                totalPrompts = 0,
                passed = false,
                reason = "No evaluation probes configured",
            )
        }

        if (isInterrupted()) {
            return interruptedResult(totalPrompts = allProbes.size, evaluated = 0, totalLatencyMs = 0L)
        }

        var scoreSum = 0f
        var echoCount = 0
        var toolProbes = 0
        var toolCorrect = 0
        var totalLatencyMs = 0L
        var evaluated = 0

        for (probe in allProbes) {
            if (isInterrupted()) {
                return interruptedResult(
                    totalPrompts = allProbes.size,
                    evaluated = evaluated,
                    totalLatencyMs = totalLatencyMs,
                )
            }

            val t0 = clockMs()
            val raw = runCatching { generateFn(probe) }.getOrDefault("")
            val elapsed = (clockMs() - t0).coerceAtLeast(0L)
            totalLatencyMs += elapsed
            evaluated++

            if (isInterrupted()) {
                return interruptedResult(
                    totalPrompts = allProbes.size,
                    evaluated = evaluated,
                    totalLatencyMs = totalLatencyMs,
                )
            }

            val output = parseModelOutput(raw)
            val replyForEchoCheck = when (output) {
                is ModelOutput.Say -> output.text
                is ModelOutput.Call -> raw
            }
            val echoed = EchoGuard.isEcho(probe.prompt, replyForEchoCheck)

            if (probe.expectedType.equals("tool", ignoreCase = true)) {
                toolProbes++
                if (output is ModelOutput.Call &&
                    (probe.expectedTool == null || output.call.name.equals(probe.expectedTool, ignoreCase = true))
                ) {
                    toolCorrect++
                    scoreSum += 1.0f
                } else if (echoed) {
                    echoCount++
                }
            } else {
                if (echoed) {
                    echoCount++
                }
                val validSay = output is ModelOutput.Say && output.text.isNotBlank()
                if (validSay && (!probe.forbidEcho || !echoed)) {
                    val replyText = (output as ModelOutput.Say).text
                    val keywordOk = probe.expectedKeywords.isEmpty() ||
                        probe.expectedKeywords.any { kw -> replyText.contains(kw, ignoreCase = true) }
                    if (keywordOk) {
                        scoreSum += 1.0f
                    }
                }
            }
        }

        val total = allProbes.size
        val evalScore = scoreSum / total.toFloat()
        val echoRate = echoCount.toFloat() / total.toFloat()
        val toolAccuracy = if (toolProbes > 0) toolCorrect.toFloat() / toolProbes.toFloat() else 1.0f
        val avgLatencyMs = if (evaluated > 0) totalLatencyMs / evaluated else 0L

        val failureReason = when {
            echoRate > MAX_ECHO_RATE ->
                "Echo rate %.2f exceeds limit %.2f".format(echoRate, MAX_ECHO_RATE)
            evalScore < MIN_EVAL_SCORE ->
                "Eval score %.2f below minimum %.2f".format(evalScore, MIN_EVAL_SCORE)
            toolProbes > 0 && toolAccuracy < MIN_TOOL_ACCURACY ->
                "Tool accuracy %.2f below minimum %.2f".format(toolAccuracy, MIN_TOOL_ACCURACY)
            baseline != null && evalScore < baseline.evalScore - REGRESSION_EPSILON ->
                "Eval score %.2f regressed vs baseline %.2f".format(evalScore, baseline.evalScore)
            baseline != null && toolAccuracy < baseline.toolAccuracy - REGRESSION_EPSILON ->
                "Tool accuracy %.2f regressed vs baseline %.2f".format(toolAccuracy, baseline.toolAccuracy)
            else -> null
        }

        val passed = failureReason == null
        val reason = failureReason ?: "Passed: score=%.2f, echo=%.2f, tool=%.2f".format(
            evalScore,
            echoRate,
            toolAccuracy,
        )

        return EvalGateResult(
            evalScore = evalScore,
            echoRate = echoRate,
            toolAccuracy = toolAccuracy,
            avgLatencyMs = avgLatencyMs,
            totalPrompts = total,
            passed = passed,
            reason = reason,
        )
    }

    private fun interruptedResult(
        totalPrompts: Int,
        evaluated: Int,
        totalLatencyMs: Long,
    ): EvalGateResult {
        val avgLatency = if (evaluated > 0) totalLatencyMs / evaluated else 0L
        return EvalGateResult(
            evalScore = 0f,
            echoRate = 1f,
            toolAccuracy = 0f,
            avgLatencyMs = avgLatency,
            totalPrompts = totalPrompts,
            passed = false,
            reason = "Interrupted during evaluation",
        )
    }

    companion object {
        const val MAX_ECHO_RATE = 0.05f
        const val MIN_EVAL_SCORE = 0.50f
        const val MIN_TOOL_ACCURACY = 0.50f
        const val REGRESSION_EPSILON = 0.01f
        private const val MAX_HELD_OUT_EPISODES = 4

        fun defaultProbes(): List<EvalProbe> = listOf(
            // 4 echo traps: model must answer distinctly and never echo the prompt back
            EvalProbe(
                id = "echo_photosynthesis",
                prompt = "Explain photosynthesis briefly",
                expectedType = "say",
                expectedKeywords = emptyList(),
                forbidEcho = true,
            ),
            EvalProbe(
                id = "echo_sky_blue",
                prompt = "Why is the sky blue?",
                expectedType = "say",
                expectedKeywords = emptyList(),
                forbidEcho = true,
            ),
            EvalProbe(
                id = "echo_http",
                prompt = "What does HTTP stand for?",
                expectedType = "say",
                expectedKeywords = emptyList(),
                forbidEcho = true,
            ),
            EvalProbe(
                id = "echo_saturn",
                prompt = "Tell me a short fact about Saturn",
                expectedType = "say",
                expectedKeywords = emptyList(),
                forbidEcho = true,
            ),
            // 4 factual / greeting probes
            EvalProbe(
                id = "greet_hello",
                prompt = "Hello Jarvis, how are you?",
                expectedType = "say",
                expectedKeywords = emptyList(),
                forbidEcho = true,
            ),
            EvalProbe(
                id = "fact_math",
                prompt = "What is 2 plus 2?",
                expectedType = "say",
                expectedKeywords = listOf("4", "four"),
                forbidEcho = true,
            ),
            EvalProbe(
                id = "fact_france",
                prompt = "What is the capital of France?",
                expectedType = "say",
                expectedKeywords = listOf("paris"),
                forbidEcho = true,
            ),
            EvalProbe(
                id = "fact_week",
                prompt = "How many days are in a week?",
                expectedType = "say",
                expectedKeywords = listOf("7", "seven"),
                forbidEcho = true,
            ),
            // 2 tool probes
            EvalProbe(
                id = "tool_torch",
                prompt = "Turn on the flashlight",
                expectedType = "tool",
                expectedTool = "torch",
                forbidEcho = true,
            ),
            EvalProbe(
                id = "tool_web_search",
                prompt = "Search Wikipedia for Albert Einstein",
                expectedType = "tool",
                expectedTool = "web_search",
                forbidEcho = true,
            ),
        )
    }
}
