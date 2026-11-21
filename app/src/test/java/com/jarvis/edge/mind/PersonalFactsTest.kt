package com.jarvis.edge.mind

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PersonalFactsTest {
    @Test
    fun rememberReplacesOldValueAndRecallReturnsIt() {
        val store = InMemoryMindStore()
        val facts = PersonalFacts(store) { VectorMath.simpleHashEmbed(it) }
        assertNull(facts.recall("age"))
        facts.remember("age", "40")
        facts.remember("age", "36")
        assertEquals("36", facts.recall("age"))
        assertEquals(1, store.listActiveFacts().count { it.text.startsWith("User's age is") })
    }
}
