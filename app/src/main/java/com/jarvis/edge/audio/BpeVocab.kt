package com.jarvis.edge.audio

import com.jarvis.edge.core.AtomicFiles
import java.io.File

/**
 * Builds the `bpe.vocab` file sherpa-onnx needs to encode hotwords (modeling_unit=bpe) from a
 * Zipformer `tokens.txt`. Sentencepiece BPE scores pieces by merge rank, which is the token id
 * order, so each piece gets score -id; special tokens like <unk> get 0.
 *
 * Without this file sherpa-onnx calls exit() as soon as a non-empty hotwords file is loaded.
 */
object BpeVocab {
    fun writeFromTokens(tokensFile: File, out: File): Boolean = runCatching {
        if (!tokensFile.isFile) return false
        val sb = StringBuilder()
        tokensFile.forEachLine(Charsets.UTF_8) { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEachLine
            val sp = line.lastIndexOf(' ')
            if (sp <= 0) return@forEachLine
            val piece = line.substring(0, sp)
            val id = line.substring(sp + 1).toIntOrNull() ?: return@forEachLine
            val score = if (piece.startsWith("<") && piece.endsWith(">")) 0 else -id
            sb.append(piece).append('\t').append(score).append('\n')
        }
        if (sb.isEmpty()) return false
        if (out.isFile && out.readText(Charsets.UTF_8) == sb.toString()) return true
        AtomicFiles.writeText(out, sb.toString())
        true
    }.getOrDefault(false)
}
