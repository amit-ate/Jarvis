package com.jarvis.edge.brain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ThreadTunerTest {
    private val redmi = CpuInfo(8, 4, "NEON=1 ARM_FMA=1", "freq,0:1612800")
    private val pad = CpuInfo(8, 2, "NEON=1 DOTPROD=1", "cap,0:495")

    @Test
    fun parsesNativeReport() {
        val c = CpuInfo.parse("ncpu=8;big=2;features=NEON=1 FP16_VA=1 DOTPROD=1;sig=cap,0:495,7:1024")
        assertEquals(CpuInfo(8, 2, "NEON=1 FP16_VA=1 DOTPROD=1", "cap,0:495,7:1024"), c)
        assertNull(CpuInfo.parse("garbage"))
        assertNull(CpuInfo.parse("ncpu=0;big=1"))
        assertEquals(4, CpuInfo.parse("ncpu=4;big=9")!!.bigCluster)
    }

    @Test
    fun candidatesFitThePhone() {
        assertEquals(listOf(2, 3, 4, 6, 8), ThreadTuner.candidates(redmi))
        assertEquals(listOf(2, 3, 4), ThreadTuner.candidates(CpuInfo(4, 4, "", "")))
        assertEquals(listOf(2, 3, 4, 5, 6), ThreadTuner.candidates(CpuInfo(6, 5, "", "")))
        assertEquals(listOf(1), ThreadTuner.candidates(CpuInfo(1, 1, "", "")))
    }

    @Test
    fun defaultsMatchTheNativeLayer() {
        assertEquals(ThreadChoice(3, 4), ThreadTuner.defaultChoice(redmi))
        assertEquals(ThreadChoice(4, 4), ThreadTuner.defaultChoice(pad))
        assertEquals(ThreadChoice(2, 2), ThreadTuner.defaultChoice(CpuInfo(2, 2, "", "")))
        // Redmi Note 5 Pro with core 5 switched off: 7 online, 3 fast.
        assertEquals(ThreadChoice(3, 3), ThreadTuner.defaultChoice(CpuInfo(7, 3, "", "")))
    }

    @Test
    fun fewerThreadsWinWhenNearlyAsFast() {
        assertEquals(4, ThreadTuner.pickFastest(listOf(2 to 90.0, 4 to 82.4, 6 to 79.6, 8 to 93.4)))
        assertEquals(6, ThreadTuner.pickFastest(listOf(2 to 90.0, 4 to 85.0, 6 to 70.0)))
        assertNull(ThreadTuner.pickFastest(emptyList()))
    }

    @Test
    fun choosesGenerateAndPrefillSeparately() {
        val t = listOf(
            ThreadTiming(2, 48.0, 91.2),
            ThreadTiming(4, 23.8, 89.7),
            ThreadTiming(8, 16.0, 86.6),
        )
        assertEquals(ThreadChoice(4, 8), ThreadTuner.choose(t))
        assertNull(ThreadTuner.choose(emptyList()))
    }

    @Test
    fun slowCoreWinnerGetsAFastCoreTrial() {
        // Redmi with core 5 off: 3 fast cores. Idle timing preferred 4 threads (one slow core).
        val redmiNow = CpuInfo(7, 3, "NEON=1", "freq")
        val r = listOf(
            ThreadTiming(2, 87.4, 188.4),
            ThreadTiming(3, 66.8, 172.4),
            ThreadTiming(4, 55.7, 154.8),
            ThreadTiming(6, 59.0, 294.8),
        )
        assertEquals(ThreadChoice(4, 4, trialGenerate = 3), ThreadTuner.choose(r, redmiNow))
        // Fast-cores-only winner needs no trial.
        val p = listOf(ThreadTiming(2, 30.0, 80.0), ThreadTiming(4, 20.0, 95.0))
        assertEquals(ThreadChoice(2, 4), ThreadTuner.choose(p, pad))
    }

    @Test
    fun trialAlternatesAndKeepsTheFasterInRealUse() {
        val t = GenerationTrial(4, 3, samplesEach = 3)
        assertEquals(4, t.next())
        t.record(4, 5.0)
        assertEquals(3, t.next())
        t.record(3, 7.0)
        repeat(2) { t.record(4, 5.5); t.record(3, 6.8) }
        assertNull(GenerationTrial(4, 3, 3).decided())
        assertEquals(3, t.decided())
        val close = GenerationTrial(4, 2, samplesEach = 2)
        repeat(2) { close.record(4, 10.2); close.record(2, 10.0) }
        assertEquals(2, close.decided()) // within 3%: fewer threads
        val clear = GenerationTrial(4, 2, samplesEach = 2)
        repeat(2) { clear.record(4, 10.8); clear.record(2, 9.8) }
        assertEquals(4, clear.decided())
    }

    @Test
    fun stopsOnlyPastTheDefaultSetWhenClearlySlower() {
        val slower = listOf(ThreadTiming(4, 20.0, 100.0), ThreadTiming(6, 30.0, 130.0))
        assertTrue(ThreadTuner.shouldStop(slower, redmi))
        val inside = listOf(ThreadTiming(2, 20.0, 100.0), ThreadTiming(3, 30.0, 130.0))
        assertFalse(ThreadTuner.shouldStop(inside, redmi))
        val mixed = listOf(ThreadTiming(4, 20.0, 100.0), ThreadTiming(6, 15.0, 130.0))
        assertFalse(ThreadTuner.shouldStop(mixed, redmi))
    }

    @Test
    fun budgetGrowsWithTheSlowestRunWithinLimits() {
        assertEquals(20_000L, ThreadTuner.budgetMs(emptyList()))
        assertEquals(8_000L, ThreadTuner.budgetMs(listOf(4_000L, 2_000L)))
        assertEquals(3_000L, ThreadTuner.budgetMs(listOf(500L)))
        assertEquals(20_000L, ThreadTuner.budgetMs(listOf(60_000L)))
    }

    @Test
    fun cacheKeyChangesWithModelVariantAndLayout(@TempDir dir: File) {
        val model = File(dir, "m.gguf").apply { writeBytes(ByteArray(10)) }
        val k1 = ThreadTuner.cacheKey(model, redmi)
        assertNotEquals(k1, ThreadTuner.cacheKey(model, redmi.copy(features = "NEON=1 DOTPROD=1")))
        assertNotEquals(k1, ThreadTuner.cacheKey(model, redmi.copy(signature = "freq,0:1")))
        model.writeBytes(ByteArray(11))
        assertNotEquals(k1, ThreadTuner.cacheKey(model, redmi))
    }

    @Test
    fun storeRoundTripsAndIgnoresJunk(@TempDir dir: File) {
        val f = File(dir, "cpu_tuning.txt")
        val store = ThreadTuningStore(f)
        assertNull(store.get("a"))
        val key = "v3|m.gguf|10|NEON=1 DOTPROD=1|cap,0:495"
        store.put(key, ThreadChoice(4, 8))
        store.put("other", ThreadChoice(3, 4))
        assertEquals(ThreadChoice(4, 8), ThreadTuningStore(f).get(key))
        store.put(key, ThreadChoice(2, 6))
        assertEquals(ThreadChoice(2, 6), store.get(key))
        assertEquals(ThreadChoice(3, 4), store.get("other"))
        f.appendText("broken line\nx=0,4\n")
        assertEquals(ThreadChoice(2, 6), store.get(key))
        assertNull(store.get("x"))
        store.put("trial", ThreadChoice(4, 4, trialGenerate = 3))
        assertEquals(ThreadChoice(4, 4, 3), ThreadTuningStore(f).get("trial"))
    }
}
