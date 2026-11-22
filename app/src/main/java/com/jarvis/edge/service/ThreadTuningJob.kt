package com.jarvis.edge.service

import com.jarvis.edge.brain.LlamaJniEngine
import com.jarvis.edge.brain.ThreadTiming
import com.jarvis.edge.brain.ThreadTuner
import kotlinx.coroutines.delay

/**
 * Finds the fastest thread counts for the chat model on this phone, once per model and phone. It runs only while the
 * assistant is idle, one short timing at a time, and steps aside whenever the user speaks (the engine cancels a timing
 * run as soon as a reply is needed). Returns a one-line summary, or null when there was nothing to do.
 */
suspend fun tuneThreadsWhenIdle(
    engine: LlamaJniEngine,
    isIdle: () -> Boolean,
    log: (String) -> Unit,
    settleMs: Long = 45_000,
    pollMs: Long = 2_000,
): String? {
    if (!engine.needsThreadTuning()) return null
    val cpu = engine.cpuInfo ?: return null
    delay(settleMs) // let start-up loading finish so it does not skew the timings

    suspend fun waitForIdle() {
        var quiet = 0L
        while (quiet < 5_000) {
            delay(pollMs)
            quiet = if (isIdle()) quiet + pollMs else 0
        }
    }

    val base = ThreadTuner.defaultChoice(cpu).let { maxOf(it.generate, it.prefill) }
    val timings = mutableListOf<ThreadTiming>()
    val runMs = mutableListOf<Long>()
    val notes = StringBuilder()
    for (k in ThreadTuner.candidates(cpu)) {
        var attempts = 0
        var timed = false
        while (true) {
            if (!engine.isLoaded) return null
            waitForIdle()
            when (val r = engine.benchThreads(k, ThreadTuner.budgetMs(runMs))) {
                is LlamaJniEngine.BenchResult.Timed -> {
                    timings += r.timing
                    runMs += r.elapsedMs
                    timed = true
                    notes.append(" $k:[prefill %.1f, gen %.1f ms/tok]".format(r.timing.prefillMsPerToken, r.timing.generateMsPerToken))
                    break
                }
                is LlamaJniEngine.BenchResult.TooSlow -> {
                    notes.append(" $k:[too slow after ${r.elapsedMs} ms]")
                    break
                }
                LlamaJniEngine.BenchResult.Cancelled -> {
                    if (++attempts >= 5) return null // the user keeps needing the model; try again next start
                }
            }
        }
        if (!timed) {
            if (k > base) break // more threads only got slower
            continue
        }
        if (ThreadTuner.shouldStop(timings, cpu)) break
    }
    val choice = ThreadTuner.choose(timings, cpu) ?: return null
    if (!engine.applyThreads(choice)) return null
    val summary = "thread tuning (${cpu.features}):$notes -> generate ${choice.generate}, prefill ${choice.prefill}" +
        (if (choice.trialGenerate > 0) "; real answers will compare generate ${choice.generate} with ${choice.trialGenerate}" else "")
    log(summary)
    return summary
}
