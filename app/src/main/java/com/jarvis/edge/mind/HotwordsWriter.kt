package com.jarvis.edge.mind

import java.io.File

/**
 * Converts phrases into Zipformer BPE tokens using `tokens.txt` and writes hotword files atomically.
 */
class HotwordsWriter(private val tokens: Map<String, Int>) {

    private val maxTokenLen: Int = tokens.keys.maxOfOrNull { it.length }?.coerceAtLeast(1) ?: 1

    /**
     * Tokenizes [phrase] into space-separated BPE tokens matching [tokens], or null if untokenizable.
     */
    fun tokenizePhrase(phrase: String): String? {
        val cleaned = phrase.trim()
            .uppercase()
            .replace('-', ' ')
            .replace(NON_TOKEN_CHARS, "")
        val words = cleaned.split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null

        val pieces = ArrayList<String>(words.size * 3)
        for (word in words) {
            val target = "$WORD_START$word"
            var idx = 0
            while (idx < target.length) {
                val maxEnd = minOf(target.length, idx + maxTokenLen)
                var matched: String? = null
                for (end in maxEnd downTo idx + 1) {
                    val sub = target.substring(idx, end)
                    if (tokens.containsKey(sub)) {
                        matched = sub
                        idx = end
                        break
                    }
                }
                if (matched == null) return null
                pieces.add(matched)
            }
        }
        return pieces.joinToString(" ")
    }

    /**
     * Tokenizes [phrases], deduplicates valid BPE lines, writes atomically to [outFile],
     * and returns the number of unique lines written.
     */
    fun writeHotwords(phrases: List<String>, outFile: File): Int {
        val lines = phrases.asSequence()
            .mapNotNull { tokenizePhrase(it) }
            .distinct()
            .toList()

        outFile.parentFile?.mkdirs()
        val tmpFile = File(outFile.parentFile ?: File("."), ".${outFile.name}.tmp")
        val content = if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"
        tmpFile.writeText(content, Charsets.UTF_8)
        if (!tmpFile.renameTo(outFile)) {
            outFile.writeText(content, Charsets.UTF_8)
            tmpFile.delete()
        }
        return lines.size
    }

    companion object {
        private const val WORD_START = "\u2581" // ▁
        private val WHITESPACE = Regex("\\s+")
        private val NON_TOKEN_CHARS = Regex("[^A-Z'\\s]")

        fun parseTokens(lines: Sequence<String>): Map<String, Int> {
            val map = LinkedHashMap<String, Int>()
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val parts = line.split(WHITESPACE)
                if (parts.size < 2) continue
                val id = parts.last().toIntOrNull() ?: continue
                val token = parts.dropLast(1).joinToString(" ")
                if (token.isNotEmpty()) {
                    map[token] = id
                }
            }
            return map
        }

        fun fromTokensFile(file: File): HotwordsWriter {
            if (!file.isFile) return HotwordsWriter(emptyMap())
            val map = file.bufferedReader(Charsets.UTF_8).use { reader ->
                parseTokens(reader.lineSequence())
            }
            return HotwordsWriter(map)
        }
    }
}
