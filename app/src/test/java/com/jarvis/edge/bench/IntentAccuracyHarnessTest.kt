package com.jarvis.edge.bench

import com.jarvis.edge.brain.FastIntents
import com.jarvis.edge.brain.ToolCall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IntentAccuracyHarnessTest {

    data class TestCase(
        val query: String,
        val expectedTool: String,
        val expectedArgKey: String? = null,
        val expectedArgVal: String? = null,
    )

    private val testSuite = listOf(
        // Torch
        TestCase("turn on the flashlight", "torch", "state", "on"),
        TestCase("turn off the flashlight", "torch", "state", "off"),
        TestCase("turn the flashlight on", "torch", "state", "on"),
        TestCase("turn the torch off", "torch", "state", "off"),
        TestCase("turn on the torch", "torch", "state", "on"),
        TestCase("turn off the torch", "torch", "state", "off"),
        TestCase("turn the light on", "torch", "state", "on"),
        TestCase("turn off the light", "torch", "state", "off"),
        TestCase("torch on", "torch", "state", "on"),
        TestCase("torch off", "torch", "state", "off"),
        TestCase("flashlight on", "torch", "state", "on"),
        TestCase("flashlight off", "torch", "state", "off"),
        TestCase("light on", "torch", "state", "on"),
        TestCase("light off", "torch", "state", "off"),

        // Volume
        TestCase("set volume to 80 percent", "set_volume", "percent", "80"),
        TestCase("volume to 40 percent", "set_volume", "percent", "40"),
        TestCase("volume 50", "set_volume", "percent", "50"),
        TestCase("volume up", "set_volume", "percent", "up"),
        TestCase("volume down", "set_volume", "percent", "down"),
        TestCase("turn up the volume", "set_volume", "percent", "up"),
        TestCase("turn down the volume", "set_volume", "percent", "down"),
        TestCase("louder", "set_volume", "percent", "up"),
        TestCase("quieter", "set_volume", "percent", "down"),

        // Brightness
        TestCase("set brightness to 50 percent", "set_brightness", "percent", "50"),
        TestCase("screen brightness 80", "set_brightness", "percent", "80"),
        TestCase("screen brighter", "set_brightness", "percent", "up"),
        TestCase("make it brighter", "set_brightness", "percent", "up"),
        TestCase("dim the screen", "set_brightness", "percent", "down"),
        TestCase("dimmer", "set_brightness", "percent", "down"),

        // Battery & Light
        TestCase("what is the battery", "battery_status"),
        TestCase("battery status", "battery_status"),
        TestCase("how much battery is left", "battery_status"),
        TestCase("battery percentage", "battery_status"),
        TestCase("check battery", "battery_status"),
        TestCase("what is the ambient light level", "light_level"),
        TestCase("light level", "light_level"),

        // Time & Date
        TestCase("what time is it", "get_time"),
        TestCase("what's the time now", "get_time"),
        TestCase("tell me the time", "get_time"),
        TestCase("current time", "get_time"),
        TestCase("what day is today", "get_date"),
        TestCase("what is today's date", "get_date"),
        TestCase("tell me the date", "get_date"),

        // Timers & Alarms
        TestCase("set a timer for 5 minutes", "set_timer", "minutes", "5"),
        TestCase("timer 10 minutes", "set_timer", "minutes", "10"),
        TestCase("timer 30 seconds", "set_timer", "seconds", "30"),
        TestCase("cancel timers", "cancel_timers"),
        TestCase("wake me up at 7 am", "set_alarm", "hour", "7"),

        // IR Blaster (TV & AC)
        TestCase("turn on the tv", "ir_send", "command", "power"),
        TestCase("turn off the tv", "ir_send", "command", "power"),
        TestCase("tv volume up", "ir_send", "command", "volume_up"),
        TestCase("tv volume down", "ir_send", "command", "volume_down"),
        TestCase("mute the tv", "ir_send", "command", "mute"),
        TestCase("turn on the ac", "ir_send", "command", "power"),

        // Vision & OCR
        TestCase("what do you see", "look", "camera", "back"),
        TestCase("what am i holding", "look", "camera", "front"),
        TestCase("read this", "read_text", "camera", "back"),

        // Weather & Web
        TestCase("what's the weather outside", "get_weather", "location", ""),
        TestCase("weather in tokyo", "get_weather", "location", "tokyo"),
        TestCase("who is Albert Einstein", "web_search", "query", "albert einstein"),
        TestCase("who was Napoleon", "web_search", "query", "napoleon"),
        TestCase("tell me about Mount Everest", "web_search", "query", "mount everest"),
        TestCase("search online for quantum computing", "web_search", "query", "quantum computing"),
        TestCase("look up photosynthesis", "web_search", "query", "photosynthesis"),
        TestCase("search wikipedia for HTTP", "web_search", "query", "http"),

        // Notes
        TestCase("remember that my bike lock is 1234", "remember", "note", "my bike lock is 1234"),
        TestCase("save a note that milk is in the fridge", "remember", "note", "milk is in the fridge"),
    )

    @Test
    fun testIntentAccuracyAndBenchmarkSpeed() {
        var passed = 0
        val total = testSuite.size

        // Warm up
        for (tc in testSuite) {
            FastIntents.match(tc.query)
        }

        // Benchmark matching speed over 1,000 iterations
        val iterations = 1000
        val t0 = System.nanoTime()
        for (it in 0 until iterations) {
            for (tc in testSuite) {
                FastIntents.match(tc.query)
            }
        }
        val elapsedUs = (System.nanoTime() - t0) / 1000
        val avgUsPerQuery = elapsedUs.toDouble() / (iterations * total)

        for (tc in testSuite) {
            val call = FastIntents.match(tc.query)
            assertNotNull(call, "Query '${tc.query}' failed to match any fast intent")
            call!!
            assertEquals(tc.expectedTool, call.name, "Mismatch for '${tc.query}'")
            if (tc.expectedArgKey != null && tc.expectedArgVal != null) {
                val actualVal = call.args[tc.expectedArgKey]
                assertEquals(
                    tc.expectedArgVal.lowercase(),
                    actualVal?.lowercase(),
                    "Arg mismatch for '${tc.query}' key '${tc.expectedArgKey}'",
                )
            }
            passed++
        }

        println("=== FAST INTENTS ACCURACY & BENCHMARK REPORT ===")
        println("Total Queries Evaluated: $total")
        println("Passed: $passed / $total (${(passed * 100.0 / total).toInt()}%)")
        println("Average Match Latency: ${"%.2f".format(avgUsPerQuery)} µs per query")
        println("Throughput: ${"%.0f".format(1_000_000.0 / avgUsPerQuery)} queries/sec")

        assertEquals(total, passed)
        assertTrue(avgUsPerQuery < 50.0, "Expected match latency < 50 µs, was $avgUsPerQuery µs")
    }
}
