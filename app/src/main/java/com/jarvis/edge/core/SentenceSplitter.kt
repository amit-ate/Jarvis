package com.jarvis.edge.core

class SentenceSplitter(private val splitClauses: Boolean = false) {
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
