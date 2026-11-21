package com.jarvis.edge.brain

import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

/**
 * How sure the model was about the tokens it produced.
 *
 * score = geometric-mean token probability (exp of the mean log-prob).
 * minProb = probability of the least likely token.
 * lowFraction = share of tokens whose probability is below [LOW_TOKEN_PROB].
 */
data class Confidence(
    val tokens: Int,
    val score: Float,
    val minProb: Float,
    val lowFraction: Float,
) {
    /** True when the answer looks like a guess. Needs at least two tokens to judge. */
    fun isUncertain(): Boolean =
        tokens >= 2 &&
            (score < UNCERTAIN_SCORE || minProb < UNCERTAIN_MIN_PROB || lowFraction > UNCERTAIN_LOW_FRACTION)

    /** Compact log line, e.g. "score=0.71 min=0.03 low=0.20 n=14". */
    fun describe(): String =
        String.format(Locale.US, "score=%.2f min=%.2f low=%.2f n=%d", score, minProb, lowFraction, tokens)

    companion object {
        const val LOW_TOKEN_PROB = 0.10f
        const val UNCERTAIN_SCORE = 0.55f
        const val UNCERTAIN_MIN_PROB = 0.02f
        const val UNCERTAIN_LOW_FRACTION = 0.34f

        val NONE = Confidence(0, 1f, 1f, 0f)
    }
}

/** Accumulates per-token log-probabilities and reports them as a [Confidence]. Not thread-safe. */
class ConfidenceMeter {
    private var count = 0
    private var sumLog = 0.0
    private var minLog = Double.POSITIVE_INFINITY
    private var lowCount = 0

    fun add(logProb: Float) {
        if (logProb.isNaN() || logProb.isInfinite()) return
        val lp = min(logProb, 0f).toDouble()
        count++
        sumLog += lp
        if (lp < minLog) minLog = lp
        if (exp(lp) < Confidence.LOW_TOKEN_PROB) lowCount++
    }

    fun snapshot(): Confidence {
        if (count == 0) return Confidence.NONE
        return Confidence(
            tokens = count,
            score = exp(sumLog / count).toFloat(),
            minProb = exp(minLog).toFloat(),
            lowFraction = lowCount.toFloat() / count,
        )
    }

    fun reset() {
        count = 0
        sumLog = 0.0
        minLog = Double.POSITIVE_INFINITY
        lowCount = 0
    }

    fun takeAndReset(): Confidence {
        val result = snapshot()
        reset()
        return result
    }
}

/** Token-weighted merge of several confidence snapshots. Empty or zero-token input gives [Confidence.NONE]. */
fun combine(parts: List<Confidence>): Confidence {
    val used = parts.filter { it.tokens > 0 }
    val total = used.sumOf { it.tokens.toLong() }
    if (total == 0L) return Confidence.NONE
    val logSum = used.sumOf { it.tokens * ln(it.score.toDouble()) }
    val lowSum = used.sumOf { it.tokens * it.lowFraction.toDouble() }
    return Confidence(
        tokens = total.toInt(),
        score = exp(logSum / total).toFloat(),
        minProb = used.minOf { it.minProb },
        lowFraction = (lowSum / total).toFloat(),
    )
}
