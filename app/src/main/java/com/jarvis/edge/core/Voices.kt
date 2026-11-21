package com.jarvis.edge.core

import java.io.File

/** A Piper (VITS) voice: [model] and its tokens.txt under the models root; [sid] picks the speaker of a multi-speaker model. */
data class TtsVoice(val id: String, val label: String, val model: String, val sid: Int) {
    fun modelFile(root: File) = File(root, model)
    fun tokensFile(root: File) = File(modelFile(root).parentFile, "tokens.txt")
}

/**
 * Voices Jarvis can speak with. Indian-accented ones come from multi-speaker English models: CMU Arctic speaker "ksp"
 * (Indian English male) and L2-Arctic "TNI" (Hindi-L1 female). Played back through the recogniser, ksp was the most
 * intelligible of all (7% words misheard vs 15% for the original British voice).
 */
object Voices {
    const val DEFAULT_ID = "indian-male"

    val ALL = listOf(
        TtsVoice("indian-male", "Indian male", "tts-voices/arctic/en_US-arctic-medium.onnx", sid = 3),
        TtsVoice("indian-female", "Indian female", "tts-voices/l2arctic/en_US-l2arctic-medium.onnx", sid = 9),
        TtsVoice("south-asian-male", "South Asian male", "tts-voices/kusal/en_US-kusal-medium.onnx", sid = 0),
        TtsVoice("british-male", "British male", "tts/en_GB-alan-medium.onnx", sid = 0),
    )

    fun available(root: File): List<TtsVoice> =
        ALL.filter { it.modelFile(root).let { f -> f.isFile && f.length() > 0 } && it.tokensFile(root).isFile }

    /** The chosen voice if its files are there, else the default, else any installed voice. */
    fun resolve(root: File, preferredId: String?): TtsVoice? {
        val have = available(root)
        return have.firstOrNull { it.id == preferredId } ?: have.firstOrNull { it.id == DEFAULT_ID } ?: have.firstOrNull()
    }

    const val PREVIEW_TEXT = "Hello, I'm Jarvis. Your timer is set for five minutes, and it will be sunny in Boston tomorrow."
}
