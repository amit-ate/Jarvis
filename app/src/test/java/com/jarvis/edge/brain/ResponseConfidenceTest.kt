package com.jarvis.edge.brain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.ln

class ResponseConfidenceTest {
    private fun meterOf(vararg logProbs: Float): ConfidenceMeter =
        ConfidenceMeter().also { m -> logProbs.forEach { m.add(it) } }

    @Test
    fun emptyMeterGivesNone() {
        val c = ConfidenceMeter().snapshot()
        assertEquals(0, c.tokens)
        assertEquals(1f, c.score, 1e-6f)
        assertEquals(1f, c.minProb, 1e-6f)
        assertEquals(0f, c.lowFraction, 1e-6f)
        assertFalse(c.isUncertain())
    }

    @Test
    fun confidentTokensAreNotUncertain() {
        val lp = ln(0.9).toFloat()
        val c = meterOf(lp, lp, lp, lp, lp)
        assertEquals(5, c.snapshot().tokens)
        assertEquals(0.9f, c.snapshot().score, 1e-3f)
        assertEquals(0f, c.snapshot().lowFraction, 1e-6f)
        assertFalse(c.snapshot().isUncertain())
    }

    @Test
    fun oneVeryUnlikelyTokenMakesAnswerUncertain() {
        val good = ln(0.95).toFloat()
        val c = meterOf(ln(0.01).toFloat(), good, good, good, good, good, good, good, good, good).snapshot()
        assertEquals(10, c.tokens)
        assertEquals(0.01f, c.minProb, 1e-4f)
        assertTrue(c.isUncertain())
    }

    @Test
    fun nanAndInfinityAreIgnored() {
        val m = ConfidenceMeter()
        m.add(Float.NaN)
        m.add(Float.POSITIVE_INFINITY)
        m.add(Float.NEGATIVE_INFINITY)
        val lp = ln(0.9).toFloat()
        m.add(lp)
        m.add(lp)
        m.add(lp)
        val c = m.snapshot()
        assertEquals(3, c.tokens)
        assertEquals(0.9f, c.score, 1e-3f)
    }

    @Test
    fun positiveLogProbIsClampedToCertain() {
        val c = meterOf(0.5f, 0.5f).snapshot()
        assertEquals(1f, c.score, 1e-6f)
        assertEquals(1f, c.minProb, 1e-6f)
    }

    @Test
    fun takeAndResetClearsMeter() {
        val m = meterOf(ln(0.8).toFloat(), ln(0.8).toFloat())
        val taken = m.takeAndReset()
        assertEquals(2, taken.tokens)
        assertEquals(0, m.snapshot().tokens)
    }

    @Test
    fun combineOfSnapshotsMatchesSnapshotOfAllTokens() {
        val logs = floatArrayOf(ln(0.9).toFloat(), ln(0.3).toFloat(), ln(0.05).toFloat(), ln(0.7).toFloat(), ln(0.6).toFloat())
        val first = meterOf(*logs.copyOfRange(0, 2)).snapshot()
        val second = meterOf(*logs.copyOfRange(2, 5)).snapshot()
        val all = meterOf(*logs).snapshot()
        val merged = combine(listOf(first, second))
        assertEquals(all.tokens, merged.tokens)
        assertEquals(all.score, merged.score, 1e-4f)
        assertEquals(all.minProb, merged.minProb, 1e-4f)
        assertEquals(all.lowFraction, merged.lowFraction, 1e-4f)
    }

    @Test
    fun combineEmptyOrZeroTokenGivesNone() {
        assertEquals(Confidence.NONE, combine(emptyList()))
        assertEquals(Confidence.NONE, combine(listOf(Confidence.NONE, Confidence.NONE)))
    }

    @Test
    fun fewerThanTwoTokensAreNeverUncertain() {
        assertFalse(Confidence.NONE.isUncertain())
        assertFalse(meterOf(ln(0.001).toFloat()).snapshot().isUncertain())
        assertFalse(Confidence(1, 0.01f, 0.01f, 1f).isUncertain())
    }

    @Test
    fun describeFormatsTwoDecimals() {
        val c = Confidence(14, 0.71f, 0.03f, 0.2f)
        assertEquals("score=0.71 min=0.03 low=0.20 n=14", c.describe())
    }
}
