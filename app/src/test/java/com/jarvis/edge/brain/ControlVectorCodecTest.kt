package com.jarvis.edge.brain

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path

class ControlVectorCodecTest {

    @Test
    fun `write and read preserves exact 24-byte LE header and float32 payload`(@TempDir tempDir: Path) {
        val file = File(tempDir.toFile(), "nested/test_expert.jvec")
        val data = floatArrayOf(
            0f, 0f, 0f, 0f, // layer 1 (outside [2, 3])
            3f, 4f, 0f, 0f, // layer 2 (norm = 5)
            0f, 0f, 1f, -2f, // layer 3 (norm = sqrt(5))
            0f, 0f, 0f, 0f, // layer 4 (outside [2, 3])
        )
        val vec = ControlVector(
            nEmbd = 4,
            nLayersMinus1 = 4,
            ilStart = 2,
            ilEnd = 3,
            data = data,
        )

        ControlVectorCodec.write(file, vec)

        val raw = file.readBytes()
        assertEquals(24 + data.size * 4, raw.size)
        assertEquals("JVEC", String(raw, 0, 4, Charsets.US_ASCII))
        val hdr = ByteBuffer.wrap(raw, 4, 20).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, hdr.getInt()) // version
        assertEquals(4, hdr.getInt()) // nEmbd
        assertEquals(4, hdr.getInt()) // nLayersMinus1
        assertEquals(2, hdr.getInt()) // ilStart
        assertEquals(3, hdr.getInt()) // ilEnd

        val decoded = ControlVectorCodec.read(file)
        assertNotNull(decoded)
        assertEquals(vec, decoded)
        assertEquals(vec.hashCode(), decoded!!.hashCode())
        assertEquals(0f, decoded.layerNorm(1), 1e-6f)
        assertEquals(5f, decoded.layerNorm(2), 1e-6f)
        assertEquals(kotlin.math.sqrt(5.0).toFloat(), decoded.layerNorm(3), 1e-5f)
        assertEquals(0f, decoded.layerNorm(0), 1e-6f)
        assertEquals(0f, decoded.layerNorm(5), 1e-6f)
    }

    @Test
    fun `read rejects corrupt magic, bad version, or truncated payload`(@TempDir tempDir: Path) {
        val dir = tempDir.toFile()

        val missing = File(dir, "missing.jvec")
        assertNull(ControlVectorCodec.read(missing))

        val badMagic = File(dir, "bad_magic.jvec").apply {
            val buf = ByteBuffer.allocate(24 + 16).order(ByteOrder.LITTLE_ENDIAN)
            buf.put("NOPE".toByteArray())
            buf.putInt(1).putInt(2).putInt(2).putInt(1).putInt(2)
            writeBytes(buf.array())
        }
        assertNull(ControlVectorCodec.read(badMagic))

        val badVersion = File(dir, "bad_ver.jvec").apply {
            val buf = ByteBuffer.allocate(24 + 16).order(ByteOrder.LITTLE_ENDIAN)
            buf.put("JVEC".toByteArray())
            buf.putInt(99).putInt(2).putInt(2).putInt(1).putInt(2)
            writeBytes(buf.array())
        }
        assertNull(ControlVectorCodec.read(badVersion))

        val truncated = File(dir, "truncated.jvec").apply {
            val buf = ByteBuffer.allocate(24 + 12).order(ByteOrder.LITTLE_ENDIAN)
            buf.put("JVEC".toByteArray())
            buf.putInt(1).putInt(2).putInt(2).putInt(1).putInt(2)
            writeBytes(buf.array())
        }
        assertNull(ControlVectorCodec.read(truncated))
    }

    @Test
    fun `write rejects mismatched payload size`(@TempDir tempDir: Path) {
        val file = File(tempDir.toFile(), "invalid.jvec")
        val bad = ControlVector(nEmbd = 4, nLayersMinus1 = 2, ilStart = 1, ilEnd = 2, data = FloatArray(5))
        assertThrows(IllegalArgumentException::class.java) {
            ControlVectorCodec.write(file, bad)
        }
    }

    @Test
    fun `blend computes weighted sum and union layer range and rejects incompatible vectors`() {
        val v1 = ControlVector(
            nEmbd = 2,
            nLayersMinus1 = 3,
            ilStart = 1,
            ilEnd = 2,
            data = floatArrayOf(1f, 2f, 3f, 4f, 0f, 0f),
        )
        val v2 = ControlVector(
            nEmbd = 2,
            nLayersMinus1 = 3,
            ilStart = 2,
            ilEnd = 3,
            data = floatArrayOf(0f, 0f, 10f, 20f, 30f, 40f),
        )

        val blended = ControlVectorCodec.blend(listOf(v1 to 0.5f, v2 to 0.25f))
        assertNotNull(blended)
        assertEquals(2, blended!!.nEmbd)
        assertEquals(3, blended.nLayersMinus1)
        assertEquals(1, blended.ilStart)
        assertEquals(3, blended.ilEnd)
        assertArrayEquals(
            floatArrayOf(0.5f, 1.0f, 4.0f, 7.0f, 7.5f, 10.0f),
            blended.data,
            1e-5f,
        )

        assertNull(ControlVectorCodec.blend(emptyList()))

        val incompatible = ControlVector(
            nEmbd = 4,
            nLayersMinus1 = 3,
            ilStart = 1,
            ilEnd = 2,
            data = FloatArray(12),
        )
        assertNull(ControlVectorCodec.blend(listOf(v1 to 1f, incompatible to 1f)))
        assertNotEquals(v1, v2)
    }
}
