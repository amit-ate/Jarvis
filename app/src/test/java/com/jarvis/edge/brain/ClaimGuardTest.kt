package com.jarvis.edge.brain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ClaimGuardTest {

    private val tools = setOf("get_weather", "web_search", "set_timer")

    private fun flagged(reply: String, toolRan: Boolean = false) =
        assertEquals(true, ClaimGuard.problem(reply, tools, toolRan) != null, reply)

    private fun ok(reply: String, toolRan: Boolean = false) =
        assertNull(ClaimGuard.problem(reply, tools, toolRan), reply)

    @Test
    fun `real tool name spoken is flagged`() {
        val reason = ClaimGuard.problem(
            "I can access and update my knowledge base using the \u201Cget_weather(location)\u201D tool.",
            tools, toolRan = false)
        assertEquals("speaks a tool name", reason)
    }

    @Test
    fun `unknown tool name is flagged as nonexistent`() {
        assertEquals("names a tool that does not exist",
            ClaimGuard.problem("Let me call update_knowledge() for you.", tools, false))
        assertEquals("names a tool that does not exist",
            ClaimGuard.problem("I used scan_inbox(query) to check.", tools, true))
    }

    @Test
    fun `real tool name is flagged even when a tool ran`() {
        assertEquals("speaks a tool name",
            ClaimGuard.problem("The set_timer tool worked.", tools, true))
    }

    @Test
    fun `ongoing or finished action without a tool run is flagged`() {
        flagged("I\u2019m running.")
        flagged("I'm running.")
        flagged("I am running.")
        flagged("I\u2019m still processing, but I\u2019ll let you know when I\u2019ve finished.")
        flagged("I'm currently analyzing your data.")
        flagged("I'm currently learning from this.")
        flagged("I've updated your notes.")
        flagged("I have updated the list.")
        flagged("I've set a reminder.")
        flagged("I have sent it.")
        flagged("I've turned off the lights.")
        flagged("I'm searching the web now.")
    }

    @Test
    fun `promise to report later is flagged`() {
        flagged("I will notify you.")
        flagged("I'll get back to you.")
        flagged("I'll let you know when it's done.")
    }

    @Test
    fun `remember claim is flagged only without a tool run`() {
        flagged("I'll remember that.")
        ok("I'll remember that.", toolRan = true)
    }

    @Test
    fun `claims are allowed once a tool actually ran`() {
        ok("I'm running.", toolRan = true)
        ok("I've updated your notes.", toolRan = true)
        ok("I'll let you know when it's done.", toolRan = true)
    }

    @Test
    fun `ordinary answers are not flagged`() {
        ok("I'm not sure.")
        ok("I'm Jarvis.")
        ok("I'm doing well.")
        ok("I'm happy to help.")
        ok("I'm sorry.")
        ok("I can help with timers.")
        ok("I don't have access to your schedule.")
        ok("I'm not capable of dreaming.")
        ok("I am a voice assistant.")
        ok("Running is good exercise.")
        ok("You're running late.")
        ok("Let me look that up.")
        ok("I'm not running anything right now.")
    }
}
