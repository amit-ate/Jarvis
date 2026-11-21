package com.jarvis.edge.brain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FastIntentsTest {

    @Test
    fun `fast intents`() {
        assertEquals(ToolCall("get_time", emptyMap()), FastIntents.match("what time is it"))
        assertEquals(ToolCall("get_time", emptyMap()), FastIntents.match("What's the time now?"))
        assertEquals(ToolCall("get_time", emptyMap()), FastIntents.match("Was the time now?"))
        assertEquals(
            ToolCall("set_timer", mapOf("minutes" to "5", "seconds" to "0")),
            FastIntents.match("Set a timer for five minutes."),
        )
        assertEquals(
            ToolCall("set_timer", mapOf("minutes" to "1", "seconds" to "30")),
            FastIntents.match("timer 1 minute 30 seconds"),
        )
        assertEquals(
            ToolCall("torch", mapOf("state" to "on")),
            FastIntents.match("turn on the flashlight"),
        )
        assertEquals(
            ToolCall("torch", mapOf("state" to "off")),
            FastIntents.match("torch off"),
        )
        assertEquals(
            ToolCall("torch", mapOf("state" to "on")),
            FastIntents.match("light please"),
        )
        assertEquals(
            ToolCall("set_volume", mapOf("percent" to "40")),
            FastIntents.match("volume to 40 percent"),
        )
        assertEquals(
            ToolCall("set_volume", mapOf("percent" to "up")),
            FastIntents.match("volume up"),
        )
        assertEquals(
            ToolCall("set_volume", mapOf("percent" to "up")),
            FastIntents.match("louder"),
        )
        assertEquals(
            ToolCall("set_brightness", mapOf("percent" to "down")),
            FastIntents.match("dim screen"),
        )
        assertEquals(
            ToolCall("set_brightness", mapOf("percent" to "up")),
            FastIntents.match("brighter"),
        )
        assertEquals(
            ToolCall("cancel_timers", emptyMap()),
            FastIntents.match("cancel timers"),
        )
        assertEquals(
            ToolCall("set_alarm", mapOf("hour" to "7", "minute" to "0")),
            FastIntents.match("wake me up at 7 am"),
        )
        assertEquals(
            ToolCall("battery_status", emptyMap()),
            FastIntents.match("battery"),
        )
        assertEquals(
            ToolCall("battery_status", emptyMap()),
            FastIntents.match("how much battery left"),
        )
        assertEquals(
            ToolCall("light_level", emptyMap()),
            FastIntents.match("ambient light"),
        )
        assertEquals(
            ToolCall("remember", mapOf("note" to "my keys are in the drawer", "text" to "my keys are in the drawer")),
            FastIntents.match("remember my keys are in the drawer"),
        )
        assertEquals(
            ToolCall("recall", mapOf("query" to "keys")),
            FastIntents.match("recall keys"),
        )
        assertEquals(
            ToolCall("recall", mapOf("query" to "keys")),
            FastIntents.match("what did I say about keys"),
        )
        assertEquals(
            ToolCall("read_text", mapOf("camera" to "back")),
            FastIntents.match("read this"),
        )
        assertEquals(
            ToolCall("look", mapOf("camera" to "back", "question" to "what do you see")),
            FastIntents.match("what do you see"),
        )
        assertEquals(
            ToolCall("look", mapOf("camera" to "front", "question" to "what am i holding")),
            FastIntents.match("what am I holding"),
        )
        assertEquals(
            ToolCall("get_weather", mapOf("location" to "")),
            FastIntents.match("What's the weather outside?"),
        )
        assertEquals(
            ToolCall("get_weather", mapOf("location" to "mumbai")),
            FastIntents.match("weather in Mumbai"),
        )
        assertEquals(
            ToolCall("web_search", mapOf("query" to "narendra modi")),
            FastIntents.match("Who is Narendra Modi?"),
        )
        assertEquals(
            ToolCall("web_search", mapOf("query" to "mount everest")),
            FastIntents.match("search online for Mount Everest"),
        )
        assertNull(FastIntents.match("what time does the moon rise in Tokyo"))
        assertNull(FastIntents.match("tell me a joke about timers"))
    }

    @Test
    fun `battery with possessive my`() {
        assertEquals(ToolCall("battery_status", emptyMap()), FastIntents.match("How is my battery?"))
        assertEquals(ToolCall("battery_status", emptyMap()), FastIntents.match("What's my battery level?"))
        assertEquals(ToolCall("battery_status", emptyMap()), FastIntents.match("check my battery"))
        assertEquals(ToolCall("battery_status", emptyMap()), FastIntents.match("How much battery do I have?"))
    }

    @Test
    fun `learned today with filler and did or do`() {
        val learned = ToolCall("mind_status", mapOf("action" to "learned_today"))
        assertEquals(learned, FastIntents.match("That's what I am asking. What did you learn today?"))
        assertEquals(learned, FastIntents.match("I was saying, what do you learn today?"))
        assertEquals(learned, FastIntents.match("What did you learn"))
        assertEquals(learned, FastIntents.match("What have you learnt today?"))
    }

    @Test
    fun `unrelated speech with filler returns null`() {
        assertNull(FastIntents.match("I was saying hello to my friend"))
        assertNull(FastIntents.match("tell me a joke"))
    }
}
