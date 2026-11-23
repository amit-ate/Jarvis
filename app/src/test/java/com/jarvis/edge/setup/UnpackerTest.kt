package com.jarvis.edge.setup

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class UnpackerTest {

    private fun item(
        dest: String,
        mustExist: List<String>,
        drop: List<String> = emptyList(),
        overlay: List<String> = emptyList(),
    ) = ManifestItem(
        id = dest, label = dest, required = true, url = "", size = 0L, sha256 = "",
        kind = ItemKind.TAR_BZ2, dest = dest, unpackedSize = 0L,
        mustExist = mustExist, drop = drop, overlay = overlay,
    )

    /** Writes a tar.bz2 at [dir]/archive.part holding [files] (path to bytes). */
    private fun archive(dir: File, files: Map<String, ByteArray>, symlinks: Map<String, String> = emptyMap()): File {
        val file = File(dir, "archive.part")
        TarArchiveOutputStream(BZip2CompressorOutputStream(FileOutputStream(file))).use { tar ->
            for ((name, bytes) in files) {
                val entry = TarArchiveEntry(name)
                entry.size = bytes.size.toLong()
                tar.putArchiveEntry(entry)
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
            for ((name, target) in symlinks) {
                tar.putArchiveEntry(TarArchiveEntry(name, TarConstants.LF_SYMLINK).also { it.linkName = target })
                tar.closeArchiveEntry()
            }
        }
        return file
    }

    private fun bytes(text: String) = text.toByteArray()

    @Test
    fun `strips the top folder and installs atomically`(@TempDir root: File) {
        val archive = archive(root, mapOf(
            "top/a.onnx" to bytes("model"),
            "top/sub/b.txt" to bytes("text"),
        ))

        Unpacker.installArchive(archive, root, item("asr-live", listOf("asr-live/a.onnx")), { null })

        assertEquals("model", File(root, "asr-live/a.onnx").readText())
        assertEquals("text", File(root, "asr-live/sub/b.txt").readText())
        assertFalse(File(root, "asr-live.tmp").exists())
        assertFalse(archive.exists())
    }

    @Test
    fun `drop removes espeak-ng-data from a voice`(@TempDir root: File) {
        val archive = archive(root, mapOf(
            "top/model.onnx" to bytes("voice"),
            "top/espeak-ng-data/phontab" to bytes("phon"),
        ))

        Unpacker.installArchive(
            archive, root,
            item("tts", listOf("tts/model.onnx"), drop = listOf("tts/espeak-ng-data")),
            { null },
        )

        assertTrue(File(root, "tts/model.onnx").isFile)
        assertFalse(File(root, "tts/espeak-ng-data").exists())
    }

    @Test
    fun `overlay replaces keywords txt with the bundled copy`(@TempDir root: File) {
        val archive = archive(root, mapOf("top/keywords.txt" to bytes("old"), "top/tokens.txt" to bytes("t")))

        Unpacker.installArchive(
            archive, root,
            item("kws", listOf("kws/keywords.txt"), overlay = listOf("kws/keywords.txt")),
        ) { path -> if (path == "kws/keywords.txt") bytes("bundled") else null }

        assertEquals("bundled", File(root, "kws/keywords.txt").readText())
        assertEquals("t", File(root, "kws/tokens.txt").readText())
    }

    @Test
    fun `leftover tmp folder from a crash is replaced`(@TempDir root: File) {
        File(root, "asr-live.tmp/stale.txt").apply { parentFile?.mkdirs(); writeText("crash") }
        val archive = archive(root, mapOf("top/a.onnx" to bytes("model")))

        Unpacker.installArchive(archive, root, item("asr-live", listOf("asr-live/a.onnx")), { null })

        assertFalse(File(root, "asr-live/stale.txt").exists())
        assertEquals("model", File(root, "asr-live/a.onnx").readText())
        assertFalse(File(root, "asr-live.tmp").exists())
    }

    @Test
    fun `archive missing a mustExist file leaves the destination untouched`(@TempDir root: File) {
        File(root, "asr-live/old.txt").apply { parentFile?.mkdirs(); writeText("kept") }
        val archive = archive(root, mapOf("top/a.onnx" to bytes("model")))

        val error = assertThrows(IOException::class.java) {
            Unpacker.installArchive(archive, root, item("asr-live", listOf("asr-live/b.onnx")), { null })
        }

        assertEquals("Archive missing asr-live/b.onnx", error.message)
        assertEquals("kept", File(root, "asr-live/old.txt").readText())
        assertFalse(File(root, "asr-live.tmp").exists())
    }

    @Test
    fun `entry with dot-dot is rejected`(@TempDir root: File) {
        val archive = archive(root, mapOf("top/../escape.txt" to bytes("x")))

        val error = assertThrows(IOException::class.java) {
            Unpacker.installArchive(archive, root, item("asr-live", listOf("asr-live/escape.txt")), { null })
        }

        assertEquals("Unsafe path in archive", error.message)
        assertFalse(File(root, "escape.txt").exists())
        assertFalse(File(root, "asr-live.tmp").exists())
    }

    @Test
    fun `symbolic links in the archive are skipped`(@TempDir root: File) {
        val archive = archive(
            root,
            mapOf("top/a.onnx" to bytes("model")),
            symlinks = mapOf("top/link.onnx" to "/etc/passwd"),
        )

        Unpacker.installArchive(archive, root, item("asr-live", listOf("asr-live/a.onnx")), { null })

        assertFalse(File(root, "asr-live/link.onnx").exists())
        assertTrue(File(root, "asr-live/a.onnx").isFile)
    }

    @Test
    fun `installFile moves part to dest`(@TempDir root: File) {
        val part = File(root, "llm/model.gguf.part").apply { parentFile?.mkdirs(); writeText("weights") }
        val dest = File(root, "llm/model.gguf")

        Unpacker.installFile(part, dest)

        assertEquals("weights", dest.readText())
        assertFalse(part.exists())
    }
}
