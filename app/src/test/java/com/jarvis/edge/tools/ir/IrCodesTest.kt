package com.jarvis.edge.tools.ir

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IrCodesTest {

    private val sampleJson = """
        {
          "devices": {
            "tv": {
              "power": { "protocol": "nec", "address": "0x04", "command": "0x08" },
              "mute": { "carrierHz": 38000, "pattern": [9000, 4500, 562] }
            }
          }
        }
    """.trimIndent()

    @Test
    fun `unknown device returns null`() {
        val codes = IrCodes(sampleJson)
        assertNull(codes.code("projector", "power"))
        assertNull(codes.code("tv", "nonexistent"))
    }

    @Test
    fun `resolves NEC and raw code entries`() {
        val codes = IrCodes(sampleJson)
        assertEquals(listOf("tv"), codes.devices())
        assertEquals(setOf("power", "mute"), codes.commands("tv").toSet())

        val power = codes.code("tv", "power")
        assertNotNull(power)
        assertEquals(38_000, power!!.carrierHz)
        assertEquals(67, power.pattern.size)

        val mute = codes.code("tv", "mute")
        assertNotNull(mute)
        assertTrue(intArrayOf(9000, 4500, 562).contentEquals(mute!!.pattern))
    }
}
