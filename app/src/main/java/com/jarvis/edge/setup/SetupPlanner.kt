package com.jarvis.edge.setup

import com.jarvis.edge.core.AtomicFiles
import com.jarvis.edge.core.ModelPaths
import java.io.File

/**
 * What a first setup left behind in the model root. No marker means an ordinary install; the Redmi and Pad have none.
 * [RUNNING] while the first setup runs; [EXTRAS_PENDING] after "Start without it".
 */
enum class SetupMarker { NONE, RUNNING, EXTRAS_PENDING }

/**
 * Decides at start whether anything must be fetched, and which manifest items are still missing. "Present" is
 * exists-and-non-empty (see [ModelPaths.isPresent]); the sizes of existing files are never compared with the manifest.
 */
class SetupPlanner(val root: File, val manifest: ModelManifest) {
    private val markerFile = File(root, MARKER_FILE)

    fun marker(): SetupMarker = when (runCatching { markerFile.readText().trim() }.getOrNull()) {
        "running" -> SetupMarker.RUNNING
        "extras-pending" -> SetupMarker.EXTRAS_PENDING
        else -> SetupMarker.NONE
    }

    fun writeMarker(marker: SetupMarker) {
        val text = when (marker) {
            SetupMarker.NONE -> {
                markerFile.delete()
                return
            }
            SetupMarker.RUNNING -> "running"
            SetupMarker.EXTRAS_PENDING -> "extras-pending"
        }
        AtomicFiles.writeText(markerFile, text)
    }

    /** True when every [ManifestItem.mustExist] path is present. */
    fun isPresent(item: ManifestItem): Boolean = item.mustExist.all { ModelPaths.isPresent(File(root, it)) }

    /** True when a first setup was interrupted, or a must-have speech model or the chat model is missing. */
    fun needsSetup(): Boolean {
        if (marker() == SetupMarker.RUNNING) return true
        val paths = ModelPaths(root, ModelPaths.resolveLlmFileName(root))
        if (paths.speechRequired().any { !ModelPaths.isPresent(File(root, it)) }) return true
        return !ModelPaths.isPresent(File(root, paths.llmRelative()))
    }

    /** Manifest items that are not present, in manifest order. */
    fun pending(): List<ManifestItem> = manifest.items.filterNot(::isPresent)

    companion object {
        const val MARKER_FILE = ".setup-state"
    }
}
