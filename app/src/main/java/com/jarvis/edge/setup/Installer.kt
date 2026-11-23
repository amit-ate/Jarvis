package com.jarvis.edge.setup

import com.jarvis.edge.core.AtomicFiles
import com.jarvis.edge.core.ModelPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.CoroutineContext

/** Downloads one url to [part] and verifies it; [Downloader.fetch] fits. */
fun interface Fetch {
    suspend fun fetch(url: String, size: Long, sha256: String, part: File, onBytes: (Long) -> Unit): FetchResult
}

/**
 * Runs the model setup: fetches what [SetupPlanner] reports missing, installs each item, and publishes [progress].
 * A first setup waits on failed items until [retry] or [startWithout] is called; the background run for extras never
 * waits and ends quietly on failure, leaving the marker for the next start.
 */
class Installer(
    private val root: File,
    private val manifest: ModelManifest,
    private val fetch: Fetch,
    private val net: NetworkGate,
    private val bundled: (String) -> ByteArray?,
    private val freeBytes: (File) -> Long = { it.usableSpace },
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val backoffMs: (attempt: Int) -> Long = { minOf(2_000L shl it, 60_000L) },
    private val netPollMs: Long = 2_000L,
    private val unpackContext: CoroutineContext = Dispatchers.IO,
) {
    private val planner = SetupPlanner(root, manifest)
    private val states = linkedMapOf<String, ItemProgress>()
    private val commands = Channel<Command>(Channel.UNLIMITED)

    private val _progress = MutableStateFlow(
        SetupProgress(emptyList(), NetState.ALLOWED, net.wifiOnly, 0L, finished = false, canStartWithout = false),
    )
    val progress: StateFlow<SetupProgress> = _progress.asStateFlow()

    private var netState = NetState.ALLOWED
    private var blockedBy: String? = null
    private var finished = false
    private var canStartWithout = false
    private var lastPublishMs = 0L
    private var receivedBytes = 0L
    private var transferReported = 0L
    private var rateBytes = 0L
    private var rateSinceMs = 0L
    private var bytesPerSec = 0L

    /** Fetches and installs everything missing. Returns true when done, or when the user chose "Start without it". */
    suspend fun runFirstSetup(): Boolean {
        planner.writeMarker(SetupMarker.RUNNING)
        return runPlan(planner.pending(), interactive = true, shown = manifest.items)
    }

    /** Fetches the extras left after "Start without it". Does nothing unless the marker says extras are pending. */
    suspend fun runExtrasInBackground() {
        if (planner.marker() != SetupMarker.EXTRAS_PENDING) return
        runPlan(planner.pending(), interactive = false)
    }

    /** Re-runs a failed item by [id]; an empty id re-checks free space. */
    fun retry(id: String) {
        commands.trySend(Command.Retry(id))
    }

    /** Ends the first setup without the failed extras. Ignored while a must-have is failed or still pending. */
    fun startWithout() {
        commands.trySend(Command.StartWithout)
    }

    /** Republishes the current progress, so a changed [NetworkGate.wifiOnly] shows at once. Safe from any thread. */
    fun refresh() = publish()

    /** [shown] is what progress covers: items that are not in [plan] start as DONE. */
    private suspend fun runPlan(plan: List<ManifestItem>, interactive: Boolean, shown: List<ManifestItem> = plan): Boolean {
        root.mkdirs()
        beginRun(shown, plan)
        var shortfall = spaceShortfall(plan)
        while (true) {
            if (shortfall == null) {
                plan.filter { states.getValue(it.id).phase == ItemPhase.QUEUED }.forEach { fetchItem(it) }
            }
            val failed = plan.filter { states.getValue(it.id).phase == ItemPhase.FAILED }
            if (shortfall == null && failed.isEmpty()) {
                planner.writeMarker(SetupMarker.NONE)
                complete()
                synchronized(this) {
                    finished = true
                    publish()
                }
                return true
            }
            if (!interactive) {
                synchronized(this) {
                    blockedBy = shortfall
                    publish()
                }
                return false
            }
            synchronized(this) {
                canStartWithout = shortfall == null && failed.none { it.required }
                blockedBy = shortfall ?: failed.firstOrNull { it.required }?.label
                publish()
            }
            when (val command = commands.receive()) {
                is Command.Retry -> when {
                    command.id.isEmpty() -> shortfall = spaceShortfall(plan)
                    states[command.id]?.phase == ItemPhase.FAILED ->
                        plan.find { it.id == command.id }?.let { setItem(it, ItemPhase.QUEUED) }
                }
                Command.StartWithout -> if (canStartWithout) {
                    planner.writeMarker(SetupMarker.EXTRAS_PENDING)
                    complete()
                    synchronized(this) {
                        finished = true
                        publish()
                    }
                    return true
                }
            }
        }
    }

    /** Runs one item to DONE or FAILED. Each failure counts as an attempt; the third one fails the item. */
    private suspend fun fetchItem(item: ManifestItem) {
        val part = File(root, "${item.dest}.part")
        var failures = 0
        while (true) {
            awaitNetwork()
            startTransfer(item, minOf(part.length(), item.size))
            val result = fetchWhileOnline(item, part)
            if (result == null) continue // left the network mid-transfer: not an attempt, the part is kept
            if (result is FetchResult.Failed) {
                // A dropped Wi-Fi often fails the transfer before the watcher has noticed: look again before counting.
                val now = net.state()
                if (now != NetState.ALLOWED) {
                    updateNet(now)
                    continue // not an attempt: the part is kept and the loop waits for the network
                }
            }
            val failure = when (result) {
                is FetchResult.Failed -> result
                is FetchResult.Ok -> {
                    val installFailure = install(item, part)
                    if (installFailure == null) {
                        setItem(item, ItemPhase.DONE, bytes = item.size)
                        return
                    }
                    installFailure
                }
            }
            failures++
            if (!failure.retryable || failures >= MAX_ATTEMPTS) {
                setItem(item, ItemPhase.FAILED, reason = failure.reason)
                return
            }
            delay(backoffMs(failures - 1))
        }
    }

    /**
     * Runs one transfer while a watcher polls the network every [netPollMs]. If the network leaves ALLOWED (Wi-Fi
     * dropped), the transfer is cancelled and null is returned; the Downloader keeps the part for a resume.
     */
    private suspend fun fetchWhileOnline(item: ManifestItem, part: File): FetchResult? = coroutineScope {
        var interrupted = false
        val transfer = async { fetch.fetch(item.url, item.size, item.sha256, part) { onBytes(item, it) } }
        val watcher = launch {
            while (true) {
                delay(netPollMs)
                val state = net.state()
                if (state != NetState.ALLOWED) {
                    interrupted = true
                    updateNet(state)
                    transfer.cancel()
                    break
                }
            }
        }
        try {
            transfer.await()
        } catch (e: CancellationException) {
            if (!interrupted) throw e
            null
        } finally {
            watcher.cancel()
        }
    }

    /** Moves the verified [part] into place. Returns null on success, else the failure to count as an attempt. */
    private suspend fun install(item: ManifestItem, part: File): FetchResult.Failed? = withContext(unpackContext) {
        try {
            setItem(item, ItemPhase.UNPACKING)
            when (item.kind) {
                ItemKind.FILE -> Unpacker.installFile(part, File(root, item.dest))
                ItemKind.TAR_BZ2 -> Unpacker.installArchive(part, root, item, bundled)
            }
            null
        } catch (e: IOException) {
            part.delete()
            FetchResult.Failed(e.message ?: e.javaClass.simpleName, retryable = true)
        }
    }

    private suspend fun awaitNetwork() {
        while (true) {
            val state = net.state()
            updateNet(state)
            if (state == NetState.ALLOWED) return
            delay(netPollMs)
        }
    }

    @Synchronized
    private fun updateNet(state: NetState) {
        if (state != netState) {
            netState = state
            publish()
        }
    }

    /** A message when the items still to fetch need more room than is free, else null. */
    private fun spaceShortfall(plan: List<ManifestItem>): String? {
        val remaining = plan.filter { states.getValue(it.id).phase != ItemPhase.DONE }
        if (remaining.isEmpty()) return null
        val sum = remaining.sumOf { it.size }
        val need = sum + sum / 10 + remaining.maxOf { it.unpackedSize }
        val have = freeBytes(root)
        return if (have >= need) null else "Not enough space: need ${gb(need)} GB, have ${gb(have)} GB"
    }

    private fun gb(bytes: Long): String = "%.1f".format(Locale.US, bytes / 1e9)

    @Synchronized
    private fun beginRun(shown: List<ManifestItem>, plan: List<ManifestItem>) {
        states.clear()
        val queued = plan.map { it.id }.toSet()
        shown.forEach {
            val phase = if (it.id in queued) ItemPhase.QUEUED else ItemPhase.DONE
            states[it.id] = ItemProgress(it.id, it.label, it.required, phase, bytes = if (phase == ItemPhase.DONE) it.size else 0L, size = it.size)
        }
        blockedBy = null
        finished = false
        canStartWithout = false
        receivedBytes = 0L
        transferReported = 0L
        rateBytes = 0L
        rateSinceMs = clockMs()
        bytesPerSec = 0L
        publish()
    }

    /** Byte updates are throttled; a change of phase is always published. */
    @Synchronized
    private fun onBytes(item: ManifestItem, bytes: Long) {
        // Only bytes that arrive over the network count toward the rate. A report below the last one means the
        // transfer began again from zero, so the whole report is new.
        receivedBytes += if (bytes >= transferReported) bytes - transferReported else bytes
        transferReported = bytes
        val current = states.getValue(item.id)
        val phase = if (bytes >= item.size) ItemPhase.VERIFYING else ItemPhase.DOWNLOADING
        states[item.id] = current.copy(phase = phase, bytes = bytes)
        if (phase != current.phase || clockMs() - lastPublishMs >= BYTE_PUBLISH_MS) publish()
    }

    @Synchronized
    private fun setItem(
        item: ManifestItem,
        phase: ItemPhase,
        bytes: Long = states.getValue(item.id).bytes,
        reason: String? = null,
    ) {
        states[item.id] = ItemProgress(item.id, item.label, item.required, phase, bytes, item.size, reason)
        publish()
    }

    /** Starts an attempt at [item]; [resumed] bytes are already on disk and are not counted as received. */
    @Synchronized
    private fun startTransfer(item: ManifestItem, resumed: Long) {
        transferReported = resumed
        setItem(item, ItemPhase.DOWNLOADING, bytes = resumed)
    }

    @Synchronized
    private fun publish() {
        val now = clockMs()
        lastPublishMs = now
        if (now - rateSinceMs >= RATE_WINDOW_MS) {
            bytesPerSec = if (netState == NetState.ALLOWED) ((receivedBytes - rateBytes) * 1000 / (now - rateSinceMs)).coerceAtLeast(0L) else 0L
            rateBytes = receivedBytes
            rateSinceMs = now
        }
        _progress.value = SetupProgress(
            items = states.values.toList(),
            net = netState,
            wifiOnly = net.wifiOnly,
            bytesPerSec = bytesPerSec,
            finished = finished,
            canStartWithout = canStartWithout,
            blockedBy = blockedBy,
        )
    }

    /** Copies bundled files that are not on the phone yet, and records the chat model if none is named yet. */
    private fun complete() {
        copyBundled()
        val active = File(root, ModelPaths.ACTIVE_MODEL_FILE)
        if (!active.exists() && ModelPaths.isPresent(File(root, "llm/${ModelPaths.QWEN_15B_MODEL}"))) {
            AtomicFiles.writeText(active, ModelPaths.QWEN_15B_MODEL)
        }
    }

    private fun copyBundled() {
        for (path in BUNDLED_FILES) {
            if (path.startsWith("kws/") && !File(root, "kws").isDirectory) continue
            val target = File(root, path)
            if (ModelPaths.isPresent(target)) continue
            AtomicFiles.write(target, bundled(path) ?: continue)
        }
    }

    private sealed interface Command {
        class Retry(val id: String) : Command
        data object StartWithout : Command
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val BYTE_PUBLISH_MS = 500L
        const val RATE_WINDOW_MS = 1_000L

        /** Files shipped inside the APK (assets/models/bundled) that are copied into the model root. */
        val BUNDLED_FILES = listOf(
            "adapters/conversation_expert.jvec",
            "adapters/tool_expert.jvec",
            "adapters/reasoning_expert.jvec",
            "adapters/anti_echo_direct.jvec",
            "adapters/experts.json",
            "kws/keywords.txt",
        )
    }
}
