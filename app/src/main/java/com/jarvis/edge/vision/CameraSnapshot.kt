package com.jarvis.edge.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class Facing {
    FRONT,
    BACK,
}

class CameraSnapshot(
    private val context: Context,
    private val onScreenFillWhite: ((Boolean) -> Unit)? = null,
) {

    private val captureExecutor = Executors.newSingleThreadExecutor()

    suspend fun capture(
        facing: Facing,
        maxEdge: Int = 768,
        lowLight: Boolean = false,
    ): Bitmap {
        val appCtx = context.applicationContext
        val provider = awaitCameraProvider(appCtx)
        val selector = if (facing == Facing.FRONT) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        val owner = OneShotLifecycleOwner()
        var usedScreenFill = false

        return withContext(Dispatchers.Main) {
            owner.start()
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                provider.unbindAll()
                val camera = provider.bindToLifecycle(owner, selector, imageCapture)
                val hasFlash = camera.cameraInfo.hasFlashUnit()

                if (lowLight) {
                    if (hasFlash) {
                        imageCapture.flashMode = ImageCapture.FLASH_MODE_ON
                    } else if (facing == Facing.FRONT) {
                        imageCapture.flashMode = ImageCapture.FLASH_MODE_OFF
                        onScreenFillWhite?.invoke(true)
                        usedScreenFill = true
                    }
                } else {
                    imageCapture.flashMode = ImageCapture.FLASH_MODE_OFF
                }

                // Allow 600 ms for AE/AWB convergence on Redmi Note 5 Pro LEGACY HAL3.
                delay(EXPOSURE_WARMUP_MS)

                try {
                    takeBitmap(imageCapture, maxEdge)
                } catch (t: Throwable) {
                    if (lowLight && facing == Facing.FRONT && !usedScreenFill) {
                        imageCapture.flashMode = ImageCapture.FLASH_MODE_OFF
                        onScreenFillWhite?.invoke(true)
                        usedScreenFill = true
                        delay(200L)
                        takeBitmap(imageCapture, maxEdge)
                    } else {
                        throw t
                    }
                }
            } finally {
                if (usedScreenFill) {
                    onScreenFillWhite?.invoke(false)
                }
                runCatching { provider.unbindAll() }
                owner.stop()
            }
        }
    }

    private suspend fun takeBitmap(imageCapture: ImageCapture, maxEdge: Int): Bitmap =
        suspendCancellableCoroutine { cont ->
            imageCapture.takePicture(
                captureExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val raw = imageProxyToBitmap(image)
                            val scaled = scaleDown(raw, maxEdge)
                            if (scaled !== raw) raw.recycle()
                            if (cont.isActive) cont.resume(scaled)
                        } catch (t: Throwable) {
                            if (cont.isActive) cont.resumeWithException(t)
                        } finally {
                            image.close()
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        if (cont.isActive) cont.resumeWithException(exception)
                    }
                },
            )
        }

    private suspend fun awaitCameraProvider(appCtx: Context): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(appCtx)
            future.addListener(
                {
                    runCatching { future.get() }
                        .onSuccess { if (cont.isActive) cont.resume(it) }
                        .onFailure { if (cont.isActive) cont.resumeWithException(it) }
                },
                ContextCompat.getMainExecutor(appCtx),
            )
        }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val buf = image.planes[0].buffer
        val bytes = ByteArray(buf.remaining())
        buf.get(bytes)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("Failed to decode captured JPEG")
        val rot = image.imageInfo.rotationDegrees
        if (rot == 0) return decoded
        val matrix = Matrix().apply { postRotate(rot.toFloat()) }
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }

    private fun scaleDown(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val maxDim = maxOf(bitmap.width, bitmap.height)
        if (maxDim <= maxEdge || maxEdge <= 0) return bitmap
        val ratio = maxEdge.toFloat() / maxDim.toFloat()
        val w = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val h = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    fun shutdown() {
        captureExecutor.shutdown()
    }

    private class OneShotLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle
            get() = registry

        fun start() {
            registry.currentState = Lifecycle.State.CREATED
            registry.currentState = Lifecycle.State.STARTED
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun stop() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    companion object {
        const val EXPOSURE_WARMUP_MS = 600L
    }
}
