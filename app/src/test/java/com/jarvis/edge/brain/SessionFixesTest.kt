package com.jarvis.edge.brain

import com.jarvis.edge.mind.ReflectionWorker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Fixes from the user's 2026-11-22 session (transcripts below are what the recogniser heard). */
class SessionFixesTest {
    @Test
    fun `run auto learning is a real command, as the recogniser hears it`() {
        for (s in listOf("Run or learn.", "Run, auto learning.", "run auto learn", "Learn now.", "Jarvis, start auto learning")) {
            assertEquals(ToolCall("mind_status", mapOf("action" to "learn_now")), FastIntents.match(s), s)
        }
        assertEquals(ToolCall("mind_status", mapOf("action" to "resume_learning")), FastIntents.match("start learning"))
    }

    @Test
    fun `questions about Jarvis are not sent to web search`() {
        for (s in listOf("Will you be able to dream?", "What are your top five thoughts?", "What's your name?", "Are you happy?")) {
            assertTrue(Brain.ABOUT_JARVIS.containsMatchIn(s), s)
        }
        for (s in listOf("Can you tell me who wrote Hamlet?", "Do you know who invented the telephone?", "What is the capital of France?", "Who is the prime minister of India?")) {
            assertFalse(Brain.ABOUT_JARVIS.containsMatchIn(s), s)
        }
    }

    @Test
    fun `only facts the user stated about themselves are kept`() {
        val said = listOf("my age is 41", "What is the capital of France?", "How many legs does a spider have?")
        assertTrue(ReflectionWorker.isAboutUser("User's age is 41", said))
        assertFalse(ReflectionWorker.isAboutUser("A spider has eight legs", said))
        assertFalse(ReflectionWorker.isAboutUser("Paris", said))
        assertFalse(ReflectionWorker.isAboutUser("User lives in Paris", said)) // Paris came from the question, not about the user
        assertFalse(ReflectionWorker.mentionsUser("Ten miles is equal to 40.6 kilometers"))
    }

    @Test
    fun `the user's own lines echoed back by the model are not facts`() {
        // Seen on the Pad: the extraction model copied its "User: ..." input lines back as facts.
        val said = listOf("What is the capital of France?", "Where are you right now?", "Hey, y'all, this.", "A. Jarvis.")
        assertFalse(ReflectionWorker.isAboutUser("User: What is the capital of France?", said))
        assertFalse(ReflectionWorker.isAboutUser("User: Where are you right now?", said))
        assertFalse(ReflectionWorker.isAboutUser("User: Hey, y'all, this.", said))
        assertFalse(ReflectionWorker.isAboutUser("User: A. Jarvis.", said))
        // A question the user asks about themselves is still not a statement of fact.
        assertFalse(ReflectionWorker.isAboutUser("User: What is my age?", listOf("What is my age?")))
        // Seen on the Pad: a request containing "me", echoed back as raw JSON.
        val asked = listOf("Tell me about the history of the Roman Empire")
        assertFalse(ReflectionWorker.isAboutUser("{\"User\":\"Tell me about the history of the Roman Empire\"}", asked))
        assertFalse(ReflectionWorker.isAboutUser("User asked about the history of the Roman Empire", asked))
        assertFalse(ReflectionWorker.isAboutUser("User wants to know about the Roman Empire", listOf("show me the Roman Empire")))
        // Real statements about themselves still count.
        assertTrue(ReflectionWorker.isAboutUser("User's favourite sport is cricket", listOf("my favourite sport is cricket", "who won the match")))
        assertTrue(ReflectionWorker.isAboutUser("User lives in Springfield", listOf("I live in Springfield now")))
    }

    @Test
    fun `learning report says only what happened`() {
        fun r(n: Int, f: Int = 0, v: Int = 0, aborted: Boolean = false) = ReflectionWorker.ReflectionReport(n, f, v, 0, 0, aborted)
        assertEquals("Done. I went through 4 conversations and learned 1 fact about you, 2 new words.", ReflectionWorker.learnNowSummary(r(4, 1, 2), false))
        assertEquals("Done. I went through 1 conversation but found nothing new to learn.", ReflectionWorker.learnNowSummary(r(1), false))
        assertEquals("Done. There were no new conversations to learn from since last time.", ReflectionWorker.learnNowSummary(r(0), false))
        assertTrue(ReflectionWorker.learnNowSummary(r(3), true).startsWith("Learning is paused"))
    }
}
