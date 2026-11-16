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
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, 16_000)
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
    modelDir: File,
    numThreads: Int = 4,
    private val onInitThread: () -> Unit = {},
) : LiveTranscriber {

    private val recognizer: OnlineRecognizer
    private var stream: OnlineStream
    private var threadPrepared = false

    init {
        val transducer = OnlineTransducerModelConfig(
            encoder = File(modelDir, "encoder-epoch-99-avg-1.int8.onnx").absolutePath,
            decoder = File(modelDir, "decoder-epoch-99-avg-1.int8.onnx").absolutePath,
            joiner = File(modelDir, "joiner-epoch-99-avg-1.int8.onnx").absolutePath,
        )
        val modelConfig = OnlineModelConfig(
            transducer = transducer,
            tokens = File(modelDir, "tokens.txt").absolutePath,
            numThreads = numThreads,
            debug = false,
            provider = "cpu",
            modelType = "zipformer",
        )
        val endpointConfig = EndpointConfig(
            rule1 = EndpointRule(false, 2.4f, 0.0f),
            rule2 = EndpointRule(true, 1.4f, 0.0f),
            rule3 = EndpointRule(false, 0.0f, 20.0f),
        )
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80, dither = 0.0f),
            modelConfig = modelConfig,
            endpointConfig = endpointConfig,
            enableEndpoint = false,
            decodingMethod = "greedy_search",
        )
        recognizer = OnlineRecognizer(null, config)
        stream = recognizer.createStream("")
    }

    override fun accept(frame: FloatArray): String {
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

    override fun finish(): String {
        stream.inputFinished()
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream)
        }
        val text = recognizer.getResult(stream).text.trim()
        reset()
        return text
    }

    override fun reset() {
        recognizer.reset(stream)
    }

    fun release() {
        stream.release()
        recognizer.release()
    }
}
