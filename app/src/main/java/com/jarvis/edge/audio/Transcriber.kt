package com.jarvis.edge.audio

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

interface Transcriber {
    fun transcribe(samples: FloatArray): String
}

interface LiveTranscriber {
    fun accept(frame: FloatArray): String
    fun finish(): String
    fun reset()
}

private fun pickOnnx(dir: File, base: String): File {
    val fp32 = File(dir, "$base.onnx")
    if (fp32.exists()) return fp32
    val int8 = File(dir, "$base.int8.onnx")
    if (int8.exists()) return int8
    return fp32
}

class SherpaMoonshineTranscriber(
    modelDir: File,
    numThreads: Int = 4,
    private val onInitThread: () -> Unit = {},
) : Transcriber {

    private val recognizer: OfflineRecognizer
    private var threadPrepared = false

    init {
        val moonshine = OfflineMoonshineModelConfig(
            preprocessor = File(modelDir, "preprocess.onnx").absolutePath,
            encoder = File(modelDir, "encode.int8.onnx").absolutePath,
            uncachedDecoder = File(modelDir, "uncached_decode.int8.onnx").absolutePath,
            cachedDecoder = File(modelDir, "cached_decode.int8.onnx").absolutePath,
        )
        val modelConfig = OfflineModelConfig(
            moonshine = moonshine,
            tokens = File(modelDir, "tokens.txt").absolutePath,
            numThreads = numThreads,
            debug = false,
            provider = "cpu",
        )
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80, dither = 0.0f),
            modelConfig = modelConfig,
            decodingMethod = "greedy_search",
        )
        recognizer = OfflineRecognizer(null, config)
    }

    override fun transcribe(samples: FloatArray): String {
        if (!threadPrepared) {
            onInitThread()
            threadPrepared = true
        }
        val normalized = samples.copyOf()
        SimdAudio.normalizeUtterance(normalized, targetPeak = 0.85f, maxGain = 4.0f)
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(normalized, 16_000)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    fun release() {
        recognizer.release()
    }
}

class SherpaLiveTranscriber(
    private val modelDir: File,
    private val numThreads: Int = 4,
    private val onInitThread: () -> Unit = {},
    hotwordsFile: File? = null,
    private val hotwordsScore: Float = 2.0f,
) : LiveTranscriber {

    private val lock = Any()
    private var recognizer: OnlineRecognizer
    private var stream: OnlineStream
    private var threadPrepared = false
    private var released = false

    init {
        recognizer = buildRecognizer(hotwordsFile)
        stream = recognizer.createStream("")
    }

    private fun buildRecognizer(hotwordsFile: File?): OnlineRecognizer {
        val transducer = OnlineTransducerModelConfig(
            encoder = pickOnnx(modelDir, "encoder-epoch-99-avg-1").absolutePath,
            decoder = pickOnnx(modelDir, "decoder-epoch-99-avg-1").absolutePath,
            joiner = pickOnnx(modelDir, "joiner-epoch-99-avg-1").absolutePath,
        )
        // A hotwords file is only safe with modeling_unit=bpe and a bpe.vocab: otherwise sherpa-onnx
        // calls exit() (not catchable) while encoding them, killing the whole service.
        val vocabFile = hotwordsFile?.let { File(it.parentFile ?: modelDir, "zipformer_bpe.vocab") }
        val useHotwords = hotwordsFile?.isFile == true && hotwordsFile.length() > 0L && vocabFile != null &&
            BpeVocab.writeFromTokens(File(modelDir, "tokens.txt"), vocabFile)
        val modelConfig = OnlineModelConfig(
            transducer = transducer,
            tokens = File(modelDir, "tokens.txt").absolutePath,
            numThreads = numThreads,
            debug = false,
            provider = "cpu",
            modelType = "zipformer",
            modelingUnit = if (useHotwords) "bpe" else "",
            bpeVocab = if (useHotwords) vocabFile!!.absolutePath else "",
        )
        val endpointConfig = EndpointConfig(
            rule1 = EndpointRule(false, 2.4f, 0.0f),
            rule2 = EndpointRule(true, 1.4f, 0.0f),
            rule3 = EndpointRule(false, 0.0f, 20.0f),
        )
        val resolvedHotwords = if (useHotwords) {
            hotwordsFile!!.absolutePath
        } else {
            ""
        }
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80, dither = 0.0f),
            modelConfig = modelConfig,
            endpointConfig = endpointConfig,
            enableEndpoint = false,
            decodingMethod = "modified_beam_search",
            maxActivePaths = 4,
            hotwordsFile = resolvedHotwords,
            hotwordsScore = hotwordsScore,
        )
        return OnlineRecognizer(null, config)
    }

    fun updateHotwords(file: File) {
        val newRecognizer = buildRecognizer(file)
        val newStream = newRecognizer.createStream("")
        synchronized(lock) {
            if (released) {
                runCatching { newStream.release() }
                runCatching { newRecognizer.release() }
                return
            }
            val oldStream = stream
            val oldRecognizer = recognizer
            recognizer = newRecognizer
            stream = newStream
            runCatching { oldStream.release() }
            runCatching { oldRecognizer.release() }
        }
    }

    override fun accept(frame: FloatArray): String {
        synchronized(lock) {
            if (released) return ""
            if (!threadPrepared) {
                onInitThread()
                threadPrepared = true
            }
            stream.acceptWaveform(frame, 16_000)
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream)
            }
            return recognizer.getResult(stream).text.trim()
        }
    }

    override fun finish(): String {
        synchronized(lock) {
            if (released) return ""
            stream.inputFinished()
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream)
            }
            val text = recognizer.getResult(stream).text.trim()
            recognizer.reset(stream)
            return text
        }
    }

    override fun reset() {
        synchronized(lock) {
            if (released) return
            recognizer.reset(stream)
        }
    }

    fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            runCatching { stream.release() }
            runCatching { recognizer.release() }
        }
    }
}
