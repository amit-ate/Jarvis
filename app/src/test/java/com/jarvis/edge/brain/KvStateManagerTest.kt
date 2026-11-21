package com.jarvis.edge.brain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class KvStateManagerTest {
    @TempDir
    lateinit var dir: File

    private class CountingLlm : LlmEngine {
        var saves = 0
        var loads = 0
        var loadResult = true
        override fun load(modelFile: File, contextTokens: Int) = Unit
        override fun generate(
            prompt: String,
            grammar: String?,
            maxTokens: Int,
            onToken: (String) -> Boolean,
        ) = GenStats(0, 0, 0, 0L, 0f)
        override fun saveState(file: File): Boolean {
            saves++
            file.writeText("x")
            return true
        }
        override fun loadState(file: File): Boolean {
            loads++
            return loadResult
        }
        override fun unload() = Unit
        override val isLoaded: Boolean = true
    }

    @Test
    fun `key changes with prompt, model size, and adapter`() {
        val kv = KvStateManager(File(dir, "kv"))
        val model = File(dir, "m.gguf").apply { writeText("abc") }
        val base = kv.key(model, "p", null)
        assertEquals(16, base.length)
        assertEquals(base, kv.key(model, "p", null))
        assertNotEquals(base, kv.key(model, "q", null))
        assertNotEquals(base, kv.key(model, "p", "ad1"))
        model.writeText("abcd")
        assertNotEquals(base, kv.key(model, "p", null))
    }

    @Test
    fun `restore with missing file returns false without calling engine`() {
        val kv = KvStateManager(File(dir, "kv"))
        val engine = CountingLlm()
        assertEquals(KvRestoreResult.MISSING, kv.restore(engine, "nokey"))
        assertEquals(0, engine.loads)
    }

    @Test
    fun `save then restore calls engine`() {
        val kv = KvStateManager(File(dir, "kv"))
        val engine = CountingLlm()
        assertTrue(kv.save(engine, "k"))
        assertEquals(KvRestoreResult.RESTORED, kv.restore(engine, "k"))
        assertEquals(1, engine.loads)
    }

    @Test
    fun `rejected state file is deleted and reported`() {
        val kv = KvStateManager(File(dir, "kv"))
        val engine = CountingLlm()
        assertTrue(kv.save(engine, "k"))
        engine.loadResult = false
        assertEquals(KvRestoreResult.REJECTED, kv.restore(engine, "k"))
        assertFalse(kv.fileFor("k").exists())
        assertEquals(KvRestoreResult.MISSING, kv.restore(engine, "k"))
    }

    @Test
    fun `pruneExcept removes other kv files only`() {
        val kvDir = File(dir, "kv").apply { mkdirs() }
        File(kvDir, "kv-a.bin").writeText("a")
        File(kvDir, "kv-b.bin").writeText("b")
        File(kvDir, "other.bin").writeText("o")
        val removed = KvStateManager(kvDir).pruneExcept("a")
        assertEquals(1, removed)
        assertTrue(File(kvDir, "kv-a.bin").exists())
        assertFalse(File(kvDir, "kv-b.bin").exists())
        assertTrue(File(kvDir, "other.bin").exists())
    }
}
