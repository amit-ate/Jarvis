package com.jarvis.edge.setup

import org.json.JSONArray
import org.json.JSONObject

/** How a manifest item arrives: one file as is, or a tar.bz2 archive unpacked into its folder. */
enum class ItemKind { FILE, TAR_BZ2 }

/**
 * One downloadable model. [dest] is relative to the model root. [mustExist] lists the paths (relative to the root)
 * that prove the item is installed. [drop] names archive entries not to unpack; [overlay] names files written over
 * the unpacked tree.
 */
data class ManifestItem(
    val id: String,
    val label: String,
    val required: Boolean,
    val url: String,
    val size: Long,
    val sha256: String,
    val kind: ItemKind,
    val dest: String,
    val unpackedSize: Long,
    val mustExist: List<String>,
    val drop: List<String>,
    val overlay: List<String>,
)

/** The pinned model list, shipped as assets/models/manifest.json. */
data class ModelManifest(val items: List<ManifestItem>) {
    companion object {
        private val SHA256_HEX = Regex("[0-9a-fA-F]{64}")

        /** Throws [IllegalArgumentException] on an unknown kind, a sha256 that is not 64 hex chars, or an empty mustExist. */
        fun parse(json: String): ModelManifest {
            val items = JSONObject(json).getJSONArray("items")
            return ModelManifest((0 until items.length()).map { parseItem(items.getJSONObject(it)) })
        }

        private fun parseItem(o: JSONObject): ManifestItem {
            val id = o.getString("id")
            val sha = o.getString("sha256")
            require(SHA256_HEX.matches(sha)) { "Bad sha256 for $id: $sha" }
            val mustExist = o.getJSONArray("mustExist").strings()
            require(mustExist.isNotEmpty()) { "Empty mustExist for $id" }
            return ManifestItem(
                id = id,
                label = o.getString("label"),
                required = o.getBoolean("required"),
                url = o.getString("url"),
                size = o.getLong("size"),
                sha256 = sha.lowercase(),
                kind = parseKind(o.getString("kind")),
                dest = o.getString("dest"),
                unpackedSize = o.getLong("unpackedSize"),
                mustExist = mustExist,
                drop = o.optJSONArray("drop")?.strings().orEmpty(),
                overlay = o.optJSONArray("overlay")?.strings().orEmpty(),
            )
        }

        private fun parseKind(raw: String): ItemKind = when (raw) {
            "file" -> ItemKind.FILE
            "tar.bz2" -> ItemKind.TAR_BZ2
            else -> throw IllegalArgumentException("Unknown kind: $raw")
        }

        private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
    }
}
