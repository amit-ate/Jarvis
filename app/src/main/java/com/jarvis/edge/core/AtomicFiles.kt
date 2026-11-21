package com.jarvis.edge.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Crash-safe file writes: data goes to a sibling `<name>.tmp`, is fsynced, then renamed
 * over the target. A kill at any point leaves either the old or the new file, never a torn one.
 */
object AtomicFiles {
    private const val TMP_SUFFIX = ".tmp"

    fun write(target: File, bytes: ByteArray) {
        target.absoluteFile.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + TMP_SUFFIX)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                throw IOException("Failed to rename ${tmp.path} to ${target.path}")
            }
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    fun writeText(target: File, text: String) {
        write(target, text.toByteArray(Charsets.UTF_8))
    }

    /** Deletes leftover `*.tmp` files in [dir] from interrupted writes. Returns how many were removed. */
    fun cleanupTemps(dir: File): Int {
        val stale = dir.listFiles { f -> f.isFile && f.name.endsWith(TMP_SUFFIX) } ?: return 0
        return stale.count { it.delete() }
    }
}
