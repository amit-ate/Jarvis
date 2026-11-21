package com.jarvis.edge.core

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One saved recording: [wav] inside the store's folder and the words spoken in it. */
data class VoiceSample(
    val wav: String,
    val text: String,
    /** "enroll": the user read [text] aloud on the voice-training screen, so the label is exact. "turn": a request
     *  spoken to Jarvis, labelled with what the recogniser heard (may be wrong). */
    val source: String,
    val timeMs: Long,
)

/**
 * Voice recordings kept for training the speech recogniser on the user's own voice: 16 kHz mono 16-bit WAV files
 * plus a manifest.jsonl with one line per file. Laptop tools (tools/voice-train) pull the folder, fine-tune and
 * evaluate the recogniser, and push the result back as models/asr-custom.
 */
class VoiceSampleStore(
    val dir: File,
    /** Turn recordings beyond this are dropped oldest first; read-aloud (enroll) recordings are always kept. */
    private val maxTurnSamples: Int = 1000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val manifest = File(dir, MANIFEST)

    fun save(samples: FloatArray, text: String, source: String): VoiceSample? {
        val label = text.trim()
        if (samples.isEmpty() || label.isEmpty()) return null
        synchronized(lock) {
            return runCatching {
                dir.mkdirs()
                val now = clock()
                var name = "${source}_$now.wav"
                var n = 1
                while (File(dir, name).exists()) name = "${source}_${now}_${n++}.wav"
                writeWav(File(dir, name), samples)
                val sample = VoiceSample(name, label, source, now)
                manifest.appendText(toJson(sample) + "\n")
                if (source == SOURCE_TURN) prune()
                sample
            }.getOrNull()
        }
    }

    fun list(): List<VoiceSample> = synchronized(lock) {
        if (!manifest.isFile) return emptyList()
        runCatching { manifest.readLines().mapNotNull(::parse) }.getOrDefault(emptyList())
            .filter { File(dir, it.wav).isFile }
    }

    fun count(source: String): Int = list().count { it.source == source }

    /** The read-aloud prompts already recorded, so the training screen can continue where the user stopped. */
    fun enrolledTexts(): Set<String> = list().filter { it.source == SOURCE_ENROLL }.mapTo(HashSet()) { it.text }

    private fun prune() {
        val all = list()
        val turns = all.filter { it.source == SOURCE_TURN }
        if (turns.size <= maxTurnSamples) return
        val drop = turns.take(turns.size - maxTurnSamples).mapTo(HashSet()) { it.wav }
        drop.forEach { File(dir, it).delete() }
        val tmp = File(dir, "$MANIFEST.tmp")
        tmp.writeText(all.filterNot { it.wav in drop }.joinToString("") { toJson(it) + "\n" })
        if (!tmp.renameTo(manifest)) {
            manifest.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun toJson(s: VoiceSample): String = JSONObject()
        .put("wav", s.wav).put("text", s.text).put("source", s.source).put("t", s.timeMs)
        .toString()

    private fun parse(line: String): VoiceSample? {
        if (line.isBlank()) return null
        return runCatching {
            val o = JSONObject(line)
            VoiceSample(o.getString("wav"), o.getString("text"), o.getString("source"), o.optLong("t"))
        }.getOrNull()
    }

    companion object {
        const val MANIFEST = "manifest.jsonl"
        const val SOURCE_ENROLL = "enroll"
        const val SOURCE_TURN = "turn"
        const val SAMPLE_RATE = 16_000

        fun writeWav(file: File, samples: FloatArray, sampleRate: Int = SAMPLE_RATE) {
            val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) data.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + samples.size * 2); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
                putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(samples.size * 2)
            }
            RandomAccessFile(file, "rw").use { f ->
                f.setLength(0)
                f.write(header.array())
                f.write(data.array())
            }
        }

        /** Reads a file written by [writeWav] back to floats in [-1, 1]. */
        fun readWav(file: File): FloatArray {
            val bytes = file.readBytes()
            val buf = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray((bytes.size - 44) / 2) { buf.short / 32768f }
        }
    }
}

/** Sentences the voice-training screen asks the user to read: the kind of requests Jarvis hears, with varied sounds. */
object VoicePrompts {
    val SENTENCES = listOf(
        "Jarvis, what time is it?",
        "What is the capital of France?",
        "And what about Germany?",
        "Who is the prime minister of India?",
        "Tell me a joke.",
        "What's my battery level?",
        "Set a timer for five minutes.",
        "Turn on the flashlight.",
        "What's the weather like today?",
        "Remind me to call my mother at six.",
        "How far is the moon from the earth?",
        "Who wrote Hamlet?",
        "What is the square root of one hundred and forty four?",
        "Open the camera.",
        "Play some music.",
        "What do you know about me?",
        "My favourite colour is blue.",
        "Thank you, that's all for now.",
        "Translate good morning into Hindi.",
        "How many legs does a spider have?",
        "Explain photosynthesis in simple words.",
        "Turn off the wifi.",
        "Call my brother.",
        "What's the date today?",
        "Who invented the telephone?",
        "How do I make a cup of tea?",
        "Read my last note.",
        "Take a note that I parked on level two.",
        "What is the boiling point of water?",
        "Convert ten miles to kilometres.",
        "Who won the cricket world cup?",
        "Is it going to rain tomorrow?",
        "Tell me something interesting.",
        "What's the population of Japan?",
        "Set an alarm for seven in the morning.",
        "How old is the Taj Mahal?",
        "Never mind.",
        "Good night, Jarvis.",
        "What's the latest news in technology?",
        "How much is twenty five times four?",
        "Describe what you see.",
        "Lower the volume a little.",
        "What's on my schedule tomorrow?",
        "Send a message to Rahul saying I'm running late.",
        "Why is the sky blue?",
        "Which planet is the largest in our solar system?",
        "Spell the word necessary.",
        "The quick brown fox jumps over the lazy dog.",
        "She sells seashells by the seashore.",
        "Please speak a bit slower.",
    )
}
