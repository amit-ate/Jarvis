package com.jarvis.edge.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.ConsumerIrManager
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.edge.core.ModelPaths
import com.jarvis.edge.tools.ir.IrEncoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VisionAndIrDeviceTest {

    @Test
    fun irTransmitsAndOcrReadsRenderedPageAndVlmDescribesScene() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        // 1. Verify ConsumerIrManager transmit works on hardware IR blaster.
        val irMgr = ctx.getSystemService(Context.CONSUMER_IR_SERVICE) as ConsumerIrManager
        assertTrue("Expected ConsumerIrManager IR emitter on whyred", irMgr.hasIrEmitter())
        irMgr.transmit(38_000, IrEncoder.nec(address = 0x04, command = 0x08))

        // 2. Verify OCR recovers >= 90% of words from a printed-page bitmap.
        val ocr = MlKitOcr()
        val expectedWords = listOf("JARVIS", "SMART", "ASSISTANT", "READY", "VOICE", "COMMANDS")
        val textBmp = Bitmap.createBitmap(1080, 320, Bitmap.Config.ARGB_8888)
        val c = Canvas(textBmp)
        c.drawColor(Color.WHITE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 56f
        }
        c.drawText("JARVIS SMART ASSISTANT", 60f, 120f, p)
        c.drawText("READY VOICE COMMANDS", 60f, 220f, p)
        val recognized = try {
            ocr.read(textBmp).uppercase()
        } finally {
            textBmp.recycle()
            ocr.close()
        }
        val hits = expectedWords.count { it in recognized }
        assertTrue(
            "Expected >= 90% word recall in OCR (got $hits/${expectedWords.size}: '$recognized')",
            hits >= expectedWords.size,
        )

        // 3. Verify CameraX rear camera capture works on LEGACY HAL.
        val snapshotter = CameraSnapshot(ctx)
        val photo = try {
            snapshotter.capture(Facing.BACK, maxEdge = 384, lowLight = false)
        } finally {
            snapshotter.shutdown()
        }
        assertTrue(photo.width > 0 && photo.height > 0 && maxOf(photo.width, photo.height) <= 384)

        // 4. Verify SmolVLM-256M describes a frame via mtmd and MemAvailable stays > 800 MB.
        val root = File(ModelPaths.DEFAULT_ROOT)
        val vlm = LlamaMtmdVlm(
            modelFile = File(root, "vlm/SmolVLM-256M-Instruct-Q8_0.gguf"),
            mmprojFile = File(root, "vlm/mmproj-SmolVLM-256M-Instruct-Q8_0.gguf"),
            maxTokens = 48,
        )
        val out = StringBuilder()
        val t0 = System.nanoTime()
        try {
            vlm.describe(photo, "What do you see briefly?") { piece ->
                out.append(piece)
                true
            }
        } finally {
            photo.recycle()
        }
        val vlmSec = (System.nanoTime() - t0) / 1e9
        val answer = out.toString().trim()
        assertTrue("Expected non-empty SmolVLM response (took ${"%.1f".format(vlmSec)}s)", answer.isNotEmpty())
        assertTrue("Expected SmolVLM inference <= 25s, took ${"%.1f".format(vlmSec)}s", vlmSec <= 25.0)

        val memAvailKb = File("/proc/meminfo").readLines()
            .firstOrNull { it.startsWith("MemAvailable:") }
            ?.replace(Regex("[^0-9]"), "")
            ?.toLongOrNull() ?: 0L
        assertTrue("Expected MemAvailable > 800 MB, got ${memAvailKb / 1024} MB", memAvailKb > 800L * 1024L)
    }

    @Test
    fun frontCameraSnapshotCapturesValidBitmap() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val snapshotter = CameraSnapshot(ctx)
        val photo = try {
            snapshotter.capture(Facing.FRONT, maxEdge = 384, lowLight = false)
        } finally {
            snapshotter.shutdown()
        }
        assertTrue("Expected valid front camera bitmap width", photo.width > 0)
        assertTrue("Expected valid front camera bitmap height", photo.height > 0)
        assertTrue("Expected scaled maxEdge <= 384", maxOf(photo.width, photo.height) <= 384)
        photo.recycle()
    }
}
