package com.jarvis.edge.mind

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Pure Kotlin vector math, serialization, and deterministic fallback text embeddings.
 */
object VectorMath {

    private val WORD_SPLIT = Regex("[^a-z0-9]+")

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || b.isEmpty() || a.size != b.size) return 0f
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            val av = a[i].toDouble()
            val bv = b[i].toDouble()
            dot += av * bv
            normA += av * av
            normB += bv * bv
        }
        val denom = sqrt(normA) * sqrt(normB)
        if (denom <= 1e-12) return 0f
        return (dot / denom).toFloat().coerceIn(-1f, 1f)
    }

    fun l2Normalize(v: FloatArray): FloatArray {
        if (v.isEmpty()) return v
        var sumSq = 0.0
        for (x in v) {
            sumSq += x.toDouble() * x.toDouble()
        }
        val norm = sqrt(sumSq).toFloat()
        if (norm <= 1e-12f) return v.copyOf()
        val out = FloatArray(v.size)
        for (i in v.indices) {
            out[i] = v[i] / norm
        }
        return out
    }

    fun toBytes(v: FloatArray): ByteArray {
        if (v.isEmpty()) return ByteArray(0)
        val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) {
            buf.putFloat(x)
        }
        return buf.array()
    }

    fun fromBytes(b: ByteArray): FloatArray {
        val count = b.size / 4
        if (count <= 0) return FloatArray(0)
        val buf = ByteBuffer.wrap(b, 0, count * 4).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(count)
        for (i in 0 until count) {
            out[i] = buf.float
        }
        return out
    }

    /**
     * Deterministic signed-projection hashed word + char-3gram L2-normalized embedding.
     */
    fun simpleHashEmbed(text: String, dim: Int = 128): FloatArray {
        require(dim > 0) { "Embedding dimension must be positive" }
        val vec = FloatArray(dim)
        val words = text.lowercase().trim().split(WORD_SPLIT).filter { it.isNotEmpty() }
        if (words.isEmpty()) return vec

        for (word in words) {
            accumulateFeature(vec, "w:$word", 1.0f)
            val padded = "^$word$"
            if (padded.length >= 3) {
                for (i in 0..padded.length - 3) {
                    accumulateFeature(vec, "c:${padded.substring(i, i + 3)}", 0.5f)
                }
            }
        }
        return l2Normalize(vec)
    }

    private fun accumulateFeature(vec: FloatArray, feature: String, weight: Float) {
        val h = fnv1a32(feature)
        val idx = (h and 0x7fffffff) % vec.size
        val sign = if ((h ushr 31) == 0) 1f else -1f
        vec[idx] += sign * weight
    }

    private fun fnv1a32(s: String): Int {
        var hash = -0x7ee3623b // 0x811c9dc5
        for (i in s.indices) {
            hash = hash xor s[i].code
            hash *= 0x01000193
        }
        return hash
    }
}
