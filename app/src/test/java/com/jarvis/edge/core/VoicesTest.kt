package com.jarvis.edge.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class VoicesTest {
    private fun install(root: File, v: TtsVoice) {
        v.modelFile(root).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1)) }
        v.tokensFile(root).writeText("a 0\n")
    }

    @Test
    fun `the chosen voice is used when installed, else the Indian male default, else any`(@TempDir root: File) {
        assertNull(Voices.resolve(root, null))
        val british = Voices.ALL.first { it.id == "british-male" }
        install(root, british)
        assertEquals("british-male", Voices.resolve(root, "indian-male")?.id)
        install(root, Voices.ALL.first { it.id == "indian-male" })
        assertEquals("indian-male", Voices.resolve(root, null)?.id)
        assertEquals("british-male", Voices.resolve(root, "british-male")?.id)
        assertEquals("indian-male", Voices.resolve(root, "indian-female")?.id)
        assertEquals(listOf("indian-male", "british-male"), Voices.available(root).map { it.id })
    }
}
