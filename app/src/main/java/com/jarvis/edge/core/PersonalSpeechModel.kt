package com.jarvis.edge.core

import org.json.JSONObject
import java.io.File
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * A sorted list of lowercase ASCII words kept as one byte array (the 235k-word English list is ~2.5 MB this way, not
 * ~20 MB of String objects). Answers "is this an English word?", allowing common endings (timers, parked, minutes).
 */
class WordList(private val bytes: ByteArray) {
    private val starts: IntArray

    init {
        val s = ArrayList<Int>(250_000)
        var i = 0
        while (i < bytes.size) {
            s.add(i)
            while (i < bytes.size && bytes[i] != '\n'.code.toByte()) i++
            i++
        }
        starts = s.toIntArray()
    }

    val size: Int get() = starts.size

    private fun compareAt(idx: Int, w: String): Int {
        var p = starts[idx]
        var k = 0
        while (true) {
            val atEnd = p >= bytes.size || bytes[p] == '\n'.code.toByte()
            if (atEnd) return if (k == w.length) 0 else -1
            if (k == w.length) return 1
            val c = (bytes[p].toInt() and 0xff) - w[k].code
            if (c != 0) return c
            p++; k++
        }
    }

    fun containsExact(w: String): Boolean {
        var lo = 0
        var hi = starts.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = compareAt(mid, w)
            if (c == 0) return true
            if (c < 0) lo = mid + 1 else hi = mid - 1
        }
        return false
    }

    fun contains(w: String): Boolean {
        if (w.isEmpty()) return false
        if (containsExact(w)) return true
        val stems = buildList {
            if (w.endsWith("'s")) add(w.dropLast(2))
            if (w.endsWith("s")) add(w.dropLast(1))
            if (w.endsWith("es")) add(w.dropLast(2))
            if (w.endsWith("ies")) add(w.dropLast(3) + "y")
            if (w.endsWith("ed")) { add(w.dropLast(2)); add(w.dropLast(1)) }
            if (w.endsWith("ing")) { add(w.dropLast(3)); add(w.dropLast(3) + "e") }
            if (w.endsWith("er") || w.endsWith("ly")) add(w.dropLast(2))
            if (w.endsWith("ers")) add(w.dropLast(3))
        }
        val doubled = w.endsWith("ed") || w.endsWith("ing") // stopped -> stop, running -> run
        return stems.any {
            it.length >= 2 && (containsExact(it) ||
                (doubled && it.length > 2 && it[it.length - 1] == it[it.length - 2] && containsExact(it.dropLast(1))))
        }
    }

    companion object {
        fun fromFile(f: File): WordList = WordList(f.readBytes())
        val EMPTY = WordList(ByteArray(0))
    }
}

/** What [PersonalSpeechModel.correct] changed: the corrected text and each word swapped. */
data class SpeechFix(val text: String, val changes: List<Pair<String, String>>, val gain: Double)

/**
 * Learns, on the phone, how this user talks and how the recogniser mishears them, and fixes transcripts with it
 * (the "noisy channel" method of spell-checkers and classic speech systems):
 *
 *   meant = argmax  P(heard | meant) x P(meant)
 *
 * - P(meant): a personal word bigram model counted from the user's own requests, typed messages and read-aloud
 *   sentences, backed off to "is it an English word" so ordinary words are not treated as errors.
 * - P(heard | meant): a confusion model counted from (heard, truth) pairs (read-aloud recordings, "no, I meant ..."
 *   corrections), with sound-alike similarity for confusions not seen yet.
 *
 * Guarded: a word only changes with direct evidence (that confusion was seen) or when the heard word is not English
 * and the replacement is one the user says, and only if the whole sentence becomes much more likely.
 */
class PersonalSpeechModel(
    private val file: File?,
    private val dictionary: WordList = WordList.EMPTY,
) {
    private val lock = Any()
    private val uni = HashMap<String, Int>()
    private val bi = HashMap<String, Int>()
    private val conf = HashMap<String, HashMap<String, Int>>() // heard -> meant -> count
    private val errorsAsTruth = HashMap<String, Int>()
    private var tokens = 0L
    private var wordsOk = 0L
    private var wordsWrong = 0L

    init {
        file?.takeIf { it.isFile }?.let { f -> runCatching { load(JSONObject(f.readText())) } }
    }

    val vocabularySize: Int get() = synchronized(lock) { uni.size }
    val confusionCount: Int get() = synchronized(lock) { conf.values.sumOf { it.size } }

    /** Counts the words of something the user said or typed (their vocabulary and phrasing). */
    fun learnText(text: String, weight: Int = 1) {
        val w = words(text)
        if (w.isEmpty()) return
        synchronized(lock) {
            var prev = BOS
            for (x in w) {
                uni.merge(x, weight, Int::plus)
                bi.merge("$prev $x", weight, Int::plus)
                tokens += weight
                prev = x
            }
            uni.merge(BOS, weight, Int::plus)
        }
    }

    /** Learns from a recognised transcript and what was really said: the words it confuses for this user. */
    fun learnPair(heard: String, truth: String) {
        val h = words(heard)
        val t = words(truth)
        if (t.isEmpty()) return
        learnText(truth)
        synchronized(lock) {
            for ((hw, tw) in align(h, t)) {
                when {
                    hw == tw -> wordsOk++
                    hw != null && tw != null -> {
                        wordsWrong++
                        conf.getOrPut(hw) { HashMap() }.merge(tw, 1, Int::plus)
                        errorsAsTruth.merge(tw, 1, Int::plus)
                    }
                    else -> wordsWrong++
                }
            }
        }
    }

    /** "No, I meant X": unlearns the misheard words as vocabulary and learns the confusion. */
    fun learnCorrection(heard: String, meant: String) {
        synchronized(lock) {
            for (w in words(heard)) uni.computeIfPresent(w) { _, c -> (c - 1).takeIf { it > 0 } }
        }
        learnPair(heard, meant)
    }

    fun correct(text: String): SpeechFix {
        val spans = WORD.findAll(text).toList()
        if (spans.isEmpty()) return SpeechFix(text, emptyList(), 0.0)
        val heard = spans.map { it.value.lowercase() }
        synchronized(lock) {
            if (uni.isEmpty() && conf.isEmpty()) return SpeechFix(text, emptyList(), 0.0)
            val pKeep = (wordsOk + 19.0) / (wordsOk + wordsWrong + 20.0)
            val cands = heard.map { h -> listOf(h) + candidates(h) }
            // Viterbi over word choices with bigram context.
            var scores = DoubleArray(cands[0].size) { j -> channel(heard[0], cands[0][j], pKeep) + lm(BOS, cands[0][j]) }
            val back = ArrayList<IntArray>()
            for (i in 1 until heard.size) {
                val next = DoubleArray(cands[i].size)
                val bp = IntArray(cands[i].size)
                for (j in cands[i].indices) {
                    var best = Double.NEGATIVE_INFINITY
                    var arg = 0
                    for (p in cands[i - 1].indices) {
                        val s = scores[p] + lm(cands[i - 1][p], cands[i][j])
                        if (s > best) { best = s; arg = p }
                    }
                    next[j] = best + channel(heard[i], cands[i][j], pKeep)
                    bp[j] = arg
                }
                back.add(bp)
                scores = next
            }
            var j = scores.indices.maxByOrNull { scores[it] } ?: 0
            val bestScore = scores[j]
            val choice = IntArray(heard.size)
            for (i in heard.size - 1 downTo 0) {
                choice[i] = j
                if (i > 0) j = back[i - 1][j]
            }
            var keepScore = channel(heard[0], heard[0], pKeep) + lm(BOS, heard[0])
            for (i in 1 until heard.size) keepScore += lm(heard[i - 1], heard[i]) + channel(heard[i], heard[i], pKeep)
            val gain = bestScore - keepScore
            if (gain < MARGIN || choice.all { it == 0 }) return SpeechFix(text, emptyList(), gain)
            // Replacing a real word needs the user's own phrasing around it ("in to" -> "into" only where they say it).
            val chosen = List(heard.size) { cands[it][choice[it]] }
            for (i in heard.indices) {
                if (choice[i] == 0 || !known(heard[i])) continue
                val prev = if (i == 0) BOS else chosen[i - 1]
                val next = chosen.getOrNull(i + 1)
                val inContext = (bi["$prev ${chosen[i]}"] ?: 0) > 0 || (next != null && (bi["${chosen[i]} $next"] ?: 0) > 0)
                if (!inContext) choice[i] = 0
            }
            if (choice.all { it == 0 }) return SpeechFix(text, emptyList(), gain)
            val sb = StringBuilder()
            var last = 0
            val changes = ArrayList<Pair<String, String>>()
            for (i in spans.indices) {
                if (choice[i] == 0) continue
                val r = spans[i].range
                val orig = spans[i].value
                val repl = cands[i][choice[i]].let { if (orig.first().isUpperCase()) it.replaceFirstChar(Char::uppercaseChar) else it }
                sb.append(text, last, r.first).append(repl)
                last = r.last + 1
                changes.add(orig to repl)
            }
            sb.append(text, last, text.length)
            return SpeechFix(sb.toString(), changes, gain)
        }
    }

    private fun candidates(h: String): List<String> {
        val out = LinkedHashSet<String>()
        val heardIsWord = known(h)
        // A seen confusion counts once it sounds alike; a real word (or a short one) needs it seen twice.
        conf[h]?.forEach { (m, n) ->
            val needed = if (heardIsWord || h.length <= 3) 2 else 1
            if (n >= needed && similarity(h, m) >= MIN_CONFUSION_SIMILARITY) out.add(m)
        }
        if (!heardIsWord && h.length >= 4) {
            for ((w, c) in uni) {
                if (w == BOS || w == h || c < MIN_USES || kotlin.math.abs(w.length - h.length) > 2) continue
                if (similarity(h, w) >= MIN_SIMILARITY && letterSimilarity(h, w) >= 0.5) out.add(w)
            }
        }
        out.remove(h)
        return out.take(8)
    }

    private fun known(w: String) = (uni[w] ?: 0) >= MIN_USES || dictionary.contains(w) || w.any(Char::isDigit)

    private fun channel(h: String, m: String, pKeep: Double): Double {
        if (h == m) return ln(pKeep)
        val seen = conf[h]?.get(m) ?: 0
        val sim = similarity(h, m)
        val p = (1 - pKeep) * (seen + sim * sim * sim) / ((errorsAsTruth[m] ?: 0) + 1.0)
        return ln(max(p, 1e-12))
    }

    private fun lm(prev: String, w: String): Double {
        val cPrev = uni[prev] ?: 0
        val cBi = bi["$prev $w"] ?: 0
        val general = when {
            dictionary.contains(w) || w.any(Char::isDigit) -> P_ENGLISH
            else -> P_UNKNOWN
        }
        val pUni = ((uni[w] ?: 0) + MU * general) / (tokens + MU)
        val lambda = cPrev / (cPrev + 3.0)
        val p = if (cPrev > 0) lambda * cBi / cPrev + (1 - lambda) * pUni else pUni
        return ln(max(p, 1e-15))
    }

    fun save() {
        val f = file ?: return
        val json = synchronized(lock) {
            JSONObject()
                .put("uni", JSONObject(uni as Map<*, *>))
                .put("bi", JSONObject(bi as Map<*, *>))
                .put("conf", JSONObject(conf.mapValues { JSONObject(it.value as Map<*, *>) }))
                .put("tokens", tokens).put("ok", wordsOk).put("wrong", wordsWrong)
                .toString()
        }
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(f)) { f.writeText(json); tmp.delete() }
        }
    }

    private fun load(o: JSONObject) {
        fun ints(j: JSONObject?, into: MutableMap<String, Int>) {
            j ?: return
            for (k in j.keys()) into[k] = j.getInt(k)
        }
        ints(o.optJSONObject("uni"), uni)
        ints(o.optJSONObject("bi"), bi)
        o.optJSONObject("conf")?.let { c ->
            for (h in c.keys()) {
                val m = HashMap<String, Int>()
                ints(c.getJSONObject(h), m)
                conf[h] = m
                for ((t, n) in m) errorsAsTruth.merge(t, n, Int::plus)
            }
        }
        tokens = o.optLong("tokens")
        wordsOk = o.optLong("ok")
        wordsWrong = o.optLong("wrong")
    }

    companion object {
        private const val BOS = "<s>"
        private val WORD = Regex("[A-Za-z0-9']+")
        /** The corrected sentence must be this much more likely (natural log; 3 is ~20x) than the transcript. */
        const val MARGIN = 3.0
        private const val MIN_USES = 2
        private const val MIN_SIMILARITY = 0.7
        private const val MIN_CONFUSION_SIMILARITY = 0.45
        private const val MU = 50.0
        private const val P_ENGLISH = 1.0 / 200_000
        private const val P_UNKNOWN = 1.0 / 20_000_000

        fun words(text: String): List<String> = WORD.findAll(text).map { it.value.lowercase() }.toList()

        /** Word alignment (edit distance): pairs of (heard, truth); null on either side for an insertion/deletion. */
        fun align(h: List<String>, t: List<String>): List<Pair<String?, String?>> {
            val d = Array(h.size + 1) { IntArray(t.size + 1) }
            for (i in 0..h.size) d[i][0] = i
            for (j in 0..t.size) d[0][j] = j
            for (i in 1..h.size) for (j in 1..t.size) {
                d[i][j] = min(min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + if (h[i - 1] == t[j - 1]) 0 else 1)
            }
            val out = ArrayList<Pair<String?, String?>>()
            var i = h.size
            var j = t.size
            while (i > 0 || j > 0) {
                if (i > 0 && j > 0 && d[i][j] == d[i - 1][j - 1] + if (h[i - 1] == t[j - 1]) 0 else 1) {
                    out.add(h[i - 1] to t[j - 1]); i--; j--
                } else if (i > 0 && d[i][j] == d[i - 1][j] + 1) {
                    out.add(h[i - 1] to null); i--
                } else {
                    out.add(null to t[j - 1]); j--
                }
            }
            return out.asReversed()
        }

        fun letterSimilarity(a: String, b: String): Double = 1.0 - lev(a, b).toDouble() / max(a.length, b.length)

        private fun lev(x: String, y: String): Int {
            var prev = IntArray(y.length + 1) { it }
            for (i in 1..x.length) {
                val cur = IntArray(y.length + 1)
                cur[0] = i
                for (j in 1..y.length) cur[j] = min(min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + if (x[i - 1] == y[j - 1]) 0 else 1)
                prev = cur
            }
            return prev[y.length]
        }

        /** Sound-alike similarity in [0, 1]: spelling and consonant skeleton ("tummers" ~ "timers", "raoul" ~ "rahul"). */
        fun similarity(a: String, b: String): Double {
            if (a == b) return 1.0
            fun skeleton(s: String) = s.replace(Regex("[aeiouhwy']"), "").replace(Regex("(.)\\1+"), "$1")
            val letters = 1.0 - lev(a, b).toDouble() / max(a.length, b.length)
            val sa = skeleton(a)
            val sb = skeleton(b)
            val cons = if (sa.isEmpty() && sb.isEmpty()) 1.0 else 1.0 - lev(sa, sb).toDouble() / max(1, max(sa.length, sb.length))
            return (letters + cons) / 2
        }
    }
}
