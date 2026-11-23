package com.jarvis.edge.mind

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OnDeviceEvalGateTest {

    private fun goodReplyFor(probe: OnDeviceEvalGate.EvalProbe): String = when (probe.id) {
        "echo_photosynthesis" -> "SAY: Plants use sunlight, water, and carbon dioxide to make sugar and oxygen."
        "echo_sky_blue" -> "SAY: Shorter blue light waves scatter more strongly across Earth's atmosphere."
        "echo_http" -> "SAY: HTTP stands for Hypertext Transfer Protocol."
        "echo_saturn" -> "SAY: Saturn is a gas giant surrounded by bright rings of ice and rock."
        "greet_hello" -> "SAY: Doing well and ready to help."
        "fact_math" -> "SAY: 2 plus 2 equals 4."
        "fact_france" -> "SAY: Its capital is Paris."
        "fact_week" -> "SAY: There are 7 days in a week."
        "tool_torch" -> """{"tool":"torch","args":{"state":"on"}}"""
        "tool_web_search" -> """{"tool":"web_search","args":{"query":"Albert Einstein"}}"""
        else -> if (probe.expectedType == "tool" && probe.expectedTool != null) {
            """{"tool":"${probe.expectedTool}","args":{}}"""
        } else {
            "SAY: Here is a direct and helpful response."
        }
    }

    @Test
    fun `defaultProbes contains 10 crisp probes covering echo traps factual and tools`() {
        val probes = OnDeviceEvalGate.defaultProbes()
        assertEquals(10, probes.size)
        assertEquals(8, probes.count { it.expectedType == "say" })
        assertEquals(2, probes.count { it.expectedType == "tool" })
        assertTrue(probes.any { it.expectedTool == "torch" })
        assertTrue(probes.any { it.expectedTool == "web_search" })
        assertTrue(probes.all { it.forbidEcho })
    }

    @Test
    fun `evaluate passes when all probes succeed with zero echoes and tracks latency`() {
        var now = 1_000L
        val gate = OnDeviceEvalGate(clockMs = { now })

        val result = gate.evaluate { probe ->
            now += 25L
            goodReplyFor(probe)
        }

        assertTrue(result.passed, "Expected pass but got: ${result.reason}")
        assertEquals(10, result.totalPrompts)
        assertEquals(1.0f, result.evalScore, 1e-5f)
        assertEquals(0.0f, result.echoRate, 1e-5f)
        assertEquals(1.0f, result.toolAccuracy, 1e-5f)
        assertEquals(25L, result.avgLatencyMs)
    }

    @Test
    fun `evaluate fails when echo rate exceeds threshold`() {
        val gate = OnDeviceEvalGate()
        val result = gate.evaluate { probe ->
            if (probe.id == "echo_photosynthesis") {
                "SAY: Explain photosynthesis briefly"
            } else {
                goodReplyFor(probe)
            }
        }

        assertFalse(result.passed)
        assertEquals(0.10f, result.echoRate, 1e-5f)
        assertTrue(result.reason.contains("Echo rate", ignoreCase = true))
    }

    @Test
    fun `evaluate rejects regression against baseline evalScore or toolAccuracy`() {
        val gate = OnDeviceEvalGate()
        val baseline = gate.evaluate { probe -> goodReplyFor(probe) }
        assertTrue(baseline.passed)

        // Factual score regression (wrong answer on math probe)
        val regressedScore = gate.evaluate(baseline = baseline) { probe ->
            if (probe.id == "fact_math") "SAY: Five." else goodReplyFor(probe)
        }
        assertFalse(regressedScore.passed)
        assertEquals(0.9f, regressedScore.evalScore, 1e-5f)
        assertTrue(regressedScore.reason.contains("regressed", ignoreCase = true))

        // Tool accuracy regression
        val regressedTool = gate.evaluate(baseline = baseline) { probe ->
            if (probe.id == "tool_torch") """{"tool":"note","args":{}}""" else goodReplyFor(probe)
        }
        assertFalse(regressedTool.passed)
        assertEquals(0.5f, regressedTool.toolAccuracy, 1e-5f)
    }

    @Test
    fun `evaluate appends up to 4 positive held-out episodes and ignores echoed or failed ones`() {
        val episodes = listOf(
            Episode(
                id = 1,
                timestampMs = 100L,
                liveTranscript = "bad echoed",
                accurateTranscript = "bad echoed",
                chosenTranscript = "bad echoed",
                reply = "bad echoed",
                echoed = true,
                outcomeOk = true,
            ),
            Episode(
                id = 2,
                timestampMs = 200L,
                liveTranscript = "failed turn",
                accurateTranscript = "failed turn",
                chosenTranscript = "failed turn",
                reply = "error",
                echoed = false,
                outcomeOk = false,
            ),
        ) + (1..6).map { i ->
            Episode(
                id = 10L + i,
                timestampMs = 1000L + i * 100L,
                liveTranscript = "custom question $i",
                accurateTranscript = "custom question $i",
                chosenTranscript = "custom question $i",
                reply = "Direct answer $i",
                toolName = if (i == 1) "timer" else null,
                echoed = false,
                outcomeOk = true,
            )
        }

        val gate = OnDeviceEvalGate()
        val seenIds = mutableListOf<String>()
        val result = gate.evaluate(heldOutEpisodes = episodes) { probe ->
            seenIds.add(probe.id)
            goodReplyFor(probe)
        }

        assertTrue(result.passed)
        assertEquals(14, result.totalPrompts)
        assertEquals(14, seenIds.size)
        assertTrue("ep_11" in seenIds)
        assertFalse("ep_1" in seenIds)
        assertFalse("ep_2" in seenIds)
    }

    @Test
    fun `evaluate aborts cleanly and fails when interrupted`() {
        var calls = 0
        val gate = OnDeviceEvalGate()
        val result = gate.evaluate(
            isInterrupted = { calls >= 2 },
        ) { probe ->
            calls++
            goodReplyFor(probe)
        }

        assertFalse(result.passed)
        assertEquals(2, calls)
        assertTrue(result.reason.contains("Interrupted", ignoreCase = true))
    }

    @Test
    fun `evaluate fails when all replies are empty even without echoes`() {
        val gate = OnDeviceEvalGate()
        val result = gate.evaluate { "" }

        assertFalse(result.passed)
        assertEquals(0.0f, result.evalScore, 1e-5f)
    }

    /** What Qwen2.5 1.5B on the Pad really answered: plain sentences for the tool probes, one restated request. */
    private fun padReplyFor(probe: OnDeviceEvalGate.EvalProbe): String = when (probe.id) {
        "tool_torch" -> "SAY: Setting the flashlight to on."
        "tool_web_search" -> "SAY: I will search Wikipedia for Albert Einstein."
        else -> goodReplyFor(probe)
    }

    @Test
    fun `a candidate only has to match the current brain on limits the current brain already misses`() {
        val gate = OnDeviceEvalGate()
        val baseline = gate.evaluate(generateFn = ::padReplyFor)
        assertFalse(baseline.passed) // the brain in use misses the fixed tool limit itself
        assertEquals(0.0f, baseline.toolAccuracy, 1e-5f)

        // Seen on the Pad: every candidate was rejected for the same misses, so nothing could ever be adopted.
        val same = gate.evaluate(baseline = baseline, generateFn = ::padReplyFor)
        assertTrue(same.passed, same.reason)

        // Worse than the current brain is still rejected.
        val worse = gate.evaluate(baseline = baseline) { probe ->
            if (probe.id == "echo_http") "SAY: What does HTTP stand for?" else padReplyFor(probe)
        }
        assertFalse(worse.passed)
        val wrong = gate.evaluate(baseline = baseline) { probe ->
            if (probe.id == "fact_math") "SAY: Five." else padReplyFor(probe)
        }
        assertFalse(wrong.passed)
    }

    @Test
    fun `a past question Jarvis looked up on the web is graded as an answer, not as a tool call`() {
        val lookedUp = Episode(
            id = 141,
            timestampMs = 100L,
            liveTranscript = "explain how a rainbow forms",
            accurateTranscript = "explain how a rainbow forms",
            chosenTranscript = "explain how a rainbow forms",
            reply = "Rainbows form when sunlight is refracted in droplets.",
            toolName = "web_search", // the Brain's own lookup after an unsure answer, not a call the model made
            echoed = false,
            outcomeOk = true,
        )
        val seen = mutableMapOf<String, OnDeviceEvalGate.EvalProbe>()
        OnDeviceEvalGate().evaluate(heldOutEpisodes = listOf(lookedUp)) { probe ->
            seen[probe.id] = probe
            goodReplyFor(probe)
        }
        assertEquals("say", seen.getValue("ep_141").expectedType)
    }
}
