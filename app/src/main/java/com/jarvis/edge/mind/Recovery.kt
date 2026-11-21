package com.jarvis.edge.mind

import com.jarvis.edge.core.AtomicFiles
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecoveryReport(
    val unclean: Boolean,
    val lastAliveMs: Long?,
    val interruptedEpisodes: Int,
    val orphansRemoved: Int,
    val tempsRemoved: Int,
)

/** Startup recovery: detects an unclean shutdown, removes leftovers, and journals what happened. */
class Recovery(
    private val repo: MindRepository,
    private val adaptersDir: File,
    private val kvDir: File,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    fun onStart(): RecoveryReport {
        val unclean = repo.getMeta(KEY_CLEAN) == "0"
        val previousStart = repo.getMeta(KEY_LAST_START)?.toLongOrNull() ?: 0L
        val now = clockMs()
        repo.setMeta(KEY_CLEAN, "0")
        repo.setMeta(KEY_LAST_START, now.toString())

        val raw = repo.getMeta(KEY_WORKING_STATE)
        val state = raw?.takeIf { it.isNotEmpty() }?.let { WorkingStateCodec.decode(it) }
        if (!raw.isNullOrEmpty() && state == null) {
            repo.recordRecoveryJournal("Discarded a corrupted working-memory snapshot.")
            repo.setMeta(KEY_WORKING_STATE, "")
        }

        val temps = AtomicFiles.cleanupTemps(adaptersDir) + AtomicFiles.cleanupTemps(kvDir)
        val orphans = cleanOrphanAdapters()
        val interrupted = if (unclean) repo.listInterruptedEpisodes(previousStart).size else 0

        if (unclean) {
            val at = state?.savedAtMs
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at ?: previousStart))
            val suffix = if (interrupted > 0) " ($interrupted unfinished replies)" else ""
            val body = if (state != null) {
                "restored my conversation and learning progress."
            } else {
                "my learning is safe but I couldn't restore the conversation."
            }
            repo.recordRecoveryJournal("I was shut down unexpectedly at $time; $body$suffix")
        }
        return RecoveryReport(unclean, state?.savedAtMs, interrupted, orphans, temps)
    }

    fun onCleanStop() = repo.setMeta(KEY_CLEAN, "1")

    fun cleanOrphanAdapters(): Int {
        val referenced = repo.listModelVersions().mapNotNull { v ->
            v.adapterPath?.let { p ->
                val f = File(p)
                (if (f.isAbsolute) f else File(adaptersDir, p)).absolutePath
            }
        }.toSet()
        val files = adaptersDir.listFiles { f -> f.isFile && ORPHAN_NAME.matches(f.name) } ?: return 0
        return files.count { it.absolutePath !in referenced && it.delete() }
    }

    private companion object {
        const val KEY_CLEAN = "clean_shutdown"
        const val KEY_LAST_START = "last_start_ms"
        const val KEY_WORKING_STATE = "working_state"
        val ORPHAN_NAME = Regex("brain_v\\d+\\.jvec")
    }
}
