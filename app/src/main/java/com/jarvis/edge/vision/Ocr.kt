package com.jarvis.edge.vision

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface Ocr {
    suspend fun read(image: Bitmap): String
}

class MlKitOcr : Ocr {
    private val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun read(image: Bitmap): String = suspendCancellableCoroutine { cont ->
        val input = InputImage.fromBitmap(image, 0)
        client.process(input)
            .addOnSuccessListener { result ->
                if (cont.isActive) {
                    cont.resume(result.text.trim())
                }
            }
            .addOnFailureListener { err ->
                if (cont.isActive) {
                    cont.resumeWithException(err)
                }
            }
    }

    fun close() {
        client.close()
    }
}
