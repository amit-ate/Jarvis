package com.jarvis.edge.brain

import kotlin.math.abs

object EchoGuard {

    private val STOPWORDS = setOf(
        "a", "an", "the", "is", "are", "was", "were", "am", "be", "been", "being",
        "to", "of", "in", "on", "at", "by", "for", "with", "from", "as", "it", "its",
        "this", "that", "these", "those", "i", "you", "he", "she", "we", "they",
        "me", "my", "your", "his", "her", "our", "their", "what", "who", "where",
        "when", "why", "how", "which", "whose", "whom", "do", "does", "did", "can",
        "could", "will", "would", "should", "have", "has", "had", "and", "or", "but",
        "not", "no", "yes", "so", "if", "then", "about", "please", "tell", "say",
        "jarvis", "hey", "ok", "okay",
    )

    private val FACTUAL_PREFIXES = listOf(
        "what ", "who ", "where ", "when ", "why ", "how ", "which ", "whose ", "whom ",
        "is ", "are ", "was ", "were ", "do ", "does ", "did ", "can ", "could ",
        "explain ", "define ", "describe ", "name ", "list ", "tell me ",
    )

    private val QUESTION_WORDS = setOf("what", "who", "where", "when", "why", "how", "which", "whose", "whom")

    private val NON_FACTUAL_PHRASES = setOf(
        "hi", "hello", "hey", "good morning", "good afternoon", "good evening",
        "good night", "bye", "goodbye", "thanks", "thank you", "ok", "okay", "yes", "no",
    )

    fun isEcho(userText: String, replyText: String): Boolean {
        val normUser = normalizeText(userText)
        val normReply = normalizeText(stripSayPrefix(replyText))

        if (normReply.isEmpty()) return false

        // Exempt explicit repeat/say requests.
        if (normUser.startsWith("repeat ") || normUser == "repeat" ||
            normUser.startsWith("say ") || normUser == "say"
        ) {
            return false
        }

        val replyWords = normReply.split(' ').filter { it.isNotEmpty() }
        if (hasThreeGramStutterLoop(replyWords)) {
            return true
        }

        if (normUser.isEmpty()) return false
        val userWords = normUser.split(' ').filter { it.isNotEmpty() }
        if (userWords.isEmpty() || replyWords.isEmpty()) return false

        // 1. Exact normalized match.
        if (normUser == normReply) return true

        // 2. Containment with <= 2 extra words (guarding 1-word greetings like "hello" -> "hello there").
        if (userWords.size >= 2 && replyWords.size >= 2) {
            if ((normReply.contains(normUser) || normUser.contains(normReply)) &&
                abs(replyWords.size - userWords.size) <= 2
            ) {
                return true
            }

            // An answer to a question restates it and adds the answer ("What is the capital of France?" ->
            // "The capital of France is Paris."): a statement with new content words is not an echo.
            val userAsks = userWords.first() in QUESTION_WORDS || userText.trim().endsWith("?")
            val replyAsks = replyWords.first() in QUESTION_WORDS || stripSayPrefix(replyText).trim().endsWith("?")
            val newContentWords = replyWords.filter { it !in STOPWORDS }.toSet() - userWords.filter { it !in STOPWORDS }.toSet()
            if (userAsks && !replyAsks && newContentWords.isNotEmpty()) return false

            // 3. Word Jaccard >= 0.62f for >= 2 words.
            val userSet = userWords.toSet()
            val replySet = replyWords.toSet()
            val intersection = userSet.count { it in replySet }
            val union = userSet.size + replySet.size - intersection
            if (union > 0 && intersection.toFloat() / union.toFloat() >= 0.62f) {
                return true
            }
        }

        // 4. High content-word overlap >= 0.78f with <= 1 new content word.
        val userContent = userWords.filter { it !in STOPWORDS }.toSet()
        val replyContent = replyWords.filter { it !in STOPWORDS }.toSet()
        if (userContent.isNotEmpty()) {
            val overlap = userContent.count { it in replyContent }.toFloat() / userContent.size.toFloat()
            val newContent = replyContent.count { it !in userContent }
            if (overlap >= 0.78f && newContent <= 1) {
                return true
            }
        }

        return false
    }

    fun looksLikeFactualQuestion(userText: String): Boolean {
        val rawTrimmed = userText.trim()
        val norm = normalizeText(rawTrimmed)
        if (norm.isEmpty() || norm in NON_FACTUAL_PHRASES) return false

        val words = norm.split(' ').filter { it.isNotEmpty() }
        if (rawTrimmed.endsWith("?") && words.size >= 2) return true

        return FACTUAL_PREFIXES.any { norm.startsWith(it) }
    }

    private fun hasThreeGramStutterLoop(words: List<String>): Boolean {
        if (words.size < 6) return false
        val counts = HashMap<String, Int>()
        val lastIndex = HashMap<String, Int>()
        for (i in 0..(words.size - 3)) {
            val gram = "${words[i]} ${words[i + 1]} ${words[i + 2]}"
            val count = (counts[gram] ?: 0) + 1
            counts[gram] = count
            val prevIdx = lastIndex[gram]
            // Immediate / tight repeat (e.g. "a b c a b c") or 3+ total occurrences.
            if (count >= 3 || (prevIdx != null && i - prevIdx <= 4)) {
                return true
            }
            lastIndex[gram] = i
        }
        return false
    }

    private fun stripSayPrefix(text: String): String {
        val trimmed = text.trimStart()
        return if (trimmed.startsWith("SAY:", ignoreCase = true)) {
            trimmed.substring(4).trimStart()
        } else {
            trimmed
        }
    }

    internal fun normalizeText(raw: String): String {
        var cleaned = raw.lowercase()
            .replace(Regex("[^a-z0-9'\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        var prev = ""
        while (cleaned != prev) {
            prev = cleaned
            cleaned = cleaned
                .removePrefix("hey jarvis ")
                .removePrefix("ok jarvis ")
                .removePrefix("okay jarvis ")
                .removePrefix("jarvis ")
                .removeSuffix(" jarvis")
                .trim()
        }
        return cleaned
    }
}
