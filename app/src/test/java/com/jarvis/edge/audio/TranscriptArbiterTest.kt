package com.jarvis.edge.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TranscriptArbiterTest {

    private val arbiter = TranscriptArbiter(confirmWerThreshold = 0.65f)

    @Test
    fun `applyCorrections replaces longest keys first on word boundaries case-insensitively`() {
        val corrections = mapOf(
            "jarvis" to "Jarvis",
            "hey jarvis" to "Hey Jarvis",
            "ac" to "air conditioner",
        )
        val input = "HEY JARVIS turn on the ac in the back"
        val out = arbiter.applyCorrections(input, corrections)
        assertEquals("Hey Jarvis turn on the air conditioner in the back", out)
    }

    @Test
    fun `applyCorrections does not chain replacements or corrupt regex characters`() {
        val corrections = mapOf(
            "foo" to "bar",
            "bar" to "baz",
            "c++" to "C++ ($100)",
        )
        assertEquals("bar baz", arbiter.applyCorrections("foo bar", corrections))
        assertEquals("learn C++ ($100) now", arbiter.applyCorrections("learn c++ now", corrections))
    }

    @Test
    fun `wordDistanceRatio ignores case and punctuation and handles empty inputs`() {
        assertEquals(0f, arbiter.wordDistanceRatio("", ""))
        assertEquals(1f, arbiter.wordDistanceRatio("hello", ""))
        assertEquals(1f, arbiter.wordDistanceRatio("", "hello"))
        assertEquals(0f, arbiter.wordDistanceRatio("Turn on the light!", "turn on the light"))
        assertEquals(0.25f, arbiter.wordDistanceRatio("turn on the light", "turn on the night"), 1e-5f)
        assertEquals(1f, arbiter.wordDistanceRatio("open youtube", "close browser"), 1e-5f)
    }

    @Test
    fun `arbitrate handles blank or equal transcripts without confirmation`() {
        val onlyLive = arbiter.arbitrate("turn on torch", "   ")
        assertEquals("turn on torch", onlyLive.chosen)
        assertFalse(onlyLive.needsConfirmation)

        val onlyAccurate = arbiter.arbitrate("", "set timer for five minutes")
        assertEquals("set timer for five minutes", onlyAccurate.chosen)
        assertFalse(onlyAccurate.needsConfirmation)

        val equal = arbiter.arbitrate("Set Timer", "set timer")
        assertEquals("set timer", equal.chosen)
        assertFalse(equal.needsConfirmation)
    }

    @Test
    fun `arbitrate prefers transcript matching intentMatcher`() {
        val res = arbiter.arbitrate(
            live = "turn on the torch",
            accurate = "turn on the porch",
            intentMatcher = { it.contains("torch") },
        )
        assertEquals("turn on the torch", res.chosen)
        assertFalse(res.needsConfirmation)

        val resAccurate = arbiter.arbitrate(
            live = "set a dimer for five minutes",
            accurate = "set a timer for five minutes",
            intentMatcher = { it.contains("timer") },
        )
        assertEquals("set a timer for five minutes", resAccurate.chosen)
        assertFalse(resAccurate.needsConfirmation)
    }

    @Test
    fun `arbitrate rejects dangling quotes truncation and stutter`() {
        val trailingQuote = arbiter.arbitrate("turn on the bedroom light", "turn on the bedroom light\"")
        assertEquals("turn on the bedroom light", trailingQuote.chosen)
        assertFalse(trailingQuote.needsConfirmation)

        val truncatedAccurate = arbiter.arbitrate("what is the weather in tokyo today", "what is")
        assertEquals("what is the weather in tokyo today", truncatedAccurate.chosen)
        assertFalse(truncatedAccurate.needsConfirmation)

        val stutterLive = arbiter.arbitrate("the the the", "what is the time")
        assertEquals("what is the time", stutterLive.chosen)
        assertFalse(stutterLive.needsConfirmation)
    }

    @Test
    fun `arbitrate applies corrections before choosing and triggers confirmation on high WER`() {
        val corrections = mapOf("jar vis" to "jarvis")
        val res = arbiter.arbitrate(
            live = "jar vis play jazz music",
            accurate = "open calendar for tomorrow",
            corrections = corrections,
        )
        assertEquals("open calendar for tomorrow", res.chosen)
        assertEquals("jar vis play jazz music", res.liveRaw)
        assertEquals("open calendar for tomorrow", res.accurateRaw)
        assertTrue(res.wordErrorRate >= 0.65f)
        assertTrue(res.needsConfirmation)
    }

    @Test
    fun `arbitrate does not request confirmation for single word or low WER differences`() {
        val singleWord = arbiter.arbitrate("hello", "yellow")
        assertEquals("yellow", singleWord.chosen)
        assertFalse(singleWord.needsConfirmation)

        val lowWer = arbiter.arbitrate("turn on the kitchen light", "turn on the kitchen lights")
        assertEquals("turn on the kitchen lights", lowWer.chosen)
        assertFalse(lowWer.needsConfirmation)
    }

    @Test
    fun `isYes and isNo recognize confirmation phrases without false positives`() {
        listOf("yes", "Yeah!", "yep.", "Correct", "right", "yup", "confirm", "Affirmative", "that's right").forEach {
            assertTrue(arbiter.isYes(it), "Expected isYes=true for '$it'")
            assertFalse(arbiter.isNo(it), "Expected isNo=false for '$it'")
        }
        listOf("no", "Nope!", "wrong", "incorrect", "Cancel", "never mind.", "not that").forEach {
            assertTrue(arbiter.isNo(it), "Expected isNo=true for '$it'")
            assertFalse(arbiter.isYes(it), "Expected isYes=false for '$it'")
        }
        assertFalse(arbiter.isYes("turn right"))
        assertFalse(arbiter.isNo("turn right"))
        assertFalse(arbiter.isYes(""))
        assertFalse(arbiter.isNo(""))
    }
}
