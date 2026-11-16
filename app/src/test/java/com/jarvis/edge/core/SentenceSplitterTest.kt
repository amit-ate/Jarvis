package com.jarvis.edge.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
}
