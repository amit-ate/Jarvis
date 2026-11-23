package com.jarvis.edge.setup

enum class ItemPhase { QUEUED, DOWNLOADING, VERIFYING, UNPACKING, DONE, FAILED }

/** One item of the current setup run. [bytes] is what has been downloaded so far; [reason] is set when FAILED. */
data class ItemProgress(
    val id: String,
    val label: String,
    val required: Boolean,
    val phase: ItemPhase,
    val bytes: Long,
    val size: Long,
    val reason: String? = null,
)

enum class NetState { ALLOWED, WAITING_FOR_WIFI, OFFLINE }

/** What the setup screen and the notification show. [blockedBy] says why the run is waiting, if it is. */
data class SetupProgress(
    val items: List<ItemProgress>,
    val net: NetState,
    val wifiOnly: Boolean,
    val bytesPerSec: Long,
    val finished: Boolean,
    val canStartWithout: Boolean,
    val blockedBy: String? = null,
) {
    val totalBytes: Long get() = items.sumOf { it.size }

    val doneBytes: Long get() = items.sumOf { if (it.phase == ItemPhase.DONE) it.size else it.bytes }

    val fraction: Float get() = if (totalBytes == 0L) 0f else (doneBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)

    /** Seconds left at the current speed, or null while the speed is unknown (zero). */
    val etaSec: Long? get() = if (bytesPerSec <= 0L) null else ((totalBytes - doneBytes) / bytesPerSec).coerceAtLeast(0L)
}
