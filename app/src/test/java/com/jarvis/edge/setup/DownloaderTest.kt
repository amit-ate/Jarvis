package com.jarvis.edge.setup

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.math.min

class DownloaderTest {

    private val blob = ByteArray(300_000).also { java.util.Random(42).nextBytes(it) }
    private val size = blob.size.toLong()
    private val sha = MessageDigest.getInstance("SHA-256").digest(blob).joinToString("") { "%02x".format(it) }

    /** Serves [blob] over HTTP on 127.0.0.1 and records what the client asked for. */
    private class Server(private val blob: ByteArray) : AutoCloseable {
        var rangeSupport = true
        var rangeUnsatisfiable = false
        var redirectTo: String? = null
        var notFound = false
        @Volatile var servedBytes = 0L
        val ranges = CopyOnWriteArrayList<String?>()

        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val acceptor = thread(isDaemon = true) {
            while (true) {
                val client = try { socket.accept() } catch (e: IOException) { break }
                client.use { handle(it) }
            }
        }

        val url: String get() = "http://127.0.0.1:${socket.localPort}/blob"

        private fun handle(client: Socket) {
            val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = input.readLine() ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = input.readLine()
                if (line.isNullOrEmpty()) break
                val colon = line.indexOf(':')
                headers[line.substring(0, colon).lowercase()] = line.substring(colon + 1).trim()
            }
            check(requestLine.startsWith("GET ")) { requestLine }
            val range = headers["range"]
            ranges += range
            val out = client.getOutputStream()
            val redirect = redirectTo
            when {
                redirect != null -> reply(out, 302, "Found", listOf("Location: $redirect"), ByteArray(0))
                notFound -> reply(out, 404, "Not Found", emptyList(), ByteArray(0))
                rangeUnsatisfiable && range != null -> reply(out, 416, "Range Not Satisfiable", emptyList(), ByteArray(0))
                else -> {
                    val start = if (rangeSupport && range != null) {
                        Regex("bytes=(\\d+)-").find(range)!!.groupValues[1].toLong()
                    } else 0L
                    val body = blob.copyOfRange(start.toInt(), blob.size)
                    servedBytes += body.size
                    if (start > 0) {
                        reply(out, 206, "Partial Content", listOf("Content-Range: bytes $start-${blob.size - 1}/${blob.size}"), body)
                    } else {
                        reply(out, 200, "OK", emptyList(), body)
                    }
                }
            }
        }

        private fun reply(out: OutputStream, code: Int, reason: String, extra: List<String>, body: ByteArray) {
            val head = buildString {
                append("HTTP/1.1 $code $reason\r\n")
                extra.forEach { append(it).append("\r\n") }
                append("Content-Length: ${body.size}\r\nConnection: close\r\n\r\n")
            }
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.write(body)
            out.flush()
        }

        override fun close() {
            socket.close()
            acceptor.join()
        }
    }

    private fun partIn(root: File) = File(root, "llm/x.gguf.part")

    private fun writeSidecar(part: File, url: String, size: Long) {
        File(part.path + ".json").writeText("""{"url":"$url","size":$size}""")
    }

    /** A sink that accepts [limit] bytes and then fails the way a full disk does. */
    private fun fullDiskAfter(limit: Int): (File, Boolean) -> OutputStream = { file, append ->
        val real = FileOutputStream(file, append)
        object : OutputStream() {
            var written = 0
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                val n = min(len, limit - written)
                real.write(b, off, n)
                written += n
                if (n < len) throw IOException("write failed: ENOSPC")
            }
            override fun close() = real.close()
        }
    }

    @Test
    fun `downloads, verifies and reports bytes`(@TempDir root: File) = runTest {
        val part = partIn(root)
        val progress = mutableListOf<Long>()
        Server(blob).use { server ->
            val result = Downloader().fetch(server.url, size, sha, part) { progress += it }
            assertEquals(FetchResult.Ok(part), result)
        }
        assertTrue(blob.contentEquals(part.readBytes()))
        assertEquals(size, progress.last())
    }

    @Test
    fun `resumes from an existing part with a Range request`(@TempDir root: File) = runTest {
        val part = partIn(root)
        part.parentFile?.mkdirs()
        part.writeBytes(blob.copyOf(100_000))
        Server(blob).use { server ->
            writeSidecar(part, server.url, size)
            val result = Downloader().fetch(server.url, size, sha, part) {}
            assertEquals(FetchResult.Ok(part), result)
            assertEquals(listOf("bytes=100000-"), server.ranges)
            assertEquals(size - 100_000, server.servedBytes)
        }
        assertTrue(blob.contentEquals(part.readBytes()))
    }

    @Test
    fun `server ignoring Range restarts the file cleanly`(@TempDir root: File) = runTest {
        val part = partIn(root)
        part.parentFile?.mkdirs()
        part.writeBytes(blob.copyOf(100_000))
        Server(blob).use { server ->
            server.rangeSupport = false
            writeSidecar(part, server.url, size)
            val result = Downloader().fetch(server.url, size, sha, part) {}
            assertEquals(FetchResult.Ok(part), result)
            assertEquals(size, server.servedBytes)
        }
        assertTrue(blob.contentEquals(part.readBytes()))
    }

    @Test
    fun `complete part goes straight to verification without a request`(@TempDir root: File) = runTest {
        val part = partIn(root)
        part.parentFile?.mkdirs()
        part.writeBytes(blob)
        Server(blob).use { server ->
            writeSidecar(part, server.url, size)
            val result = Downloader().fetch(server.url, size, sha, part) {}
            assertEquals(FetchResult.Ok(part), result)
            assertEquals(0L, server.servedBytes)
            assertTrue(server.ranges.isEmpty())
        }
    }

    @Test
    fun `416 for a short part deletes it and fails retryably`(@TempDir root: File) = runTest {
        val part = partIn(root)
        part.parentFile?.mkdirs()
        part.writeBytes(blob.copyOf(100_000))
        Server(blob).use { server ->
            server.rangeUnsatisfiable = true
            writeSidecar(part, server.url, size)
            val result = Downloader().fetch(server.url, size, sha, part) {}
            assertEquals(FetchResult.Failed("HTTP 416", true), result)
            assertFalse(part.exists())
        }
    }

    @Test
    fun `checksum mismatch deletes the part`(@TempDir root: File) = runTest {
        val part = partIn(root)
        Server(blob).use { server ->
            val wrongSha = "0".repeat(64)
            val result = Downloader().fetch(server.url, size, wrongSha, part) {}
            assertEquals(FetchResult.Failed("Checksum mismatch", true), result)
            assertFalse(part.exists())
        }
    }

    @Test
    fun `follows a redirect to another host`(@TempDir root: File) = runTest {
        val part = partIn(root)
        Server(blob).use { target ->
            Server(blob).use { redirector ->
                redirector.redirectTo = target.url
                val result = Downloader().fetch(redirector.url, size, sha, part) {}
                assertEquals(FetchResult.Ok(part), result)
                assertEquals(size, target.servedBytes)
            }
        }
    }

    @Test
    fun `HTTP 404 fails with the status in the reason`(@TempDir root: File) = runTest {
        Server(blob).use { server ->
            server.notFound = true
            val result = Downloader().fetch(server.url, size, sha, partIn(root)) {}
            assertEquals(FetchResult.Failed("HTTP 404", true), result)
        }
    }

    @Test
    fun `stale sidecar for a different url discards the part`(@TempDir root: File) = runTest {
        val part = partIn(root)
        part.parentFile?.mkdirs()
        part.writeBytes(blob.copyOf(100_000))
        Server(blob).use { server ->
            writeSidecar(part, "http://elsewhere.invalid/blob", size)
            val result = Downloader().fetch(server.url, size, sha, part) {}
            assertEquals(FetchResult.Ok(part), result)
            assertEquals(listOf<String?>(null), server.ranges)
            assertEquals(size, server.servedBytes)
        }
    }

    @Test
    fun `not enough free space fails before any request`(@TempDir root: File) = runTest {
        Server(blob).use { server ->
            val result = Downloader(freeBytes = { 10L }).fetch(server.url, size, sha, partIn(root)) {}
            assertEquals(FetchResult.Failed("Not enough space", false), result)
            assertEquals(0L, server.servedBytes)
        }
    }

    @Test
    fun `disk error while writing keeps the part and fails`(@TempDir root: File) = runTest {
        val part = partIn(root)
        Server(blob).use { server ->
            val result = Downloader(openSink = fullDiskAfter(50_000)).fetch(server.url, size, sha, part) {}
            assertEquals(FetchResult.Failed("Not enough space", false), result)
        }
        assertEquals(50_000L, part.length())
    }
}
