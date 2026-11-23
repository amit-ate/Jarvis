package com.jarvis.edge.setup

import com.jarvis.edge.core.ModelPaths
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Turns a verified download into an installed item. Nothing reaches `<root>/<dest>` until the whole item checks out:
 * archives unpack into `<dest>.tmp` and are renamed into place once their mustExist files are present.
 */
object Unpacker {

    /**
     * Unpacks the tar.bz2 [archive] (its top folder stripped) into `<root>/<dest>` and deletes [archive]. [bundled]
     * supplies the bytes of the [ManifestItem.overlay] files. Throws [IOException] and leaves an existing
     * `<root>/<dest>` untouched when the archive is unsafe or incomplete.
     */
    fun installArchive(archive: File, root: File, item: ManifestItem, bundled: (String) -> ByteArray?) {
        val dest = File(root, item.dest)
        val tmp = File(root, "${item.dest}.tmp")
        try {
            tmp.deleteRecursively()
            tmp.mkdirs()
            extract(archive, tmp)
            item.drop.forEach { File(tmp, insideItem(item, it)).deleteRecursively() }
            item.overlay.forEach { path ->
                val bytes = bundled(path) ?: throw IOException("Missing bundled file $path")
                File(tmp, insideItem(item, path)).apply { parentFile?.mkdirs(); writeBytes(bytes) }
            }
            item.mustExist.forEach { path ->
                if (!ModelPaths.isPresent(File(tmp, insideItem(item, path)))) throw IOException("Archive missing $path")
            }
        } catch (e: Throwable) {
            tmp.deleteRecursively()
            throw e
        }
        dest.deleteRecursively()
        if (!tmp.renameTo(dest)) throw IOException("Could not move ${tmp.name} to ${dest.name}")
        archive.delete()
    }

    /** Moves a verified single file into place. */
    fun installFile(part: File, dest: File) {
        dest.parentFile?.mkdirs()
        if (!part.renameTo(dest)) throw IOException("Could not move ${part.name} to ${dest.name}")
    }

    private fun extract(archive: File, target: File) {
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive)))).use { tar ->
            generateSequence { tar.nextEntry }.forEach { entry ->
                val relative = stripTopFolder(entry.name) ?: return@forEach
                val out = File(target, relative)
                when {
                    entry.isSymbolicLink || entry.isLink -> Unit
                    entry.isDirectory -> out.mkdirs()
                    entry.isFile -> {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { tar.copyTo(it) }
                    }
                }
            }
        }
    }

    /** The entry path without its top folder, or null for the top folder itself. */
    private fun stripTopFolder(name: String): String? {
        if (name.startsWith("/")) throw IOException("Unsafe path in archive")
        val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) throw IOException("Unsafe path in archive")
        return parts.drop(1).takeIf { it.isNotEmpty() }?.joinToString("/")
    }

    /** A manifest path (root-relative, e.g. "kws/keywords.txt") as a path inside the item's folder. */
    private fun insideItem(item: ManifestItem, path: String): String = path.removePrefix("${item.dest}/")
}
