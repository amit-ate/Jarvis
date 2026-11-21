package com.jarvis.edge.brain

import java.io.File
import java.security.MessageDigest

enum class KvRestoreResult { RESTORED, MISSING, REJECTED }

/** Saves and restores the model's KV cache, keyed by what shaped it (model, prompt, adapters). */
class KvStateManager(private val kvDir: File) {

    fun key(modelFile: File, systemPrompt: String, adapterId: String?): String {
        val raw = "${modelFile.name}|${modelFile.length()}|${modelFile.lastModified()}|" +
            "${systemPrompt.hashCode()}|${adapterId ?: "none"}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    fun fileFor(key: String): File = File(kvDir, "kv-$key.bin")

    fun save(engine: LlmEngine, key: String): Boolean {
        kvDir.mkdirs()
        return engine.saveState(fileFor(key))
    }

    /** A file the engine rejects is deleted so it is never re-read; the caller should journal REJECTED. */
    fun restore(engine: LlmEngine, key: String): KvRestoreResult {
        val f = fileFor(key)
        if (!f.isFile) return KvRestoreResult.MISSING
        if (engine.loadState(f)) return KvRestoreResult.RESTORED
        f.delete()
        return KvRestoreResult.REJECTED
    }

    /** Deletes every kv-*.bin except the one for [key]; returns how many were removed. */
    fun pruneExcept(key: String): Int {
        val keep = fileFor(key).name
        val stale = kvDir.listFiles { f -> f.name.startsWith("kv-") && f.name.endsWith(".bin") && f.name != keep }
            ?: return 0
        return stale.count { it.delete() }
    }
}
