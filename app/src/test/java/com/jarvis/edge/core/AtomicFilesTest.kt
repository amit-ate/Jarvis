package com.jarvis.edge.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class AtomicFilesTest {

    @Test
    fun `write replaces content atomically`(@TempDir tempDir: Path) {
        val dir = tempDir.toFile()
        val target = File(dir, "x.bin")

        AtomicFiles.writeText(target, "a")
        AtomicFiles.writeText(target, "b")

        assertEquals("b", target.readText())
        assertFalse(File(dir, "x.bin.tmp").exists())
    }

    @Test
    fun `write creates missing parent directories`(@TempDir tempDir: Path) {
        val target = File(tempDir.toFile(), "nested/deep/x.bin")

        AtomicFiles.write(target, byteArrayOf(1, 2, 3))

        assertTrue(target.isFile)
        assertEquals(listOf<Byte>(1, 2, 3), target.readBytes().toList())
    }

    @Test
    fun `stale tmp is cleaned`(@TempDir tempDir: Path) {
        val dir = tempDir.toFile()
        val keep = File(dir, "x.bin").apply { writeText("keep") }
        val stale = File(dir, "x.bin.tmp").apply { writeText("partial") }

        val removed = AtomicFiles.cleanupTemps(dir)

        assertEquals(1, removed)
        assertFalse(stale.exists())
        assertTrue(keep.exists())
        assertEquals("keep", keep.readText())
    }

    @Test
    fun `cleanupTemps on missing directory returns zero`(@TempDir tempDir: Path) {
        assertEquals(0, AtomicFiles.cleanupTemps(File(tempDir.toFile(), "absent")))
    }
}
