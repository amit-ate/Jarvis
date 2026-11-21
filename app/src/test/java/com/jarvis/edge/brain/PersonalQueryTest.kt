package com.jarvis.edge.brain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PersonalQueryTest {

    @Test
    fun `recognises questions about the user's own attributes`() {
        assertEquals("age", PersonalQuery.askedAttribute("how old am I"))
        assertEquals("age", PersonalQuery.askedAttribute("What's my age?"))
        assertEquals("age", PersonalQuery.askedAttribute("whats my age"))
        assertEquals("age", PersonalQuery.askedAttribute("what is my age"))
        assertEquals("age", PersonalQuery.askedAttribute("tell me my age"))
        assertEquals("age", PersonalQuery.askedAttribute("do you know my age"))
        assertEquals("name", PersonalQuery.askedAttribute("what's my name"))
        assertEquals("name", PersonalQuery.askedAttribute("who am I?"))
        assertEquals("favourite colour", PersonalQuery.askedAttribute("what is my favourite colour"))
    }

    @Test
    fun `statements and commands about my things are not personal questions`() {
        assertNull(PersonalQuery.askedAttribute("my age is 44"))
        assertNull(PersonalQuery.askedAttribute("set my alarm"))
        assertNull(PersonalQuery.askedAttribute("call my mom"))
        assertNull(PersonalQuery.askedAttribute("turn on my flashlight"))
        assertNull(PersonalQuery.askedAttribute("tell me a joke"))
    }

    @Test
    fun `detects device and phone status questions`() {
        assertTrue(PersonalQuery.isAboutDevice("what's my battery"))
        assertTrue(PersonalQuery.isAboutDevice("how is my battery"))
        assertTrue(PersonalQuery.isAboutDevice("is the phone hot"))
        assertTrue(PersonalQuery.isAboutDevice("what's the CPU temperature"))
        assertTrue(PersonalQuery.isAboutDevice("how are you feeling"))
        assertTrue(PersonalQuery.isAboutDevice("what's your status"))
        assertFalse(PersonalQuery.isAboutDevice("what's my age"))
        assertFalse(PersonalQuery.isAboutDevice("tell me a joke"))
        assertFalse(PersonalQuery.isAboutDevice("what's my program"))
    }
}
