package com.jarvis.edge.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class CrashLogTest {
    private fun tmp() = File(Files.createTempDirectory("crash").toFile(), CrashLog.FILE_NAME)

    @Test
    fun recordsAndReadsTheLatestCrash() {
        val f = tmp()
        val log = CrashLog(f)
        assertNull(log.latest())
        log.record("main", IllegalStateException("boom"), timeMs = 1234L)
        val c = log.latest()!!
        assertEquals(1234L, c.timeMs)
        assertEquals("main", c.thread)
        assertEquals("IllegalStateException: boom", c.summary)
        assertTrue(c.trace.contains("CrashLogTest"))
    }

    @Test
    fun dismissForgetsIt() {
        val log = CrashLog(tmp())
        log.record("t", RuntimeException("x"))
        log.dismiss()
        assertNull(log.latest())
    }

    @Test
    fun garbageFileIsIgnored() {
        val f = tmp()
        f.writeText("not a crash")
        assertNull(CrashLog(f).latest())
    }
}
