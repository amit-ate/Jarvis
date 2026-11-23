package com.jarvis.edge.setup

import com.jarvis.edge.core.ModelPaths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ModelManifestTest {

    private val twoItems = """
        {"version": 1, "items": [
          {"id": "kws", "label": "Wake word", "required": true, "url": "https://example.com/kws.tar.bz2",
           "size": 10, "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "kind": "tar.bz2", "dest": "kws",
           "unpackedSize": 20, "mustExist": ["kws/tokens.txt"], "drop": ["espeak-ng-data"], "overlay": ["kws/keywords.txt"]},
          {"id": "vad", "label": "Voice detector", "required": true, "url": "https://example.com/vad.onnx",
           "size": 5, "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "kind": "file", "dest": "vad/silero_vad.onnx",
           "unpackedSize": 5, "mustExist": ["vad/silero_vad.onnx"], "drop": [], "overlay": []}
        ]}
    """.trimIndent()

    @Test
    fun `parses items in order with all fields`() {
        val m = ModelManifest.parse(twoItems)
        assertEquals(listOf("kws", "vad"), m.items.map { it.id })
        val kws = m.items[0]
        assertEquals("Wake word", kws.label)
        assertTrue(kws.required)
        assertEquals("https://example.com/kws.tar.bz2", kws.url)
        assertEquals(10L, kws.size)
        assertEquals("a".repeat(64), kws.sha256)
        assertEquals(ItemKind.TAR_BZ2, kws.kind)
        assertEquals("kws", kws.dest)
        assertEquals(20L, kws.unpackedSize)
        assertEquals(listOf("kws/tokens.txt"), kws.mustExist)
        assertEquals(listOf("espeak-ng-data"), kws.drop)
        assertEquals(listOf("kws/keywords.txt"), kws.overlay)
        assertEquals(ItemKind.FILE, m.items[1].kind)
        assertEquals(emptyList<String>(), m.items[1].drop)
    }

    @Test
    fun `rejects a sha256 that is not 64 hex chars`() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelManifest.parse(twoItems.replace("a".repeat(64), "abc123"))
        }
    }

    @Test
    fun `rejects an unknown kind`() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelManifest.parse(twoItems.replace("\"kind\": \"tar.bz2\"", "\"kind\": \"zip\""))
        }
    }

    @Test
    fun `rejects an item with an empty mustExist`() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelManifest.parse(twoItems.replace("\"mustExist\": [\"vad/silero_vad.onnx\"]", "\"mustExist\": []"))
        }
    }

    @Test
    fun `the shipped manifest parses and covers speechRequired`() {
        val m = ModelManifest.parse(File("src/main/assets/models/manifest.json").readText())
        val required = m.items.filter { it.required }.flatMap { it.mustExist }.toSet()
        assertTrue(ModelPaths(File("x")).speechRequired().all { it in required })
        assertTrue(m.items.any { it.required && it.dest == "llm/qwen2.5-1.5b-instruct-q4_0.gguf" })
        assertEquals(15, m.items.size)
    }
}
