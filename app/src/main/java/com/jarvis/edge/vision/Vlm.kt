package com.jarvis.edge.vision

import android.graphics.Bitmap
import com.jarvis.edge.brain.LlamaJniEngine
import com.jarvis.edge.brain.TokenCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

interface Vlm {
    suspend fun describe(
        image: Bitmap,
        question: String,
        onToken: (String) -> Boolean,
    )
}

class LlamaMtmdVlm(
    private val modelFile: File,
    private val mmprojFile: File,
    private val maxTokens: Int = 96,
) : Vlm {

    override suspend fun describe(
        image: Bitmap,
        question: String,
        onToken: (String) -> Boolean,
    ) = withContext(Dispatchers.Default) {
        require(modelFile.exists() && mmprojFile.exists()) {
            "VLM files missing: ${modelFile.name} / ${mmprojFile.name}"
        }
        val w = image.width
        val h = image.height
        val pixels = IntArray(w * h)
        image.getPixels(pixels, 0, w, 0, 0, w, h)
        val rgb = ByteArray(w * h * 3)
        for (i in pixels.indices) {
            val argb = pixels[i]
            rgb[i * 3] = ((argb ushr 16) and 0xFF).toByte()
            rgb[i * 3 + 1] = ((argb ushr 8) and 0xFF).toByte()
            rgb[i * 3 + 2] = (argb and 0xFF).toByte()
        }

        LlamaJniEngine.nativeDescribeImage(
            modelPath = modelFile.absolutePath,
            mmprojPath = mmprojFile.absolutePath,
            rgbBytes = rgb,
            width = w,
            height = h,
            question = question.ifBlank { "Describe what you see concisely." },
            maxTokens = maxTokens,
            callback = TokenCallback { piece -> onToken(piece) },
        )
    }
}
