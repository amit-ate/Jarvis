package com.jarvis.edge.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SentenceSplitterTest {

    @Test
    fun `emits a sentence when terminal punctuation is followed by space`() {
        val s = SentenceSplitter()
        assertEquals(emptyList<String>(), s.push("It is 5"))
        assertEquals(listOf("It is 5 p.m."), s.push(" p.m. The"))
        assertEquals("The", s.flush())
    }

    @Test
    fun `does not split inside decimals`() {
        val s = SentenceSplitter()
        val first = s.push("It is 21.5 degrees. Nice.")
        assertEquals(listOf("It is 21.5 degrees."), first)
        assertEquals("Nice.", s.flush())
    }

    @Test
    fun `flush returns null when empty`() {
        val s = SentenceSplitter()
        assertNull(s.flush())
        assertEquals(emptyList<String>(), s.push("   "))
        assertNull(s.flush())
    }

    @Test
    fun `long sentences are cut into short pieces for the voice, short ones are left alone`() {
        // The voice engine keeps working memory sized to the longest sentence it has spoken (+128 MB for 85 characters
        // on the Pad, +294 MB for ~150 on the Redmi), so a cap on piece length caps that memory.
        assertEquals(listOf("William Shakespeare wrote Hamlet."), SentenceSplitter.ttsChunks("William Shakespeare wrote Hamlet."))

        val long = "Future airplanes will utilize advanced materials like carbon fiber composites and shape-memory alloys " +
            "to achieve unprecedented levels of efficiency and stability,"
        val pieces = SentenceSplitter.ttsChunks(long, maxChars = 70)
        assertTrue(pieces.size >= 2, pieces.toString())
        assertTrue(pieces.all { it.length <= 70 }, pieces.toString())
        assertEquals(long.split(" ").filter { it.isNotEmpty() }, pieces.joinToString(" ").split(" ").filter { it.isNotEmpty() })

        // Clause breaks are preferred over plain spaces.
        val clauses = SentenceSplitter.ttsChunks(
            "Airplanes stay up because their wings make lift, and the engines push them forward fast enough to keep it.",
            maxChars = 70,
        )
        assertEquals("Airplanes stay up because their wings make lift,", clauses.first())

        // A single word longer than the cap is kept whole rather than cut mid-word.
        assertEquals(listOf("Supercalifragilisticexpialidocious"), SentenceSplitter.ttsChunks("Supercalifragilisticexpialidocious", maxChars = 10))
        assertEquals(emptyList<String>(), SentenceSplitter.ttsChunks("   "))
    }
}
