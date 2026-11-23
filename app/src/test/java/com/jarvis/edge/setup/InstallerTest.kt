package com.jarvis.edge.setup

import com.jarvis.edge.core.ModelPaths
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class InstallerTest {

    /** Records every url asked for and serves scripted failures before the real bytes. */
    private class FakeFetch(private val blobs: Map<String, ByteArray>) : Fetch {
        val calls = mutableListOf<String>()
        val scripted = mutableMapOf<String, ArrayDeque<FetchResult.Failed>>()

        override suspend fun fetch(url: String, size: Long, sha256: String, part: File, onBytes: (Long) -> Unit): FetchResult {
            calls += url
            scripted[url]?.removeFirstOrNull()?.let { return it }
            val bytes = blobs.getValue(url)
            part.parentFile?.mkdirs()
            part.writeBytes(bytes)
            onBytes(bytes.size.toLong())
            return FetchResult.Ok(part)
        }

        fun callsFor(url: String) = calls.count { it == url }
    }

    private class FakeGate(var current: NetState = NetState.ALLOWED, override var wifiOnly: Boolean = true) : NetworkGate {
        override fun state(): NetState = current
    }

    private fun file(
        id: String,
        label: String = id,
        required: Boolean = true,
        dest: String = id,
        size: Long = 10L,
    ) = ManifestItem(
        id = id, label = label, required = required, url = "https://example.invalid/$id", size = size, sha256 = "",
        kind = ItemKind.FILE, dest = dest, unpackedSize = 0L, mustExist = listOf(dest), drop = emptyList(), overlay = emptyList(),
    )

    private fun url(item: ManifestItem) = item.url

    private fun fetchFor(vararg items: ManifestItem): FakeFetch =
        FakeFetch(items.associate { it.url to "bytes of ${it.id}".toByteArray() })

    private fun installer(
        root: File,
        items: List<ManifestItem>,
        fetch: Fetch,
        gate: FakeGate = FakeGate(),
        bundled: (String) -> ByteArray? = { null },
        freeBytes: (File) -> Long = { Long.MAX_VALUE },
        netPollMs: Long = 1_000L,
    ) = Installer(
        root, ModelManifest(items), fetch, gate, bundled,
        freeBytes = freeBytes, backoffMs = { 0L }, netPollMs = netPollMs,
    )

    private fun marker(root: File) = File(root, SetupPlanner.MARKER_FILE).takeIf { it.exists() }?.readText()

    private fun tarBz2(files: Map<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(bytes)).use { tar ->
            for ((name, text) in files) {
                val data = text.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = data.size.toLong() })
                tar.write(data)
                tar.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }

    @Test
    fun `fetches pending items in manifest order and finishes`(@TempDir root: File) = runTest {
        val a = file("a"); val b = file("b"); val c = file("c")
        val fetch = fetchFor(a, b, c)
        val installer = installer(root, listOf(a, b, c), fetch)

        assertTrue(installer.runFirstSetup())

        assertEquals(listOf(a, b, c).map(::url), fetch.calls)
        assertEquals("bytes of b", File(root, "b").readText())
        assertNull(marker(root))
        assertTrue(installer.progress.value.finished)
        assertTrue(installer.progress.value.items.all { it.phase == ItemPhase.DONE })
    }

    @Test
    fun `present items are skipped`(@TempDir root: File) = runTest {
        val a = file("a"); val b = file("b")
        File(root, "a").writeBytes(byteArrayOf(1))
        val fetch = fetchFor(a, b)

        assertTrue(installer(root, listOf(a, b), fetch).runFirstSetup())

        assertEquals(listOf(url(b)), fetch.calls)
    }

    @Test
    fun `an extra failing three times is marked failed and the run continues`(@TempDir root: File) = runTest {
        val must = file("must"); val vision = file("vision", label = "Vision", required = false); val last = file("last")
        val fetch = fetchFor(must, vision, last)
        fetch.scripted[url(vision)] = ArrayDeque(List(3) { FetchResult.Failed("Checksum mismatch", retryable = true) })
        val installer = installer(root, listOf(must, vision, last), fetch)

        val setup = async { installer.runFirstSetup() }
        advanceUntilIdle()

        assertEquals(3, fetch.callsFor(url(vision)))
        assertEquals("bytes of last", File(root, "last").readText())
        val state = installer.progress.value
        assertTrue(state.items.first { it.id == "vision" }.phase == ItemPhase.FAILED)
        assertEquals("Checksum mismatch", state.items.first { it.id == "vision" }.reason)
        assertTrue(state.canStartWithout)
        assertNull(state.blockedBy)
        assertFalse(setup.isCompleted)

        installer.startWithout()
        assertTrue(setup.await())
    }

    @Test
    fun `HTTP 404 stops after three attempts`(@TempDir root: File) = runTest {
        val must = file("must")
        val fetch = fetchFor(must)
        fetch.scripted[url(must)] = ArrayDeque(List(3) { FetchResult.Failed("HTTP 404", retryable = true) })
        val installer = installer(root, listOf(must), fetch)

        val setup = async { installer.runFirstSetup() }
        advanceUntilIdle()

        assertEquals(3, fetch.callsFor(url(must)))
        assertEquals("HTTP 404", installer.progress.value.items.single().reason)
        setup.cancel()
    }

    @Test
    fun `a failed must-have blocks and start without is refused`(@TempDir root: File) = runTest {
        val qwen = file("qwen-1.5b", label = "Brain · Qwen2.5 1.5B", dest = "llm/${ModelPaths.QWEN_15B_MODEL}")
        val extra = file("extra", required = false)
        val fetch = fetchFor(qwen, extra)
        fetch.scripted[url(qwen)] = ArrayDeque(List(3) { FetchResult.Failed("HTTP 500", retryable = true) })
        val installer = installer(root, listOf(qwen, extra), fetch)

        val setup = async { installer.runFirstSetup() }
        advanceUntilIdle()
        assertEquals("Brain · Qwen2.5 1.5B", installer.progress.value.blockedBy)
        assertFalse(installer.progress.value.canStartWithout)

        installer.startWithout()
        advanceUntilIdle()

        assertFalse(setup.isCompleted)
        assertEquals("running", marker(root))
        setup.cancel()
    }

    @Test
    fun `retry re-runs a failed item`(@TempDir root: File) = runTest {
        val must = file("must"); val vision = file("vision", required = false)
        val fetch = fetchFor(must, vision)
        fetch.scripted[url(vision)] = ArrayDeque(List(3) { FetchResult.Failed("Checksum mismatch", retryable = true) })
        val installer = installer(root, listOf(must, vision), fetch)

        val setup = async { installer.runFirstSetup() }
        advanceUntilIdle()
        installer.retry("vision")
        advanceUntilIdle()

        assertEquals(4, fetch.callsFor(url(vision)))
        assertTrue(setup.await())
        assertEquals("bytes of vision", File(root, "vision").readText())
    }

    @Test
    fun `start without writes EXTRAS_PENDING and returns`(@TempDir root: File) = runTest {
        val must = file("must"); val vision = file("vision", required = false)
        val fetch = fetchFor(must, vision)
        fetch.scripted[url(vision)] = ArrayDeque(List(3) { FetchResult.Failed("HTTP 503", retryable = true) })
        val installer = installer(root, listOf(must, vision), fetch)

        val setup = async { installer.runFirstSetup() }
        advanceUntilIdle()
        installer.startWithout()

        assertTrue(setup.await())
        assertEquals("extras-pending", marker(root))
        assertTrue(installer.progress.value.finished)
    }

    @Test
    fun `background run fetches pending extras and clears the marker`(@TempDir root: File) = runTest {
        val must = file("must"); val extra = file("extra", required = false)
        File(root, "must").writeBytes(byteArrayOf(1))
        File(root, SetupPlanner.MARKER_FILE).writeText("extras-pending")
        val fetch = fetchFor(must, extra)
        val installer = installer(root, listOf(must, extra), fetch)

        installer.runExtrasInBackground()

        assertEquals(listOf(url(extra)), fetch.calls)
        assertNull(marker(root))
    }

    @Test
    fun `background run that fails keeps the marker for the next start`(@TempDir root: File) = runTest {
        val extra = file("extra", required = false)
        File(root, SetupPlanner.MARKER_FILE).writeText("extras-pending")
        val fetch = fetchFor(extra)
        fetch.scripted[url(extra)] = ArrayDeque(List(3) { FetchResult.Failed("HTTP 404", retryable = true) })

        installer(root, listOf(extra), fetch).runExtrasInBackground()

        assertEquals(3, fetch.callsFor(url(extra)))
        assertEquals("extras-pending", marker(root))
    }

    @Test
    fun `waits while network is not allowed`(@TempDir root: File) = runTest {
        val a = file("a")
        val fetch = fetchFor(a)
        val gate = FakeGate(NetState.WAITING_FOR_WIFI)
        val installer = installer(root, listOf(a), fetch, gate, netPollMs = 1_000L)

        val setup = async { installer.runFirstSetup() }
        advanceTimeBy(3_500L)

        assertTrue(fetch.calls.isEmpty())
        assertEquals(NetState.WAITING_FOR_WIFI, installer.progress.value.net)

        gate.current = NetState.ALLOWED
        advanceUntilIdle()

        assertTrue(setup.await())
        assertEquals(listOf(url(a)), fetch.calls)
    }

    @Test
    fun `not enough space blocks before any fetch`(@TempDir root: File) = runTest {
        val a = file("a", size = 1_000L)
        val fetch = fetchFor(a)
        var free = 1_000L
        val installer = installer(root, listOf(a), fetch, freeBytes = { free })

        val setup = async { installer.runFirstSetup() }
        advanceUntilIdle()

        assertTrue(fetch.calls.isEmpty())
        assertTrue(installer.progress.value.blockedBy!!.startsWith("Not enough space: need "))
        assertFalse(setup.isCompleted)

        free = Long.MAX_VALUE
        installer.retry("")
        assertTrue(setup.await())
        assertEquals(listOf(url(a)), fetch.calls)
    }

    @Test
    fun `keeps an existing active model file`(@TempDir root: File) = runTest {
        val qwen = file("qwen-1.5b", dest = "llm/${ModelPaths.QWEN_15B_MODEL}")
        File(root, qwen.dest).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        File(root, ModelPaths.ACTIVE_MODEL_FILE).apply { parentFile!!.mkdirs(); writeText(ModelPaths.DEFAULT_LLM_MODEL) }

        assertTrue(installer(root, listOf(qwen), fetchFor()).runFirstSetup())

        assertEquals(ModelPaths.DEFAULT_LLM_MODEL, File(root, ModelPaths.ACTIVE_MODEL_FILE).readText())
    }

    @Test
    fun `names qwen 1_5b as the active model when none is named and it is present`(@TempDir root: File) = runTest {
        val qwen = file("qwen-1.5b", dest = "llm/${ModelPaths.QWEN_15B_MODEL}")
        File(root, qwen.dest).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }

        assertTrue(installer(root, listOf(qwen), fetchFor()).runFirstSetup())

        assertEquals(ModelPaths.QWEN_15B_MODEL, File(root, ModelPaths.ACTIVE_MODEL_FILE).readText())
    }

    @Test
    fun `bundled adapters and keywords are copied only if absent`(@TempDir root: File) = runTest {
        val present = file("present")
        File(root, "present").writeBytes(byteArrayOf(1))
        File(root, "adapters").mkdirs()
        File(root, "adapters/experts.json").writeText("old")
        File(root, "kws").mkdirs()
        File(root, "kws/tokens.txt").writeText("tokens")
        val bundled = mapOf(
            "adapters/experts.json" to "new".toByteArray(),
            "adapters/tool_expert.jvec" to "tool".toByteArray(),
            "kws/keywords.txt" to "kw".toByteArray(),
        )

        assertTrue(installer(root, listOf(present), fetchFor(), bundled = { bundled[it] }).runFirstSetup())

        assertEquals("old", File(root, "adapters/experts.json").readText())
        assertEquals("tool", File(root, "adapters/tool_expert.jvec").readText())
        assertEquals("kw", File(root, "kws/keywords.txt").readText())
    }

    @Test
    fun `archive item installs from the verified part and overlays the bundled file`(@TempDir root: File) = runTest {
        val kws = ManifestItem(
            id = "kws", label = "Wake word", required = true, url = "https://example.invalid/kws", size = 1L, sha256 = "",
            kind = ItemKind.TAR_BZ2, dest = "kws", unpackedSize = 1L, mustExist = listOf("kws/tokens.txt"),
            drop = emptyList(), overlay = listOf("kws/keywords.txt"),
        )
        val fetch = FakeFetch(mapOf(kws.url to tarBz2(mapOf("top/tokens.txt" to "t"))))
        val installer = installer(root, listOf(kws), fetch, bundled = { if (it == "kws/keywords.txt") "kw".toByteArray() else null })

        assertTrue(installer.runFirstSetup())

        assertEquals("t", File(root, "kws/tokens.txt").readText())
        assertEquals("kw", File(root, "kws/keywords.txt").readText())
        assertFalse(File(root, "kws.part").exists())
    }

    /** Writes the first half, then suspends until cancelled; the second call writes the rest. */
    private class SuspendingFetch : Fetch {
        val calls = mutableListOf<String>()
        var cancelledMidTransfer = false

        override suspend fun fetch(url: String, size: Long, sha256: String, part: File, onBytes: (Long) -> Unit): FetchResult {
            calls += url
            part.parentFile?.mkdirs()
            if (calls.size == 1) {
                part.writeBytes(ByteArray(50))
                onBytes(50L)
                try {
                    awaitCancellation()
                } finally {
                    cancelledMidTransfer = true
                }
            }
            part.appendBytes(ByteArray((size - part.length()).toInt()))
            onBytes(size)
            return FetchResult.Ok(part)
        }
    }

    @Test
    fun `leaving Wi-Fi cancels the transfer, resumes on Wi-Fi, and is not counted as an attempt`(@TempDir root: File) = runTest {
        val a = file("a", size = 100L)
        val fetch = SuspendingFetch()
        val gate = FakeGate(NetState.ALLOWED)
        val installer = installer(root, listOf(a), fetch, gate, netPollMs = 1_000L)

        val setup = async { installer.runFirstSetup() }
        advanceTimeBy(1_500L)
        assertEquals(1, fetch.calls.size)

        gate.current = NetState.WAITING_FOR_WIFI
        advanceTimeBy(1_500L)

        assertTrue(fetch.cancelledMidTransfer)
        assertEquals(NetState.WAITING_FOR_WIFI, installer.progress.value.net)
        assertEquals(ItemPhase.DOWNLOADING, installer.progress.value.items.single().phase)
        assertFalse(setup.isCompleted)

        gate.current = NetState.ALLOWED
        advanceUntilIdle()

        assertTrue(setup.await())
        assertEquals(2, fetch.calls.size)
        assertEquals(100L, File(root, "a").length())
        assertEquals(NetState.ALLOWED, installer.progress.value.net)
        assertTrue(installer.progress.value.items.single().phase == ItemPhase.DONE)
    }

    @Test
    fun `refresh republishes the Wi-Fi switch after the gate changes`(@TempDir root: File) {
        val a = file("a")
        val gate = FakeGate(NetState.ALLOWED, wifiOnly = true)
        val installer = installer(root, listOf(a), fetchFor(a), gate)
        assertTrue(installer.progress.value.wifiOnly)

        gate.wifiOnly = false
        installer.refresh()

        assertFalse(installer.progress.value.wifiOnly)
    }

    @Test
    fun `progress fraction and eta`() {
        val progress = SetupProgress(
            items = listOf(
                ItemProgress("a", "A", true, ItemPhase.DONE, bytes = 0L, size = 100L),
                ItemProgress("b", "B", true, ItemPhase.DOWNLOADING, bytes = 50L, size = 100L),
            ),
            net = NetState.ALLOWED, wifiOnly = true, bytesPerSec = 50L, finished = false, canStartWithout = false,
        )

        assertEquals(0.75f, progress.fraction)
        assertEquals(1L, progress.etaSec)
        assertNull(progress.copy(bytesPerSec = 0L).etaSec)
    }
}
