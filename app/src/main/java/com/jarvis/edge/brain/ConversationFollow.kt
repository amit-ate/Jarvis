package com.jarvis.edge.brain

import kotlin.math.max

data class TaughtFact(val subject: String, val relation: String, val value: String) {
    fun statement(): String = "$subject's $relation is $value"
}

data class EntityFix(val heard: String, val meant: String, val correctedText: String)

/**
 * Detects two things in a voice conversation: the user TEACHING a fact
 * ("Rahul Gandhi's sister is Priyanka") and the user CORRECTING a name the
 * recogniser mangled ("It is Rahul Gandhi's" after "Who is Raul Garnish sister?").
 * Pure regex and string logic, no Android dependencies.
 */
object ConversationFollow {

    private val WS = Regex("\\s+")
    private val LEAD_IN = Regex("(?i)^(?:as far as i know|as i know|actually|no|fyi|for your information|by the way|well)\\b,?\\s*")
    private val QUESTION_START = Regex("(?i)^(?:who|what|when|where|why|how|is|are|do|does|can)\\b")
    private val PRONOUNS = setOf("my", "your", "his", "her", "their", "its", "our", "the", "this", "that", "it")

    private val FACT = Regex(
        "^([\\p{L}][\\p{L}.\\-]*(?:\\s+[\\p{L}][\\p{L}.\\-]*){0,3})['’']s\\s+" +
            "([\\p{L}]+(?:\\s+[\\p{L}]+){0,2}?)(?:['’']s)?\\s+(?:name\\s+)?is\\s+" +
            "([^\\s?!.,;]+(?:\\s+[^\\s?!.,;]+){0,3})\\s*[.!?,;]*$"
    )

    private val CORRECTION = Regex(
        "(?i)^(?:(?:no|sorry|oh|o)\\b[,.]?\\s*)*" +
            "(?:it\\s+is|it['’']s|its|i\\s+said|i\\s+meant|i\\s+mean)\\s+(.+)$"
    )
    private val TRAIL_NON_LETTER = Regex("[^\\p{L}\\p{N}]+$")
    private val LEAD_NON_LETTER = Regex("^[^\\p{L}\\p{N}]+")
    private val POSSESSIVE = Regex("(?i)['’']s$")
    private val TRAIL_PUNCT = Regex("[\\s.!?,;]+$")
    private const val MIN_SIMILARITY = 0.35

    /** Returns the fact the user taught, or null when the text is not a plain "X's relation is Y" statement. */
    fun taughtFact(text: String): TaughtFact? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val body = LEAD_IN.replace(trimmed, "").trim()
        if (body.isEmpty() || body.endsWith("?") || QUESTION_START.containsMatchIn(body)) return null
        val m = FACT.find(body) ?: return null
        val subject = norm(m.groupValues[1])
        val relation = norm(m.groupValues[2]).lowercase()
        val value = norm(m.groupValues[3])
        if (subject.split(WS).first().lowercase() in PRONOUNS) return null
        return TaughtFact(subject, relation, value)
    }

    /**
     * Returns the name the user is correcting in [previousUserText], replaced by the corrected name,
     * or null when the text is not a short correction or no candidate name is similar enough.
     */
    fun entityFix(text: String, previousUserText: String?): EntityFix? {
        val prev = previousUserText?.takeIf { it.isNotBlank() } ?: return null
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.split(WS).size > 6) return null
        val m = CORRECTION.find(trimmed) ?: return null
        val x = m.groupValues[1].trim()
            .replace(TRAIL_PUNCT, "")
            .replace(POSSESSIVE, "")
            .trim()
        if (x.isEmpty() || !x[0].isLetter()) return null
        val wordCount = x.split(WS).size
        if (wordCount !in 1..4) return null

        val best = bestSpan(prev, x, wordCount) ?: return null
        val heard = prev.substring(best.first, best.second)
        if (heard.equals(x, ignoreCase = true)) return null
        return EntityFix(heard, x, prev.replaceRange(best.first, best.second, x))
    }

    private data class Tok(val start: Int, val end: Int, val name: String)

    private fun norm(s: String): String = s.trim().replace(WS, " ")

    /** Word tokens of prev; name is the word without surrounding punctuation and a trailing possessive. */
    private fun tokens(prev: String): List<Tok> =
        Regex("\\S+").findAll(prev).map { mt ->
            val noTrail = mt.value.replace(TRAIL_NON_LETTER, "")
            val noPoss = noTrail.replace(POSSESSIVE, "")
            val lead = LEAD_NON_LETTER.find(noPoss)?.value?.length ?: 0
            val name = noPoss.substring(lead)
            val start = mt.range.first + lead
            Tok(start, start + name.length, name)
        }.toList()

    /** Best candidate span (start, end offsets into prev) whose similarity to x is at least MIN_SIMILARITY. */
    private fun bestSpan(prev: String, x: String, wordCount: Int): Pair<Int, Int>? {
        val toks = tokens(prev)
        val allCaps = prev.none { it.isLowerCase() } && prev.any { it.isLetter() }
        val candidates = mutableListOf<Pair<Int, Int>>()

        if (allCaps) {
            for (k in 1..wordCount) {
                for (i in 0..toks.size - k) {
                    val window = toks.subList(i, i + k)
                    if (window.all { it.name.isNotEmpty() }) {
                        candidates += window.first().start to window.last().end
                    }
                }
            }
        } else {
            fun eligible(i: Int): Boolean {
                val name = toks[i].name
                return i > 0 && name.isNotEmpty() && name[0].isUpperCase() && name != "I"
            }
            var i = 0
            while (i < toks.size) {
                if (eligible(i)) {
                    var j = i
                    while (j + 1 < toks.size && eligible(j + 1)) j++
                    candidates += toks[i].start to toks[j].end
                    i = j + 1
                } else {
                    i++
                }
            }
        }

        var best: Pair<Int, Int>? = null
        var bestScore = -1.0
        for (c in candidates) {
            val score = similarity(prev.substring(c.first, c.second), x)
            if (score >= MIN_SIMILARITY && score > bestScore) {
                best = c
                bestScore = score
            }
        }
        return best
    }

    private fun similarity(a: String, b: String): Double {
        val x = a.filter { it.isLetter() }.lowercase()
        val y = b.filter { it.isLetter() }.lowercase()
        val len = max(x.length, y.length)
        if (len == 0) return 0.0
        return 1.0 - levenshtein(x, y).toDouble() / len
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }
}
