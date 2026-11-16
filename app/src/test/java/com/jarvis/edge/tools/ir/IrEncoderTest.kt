package com.jarvis.edge.tools.ir

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IrEncoderTest {

    @Test
    fun `NEC frame shape`() {
        val p = IrEncoder.nec(address = 0x04, command = 0x08)
        assertEquals(67, p.size)
        assertEquals(listOf(9000, 4500), p.take(2))
        assertEquals(562, p[2])
        assertEquals(562, p.last())
        assertEquals(16, p.count { it == 1687 })
    }

    @Test
    fun `Samsung frame uses a 4500-4500 header`() {
        val p = IrEncoder.samsung(address = 0x07, command = 0x02)
        assertEquals(67, p.size)
        assertEquals(listOf(4500, 4500), p.take(2))
        assertEquals(560, p[2])
        assertEquals(560, p.last())
    }
}
