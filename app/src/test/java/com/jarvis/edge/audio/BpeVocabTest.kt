package com.jarvis.edge.audio

import com.jarvis.edge.mind.HotwordsWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class BpeVocabTest {
    @TempDir lateinit var dir: File

    private fun tokens(): File = File(dir, "tokens.txt").apply {
        writeText("<blk> 0\n<sos/eos> 1\n<unk> 2\nS 3\n▁THE 4\n▁A 5\nGE 6\n")
    }

    @Test
    fun vocabScoresPiecesByIdAndSpecialsZero() {
        val out = File(dir, "bpe.vocab")
        assertTrue(BpeVocab.writeFromTokens(tokens(), out))
        val lines = out.readLines()
        assertEquals("<blk>\t0", lines[0])
        assertEquals("<unk>\t0", lines[2])
        assertEquals("▁THE\t-4", lines[4])
        assertEquals("GE\t-6", lines[6])
    }

    @Test
    fun missingTokensFileFails() {
        assertFalse(BpeVocab.writeFromTokens(File(dir, "nope.txt"), File(dir, "bpe.vocab")))
    }

    @Test
    fun hotwordsAreWrittenAsPlainWordsNotPieces() {
        val writer = HotwordsWriter.fromTokensFile(tokens())
        val out = File(dir, "hot.txt")
        assertEquals(1, writer.writeHotwords(listOf("age", "Age", "zzz"), out))
        assertEquals(listOf("AGE"), out.readLines().filter { it.isNotBlank() })
        assertNull(writer.hotwordLine("zzz"))
    }
}
