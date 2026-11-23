package com.jarvis.edge.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

interface Speaker {
    val isStopped: Boolean get() = false
    suspend fun speak(sentence: String)
    fun stop()
    /** Clears a stale stop request so a tap during a previous turn cannot mute the next one. */
    fun clearStop() {}
}

class SherpaSpeaker(
    private val ttsDir: File,
    private val cacheDir: File,
    voice: com.jarvis.edge.core.TtsVoice? = null,
    private val speed: Float = 1.0f,
    private val numThreads: Int = 2,
) : Speaker {

    private val ttsLock = Any()
    private var tts: OfflineTts
    @Volatile private var voiceId: String = voice?.id ?: "en_GB-alan-medium"
    @Volatile private var sid: Int = voice?.sid ?: 0
    /** The models root (ttsDir's parent): voice paths are relative to it. */
    private val modelsRoot: File get() = ttsDir.parentFile ?: ttsDir
    private val activeTrack = AtomicReference<AudioTrack?>(null)
    @Volatile
    private var stopped = false

    override val isStopped: Boolean
        get() = stopped

    init {
        cacheDir.mkdirs()
        tts = build(voice)
    }

    val currentVoiceId: String get() = voiceId

    private fun build(voice: com.jarvis.edge.core.TtsVoice?): OfflineTts {
        val model = voice?.modelFile(modelsRoot) ?: File(ttsDir, "en_GB-alan-medium.onnx")
        val tokens = voice?.tokensFile(modelsRoot) ?: File(ttsDir, "tokens.txt")
        val vits = OfflineTtsVitsModelConfig(
            model = model.absolutePath,
            tokens = tokens.absolutePath,
            // Every Piper voice uses the same espeak-ng data, shipped once in models/tts.
            dataDir = File(ttsDir, "espeak-ng-data").absolutePath,
        )
        val modelConfig = OfflineTtsModelConfig(
            vits = vits,
            numThreads = numThreads,
            debug = false,
            provider = "cpu",
        )
        return OfflineTts(null, OfflineTtsConfig(model = modelConfig))
    }

    /** Switches to [voice] (loads its model; cached phrases are per voice). */
    fun switchVoice(voice: com.jarvis.edge.core.TtsVoice) {
        if (voice.id == voiceId) return
        val next = build(voice)
        val old = synchronized(ttsLock) {
            val o = tts
            tts = next
            voiceId = voice.id
            sid = voice.sid
            o
        }
        runCatching { old.release() }
    }

    private fun generate(text: String) = synchronized(ttsLock) { tts.generate(text, sid, speed) }

    fun prewarmFixedPhrases() {
        FIXED_PHRASES.forEach { phrase ->
            val file = cacheFile(phrase)
            if (!file.exists()) {
                val audio = generate(phrase)
                writePcmCache(file, audio.sampleRate, audio.samples)
            }
        }
    }

    override suspend fun speak(sentence: String) = withContext(Dispatchers.IO) {
        val clean = sentence.trim()
        if (clean.isEmpty()) return@withContext
        stopped = false
        // Long sentences are spoken in short pieces: the voice engine keeps memory sized to the longest piece it made.
        for (piece in com.jarvis.edge.core.SentenceSplitter.ttsChunks(clean)) {
            if (stopped) break
            speakPiece(piece)
        }
    }

    private fun speakPiece(clean: String) {
        val cached = cacheFile(clean)
        val (sampleRate, pcm) = if (cached.exists()) {
            readPcmCache(cached)
        } else {
            val t0 = System.currentTimeMillis()
            val audio = generate(clean)
            android.util.Log.d("JarvisTts", "voice=$voiceId ${System.currentTimeMillis() - t0} ms for ${audio.samples.size * 1000L / maxOf(1, audio.sampleRate)} ms of speech")
            val shorts = floatToPcm16(audio.samples)
            writePcmCacheShorts(cached, audio.sampleRate, shorts)
            audio.sampleRate to shorts
        }

        if (stopped || pcm.isEmpty()) return
        playBlocking(sampleRate, pcm)
    }

    override fun stop() {
        stopped = true
        activeTrack.getAndSet(null)?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    override fun clearStop() {
        stopped = false
    }

    fun release() {
        stop()
        synchronized(ttsLock) { tts.release() }
    }

    private fun playBlocking(sampleRate: Int, pcm: ShortArray) {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, 4096))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        activeTrack.set(track)
        try {
            track.play()
            var offset = 0
            val chunk = 1024
            while (!stopped && offset < pcm.size) {
                val n = minOf(chunk, pcm.size - offset)
                val written = track.write(pcm, offset, n)
                if (written <= 0) break
                offset += written
            }
            if (!stopped) {
                track.stop()
            }
        } finally {
            activeTrack.compareAndSet(track, null)
            runCatching { track.release() }
        }
    }

    private fun cacheFile(text: String): File {
        val key = "$voiceId|$speed|$text"
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(24)
        return File(cacheDir, "$hash.pcm")
    }

    private fun floatToPcm16(samples: FloatArray): ShortArray = ShortArray(samples.size) { i ->
        val clamped = samples[i].coerceIn(-1f, 1f)
        (clamped * 32767f).toInt().toShort()
    }

    private fun writePcmCache(file: File, sampleRate: Int, samples: FloatArray) {
        writePcmCacheShorts(file, sampleRate, floatToPcm16(samples))
    }

    private fun writePcmCacheShorts(file: File, sampleRate: Int, pcm: ShortArray) {
        val buf = ByteBuffer.allocate(4 + pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(sampleRate)
        for (s in pcm) buf.putShort(s)
        file.writeBytes(buf.array())
    }

    private fun readPcmCache(file: File): Pair<Int, ShortArray> {
        val bytes = file.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val sampleRate = buf.getInt()
        val pcm = ShortArray((bytes.size - 4) / 2) { buf.getShort() }
        return sampleRate to pcm
    }

    companion object {
        val FIXED_PHRASES = listOf(
            "Sorry, I didn't catch that.",
            "Let me take a look.",
            "Your timer is done.",
            "One moment.",
            "I can't do that yet.",
        )
    }
}
