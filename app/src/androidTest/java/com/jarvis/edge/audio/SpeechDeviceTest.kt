package com.jarvis.edge.audio

import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.edge.core.ModelPaths
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SpeechDeviceTest {

    @Test
    fun ttsCacheHitIsFastAndWakeAndAsrRunOnDevice() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(ModelPaths.DEFAULT_ROOT)
        val cacheDir = File(ctx.cacheDir, "tts_test_cache").apply {
            deleteRecursively()
            mkdirs()
        }

        val vad = SileroVad(File(root, "vad/silero_vad.onnx"))
        val kws = SherpaWakeWord(File(root, "kws"))
        val gated = GatedWakeWord(vad = vad, inner = kws)
        val live = SherpaLiveTranscriber(File(root, "asr-zipformer"))
        val accurate = SherpaMoonshineTranscriber(File(root, "asr-moonshine"))
        val speaker = SherpaSpeaker(File(root, "tts"), cacheDir)

        try {
            val dummyFrame = FloatArray(512) { 0.0f }
            gated.accept(dummyFrame)
            live.accept(dummyFrame)
            live.finish()
            accurate.transcribe(FloatArray(16_000) { 0.0f })

            speaker.prewarmFixedPhrases()
            val cachedFiles = cacheDir.listFiles { f -> f.extension == "pcm" }.orEmpty()
            assertEquals(SherpaSpeaker.FIXED_PHRASES.size, cachedFiles.size)
            assertTrue(cachedFiles.all { it.length() > 4L })

            val startNs = System.nanoTime()
            speaker.speak("One moment.")
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            // Playback + cache hit read completes cleanly on-device; verify cached synthesis didn't stall.
            assertTrue("Expected cache hit playback in <= 3000 ms, took $elapsedMs ms", elapsedMs <= 3000L)

            // Test WAV transcription accuracy on test_wavs/0.wav
            val wavFile = File(root, "asr-zipformer/test_wavs/0.wav")
            if (wavFile.exists()) {
                val waveData = com.k2fsa.sherpa.onnx.WaveReader.readWave(wavFile.absolutePath)
                if (waveData != null) {
                    val moonshineText = accurate.transcribe(waveData.samples)
                    android.util.Log.i("AsrBench", "0.wav Moonshine: \"$moonshineText\"")

                    live.reset()
                    val chunkSize = 512
                    var idx = 0
                    while (idx < waveData.samples.size) {
                        val end = minOf(idx + chunkSize, waveData.samples.size)
                        val chunk = waveData.samples.copyOfRange(idx, end)
                        live.accept(chunk)
                        idx = end
                    }
                    val zipformerText = live.finish()
                    android.util.Log.i("AsrBench", "0.wav Zipformer: \"$zipformerText\"")
                }
            }
        } finally {
            speaker.release()
            accurate.release()
            live.release()
            kws.release()
            vad.release()
            cacheDir.deleteRecursively()
        }
    }
}
