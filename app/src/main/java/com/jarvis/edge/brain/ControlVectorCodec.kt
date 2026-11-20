package com.jarvis.edge.brain

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

data class ControlVector(
    val nEmbd: Int,
    val nLayersMinus1: Int,
    val ilStart: Int,
    val ilEnd: Int,
    val data: FloatArray,
) {
    fun layerNorm(layer1Based: Int): Float {
        if (layer1Based < 1 || layer1Based > nLayersMinus1 || nEmbd <= 0) {
            return 0f
        }
        val offset = (layer1Based - 1) * nEmbd
        if (offset < 0 || offset + nEmbd > data.size) {
            return 0f
        }
        var sumSq = 0.0
        for (d in 0 until nEmbd) {
            val v = data[offset + d].toDouble()
            sumSq += v * v
        }
        return sqrt(sumSq).toFloat()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ControlVector) return false
        return nEmbd == other.nEmbd &&
            nLayersMinus1 == other.nLayersMinus1 &&
            ilStart == other.ilStart &&
            ilEnd == other.ilEnd &&
            data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = nEmbd
        result = 31 * result + nLayersMinus1
        result = 31 * result + ilStart
        result = 31 * result + ilEnd
        result = 31 * result + data.contentHashCode()
        return result
    }
}

object ControlVectorCodec {
    private const val MAGIC = "JVEC"
    private const val VERSION = 1
    private const val HEADER_BYTES = 24

    fun write(file: File, vec: ControlVector) {
        require(vec.nEmbd > 0) { "nEmbd must be positive: ${vec.nEmbd}" }
        require(vec.nLayersMinus1 > 0) { "nLayersMinus1 must be positive: ${vec.nLayersMinus1}" }
        require(vec.ilStart >= 1 && vec.ilEnd >= vec.ilStart && vec.ilEnd <= vec.nLayersMinus1) {
            "Invalid layer range [${vec.ilStart}, ${vec.ilEnd}] for nLayersMinus1=${vec.nLayersMinus1}"
        }
        val expectedLen = vec.nEmbd.toLong() * vec.nLayersMinus1.toLong()
        require(vec.data.size.toLong() == expectedLen) {
            "Expected $expectedLen floats, got ${vec.data.size}"
        }

        val payloadBytes = expectedLen * 4L
        val totalBytes = (HEADER_BYTES.toLong() + payloadBytes).toInt()
        val buf = ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (ch in MAGIC) {
            buf.put(ch.code.toByte())
        }
        buf.putInt(VERSION)
        buf.putInt(vec.nEmbd)
        buf.putInt(vec.nLayersMinus1)
        buf.putInt(vec.ilStart)
        buf.putInt(vec.ilEnd)
        for (v in vec.data) {
            buf.putFloat(v)
        }

        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val tmp = File(parent ?: File("."), "${file.name}.tmp")
        tmp.writeBytes(buf.array())
        if (!tmp.renameTo(file)) {
            file.writeBytes(buf.array())
            tmp.delete()
        }
    }

    fun read(file: File): ControlVector? {
        if (!file.isFile || file.length() < HEADER_BYTES.toLong()) {
            return null
        }
        return runCatching {
            val bytes = file.readBytes()
            if (bytes.size < HEADER_BYTES) return null
            if (bytes[0] != 'J'.code.toByte() ||
                bytes[1] != 'V'.code.toByte() ||
                bytes[2] != 'E'.code.toByte() ||
                bytes[3] != 'C'.code.toByte()
            ) {
                return null
            }

            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buf.position(4)
            val version = buf.getInt()
            val nEmbd = buf.getInt()
            val nLayersMinus1 = buf.getInt()
            val ilStart = buf.getInt()
            val ilEnd = buf.getInt()

            if (version != VERSION || nEmbd <= 0 || nLayersMinus1 <= 0) {
                return null
            }
            if (ilStart < 1 || ilEnd < ilStart || ilEnd > nLayersMinus1) {
                return null
            }

            val expectedElems = nEmbd.toLong() * nLayersMinus1.toLong()
            if (expectedElems <= 0L || expectedElems > Int.MAX_VALUE / 4L) {
                return null
            }
            val expectedTotalBytes = HEADER_BYTES.toLong() + expectedElems * 4L
            if (bytes.size.toLong() != expectedTotalBytes) {
                return null
            }

            val data = FloatArray(expectedElems.toInt()) { buf.getFloat() }
            ControlVector(
                nEmbd = nEmbd,
                nLayersMinus1 = nLayersMinus1,
                ilStart = ilStart,
                ilEnd = ilEnd,
                data = data,
            )
        }.getOrNull()
    }

    fun blend(entries: List<Pair<ControlVector, Float>>): ControlVector? {
        if (entries.isEmpty()) return null
        val first = entries.first().first
        val nEmbd = first.nEmbd
        val nLayersMinus1 = first.nLayersMinus1
        if (nEmbd <= 0 || nLayersMinus1 <= 0) return null

        val expectedLenLong = nEmbd.toLong() * nLayersMinus1.toLong()
        if (expectedLenLong > Int.MAX_VALUE) return null
        val expectedLen = expectedLenLong.toInt()

        var minStart = Int.MAX_VALUE
        var maxEnd = Int.MIN_VALUE
        val out = FloatArray(expectedLen)

        for ((vec, scale) in entries) {
            if (!scale.isFinite()) return null
            if (vec.nEmbd != nEmbd || vec.nLayersMinus1 != nLayersMinus1 || vec.data.size != expectedLen) {
                return null
            }
            if (vec.ilStart < minStart) minStart = vec.ilStart
            if (vec.ilEnd > maxEnd) maxEnd = vec.ilEnd
            if (scale != 0.0f) {
                for (i in 0 until expectedLen) {
                    out[i] += scale * vec.data[i]
                }
            }
        }

        return ControlVector(
            nEmbd = nEmbd,
            nLayersMinus1 = nLayersMinus1,
            ilStart = minStart,
            ilEnd = maxEnd,
            data = out,
        )
    }
}
