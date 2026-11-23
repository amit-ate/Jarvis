# In-app model download — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A phone without models (the Pixel 7a) sets Jarvis up by itself: it downloads, verifies and installs the model set, showing the "Reactor charging" screen, then starts. Phones that already have their models start exactly as today.

**Architecture:** A new package `com.jarvis.edge.setup` holds plain-Kotlin, JVM-testable parts: the manifest, a planner that decides whether setup is needed and what to fetch, a resumable verified downloader, a tar.bz2 unpacker, and an installer that runs the items in order with the required/extras rules and publishes `SetupProgress`. `JarvisService` asks the planner at start; in setup mode it runs the installer instead of the voice pipeline, then starts the pipeline. `HudActivity` shows `SetupScreen` while setup is active.

**Tech Stack:** Kotlin 1.9.25, AGP 8.5.2, Jetpack Compose (BOM 2024.06.00), coroutines 1.8.1, JUnit 5, `HttpURLConnection`, `org.apache.commons:commons-compress`, the JDK's `com.sun.net.httpserver.HttpServer` for tests.

**Spec:** `docs/superpowers/specs/2026-11-23-in-app-model-download-design.md`

## Global Constraints

- Model root stays `/sdcard/Jarvis/models` (`ModelPaths.DEFAULT_ROOT`); `scripts/push-models.sh` keeps working unchanged.
- targetSdk 28, minSdk 28, arm64 only; no new network library (`HttpURLConnection`); only new dependency `org.apache.commons:commons-compress`.
- "Present" = exists and non-empty (same test as `ModelPaths.require`); sizes are never compared with the manifest for existing files.
- A phone that passes the start check downloads **nothing** (Redmi, Pad).
- No file reaches its final path before its SHA-256 matches; archives unpack into `<dest>.tmp/` and are renamed into place.
- Resume with `Range: bytes=N-`; a 200 reply to a Range request restarts the file from zero.
- Wi-Fi (unmetered) only by default; a switch allows mobile data.
- Checksum retries: 3 per item, then the item is failed.
- Must-have items: wake word, voice detector, `asr-zipformer`, live hearing (kroko), Alan voice, Qwen2.5 1.5B. Everything else is an extra.
- `llm/active_model.txt` = `qwen2.5-1.5b-instruct-q4_0.gguf` written only if the file does not exist.
- Screen copy: title "Bringing Jarvis online"; footer "Jarvis wakes when every module checks out"; notification "Downloading models · 48%".
- Commits: `TZ=UTC`, author email `102742568+amit-ate@users.noreply.github.com` (repo-local config already set), message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Unit tests: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew testDebugUnitTest`; all existing 318 tests keep passing.

## Decisions this plan adds (builder calls, consistent with the spec)

- **Setup marker.** `<root>/.setup-state` holds `running` while a first setup runs and `extras-pending` after "Start without it". Without it, a setup interrupted after the must-haves would pass the start check next boot and never fetch the extras. The Redmi and Pad have no marker, so nothing changes for them.
- **Chat-model fallback order** becomes `[gemma-3-1b, qwen2.5-1.5b, qwen2.5-0.5b]`, so a phone holding Qwen 1.5B resolves it even before `active_model.txt` exists. Phones holding Gemma are unchanged.
- **Debug model root.** Debug builds read `/sdcard/Jarvis/model_root.txt` (one absolute path) if present; release builds always use `DEFAULT_ROOT`.
- The service already holds a partial wake lock for its whole life (`acquireWakeLock()` in `onCreate`); no extra lock.

## Review Focus

1. **App killed or phone rebooted mid-archive-unpack** → next start finds `<dest>.tmp/` leftovers and a completed `.part`; expect the leftovers to be deleted and the item re-verified and unpacked, never a half folder at `<dest>`. Test in Task 4.
2. **Server returns 416 (Range past end) for a `.part` already complete** (e.g. killed between last byte and hashing) → expect it to go straight to verification, not fail. Test in Task 3.
3. **Disk fills mid-download despite the free-space check** (other apps write) → expect the item to fail with "Not enough space" and the `.part` kept, not a crash loop. Test in Task 3.
4. **Upstream URL returns HTML/404 (moved release asset)** → expect the HTTP status in the row's reason ("HTTP 404"), counted against the 3 attempts, not retried forever. Test in Task 3 (reason) and Task 5 (attempt limit).
5. **Existing destination folder but empty (an earlier failed push)** → counts as missing and is replaced, not merged. Test in Task 2.

---

## File Structure

| File | Responsibility |
|---|---|
| `scripts/make-model-manifest.py` (create) | Laptop tool: pins HF commits, downloads/hashes every item, lists archive contents, writes the manifest asset |
| `app/src/main/assets/models/manifest.json` (generated) | The fixed download set |
| `app/src/main/assets/models/bundled/` (create) | `adapters/*.jvec`, `adapters/experts.json`, `kws/keywords.txt` |
| `setup/ModelManifest.kt` | Manifest data classes + JSON parsing |
| `setup/SetupPlanner.kt` | Start check, pending items, marker file |
| `setup/Downloader.kt` | One item: resumable HTTP to `.part`, SHA-256 |
| `setup/Unpacker.kt` | tar.bz2 → `<dest>.tmp/` with strip + drops, atomic folder install |
| `setup/SetupProgress.kt` | Progress state types |
| `setup/Installer.kt` | Runs items in order: retries, required/extras, bundled files, active model, progress |
| `setup/NetworkGate.kt` | Interface + Android impl (Wi-Fi-only switch) |
| `core/ModelPaths.kt` (modify) | Fallback order; `rootFor` debug override |
| `service/JarvisService.kt` (modify) | Setup mode at start, progress flow, notification |
| `hud/SetupScreen.kt` (create), `hud/HudActivity.kt` (modify) | The ready screen |

All new Kotlin under `app/src/main/java/com/jarvis/edge/setup/`; tests under `app/src/test/java/com/jarvis/edge/setup/`.

---

### Task 1: Manifest generator, manifest asset and bundled files

**Files:**
- Create: `scripts/make-model-manifest.py`
- Create (generated): `app/src/main/assets/models/manifest.json`
- Create: `app/src/main/assets/models/bundled/adapters/{anti_echo_direct,conversation_expert,reasoning_expert,tool_expert}.jvec` (copy from `models/adapters/`), `.../adapters/experts.json` (pull from the Redmi: `adb shell cat /sdcard/Jarvis/models/adapters/experts.json`), `.../kws/keywords.txt` (exact content of the heredoc in `scripts/fetch-models.sh`)

**Interfaces:**
- Produces: the manifest JSON schema below, consumed by Task 2.

Manifest schema (field names fixed here):
```json
{ "version": 1,
  "items": [
    { "id": "kws", "label": "Wake word", "required": true,
      "url": "https://…", "size": 19000000, "sha256": "<64 hex>",
      "kind": "tar.bz2", "dest": "kws", "unpackedSize": 21000000,
      "mustExist": ["kws/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx", "…"],
      "drop": [], "overlay": ["kws/keywords.txt"] } ] }
```
`kind` is `file` (dest = file path) or `tar.bz2` (dest = folder, top folder stripped). `drop` = paths inside the unpacked folder to delete (tts-voices: `["espeak-ng-data"]`). `overlay` = root-relative paths replaced by the bundled copy after unpacking. `mustExist` paths are root-relative; for archives they list every regular file in the archive after strip/drop (dirs excluded), for `file` the dest itself.

Items, in this order (ids → dest; required ✓):
`kws`→`kws` ✓ · `vad`→`vad/silero_vad.onnx` ✓ · `asr-zipformer` ✓ · `asr-live` (kroko) ✓ · `asr-parakeet` · `tts` (Alan) ✓ · `qwen-1.5b`→`llm/qwen2.5-1.5b-instruct-q4_0.gguf` ✓ · `voice-arctic`, `voice-l2arctic`, `voice-kusal`→`tts-voices/<v>` · `asr-moonshine` · `qwen-0.5b`→`llm/qwen2.5-0.5b-instruct-q4_0.gguf` · `vlm`, `vlm-mmproj`→`vlm/…` · `embed`→`embed/all-MiniLM-L6-v2-Q8_0.gguf`.
Labels (screen): "Wake word", "Voice detector", "Basic hearing", "Live hearing", "Accurate hearing", "Voice · Alan", "Brain · Qwen2.5 1.5B", "Voice · Arctic", "Voice · L2-Arctic", "Voice · Kusal", "Hearing fallback", "Backup brain · Qwen2.5 0.5B", "Vision", "Vision projector", "Memory embedder".
URLs: exactly those in `scripts/fetch-models.sh`, except HF URLs use `https://huggingface.co/<repo>/resolve/<commit sha>/<file>` with the commit from `https://huggingface.co/api/models/<repo>` (`sha` field).

- [ ] **Step 1: Write `scripts/make-model-manifest.py`** (Python 3 stdlib only; item table at the top; caches downloads in `bench/host-tools/manifest-cache/` (already git-ignored via `bench/host-tools/`); reuses `models/llm/*.gguf` etc. for HF files when present, after checking the hash against the HF API's `lfs.sha256` from `https://huggingface.co/api/models/<repo>/tree/<sha>`; for archives reads the listing with `tarfile` to fill `mustExist` and `unpackedSize`; writes the asset with sorted keys and 2-space indent).

- [ ] **Step 2: Run it**

Run: `python3 -I scripts/make-model-manifest.py`
Expected: `app/src/main/assets/models/manifest.json` with 15 items, every `sha256` 64 hex chars, sum of `size` ≈ 2.3 GB; every path in `ModelPaths(…).speechRequired()` appears in some required item's `mustExist`.

- [ ] **Step 3: Copy the bundled files** listed under Files; check `keywords.txt` byte-for-byte against the heredoc.

- [ ] **Step 4: Commit**

```bash
git add scripts/make-model-manifest.py app/src/main/assets/models
TZ=UTC git commit -m "feat(setup): pinned model manifest and bundled adapter files"
```

---

### Task 2: Manifest parsing and the setup planner

**Files:**
- Create: `setup/ModelManifest.kt`, `setup/SetupPlanner.kt`
- Modify: `core/ModelPaths.kt` (`PREFERRED_LLM_MODELS`; add `QWEN_15B_MODEL` const)
- Test: `setup/ModelManifestTest.kt`, `setup/SetupPlannerTest.kt`, `core/ModelPathsTest.kt`

**Interfaces:**
- Produces:
  - `enum class ItemKind { FILE, TAR_BZ2 }`
  - `data class ManifestItem(val id: String, val label: String, val required: Boolean, val url: String, val size: Long, val sha256: String, val kind: ItemKind, val dest: String, val unpackedSize: Long, val mustExist: List<String>, val drop: List<String>, val overlay: List<String>)`
  - `data class ModelManifest(val items: List<ManifestItem>) { companion object { fun parse(json: String): ModelManifest } }` — throws `IllegalArgumentException` on unknown `kind`, bad sha (not 64 hex) or empty `mustExist`.
  - `enum class SetupMarker { NONE, RUNNING, EXTRAS_PENDING }`
  - `class SetupPlanner(val root: File, val manifest: ModelManifest)` with
    - `fun marker(): SetupMarker`; `fun writeMarker(m: SetupMarker)` (`NONE` deletes `.setup-state`; writes via `AtomicFiles`)
    - `fun isPresent(item: ManifestItem): Boolean` — every `mustExist` path exists and is non-empty (dirs: non-empty listing)
    - `fun needsSetup(): Boolean` — `marker() == RUNNING` or any `ModelPaths(root).speechRequired()` path missing or no chat model (`File(root, "llm/" + ModelPaths.resolveLlmFileName(root))` absent/empty)
    - `fun pending(): List<ManifestItem>` — manifest order, items not present

- [ ] **Step 1: Write the failing tests**

```kotlin
// ModelManifestTest
@Test fun `parses items in order with all fields`()            // 2-item JSON → ids, kind TAR_BZ2/FILE, mustExist, drop, overlay
@Test fun `rejects a sha256 that is not 64 hex chars`()        // assertThrows<IllegalArgumentException>
@Test fun `the shipped manifest parses and covers speechRequired`() {
    val m = ModelManifest.parse(File("src/main/assets/models/manifest.json").readText())
    val required = m.items.filter { it.required }.flatMap { it.mustExist }.toSet()
    assertTrue(ModelPaths(File("x")).speechRequired().all { it in required })
    assertTrue(m.items.any { it.required && it.dest == "llm/qwen2.5-1.5b-instruct-q4_0.gguf" })
    assertEquals(15, m.items.size)
}
// SetupPlannerTest (@TempDir root; helper fills speechRequired files + one chat model)
@Test fun `phone with speech models and gemma needs no setup`()
@Test fun `empty root needs setup and pending is the whole manifest in order`()
@Test fun `missing chat model alone triggers setup`()
@Test fun `qwen 1_5b alone counts as a chat model`()   // only llm/qwen2.5-1.5b… present + speech → needsSetup false
@Test fun `RUNNING marker forces setup even when all core files exist`()
@Test fun `EXTRAS_PENDING marker does not force setup`()
@Test fun `an empty destination folder counts as missing`()   // Review Focus 5: mkdir asr-live with no files → pending contains asr-live
@Test fun `a present file with a different size than the manifest is not re-fetched`()
// ModelPathsTest
@Test fun `qwen 1_5b is chosen when gemma is absent`()   // resolveLlmFileName → qwen2.5-1.5b-instruct-q4_0.gguf
@Test fun `gemma still wins when present`()
```
(`speechRequired` includes `tts/espeak-ng-data`, a folder: the helper must create it with one file.)

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew testDebugUnitTest --tests 'com.jarvis.edge.setup.*' --tests 'com.jarvis.edge.core.ModelPathsTest'`
Expected: FAIL (unresolved references).

- [ ] **Step 3: Implement** `ModelManifest.parse` with `org.json`; `SetupPlanner` as specified; `PREFERRED_LLM_MODELS = listOf(DEFAULT_LLM_MODEL, QWEN_15B_MODEL, FALLBACK_LLM_MODEL)` with `QWEN_15B_MODEL = "qwen2.5-1.5b-instruct-q4_0.gguf"`.

- [ ] **Step 4: Run tests, verify PASS** (same command, plus the full suite once).

- [ ] **Step 5: Commit** — `feat(setup): manifest and start check`

---

### Task 3: Resumable, verified downloader

**Files:**
- Create: `setup/Downloader.kt`
- Test: `setup/DownloaderTest.kt` (local `com.sun.net.httpserver.HttpServer` on port 0, serving a 300 KB random blob with switchable Range support, redirect, 404, 416 and a served-bytes counter)

**Interfaces:**
- Produces:
  - `sealed class FetchResult { data class Ok(val file: File) : FetchResult(); data class Failed(val reason: String, val retryable: Boolean) : FetchResult() }`
  - `class Downloader(private val freeBytes: (File) -> Long = { it.usableSpace }, private val connectTimeoutMs: Int = 15_000, private val readTimeoutMs: Int = 30_000)` with
    `suspend fun fetch(url: String, size: Long, sha256: String, part: File, onBytes: (Long) -> Unit): FetchResult`
  - Writes `part` (e.g. `llm/x.gguf.part`) and a sidecar `part.path + ".json"` = `{"url":…,"size":…}`; a sidecar whose url or size differs from the call discards the `.part`. On hash mismatch deletes `.part` and returns `Failed("Checksum mismatch", retryable = true)`. On `Ok` the `.part` is fsynced and verified; the caller renames it. Redirects followed manually (up to 5, cross-host). Coroutine cancellation leaves `.part` intact.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `downloads, verifies and reports bytes`()                 // Ok, part bytes == blob, last onBytes == size
@Test fun `resumes from an existing part with a Range request`()    // pre-write first 100_000 bytes + sidecar → server sees "bytes=100000-", served == size - 100_000
@Test fun `server ignoring Range restarts the file cleanly`()      // 200 reply → Ok and content correct (no duplicated prefix)
@Test fun `complete part with 416 goes straight to verification`() // Review Focus 2
@Test fun `checksum mismatch deletes the part`()                    // Failed("Checksum mismatch", true), part absent
@Test fun `follows a redirect to another host`()                   // 302 → second server
@Test fun `HTTP 404 fails with the status in the reason`()          // Review Focus 4: reason == "HTTP 404", retryable == true
@Test fun `stale sidecar for a different url discards the part`()
@Test fun `not enough free space fails before any request`()       // freeBytes = { 10 } → Failed("Not enough space", false), served == 0
@Test fun `disk error while writing keeps the part and fails`()    // Review Focus 3: injected sink throwing IOException("ENOSPC") after 50_000 bytes → Failed("Not enough space", false), part length == 50_000
```
(For the last test add an internal constructor parameter `openSink: (File, Boolean) -> OutputStream = { f, append -> FileOutputStream(f, append) }`.)

- [ ] **Step 2: Run, verify FAIL**: `./gradlew testDebugUnitTest --tests 'com.jarvis.edge.setup.DownloaderTest'`

- [ ] **Step 3: Implement `Downloader.fetch`** on `Dispatchers.IO`; stream in 64 KB chunks with `ensureActive()` per chunk; SHA-256 over the whole file after the last byte (re-read from the start, since resumed bytes were not hashed in this run); IOException whose message contains "ENOSPC" or "No space" → `Not enough space` (not retryable); other IOExceptions → `Failed(message, retryable = true)`. Free-space check: `size - part.length()` must fit.

- [ ] **Step 4: Run, verify PASS**

- [ ] **Step 5: Commit** — `feat(setup): resumable downloader with SHA-256 check`

---

### Task 4: tar.bz2 unpacker and atomic install

**Files:**
- Modify: `app/build.gradle.kts` — `implementation("org.apache.commons:commons-compress:1.27.1")`
- Create: `setup/Unpacker.kt`
- Test: `setup/UnpackerTest.kt` (builds small tar.bz2 fixtures in `@TempDir` with commons-compress)

**Interfaces:**
- Produces:
  - `object Unpacker` with
    - `fun installArchive(archive: File, root: File, item: ManifestItem, bundled: (String) -> ByteArray?)` — unpacks into `<root>/<dest>.tmp/` (deleted first if left over), stripping the first path component; rejects entries escaping the folder (`..`, absolute) with `IOException("Unsafe path in archive")`; skips links; deletes each `drop` path; writes `bundled(path)` over each `overlay` path; checks every `mustExist` path under `.tmp` (else `IOException("Archive missing <path>")`); deletes any existing `<root>/<dest>`, renames `.tmp` → `<dest>`, deletes the archive
    - `fun installFile(part: File, dest: File)` — mkdirs parent, `renameTo` (atomic on the same filesystem), throws `IOException` on failure

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `strips the top folder and installs atomically`()            // top/a.onnx, top/sub/b.txt → root/asr-live/a.onnx, sub/b.txt; no .tmp left; archive deleted
@Test fun `drop removes espeak-ng-data from a voice`()
@Test fun `overlay replaces keywords txt with the bundled copy`()
@Test fun `leftover tmp folder from a crash is replaced`()             // Review Focus 1
@Test fun `archive missing a mustExist file leaves the destination untouched`()  // existing dest kept, IOException
@Test fun `entry with dot-dot is rejected`()
@Test fun `installFile moves part to dest`()
```

- [ ] **Step 2: Run, verify FAIL**: `./gradlew testDebugUnitTest --tests 'com.jarvis.edge.setup.UnpackerTest'`
- [ ] **Step 3: Implement** with `BZip2CompressorInputStream` + `TarArchiveInputStream`.
- [ ] **Step 4: Run, verify PASS**; `./gradlew assembleDebug` succeeds (dependency resolves, no duplicate-class errors).
- [ ] **Step 5: Commit** — `feat(setup): tar.bz2 unpacking with atomic install`

---

### Task 5: Installer, progress and network gate

**Files:**
- Create: `setup/SetupProgress.kt`, `setup/NetworkGate.kt`, `setup/Installer.kt`
- Test: `setup/InstallerTest.kt` (fake `Fetch` and `NetworkGate`; real `Unpacker` with tiny archives; `runTest`; `backoffMs = { 0 }`)

**Interfaces:**
- Consumes: `ModelManifest`, `SetupPlanner`, `FetchResult`, `Unpacker`.
- Produces:
  - `enum class ItemPhase { QUEUED, DOWNLOADING, VERIFYING, UNPACKING, DONE, FAILED }`
  - `data class ItemProgress(val id: String, val label: String, val required: Boolean, val phase: ItemPhase, val bytes: Long, val size: Long, val reason: String? = null)`
  - `enum class NetState { ALLOWED, WAITING_FOR_WIFI, OFFLINE }`
  - `data class SetupProgress(val items: List<ItemProgress>, val net: NetState, val wifiOnly: Boolean, val bytesPerSec: Long, val finished: Boolean, val canStartWithout: Boolean, val blockedBy: String? = null)` with computed `totalBytes`, `doneBytes`, `fraction: Float` (by bytes), `etaSec: Long?` (null when `bytesPerSec == 0`)
  - `interface NetworkGate { fun state(): NetState; var wifiOnly: Boolean }`; `class AndroidNetworkGate(ctx: Context, prefs: SharedPreferences) : NetworkGate` (unmetered = `NET_CAPABILITY_NOT_METERED`; `wifiOnly` persisted as pref `setup_wifi_only`, default true)
  - `fun interface Fetch { suspend fun fetch(url: String, size: Long, sha256: String, part: File, onBytes: (Long) -> Unit): FetchResult }` (`Downloader::fetch` fits)
  - `class Installer(root: File, manifest: ModelManifest, fetch: Fetch, net: NetworkGate, bundled: (String) -> ByteArray?, freeBytes: (File) -> Long = { it.usableSpace }, clockMs: () -> Long = System::currentTimeMillis, backoffMs: (attempt: Int) -> Long = { minOf(2_000L shl it, 60_000L) })` with
    - `val progress: StateFlow<SetupProgress>` (byte updates throttled to ≥ 500 ms apart; phase changes always published)
    - `suspend fun runFirstSetup(): Boolean` — marker RUNNING; free-space check; fetch `planner.pending()` in order; install; copy absent bundled files; on all DONE: active model, marker NONE, return true; while any item is FAILED, suspend until `retry` / `startWithout`
    - `suspend fun runExtrasInBackground()` — for marker EXTRAS_PENDING: fetch pending items; marker NONE when nothing is pending; failures end the run quietly
    - `fun retry(id: String)`; `fun startWithout()` — honoured only when every FAILED item is an extra: marker EXTRAS_PENDING, active model, `runFirstSetup` returns true
  - Rules:
    - Network gate polled every 2 s while not ALLOWED; `progress.net` shows why.
    - Any `Failed` (checksum, HTTP status, IO) counts as an attempt; after 3 the item is FAILED with its reason and the run goes on to the next item. Not-retryable failures (space) fail at once.
    - A FAILED must-have sets `blockedBy` = its label; `canStartWithout` = no pending/active item left and only extras FAILED.
    - Free space, once before the first fetch: sum of pending `size` + 10% + largest pending `unpackedSize`; short → `blockedBy = "Not enough space: need X.X GB, have Y.Y GB"`, nothing fetched; `retry("")` re-checks.
    - Bundled files (`adapters/*.jvec`, `adapters/experts.json`; `kws/keywords.txt` once `kws/` exists) copied only if absent.
    - Active model: write `llm/active_model.txt` = `qwen2.5-1.5b-instruct-q4_0.gguf` only if absent and that model is present.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `fetches pending items in manifest order and finishes`()          // fake fetch records order == manifest ids; returns true; marker NONE
@Test fun `present items are skipped`()
@Test fun `an extra failing three times is marked failed and the run continues`()  // vision FAILED "Checksum mismatch", fetch called 3× for it; later items DONE; canStartWithout
@Test fun `HTTP 404 stops after three attempts`()                         // Review Focus 4
@Test fun `a failed must-have blocks and start without is refused`()       // blockedBy == "Brain · Qwen2.5 1.5B"; startWithout() ignored; still suspended
@Test fun `retry re-runs a failed item`()
@Test fun `start without writes EXTRAS_PENDING and returns`()
@Test fun `background run fetches pending extras and clears the marker`()
@Test fun `waits while network is not allowed`()                         // WAITING_FOR_WIFI → no fetch calls; flip to ALLOWED → proceeds
@Test fun `not enough space blocks before any fetch`()
@Test fun `active model written only when absent`()                       // existing "google_gemma-3-1b-it-Q4_0.gguf" kept
@Test fun `bundled adapters and keywords are copied only if absent`()
@Test fun `progress fraction and eta`()                                    // pure SetupProgress test
```

- [ ] **Step 2: Run, verify FAIL**: `./gradlew testDebugUnitTest --tests 'com.jarvis.edge.setup.InstallerTest'`
- [ ] **Step 3: Implement** `SetupProgress`, `NetworkGate` (+ Android impl, not JVM-tested), `Installer`.
- [ ] **Step 4: Run, verify PASS**, then the full suite.
- [ ] **Step 5: Commit** — `feat(setup): installer with required/extras rules and progress`

---

### Task 6: Service integration and debug model root

**Files:**
- Modify: `app/build.gradle.kts` (`buildFeatures { buildConfig = true }`)
- Modify: `core/ModelPaths.kt` — add `fun rootFor(debug: Boolean, overrideFile: File = File("/sdcard/Jarvis/model_root.txt")): File` in the companion
- Modify: `service/JarvisService.kt:193,295`, `hud/ChatActivity.kt:88` — `ModelPaths.rootFor(BuildConfig.DEBUG)` instead of `File(ModelPaths.DEFAULT_ROOT)`
- Modify: `service/JarvisService.kt` — setup mode
- Test: `core/ModelPathsTest.kt`

**Interfaces:**
- Consumes: `SetupPlanner`, `Installer`, `Downloader`, `AndroidNetworkGate`.
- Produces (companion of `JarvisService`, read by Task 7):
  - `val setup: StateFlow<SetupProgress?>` (null = not in setup mode)
  - `fun setupRetry(context: Context, id: String)`, `fun setupStartWithout(context: Context)`, `fun setupWifiOnly(context: Context, on: Boolean)` — each `startService` with actions `ACTION_SETUP_RETRY` (extra `EXTRA_TEXT` = id), `ACTION_SETUP_START_WITHOUT`, `ACTION_SETUP_WIFI_ONLY` (boolean extra `"on"`)

Behaviour in `startVoicePipeline()`, before the existing `missingSpeech` check: build `SetupPlanner(root, ModelManifest.parse(assets "models/manifest.json"))`.
- `needsSetup()`: create the `Installer` (bundled = read `assets.open("models/bundled/" + path)`, null if absent); mirror `installer.progress` into `_setup` and the notification ("Downloading models · N%", only when N changes); `updateState(IDLE, "Setting up")`; await `runFirstSetup()`; `_setup.value = null`; then fall through into the unchanged pipeline code.
- else `marker() == EXTRAS_PENDING`: launch `runExtrasInBackground()` on `Dispatchers.IO`, continue normally.
- else: unchanged.

- [ ] **Step 1: Write failing tests**

```kotlin
@Test fun `rootFor release ignores the override file`()       // rootFor(false, file "/x") == File(DEFAULT_ROOT)
@Test fun `rootFor debug uses the override path`()            // file "/sdcard/Jarvis/models-dltest\n" → File("/sdcard/Jarvis/models-dltest")
@Test fun `rootFor debug without the file uses the default`()
@Test fun `rootFor debug ignores a relative path`()
```
- [ ] **Step 2: Run, verify FAIL** (`--tests 'com.jarvis.edge.core.ModelPathsTest'`)
- [ ] **Step 3: Implement** `rootFor` and the service changes.
- [ ] **Step 4: Verify** the full unit suite PASSES and `./gradlew assembleDebug` is BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** — `feat(setup): service enters setup mode when the basics are missing`

---

### Task 7: "Reactor charging" setup screen

**Files:**
- Create: `hud/SetupScreen.kt`
- Modify: `hud/HudActivity.kt` (collect `JarvisService.setup`; when non-null show `SetupScreen` inside `JarvisTheme` instead of `HomeHud`)
- Test: `hud/SetupScreenTextTest.kt` (pure text helpers)

**Interfaces:**
- Consumes: `SetupProgress`, `JarvisService.setupRetry / setupStartWithout / setupWifiOnly`.
- Produces: `@Composable fun SetupScreen(progress: SetupProgress, freeBytes: Long, onRetry: (String) -> Unit, onStartWithout: () -> Unit, onWifiOnly: (Boolean) -> Unit)` and pure helpers in the same file:
  - `fun gbText(done: Long, total: Long): String` → `"1.1 of 2.3 GB"` (decimal GB, one decimal)
  - `fun etaText(sec: Long?): String` → `"~9 min left"`, `"<1 min left"` under 60 s, `""` for null
  - `fun stepText(p: SetupProgress): String` → active phase verb + label, lower case, e.g. `"downloading brain · qwen2.5 1.5b"`, `"verifying …"`, `"installing …"` (UNPACKING); `"waiting for Wi-Fi"` when `net == WAITING_FOR_WIFI`; `"offline"`; `"Ready"` when finished

Layout (mockup A, existing theme only — `Jx`, `GlassPanel`, `HudLabel`, `StatusChip`, `PillButton`, `JarvisOrb`):
- Title "Bringing Jarvis online"; chips "ON-DEVICE", Wi-Fi state (`Jx.Mint` allowed / `Jx.Amber` waiting / `Jx.Red` offline), "x.x GB free".
- `JarvisOrb(state = IDLE, micLevel = 0f, size = 220.dp)` inside a `Canvas` ring: track `Jx.Line`, sweep `Jx.Cyan` = fraction × 360°; centre percent (`Jx.Mono`, 34 sp); below: `stepText`, then `gbText · etaText`.
- `GlassPanel` list: DONE ✓ `Jx.Mint`; active row with its own `LinearProgressIndicator`; QUEUED ○ `Jx.TextFaint`; FAILED in `Jx.Red` with reason + `PillButton("Retry")`. Collapsed: "N modules ready" line + active + next 2 + every failed; "tap to expand" toggles the full list.
- `blockedBy` in `Jx.Red` above the list. `PillButton("Start without it")` only when `canStartWithout`.
- Footer `HudLabel("Jarvis wakes when every module checks out")` and a "Wi-Fi only" `Switch`.
- When `finished`: ring full, centre "Ready".

- [ ] **Step 1: Write failing tests**: `gbText(1_100_000_000, 2_300_000_000) == "1.1 of 2.3 GB"`, `etaText(540) == "~9 min left"`, `etaText(30) == "<1 min left"`, `etaText(null) == ""`, `stepText` for downloading the Qwen item, waiting for Wi-Fi, and finished.
- [ ] **Step 2: Run, verify FAIL** (`--tests 'com.jarvis.edge.hud.SetupScreenTextTest'`)
- [ ] **Step 3: Implement** the helpers and the composable; wire `HudActivity`.
- [ ] **Step 4: Verify** tests PASS and `./gradlew assembleDebug` succeeds.
- [ ] **Step 5: Commit** — `feat(hud): reactor-charging setup screen`

---

### Task 8: Device test on the Redmi (separate folder)

No code unless a bug turns up (then: systematic-debugging, a failing unit test, fix, commit).

- [ ] **Step 1:** Record the Redmi's model folder for the "untouched" check: `adb shell 'ls -lR /sdcard/Jarvis/models | md5sum'` → save the hash.
- [ ] **Step 2:** `adb shell 'echo /sdcard/Jarvis/models-dltest > /sdcard/Jarvis/model_root.txt'`; `adb install -r app/build/outputs/apk/debug/app-debug.apk`; restart Jarvis. Expected: the setup screen, Wi-Fi chip green, items in manifest order, notification "Downloading models · N%".
- [ ] **Step 3 (resume):** during the Qwen 1.5B download, `adb shell am force-stop com.jarvis.edge`, reopen; the `.part` size before/after shows it resumed, not restarted. Wi-Fi off → "waiting for Wi-Fi", no growth; Wi-Fi on → continues.
- [ ] **Step 4 (finish):** all rows ✓ → Jarvis starts; "Jarvis, what time is it?" gets a spoken answer; `/sdcard/Jarvis/models-dltest/llm/active_model.txt` = `qwen2.5-1.5b-instruct-q4_0.gguf`; no `.part`, `.tmp` or `.setup-state` left.
- [ ] **Step 5 (untouched):** `adb shell rm /sdcard/Jarvis/model_root.txt`; restart → normal start on Gemma, no setup screen; the Step 1 hash is unchanged. Then, after checking the path, `adb shell rm -r /sdcard/Jarvis/models-dltest` to free 2.3 GB.
- [ ] **Step 6:** Add a "Device test" section with the results (time taken, resume evidence) to the spec; commit — `docs(setup): Redmi device test results`.

Pixel 7a (later, by the user, no laptop): install the APK file, open Jarvis, accept the storage permission, run the full first-run flow.
