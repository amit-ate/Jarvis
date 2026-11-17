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
            ToolCall("set_volume", mapOf("percent" to "40")),
            FastIntents.match("volume to 40 percent"),
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
}
