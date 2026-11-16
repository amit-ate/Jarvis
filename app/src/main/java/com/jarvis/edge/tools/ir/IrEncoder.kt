package com.jarvis.edge.tools.ir

object IrEncoder {

    fun nec(address: Int, command: Int): IntArray {
        val addr = address and 0xFF
        val addrInv = addr.inv() and 0xFF
        val cmd = command and 0xFF
        val cmdInv = cmd.inv() and 0xFF

        val pattern = IntArray(67)
        pattern[0] = 9000
        pattern[1] = 4500
        var idx = 2
        val bytes = intArrayOf(addr, addrInv, cmd, cmdInv)
        for (b in bytes) {
            for (bit in 0 until 8) {
                pattern[idx++] = 562
                val isOne = ((b ushr bit) and 1) == 1
                pattern[idx++] = if (isOne) 1687 else 562
            }
        }
        pattern[idx] = 562
        return pattern
    }

    fun samsung(address: Int, command: Int): IntArray {
        val addrLow = address and 0xFF
        val addrHigh = if (address > 0xFF) (address ushr 8) and 0xFF else addrLow
        val cmd = command and 0xFF
        val cmdInv = cmd.inv() and 0xFF

        val pattern = IntArray(67)
        pattern[0] = 4500
        pattern[1] = 4500
        var idx = 2
        val bytes = intArrayOf(addrLow, addrHigh, cmd, cmdInv)
        for (b in bytes) {
            for (bit in 0 until 8) {
                pattern[idx++] = 560
                val isOne = ((b ushr bit) and 1) == 1
                pattern[idx++] = if (isOne) 1690 else 560
            }
        }
        pattern[idx] = 560
        return pattern
    }
}
