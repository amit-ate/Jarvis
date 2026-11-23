package com.jarvis.edge.core

class SentenceSplitter(private val splitClauses: Boolean = false) {
    companion object {
        /**
         * Longest piece handed to the voice in one go. Piper/VITS keeps working memory sized to the longest piece it has
         * spoken (on the Pad +128 MB for 85 characters, on the Redmi +294 MB for ~150), so this caps that memory.
         */
        const val MAX_TTS_CHARS = 70
        private const val MIN_CLAUSE_PIECE = 15

        /** Cuts [text] into pieces of at most [maxChars] at a clause break (, ; :) if possible, else at a space. */
        fun ttsChunks(text: String, maxChars: Int = MAX_TTS_CHARS): List<String> {
            var rest = text.trim().replace(Regex("\\s+"), " ")
            val out = ArrayList<String>()
            while (rest.length > maxChars) {
                var cut = -1
                for (i in minOf(maxChars - 1, rest.length - 2) downTo MIN_CLAUSE_PIECE) {
                    if ((rest[i] == ',' || rest[i] == ';' || rest[i] == ':') && rest[i + 1] == ' ') {
                        cut = i + 1
                        break
                    }
                }
                if (cut < 0) cut = rest.lastIndexOf(' ', maxChars).takeIf { it > 0 } ?: -1
                if (cut < 0) cut = rest.indexOf(' ') // a single word longer than the cap stays whole
                if (cut <= 0) break
                out.add(rest.substring(0, cut).trim())
                rest = rest.substring(cut).trim()
            }
            if (rest.isNotEmpty()) out.add(rest)
            return out
        }
    }

    private val buf = StringBuilder()

    fun push(token: String): List<String> {
        if (token.isEmpty()) return emptyList()
        buf.append(token)
        val out = ArrayList<String>()

        while (true) {
            val splitIdx = findSentenceBreak(buf)
            if (splitIdx < 0) break
            val sentence = buf.substring(0, splitIdx + 1).trim()
            if (sentence.isNotEmpty()) {
                out.add(sentence)
            }
            var restStart = splitIdx + 1
            while (restStart < buf.length && buf[restStart].isWhitespace()) {
                restStart++
            }
            buf.delete(0, restStart)
        }
        return out
    }

    fun flush(): String? {
        val rem = buf.toString().trim()
        buf.clear()
        return rem.ifEmpty { null }
    }

    private fun findSentenceBreak(text: CharSequence): Int {
        for (i in 0 until text.length - 1) {
            val c = text[i]
            if (c == '.' || c == '!' || c == '?') {
                if (!text[i + 1].isWhitespace()) continue
                if (c == '.' && isSingleLetterOrDigitAbbrev(text, i)) continue
                return i
            }
            if (splitClauses && (c == ',' || c == ';' || c == ':') && i >= 30) {
                if (text[i + 1].isWhitespace()) {
                    return i
                }
            }
        }
        return -1
    }

    private fun isSingleLetterOrDigitAbbrev(text: CharSequence, dotIdx: Int): Boolean {
        if (dotIdx == 0) return true
        val prev = text[dotIdx - 1]
        if (!prev.isLetterOrDigit()) return false
        val beforePrev = if (dotIdx >= 2) text[dotIdx - 2] else ' '
        // A standalone single letter or digit (e.g. "A. ", "1. ") is preceded by whitespace/start.
        // Multi-part dotted abbreviations like "p.m." have '.' before the last letter, so they CAN end a sentence.
        return beforePrev.isWhitespace()
    }
}
