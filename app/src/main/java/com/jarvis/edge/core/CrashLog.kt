package com.jarvis.edge.core

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** What went wrong the last time the app crashed, as shown on the home screen. */
data class CrashInfo(val timeMs: Long, val thread: String, val summary: String, val trace: String)

/**
 * Records uncaught crashes to a small file so the next launch can say what happened, instead of the app
 * silently restarting. Only the latest crash is kept; it stays until the user dismisses it.
 */
class CrashLog(private val file: File) {

    fun record(thread: String, error: Throwable, timeMs: Long = System.currentTimeMillis()) {
        runCatching {
            val sw = StringWriter()
            error.printStackTrace(PrintWriter(sw))
            val summary = (error::class.java.simpleName + ": " + (error.message ?: "")).trim().take(300)
            file.parentFile?.mkdirs()
            file.writeText("$timeMs\n$thread\n$summary\n" + sw.toString().take(MAX_TRACE))
        }
    }

    fun latest(): CrashInfo? = runCatching {
        if (!file.isFile) return null
        val lines = file.readText().split('\n')
        if (lines.size < 3) return null
        CrashInfo(lines[0].trim().toLong(), lines[1], lines[2], lines.drop(3).joinToString("\n"))
    }.getOrNull()

    fun dismiss() {
        runCatching { file.delete() }
    }

    companion object {
        const val FILE_NAME = "last_crash.txt"
        private const val MAX_TRACE = 16_000

        /** Chains onto the existing handler, so the system still restarts the app the usual way. */
        fun install(file: File) {
            val log = CrashLog(file)
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                log.record(t.name, e)
                previous?.uncaughtException(t, e)
            }
        }
    }
}
