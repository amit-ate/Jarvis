package com.jarvis.edge.brain

import java.io.File

/**
 * The processor as the native layer sees it: online core count, how many online cores share the top speed, SIMD
 * features, and the core layout (which also names the online cores, so a core switched off changes the tuning key).
 */
data class CpuInfo(val ncpu: Int, val bigCluster: Int, val features: String, val signature: String) {
    companion object {
        /** Parses "ncpu=8;big=4;features=NEON=1 DOTPROD=1;sig=cap,0:495,...". */
        fun parse(raw: String): CpuInfo? {
            val fields = raw.split(';').mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i) to part.substring(i + 1)
            }.toMap()
            val ncpu = fields["ncpu"]?.toIntOrNull() ?: return null
            val big = fields["big"]?.toIntOrNull() ?: return null
            if (ncpu < 1 || big < 1) return null
            return CpuInfo(ncpu, big.coerceAtMost(ncpu), fields["features"].orEmpty(), fields["sig"].orEmpty())
        }
    }
}

/**
 * Threads for generating new tokens and for reading the prompt. [trialGenerate] > 0 means the idle timing could not
 * settle generation: real answers still alternate between [generate] and [trialGenerate] (see [GenerationTrial]).
 */
data class ThreadChoice(val generate: Int, val prefill: Int, val trialGenerate: Int = 0)

/** One timed run: milliseconds per prompt token and per generated token with [threads] threads. */
data class ThreadTiming(val threads: Int, val prefillMsPerToken: Double, val generateMsPerToken: Double)

/**
 * Plans and judges the per-phone thread tuning. The native side times one thread count at a time
 * ([LlamaJniEngine.benchThreads]); this decides which counts to try, when to stop and which to keep.
 */
object ThreadTuner {
    /** Bump when the timing method changes, so phones re-tune. */
    const val VERSION = 5

    /** A count this much slower than the best still wins if it uses fewer threads (less heat, room for audio). */
    const val TOLERANCE = 0.05

    /** Counts to try, smallest first: 2, 3, 4, 6, 8 that fit, plus the size of the fastest cluster. */
    fun candidates(cpu: CpuInfo): List<Int> =
        (listOf(2, 3, 4, 6, 8) + listOf(cpu.bigCluster).filter { it > 1 })
            .filter { it <= cpu.ncpu }
            .distinct()
            .sorted()
            .ifEmpty { listOf(cpu.ncpu) }

    /** What the native layer uses before tuning; mirrors default_threads() in llama_jni.cpp. */
    fun defaultChoice(cpu: CpuInfo): ThreadChoice = when {
        cpu.bigCluster >= 4 -> ThreadChoice(3, 4)
        cpu.bigCluster == 3 -> ThreadChoice(3, 3)
        else -> minOf(4, cpu.ncpu).let { ThreadChoice(it, it) }
    }

    /** The fewest threads within [TOLERANCE] of the fastest. */
    fun pickFastest(times: List<Pair<Int, Double>>): Int? {
        val best = times.minOfOrNull { it.second } ?: return null
        return times.filter { it.second <= best * (1 + TOLERANCE) }.minOf { it.first }
    }

    fun choose(timings: List<ThreadTiming>): ThreadChoice? {
        val gen = pickFastest(timings.map { it.threads to it.generateMsPerToken }) ?: return null
        val pre = pickFastest(timings.map { it.threads to it.prefillMsPerToken }) ?: return null
        return ThreadChoice(gen, pre)
    }

    /**
     * Like [choose], but when the fastest generation count borrows slower cores, the best count on the fast cores alone
     * is kept as a trial alternative. While the phone is idle slow cores look free; during a real answer speech
     * synthesis and audio share them (on the Redmi Note 5 Pro, 4 threads timed 10% faster idle but ran 15% slower in
     * use), so real answers decide between the two.
     */
    fun choose(timings: List<ThreadTiming>, cpu: CpuInfo): ThreadChoice? {
        val base = choose(timings) ?: return null
        if (base.generate <= cpu.bigCluster) return base
        val fastOnly = pickFastest(timings.filter { it.threads <= cpu.bigCluster }.map { it.threads to it.generateMsPerToken })
            ?: return base
        return if (fastOnly == base.generate) base else base.copy(trialGenerate = fastOnly)
    }

    /**
     * Past the default set, stop once a bigger count is clearly (15%) slower than the previous at both jobs: adding
     * slower cores only makes every barrier wait longer.
     */
    fun shouldStop(timings: List<ThreadTiming>, cpu: CpuInfo): Boolean {
        if (timings.size < 2) return false
        val last = timings[timings.size - 1]
        val prev = timings[timings.size - 2]
        val base = defaultChoice(cpu).let { maxOf(it.generate, it.prefill) }
        return last.threads > base &&
            last.generateMsPerToken > prev.generateMsPerToken * 1.15 &&
            last.prefillMsPerToken > prev.prefillMsPerToken * 1.15
    }

    /** Time allowed for timing one count: twice the slowest run so far (first run 20 s, it may page weights in). */
    fun budgetMs(previousRunMs: List<Long>): Long =
        previousRunMs.maxOrNull()?.let { (it * 2).coerceIn(3_000L, 20_000L) } ?: 20_000L

    /** Results depend on the model file, the CPU code variant and the core layout, so all three are in the key. */
    fun cacheKey(modelFile: File, cpu: CpuInfo): String =
        "v${VERSION}|${modelFile.name}|${modelFile.length()}|${cpu.features}|${cpu.signature}"
}

/**
 * Decides between two generation thread counts from real answers: uses whichever has fewer samples next, and after
 * [samplesEach] answers each keeps the higher median speed (the fewer threads when within 3%).
 */
class GenerationTrial(val first: Int, val second: Int, private val samplesEach: Int = 5) {
    private val speeds = mapOf(first to mutableListOf<Double>(), second to mutableListOf<Double>())

    fun next(): Int = if (speeds.getValue(second).size < speeds.getValue(first).size) second else first

    fun record(threads: Int, tokensPerSecond: Double) {
        if (tokensPerSecond > 0) speeds[threads]?.add(tokensPerSecond)
    }

    /** The winner once both counts have enough answers, else null. */
    fun decided(): Int? {
        val a = speeds.getValue(first)
        val b = speeds.getValue(second)
        if (a.size < samplesEach || b.size < samplesEach) return null
        val ma = median(a)
        val mb = median(b)
        val (fewer, more) = if (first < second) first to second else second to first
        val mFewer = if (fewer == first) ma else mb
        val mMore = if (more == first) ma else mb
        return if (mMore > mFewer * 1.03) more else fewer
    }

    fun summary(): String = listOf(first, second).joinToString(", ") { t ->
        val s = speeds.getValue(t)
        "$t threads: median %.1f tok/s over %d answers".format(if (s.isEmpty()) 0.0 else median(s), s.size)
    }

    private fun median(v: List<Double>): Double = v.sorted().let { s ->
        if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}

/** Tuning results kept as "key=generate,prefill[,trialGenerate]" lines in the app's private files. */
class ThreadTuningStore(private val file: File) {
    @Synchronized
    fun get(key: String): ThreadChoice? = readAll()[key]

    @Synchronized
    fun put(key: String, choice: ThreadChoice) {
        val all = readAll().toMutableMap()
        all[key] = choice
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(
            all.entries.joinToString("") { (k, c) ->
                "$k=${c.generate},${c.prefill}" + (if (c.trialGenerate > 0) ",${c.trialGenerate}" else "") + "\n"
            },
        )
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun readAll(): Map<String, ThreadChoice> {
        if (!file.isFile) return emptyMap()
        return runCatching {
            file.readLines().mapNotNull { line ->
                val i = line.lastIndexOf('=')
                if (i <= 0) return@mapNotNull null
                val parts = line.substring(i + 1).split(',').mapNotNull { it.trim().toIntOrNull() }
                when {
                    parts.size !in 2..3 || parts.any { it < 1 } -> null
                    else -> line.substring(0, i) to ThreadChoice(parts[0], parts[1], parts.getOrElse(2) { 0 })
                }
            }.toMap()
        }.getOrDefault(emptyMap())
    }
}
