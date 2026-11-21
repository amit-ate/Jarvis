package com.jarvis.edge.mind

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MindToolsTest {

    @Test
    fun `know_about_me speaks a stored user fact as your and uses singular fact`() = runBlocking {
        val store = InMemoryMindStore(clockMs = { 1_000_000L })
        store.learnFact("User's age is 41", VectorMath.simpleHashEmbed("User's age is 41"))
        val tool = MindStatusTool(store = store, selfSnapshotProvider = { error("not used") })
        val result = tool.run(mapOf("action" to "know_about_me"))
        assertTrue(result.spoken.contains("your age is 41"), result.spoken)
        assertTrue(result.spoken.contains("1 fact"), result.spoken)
        assertFalse(result.spoken.contains("1 facts"), result.spoken)
    }
}
