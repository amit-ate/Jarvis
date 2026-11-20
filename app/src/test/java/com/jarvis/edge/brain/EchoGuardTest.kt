package com.jarvis.edge.brain

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EchoGuardTest {

    @Test
    fun `detects exact and punctuated echoes`() {
        assertTrue(EchoGuard.isEcho("Why is the ocean salty?", "SAY: Why is the ocean salty?"))
        assertTrue(EchoGuard.isEcho("Hey Jarvis why is the ocean salty", "why is the ocean salty"))
    }

    @Test
    fun `detects containment and high jaccard or content overlap echoes`() {
        assertTrue(EchoGuard.isEcho("why is the sky blue", "Sure, why is the sky blue?"))
        assertTrue(EchoGuard.isEcho("explain photosynthesis briefly", "Well explain photosynthesis briefly"))
    }

    @Test
    fun `detects intra-reply 3-gram stutter loops`() {
        assertTrue(
            EchoGuard.isEcho(
                "tell me a joke",
                "I do not know what I do not know what I do not know",
            ),
        )
    }

    @Test
    fun `allows real answers and exempts repeat or say requests`() {
        assertFalse(EchoGuard.isEcho("Why is the ocean salty?", "Rivers carry dissolved minerals into the sea."))
        assertFalse(EchoGuard.isEcho("hello", "Hello there! How can I help?"))
        assertFalse(EchoGuard.isEcho("repeat after me I am ready", "I am ready"))
        assertFalse(EchoGuard.isEcho("say good morning", "Good morning"))
    }

    @Test
    fun `identifies factual questions accurately`() {
        assertTrue(EchoGuard.looksLikeFactualQuestion("Who discovered gravity?"))
        assertTrue(EchoGuard.looksLikeFactualQuestion("What is the capital of Japan"))
        assertTrue(EchoGuard.looksLikeFactualQuestion("Explain how batteries work"))
        assertFalse(EchoGuard.looksLikeFactualQuestion("Hello"))
        assertFalse(EchoGuard.looksLikeFactualQuestion("Thank you"))
    }
}
