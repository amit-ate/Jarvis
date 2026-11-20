package com.jarvis.edge.audio

data class ArbitrationResult(
    val chosen: String,
    val liveRaw: String,
    val accurateRaw: String,
    val wordErrorRate: Float,
    val needsConfirmation: Boolean,
)

class TranscriptArbiter(
    private val confirmWerThreshold: Float = 0.65f,
) {
    fun applyCorrections(text: String, corrections: Map<String, String>): String {
        if (text.isBlank() || corrections.isEmpty()) return text
        val entries = corrections.entries
            .mapNotNull { (k, v) ->
                val trimmedKey = k.trim()
                if (trimmedKey.isEmpty()) null else trimmedKey to v
            }
            .sortedByDescending { it.first.length }
        if (entries.isEmpty()) return text

        val lookup = LinkedHashMap<String, String>(entries.size)
        for ((k, v) in entries) {
            val lower = k.lowercase()
            if (!lookup.containsKey(lower)) {
                lookup[lower] = v
            }
        }

        val alternation = entries.joinToString("|") { Regex.escape(it.first) }
        val pattern = Regex("(?<!\\w)($alternation)(?!\\w)", RegexOption.IGNORE_CASE)
        return pattern.replace(text) { match ->
            lookup[match.value.lowercase()] ?: match.value
        }
    }

    fun wordDistanceRatio(a: String, b: String): Float {
        val aTokens = tokenizeWords(a)
        val bTokens = tokenizeWords(b)
        if (aTokens.isEmpty() && bTokens.isEmpty()) return 0f
        if (aTokens.isEmpty() || bTokens.isEmpty()) return 1f

        val m = aTokens.size
        val n = bTokens.size
        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)

        for (i in 1..m) {
            curr[0] = i
            val aWord = aTokens[i - 1]
            for (j in 1..n) {
                val cost = if (aWord == bTokens[j - 1]) 0 else 1
                curr[j] = minOf(
                    prev[j] + 1,
                    curr[j - 1] + 1,
                    prev[j - 1] + cost,
                )
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }

        val maxLen = maxOf(m, n).toFloat()
        return (prev[n] / maxLen).coerceIn(0f, 1f)
    }

    fun arbitrate(
        live: String,
        accurate: String,
        corrections: Map<String, String> = emptyMap(),
        intentMatcher: (String) -> Boolean = { false },
    ): ArbitrationResult {
        val liveRaw = live.trim()
        val accurateRaw = accurate.trim()
        val l = applyCorrections(liveRaw, corrections).trim()
        val a = applyCorrections(accurateRaw, corrections).trim()
        val wer = wordDistanceRatio(l, a)

        if (a.isBlank() && l.isBlank()) {
            return ArbitrationResult("", liveRaw, accurateRaw, wer, needsConfirmation = false)
        }
        if (a.isBlank()) {
            return ArbitrationResult(l, liveRaw, accurateRaw, wer, needsConfirmation = false)
        }
        if (l.isBlank()) {
            return ArbitrationResult(a, liveRaw, accurateRaw, wer, needsConfirmation = false)
        }
        if (l.equals(a, ignoreCase = true)) {
            return ArbitrationResult(a, liveRaw, accurateRaw, wer, needsConfirmation = false)
        }

        val lMatches = intentMatcher(l)
        val aMatches = intentMatcher(a)
        if (lMatches && !aMatches) {
            return ArbitrationResult(l, liveRaw, accurateRaw, wer, needsConfirmation = false)
        }
        if (aMatches && !lMatches) {
            return ArbitrationResult(a, liveRaw, accurateRaw, wer, needsConfirmation = false)
        }

        val lTokens = tokenizeWords(l)
        val aTokens = tokenizeWords(a)
        val lGarbage = isGarbageOrTruncated(l, lTokens, aTokens)
        val aGarbage = isGarbageOrTruncated(a, aTokens, lTokens)

        if (aGarbage && !lGarbage) {
            return ArbitrationResult(l, liveRaw, accurateRaw, wer, needsConfirmation = false)
        }
        if (lGarbage && !aGarbage) {
            return ArbitrationResult(a, liveRaw, accurateRaw, wer, needsConfirmation = false)
        }

        val needsConfirm = wer >= confirmWerThreshold && lTokens.size >= 2 && aTokens.size >= 2
        return ArbitrationResult(a, liveRaw, accurateRaw, wer, needsConfirmation = needsConfirm)
    }

    fun isYes(text: String): Boolean {
        val norm = normalizePhrase(text)
        if (norm.isEmpty()) return false
        if (norm in YES_PHRASES) return true
        val words = norm.split(' ')
        return words.size <= 3 && words[0] in YES_LEADING_WORDS && words.none { it in NO_PHRASES }
    }

    fun isNo(text: String): Boolean {
        val norm = normalizePhrase(text)
        if (norm.isEmpty()) return false
        if (norm in NO_PHRASES) return true
        val words = norm.split(' ')
        return words.size <= 3 && words[0] in NO_LEADING_WORDS && words.none { it in YES_PHRASES }
    }

    private fun isGarbageOrTruncated(
        text: String,
        tokens: List<String>,
        otherTokens: List<String>,
    ): Boolean {
        if (text.endsWith("'") || text.endsWith("\"")) return true
        if (otherTokens.size >= 4 && tokens.size <= otherTokens.size / 2) return true
        if (tokens.size >= 3 && tokens.distinct().size == 1) return true
        if (tokens.size >= 3 && tokens.windowed(3).any { it[0] == it[1] && it[1] == it[2] }) return true
        if (tokens.size == 2 && tokens[0] == tokens[1] && otherTokens.distinct().size >= 2) return true
        return false
    }

    private fun tokenizeWords(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return text.lowercase()
            .replace(NON_WORD_SPACE_REGEX, " ")
            .trim()
            .split(WHITESPACE_REGEX)
            .map { it.trim('\'') }
            .filter { it.isNotEmpty() }
    }

    private fun normalizePhrase(text: String): String {
        return text.lowercase()
            .replace(NON_WORD_SPACE_REGEX, " ")
            .trim()
            .replace(WHITESPACE_REGEX, " ")
            .trim('\'')
    }

    companion object {
        private val NON_WORD_SPACE_REGEX = Regex("[^\\p{L}\\p{N}'\\s]")
        private val WHITESPACE_REGEX = Regex("\\s+")

        private val YES_PHRASES = setOf(
            "yes",
            "yeah",
            "yep",
            "correct",
            "right",
            "yup",
            "confirm",
            "affirmative",
            "that's right",
            "thats right",
        )

        private val YES_LEADING_WORDS = setOf("yes", "yeah", "yep", "yup")

        private val NO_PHRASES = setOf(
            "no",
            "nope",
            "wrong",
            "incorrect",
            "cancel",
            "never mind",
            "not that",
        )

        private val NO_LEADING_WORDS = setOf("no", "nope")
    }
}
