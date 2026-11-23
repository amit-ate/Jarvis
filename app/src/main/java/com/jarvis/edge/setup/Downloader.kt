package com.jarvis.edge.setup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Outcome of [Downloader.fetch]. [Failed.retryable] says whether trying the same file again can help. */
sealed class FetchResult {
    data class Ok(val file: File) : FetchResult()
    data class Failed(val reason: String, val retryable: Boolean) : FetchResult()
}

/**
 * Downloads one file to [part], resuming a partial download with an HTTP Range request, and checks its SHA-256
 * before reporting success. Progress of an unverified file lives in [part]; only the caller moves a verified file
 * to its final path. A sidecar file next to [part] records the url and size the partial bytes belong to.
 */
class Downloader(
    private val freeBytes: (File) -> Long = { it.usableSpace },
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val openSink: (File, Boolean) -> OutputStream = { file, append -> FileOutputStream(file, append) },
) {

    suspend fun fetch(url: String, size: Long, sha256: String, part: File, onBytes: (Long) -> Unit): FetchResult =
        withContext(Dispatchers.IO) {
            try {
                fetchVerified(url, size, sha256, part, onBytes)
            } catch (abort: Abort) {
                abort.failed
            } catch (e: IOException) {
                val message = e.message.orEmpty()
                if (message.contains("ENOSPC") || message.contains("No space")) {
                    FetchResult.Failed("Not enough space", retryable = false)
                } else {
                    FetchResult.Failed(e.message ?: e.javaClass.simpleName, retryable = true)
                }
            }
        }

    private suspend fun fetchVerified(
        url: String,
        size: Long,
        sha256: String,
        part: File,
        onBytes: (Long) -> Unit,
    ): FetchResult {
        part.parentFile?.mkdirs()
        val sidecar = File(part.path + ".json")
        val resumable = sidecarMatches(sidecar, url, size) && part.length() <= size
        val have = if (resumable) part.length() else 0L
        if (freeBytes(part.absoluteFile.parentFile ?: part.absoluteFile) < size - have) {
            throw Abort(FetchResult.Failed("Not enough space", retryable = false))
        }
        if (!resumable) part.delete()
        sidecar.writeText(JSONObject().put("url", url).put("size", size).toString())

        if (have < size) {
            transfer(url, size, part, have, onBytes)
        } else {
            onBytes(size)
        }
        FileOutputStream(part, true).use { it.fd.sync() }

        if (sha256Of(part) != sha256.lowercase()) {
            part.delete()
            sidecar.delete()
            return FetchResult.Failed("Checksum mismatch", retryable = true)
        }
        sidecar.delete()
        return FetchResult.Ok(part)
    }

    /** Appends the rest of the body to [part], starting at byte [have]. Throws [Abort] on an HTTP failure. */
    private suspend fun transfer(url: String, size: Long, part: File, have: Long, onBytes: (Long) -> Unit) {
        val conn = connect(url, have)
        try {
            val code = conn.responseCode
            if (code == 416) {
                part.delete()
                throw Abort(FetchResult.Failed("HTTP 416", retryable = true))
            }
            if (code != 200 && code != 206) throw Abort(FetchResult.Failed("HTTP $code", retryable = true))

            // A 200 answer to a Range request is the whole file again, so the old bytes must go.
            val start = if (code == 206) have else 0L
            var written = start
            val buffer = ByteArray(CHUNK_BYTES)
            conn.inputStream.use { input ->
                openSink(part, start > 0).use { out ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        written += n
                        onBytes(written)
                    }
                }
            }
            if (written < size) throw IOException("Connection closed after $written of $size bytes")
        } finally {
            conn.disconnect()
        }
    }

    /** Opens the request, following redirects by hand so that the Range header is sent to every host. */
    private fun connect(url: String, have: Long): HttpURLConnection {
        var target = URL(url)
        for (hop in 0..MAX_REDIRECTS) {
            val conn = target.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            val code = try {
                conn.responseCode
            } catch (e: Throwable) {
                conn.disconnect()
                throw e
            }
            if (code !in REDIRECT_CODES) return conn
            val location = conn.getHeaderField("Location")
            conn.disconnect()
            if (location == null) throw Abort(FetchResult.Failed("HTTP $code", retryable = true))
            target = URL(target, location)
        }
        throw Abort(FetchResult.Failed("Too many redirects", retryable = true))
    }

    private suspend fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(CHUNK_BYTES)
        file.inputStream().use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sidecarMatches(sidecar: File, url: String, size: Long): Boolean = try {
        val json = JSONObject(sidecar.readText())
        json.getString("url") == url && json.getLong("size") == size
    } catch (e: IOException) {
        false
    } catch (e: JSONException) {
        false
    }

    /** Carries a failure out of the nested helpers; [fetch] turns it into the result. */
    private class Abort(val failed: FetchResult.Failed) : RuntimeException()

    private companion object {
        const val CHUNK_BYTES = 64 * 1024
        const val MAX_REDIRECTS = 5
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
