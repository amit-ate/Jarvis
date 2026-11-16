package com.jarvis.edge.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

interface WakeWord {
    fun accept(frame: FloatArray): Boolean
    fun reset()
}

interface Vad {
    fun isSpeech(frame: FloatArray): Boolean
    fun reset() {}
}

class GatedWakeWord(
    private val vad: Vad,
    private val inner: WakeWord,
    private val preRollMs: Int = 500,
    private val hangoverMs: Int = 1000,
) : WakeWord {

    private val preRollCapacity = ((preRollMs * SAMPLES_PER_MS) + FRAME_SAMPLES - 1) / FRAME_SAMPLES
    private val hangoverLimit = ((hangoverMs * SAMPLES_PER_MS) + FRAME_SAMPLES - 1) / FRAME_SAMPLES
    private val preRoll = ArrayDeque<FloatArray>(preRollCapacity + 1)
    private var active = false
    private var silentFrames = 0

    override fun accept(frame: FloatArray): Boolean {
        val speech = vad.isSpeech(frame)
        if (!active) {
            if (!speech) {
                if (preRollCapacity > 0) {
                    if (preRoll.size >= preRollCapacity) {
                        preRoll.removeFirst()
                    }
                    preRoll.addLast(frame.copyOf())
                }
                return false
            }
            active = true
            silentFrames = 0
            while (preRoll.isNotEmpty()) {
                val buffered = preRoll.removeFirst()
                if (inner.accept(buffered)) {
                    reset()
                    return true
                }
            }
            if (inner.accept(frame)) {
                reset()
                return true
            }
            return false
        }

        if (speech) {
            silentFrames = 0
        } else {
            silentFrames++
        }

        val triggered = inner.accept(frame)
        if (triggered) {
            reset()
            return true
        }

        if (!speech && silentFrames >= hangoverLimit) {
            active = false
            silentFrames = 0
            inner.reset()
        }
        return false
    }

    override fun reset() {
        active = false
        silentFrames = 0
        preRoll.clear()
        vad.reset()
        inner.reset()
    }

    companion object {
        private const val SAMPLES_PER_MS = 16
        private const val FRAME_SAMPLES = 512
    }
}

class SherpaWakeWord(
    kwsDir: File,
    keywordsThreshold: Float = 0.25f,
    keywordsScore: Float = 1.5f,
    numThreads: Int = 2,
    private val onInitThread: () -> Unit = {},
) : WakeWord {

    private val spotter: KeywordSpotter
    private var stream: OnlineStream
    private var threadPrepared = false

    init {
        val transducer = OnlineTransducerModelConfig(
            encoder = File(kwsDir, "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx").absolutePath,
            decoder = File(kwsDir, "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx").absolutePath,
            joiner = File(kwsDir, "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx").absolutePath,
        )
        val modelConfig = OnlineModelConfig(
            transducer = transducer,
            tokens = File(kwsDir, "tokens.txt").absolutePath,
            numThreads = numThreads,
            debug = false,
            provider = "cpu",
            modelType = "zipformer2",
        )
        val config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80, dither = 0.0f),
            modelConfig = modelConfig,
            maxActivePaths = 4,
            keywordsFile = File(kwsDir, "keywords.txt").absolutePath,
            keywordsScore = keywordsScore,
            keywordsThreshold = keywordsThreshold,
            numTrailingBlanks = 1,
        )
        spotter = KeywordSpotter(null, config)
        stream = spotter.createStream("")
    }

    override fun accept(frame: FloatArray): Boolean {
        if (!threadPrepared) {
            onInitThread()
            threadPrepared = true
        }
        stream.acceptWaveform(frame, 16_000)
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            val result = spotter.getResult(stream)
            if (result.keyword.isNotBlank()) {
                reset()
                return true
            }
        }
        return false
    }

    override fun reset() {
        spotter.reset(stream)
    }

    fun release() {
        stream.release()
        spotter.release()
    }
}

class Earcon(
    private val suppressMicMs: (Long) -> Unit = {},
) {
    private val sampleRate = 16_000
    private val chimePcm: ShortArray by lazy { buildWakeChime() }

    fun playWake() {
        val durationMs = (chimePcm.size * 1000L) / sampleRate
        suppressMicMs(durationMs + 80L)
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, chimePcm.size * 2))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            track.write(chimePcm, 0, chimePcm.size)
            track.play()
            Thread.sleep(durationMs + 20L)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun buildWakeChime(): ShortArray {
        val totalSamples = (sampleRate * 0.10f).toInt()
        val half = totalSamples / 2
        return ShortArray(totalSamples) { i ->
            val freq = if (i < half) 880.0 else 1320.0
            val env = 0.25 * (1.0 - (i.toDouble() / totalSamples) * 0.4)
            (sin(2.0 * PI * freq * i / sampleRate) * env * Short.MAX_VALUE).toInt().toShort()
        }
    }
}
