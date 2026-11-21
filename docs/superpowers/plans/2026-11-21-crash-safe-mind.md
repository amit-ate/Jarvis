# Crash-Safe Mind Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Per the user's preference, dispatch implementers for small tasks with `model: "haiku"`.

**Goal:** If the app is killed, crashes or the phone reboots, Jarvis comes back with its memory, its conversation (its "train of thought"), the model's working memory (KV cache), a pending "Did you say…?" and its learning progress. At most the sentence being spoken is lost.

**Architecture:**
- The durable store is the existing `SqliteMindStore`, switched to WAL mode.
- Episodes are written *before* Jarvis speaks.
- A small `WorkingState` JSON snapshot lives in the `meta` table under the key `working_state`.
- The llama.cpp KV cache is saved and loaded with `llama_state_seq_save_file` / `llama_state_seq_load_file`, under a key that includes the model, prompt and adapter. It is only an optimization; the text in `WorkingState` is the source of truth.
- Every file is written atomically (temp file → fsync → rename).
- A recovery step at startup detects an unclean shutdown, cleans up orphaned files and writes a journal entry.

**Tech Stack:** Kotlin, Android API 28, SQLite, org.json, llama.cpp JNI (C++17), JUnit 5.

**Spec:** `docs/superpowers/specs/2026-11-20-living-jarvis-design.md`, section "Surviving abrupt shutdown"

## Prerequisite

`JarvisService.kt`, `MindScreen.kt`, `MindUiBridge.kt`, `MindTools.kt`, `MindUiBridgeTest.kt` and `bench/eval/experts.json` have **uncommitted work by the user** (the evolution-cycle wiring). Do not start until the user has committed or stashed it. Never `git add` those files as part of this plan unless a task modifies them.

## Global Constraints

- Everything runs on the phone. No network for any of this.
- Checkpoint files are written to a temp file, fsynced, then atomically renamed. A corrupt or mismatched file is discarded and journaled, never loaded.
- A half-trained adapter is never activated.
- Quick commands must not get slower: no checkpoint I/O on the turn's critical path except the single episode insert.
- JDK for Gradle: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`.
- JVM tests: `./gradlew testDebugUnitTest`. Device tests: `./gradlew connectedDebugAndroidTest` (phone serial `<serial>`).

## Review Focus

1. **The app is killed between "transcribed" and "spoke".** After restart the episode exists with an empty reply and `outcomeOk=false`. Reflection does not learn a fact from it. *(Task 2 test.)*
2. **The KV file is from another model, prompt or adapter** (e.g. the evolution cycle activated a new `.jvec` overnight). It must not load; the cache is rebuilt from text. *(Task 5 key test.)*
3. **Truncated or garbage checkpoint files** (a power cut mid-write left an old `.tmp`). The loader returns null/false, no exception reaches `onCreate`, and the `.tmp` file is deleted. *(Tasks 1, 3, 5.)*
4. **The orphan cleanup deletes only what it should.** It must never delete expert adapters from `experts.json` or the active version. It only deletes `brain_v*.jvec` files that no `model_versions` row references. *(Task 4 test.)*
5. **Restored history is older than the 2-minute idle reset.** Restore respects `Brain.HISTORY_IDLE_RESET_MS`, so Jarvis doesn't continue a conversation from yesterday. *(Task 3 test.)*

---

### Task 1: Atomic file writes

**Files:**
- Create: `app/src/main/java/com/jarvis/edge/core/AtomicFiles.kt`
- Modify: `app/src/main/java/com/jarvis/edge/brain/ControlVectorCodec.kt` (`write`)
- Modify: `app/src/main/java/com/jarvis/edge/mind/BrainEvolver.kt` (`writeJvec`)
- Test: `app/src/test/java/com/jarvis/edge/core/AtomicFilesTest.kt`

**Interfaces:**
- Produces:
  - `object AtomicFiles { fun write(target: File, bytes: ByteArray); fun writeText(target: File, text: String); fun cleanupTemps(dir: File): Int }`
  - The temp file is `File(target.parentFile, target.name + ".tmp")`.
  - `write` uses `FileOutputStream` → `fd.sync()` → `renameTo`. If the rename fails it throws `IOException` and the target is left untouched.

- [ ] **Step 1: Write the failing tests**
  - `write replaces content atomically`: write "a", then "b"; read back "b"; no `.tmp` left behind.
  - `stale tmp is cleaned`: create `x.bin.tmp`; `cleanupTemps(dir)` returns 1 and the file is gone, while `x.bin` stays.
  - `ControlVectorCodec write then read roundtrips` still passes (existing test), and no `.tmp` remains.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*AtomicFilesTest*'`. Expected: FAIL (unresolved `AtomicFiles`).
- [ ] **Step 3: Implement `AtomicFiles`**, then route `ControlVectorCodec.write` and `BrainEvolver.writeJvec` through `AtomicFiles.write(file, buffer.array())`.
- [ ] **Step 4: Run** `./gradlew testDebugUnitTest`. Expected: PASS, including the existing `ControlVectorCodecTest` and `BrainEvolverTest`.
- [ ] **Step 5: Commit** `feat(core): atomic temp+fsync+rename writes for adapters`

### Task 2: Durable episodes (WAL, written before speaking)

**Files:**
- Modify: `app/src/main/java/com/jarvis/edge/mind/MindStore.kt` (`MindRepository`, `InMemoryMindStore`, `SqliteMindStore`)
- Test: `app/src/test/java/com/jarvis/edge/mind/MindStoreTest.kt`

**Interfaces:**
- Produces, on `MindRepository`:
  - `fun beginEpisode(timestampMs: Long, live: String, accurate: String, chosen: String): Long`
    - Inserts an `Episode` with `reply = ""`, `outcomeOk = false`, `reflected = false`.
    - Returns the id.
  - `fun completeEpisode(id: Long, reply: String, toolName: String?, toolArgsJson: String?, echoed: Boolean)`
    - Fills the reply and sets `outcomeOk = !echoed`.
  - `fun listInterruptedEpisodes(sinceMs: Long): List<Episode>`
    - Returns episodes with `reply == ""` and `timestampMs >= sinceMs`.
- On `SqliteMindStore`:
  - `override fun onConfigure(db)` calls `db.enableWriteAheadLogging()` and `db.execSQL("PRAGMA synchronous=NORMAL")`.
  - The existing `addEpisode` stays (used by tests and legacy paths).
- Reflection rule: `ReflectionWorker` must skip episodes whose `reply` is empty when extracting facts. It still marks them reflected.

- [ ] **Step 1: Write the failing tests** (`InMemoryMindStore`)
  - `begun but not completed episode is listed as interrupted`
  - `completed episode keeps reply and outcome`: `completeEpisode(id, "It's sunny", null, null, echoed = false)` → reply is "It's sunny", `outcomeOk` is true.
  - `ReflectionWorkerTest`: `interrupted episode yields no facts`. A begun-only episode whose text is "my name is Amy" yields `countActiveFacts() == 0` after `reflectOnce`, and the episode is marked reflected.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*MindStoreTest*' --tests '*ReflectionWorkerTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement** the three methods in both stores, `onConfigure` in `SqliteMindStore`, and the empty-reply skip in `ReflectionWorker`.
- [ ] **Step 4: Run** `./gradlew testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(mind): WAL + write episode before speaking`

### Task 3: Working-state snapshot (conversation + pending confirmation + mute)

**Files:**
- Create: `app/src/main/java/com/jarvis/edge/mind/WorkingState.kt`
- Modify: `app/src/main/java/com/jarvis/edge/brain/Brain.kt`
- Modify: `app/src/main/java/com/jarvis/edge/core/TurnController.kt`
- Modify: `app/src/main/java/com/jarvis/edge/mind/InitiativeEngine.kt`
- Test: `app/src/test/java/com/jarvis/edge/mind/WorkingStateTest.kt`

**Interfaces:**
- Produces:
  - `data class WorkingState(val version: Int = 1, val savedAtMs: Long, val history: List<Turn>, val lastTurnMs: Long, val pendingHeard: String?, val pendingCandidate: String?, val initiativeMutedUntilMs: Long, val openEpisodeId: Long?)`
  - `object WorkingStateCodec { fun encode(s: WorkingState): String; fun decode(json: String): WorkingState? }`. `decode` returns null on any parse error or unknown `version`.
  - `class WorkingStateStore(private val repo: MindRepository) { fun save(s: WorkingState); fun load(): WorkingState? }`, using meta key `"working_state"`.
  - `Brain.snapshotHistory(): Pair<List<Turn>, Long>`: the turns plus `lastTurnMillis`.
  - `Brain.restoreHistory(turns: List<Turn>, lastTurnMs: Long)`: a no-op if `clockMillis() - lastTurnMs > HISTORY_IDLE_RESET_MS`.
  - `TurnController.snapshotPending(): Pair<String, String>?` and `TurnController.restorePending(heard: String, candidate: String)`.
  - `InitiativeEngine.mutedUntilMs(): Long` and `InitiativeEngine.restoreMute(untilMs: Long)`.

- [ ] **Step 1: Write the failing tests**
  - `codec roundtrip`: a state with 2 turns and a pending confirmation → `encode` → `decode` gives an equal state.
  - `garbage json decodes to null`: input `"{not json"`.
  - `unknown version decodes to null`: `version` 99.
  - `BrainTest`:
    - `restored history is used in next prompt`: restore `[Turn("my dog is Rex", "SAY: Nice.")]` with `lastTurnMs = now - 10_000`; the next prompt contains "my dog is Rex".
    - `stale history is not restored`: `lastTurnMs = now - 3_600_000` → the prompt does not contain it.
  - `TurnControllerTest`: `restored pending confirmation accepts yes`. After `restorePending("set timer fifteen", "set a timer for 15 minutes")`, the live transcript "yes" runs the candidate.
- [ ] **Step 2: Run** the tests. Expected: FAIL.
- [ ] **Step 3: Implement** using `org.json.JSONObject`, and expose the existing private `PendingConfirmation` through the two TurnController methods.
- [ ] **Step 4: Run** `./gradlew testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(mind): working-state snapshot of conversation and pending confirmation`

### Task 4: Recovery on startup (unclean-shutdown detection, orphan cleanup, journal)

**Files:**
- Create: `app/src/main/java/com/jarvis/edge/mind/Recovery.kt`
- Modify: `app/src/main/java/com/jarvis/edge/mind/MindStore.kt` (add `recordRecoveryJournal` to both stores)
- Modify: `app/src/main/java/com/jarvis/edge/mind/SelfModel.kt`
- Test: `app/src/test/java/com/jarvis/edge/mind/RecoveryTest.kt`

**Interfaces:**
- Consumes:
  - `AtomicFiles.cleanupTemps` (Task 1)
  - `listInterruptedEpisodes` (Task 2)
  - `WorkingStateStore` (Task 3)
- Produces:
  - `MindRepository.recordRecoveryJournal(summary: String): JournalEntry`, with `type = "recovery"`, `targetTable = "meta"` and `targetId = 0`.
  - `class Recovery(repo: MindRepository, adaptersDir: File, kvDir: File, clockMs: () -> Long)`:
    - `fun onStart(): RecoveryReport`:
      - reads meta `"clean_shutdown"`;
      - an unclean shutdown is when the value is `"0"`; a missing value means first run, which is not unclean;
      - then sets `"clean_shutdown"` to `"0"` and `"last_start_ms"`;
      - cleans temps in both dirs and orphan adapters;
      - journals a summary if the shutdown was unclean.
    - `fun onCleanStop()` sets `"clean_shutdown"` to `"1"`.
    - `fun cleanOrphanAdapters(): Int` deletes only files matching `Regex("brain_v\\d+\\.jvec")` whose absolute path is not the `adapterPath` of any `listModelVersions()` row.
  - `data class RecoveryReport(val unclean: Boolean, val lastAliveMs: Long?, val interruptedEpisodes: Int, val orphansRemoved: Int, val tempsRemoved: Int)`
  - Journal summary text (exact): `"I was shut down unexpectedly at HH:mm; restored my conversation and learning progress."`. Add `" (N unfinished replies)"` when N > 0. `lastAliveMs` comes from `WorkingState.savedAtMs`.
  - `SelfModel`: an optional `recoveredAtMs: Long?` field. `toPromptLine` appends `"recovered from unexpected shutdown"` while `now - recoveredAtMs < 10 min`.

- [ ] **Step 1: Write the failing tests**
  - `first run is not unclean`
  - `missing clean flag after start is unclean`: `onStart()`, then `onStart()` again with no `onCleanStop` → `unclean == true`, and one journal entry of type "recovery".
  - `clean stop then start is clean`
  - `orphan cleanup spares experts and referenced versions`: the dir holds `brain_v1.jvec` (referenced), `brain_v2.jvec` (unreferenced), `chat.jvec` (an expert) and `brain_v3.jvec.tmp` → `orphansRemoved == 1`, `tempsRemoved == 1`, and `brain_v1.jvec` and `chat.jvec` remain.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(mind): detect unclean shutdown, clean orphans, journal recovery`

### Task 5: Persist the model's working memory (KV cache)

**Files:**
- Modify: `app/src/main/cpp/llama_jni.cpp`
- Modify: `app/src/main/java/com/jarvis/edge/brain/LlmEngine.kt`
- Create: `app/src/main/java/com/jarvis/edge/brain/KvStateManager.kt`
- Test: `app/src/test/java/com/jarvis/edge/brain/KvStateManagerTest.kt`
- Test: `app/src/androidTest/java/com/jarvis/edge/brain/LlmEngineDeviceTest.kt`

**Interfaces:**
- **JNI:**
  - `nativeSaveState(handle: Long, path: String): Boolean`
    - Calls `llama_state_seq_save_file(ctx, tmpPath, 0, prev_tokens.data(), prev_tokens.size())`, then fsync and `rename(tmpPath, path)`.
    - Returns false if `prev_tokens` is empty.
  - `nativeLoadState(handle: Long, path: String): Boolean`
    - Clears memory, then calls `llama_state_seq_load_file(ctx, path, 0, buf, n_ctx, &n_out)`.
    - On success it sets `session->prev_tokens = buf[0..n_out)`.
    - On failure it clears memory and `prev_tokens`, then returns false.
- **Kotlin:**
  - On `LlmEngine`: `fun saveState(file: File): Boolean = false` and `fun loadState(file: File): Boolean = false` (defaults keep the test fakes compiling). `LlamaJniEngine` overrides both.
  - `class KvStateManager(kvDir: File)`:
    - `fun key(modelFile: File, systemPrompt: String, adapterId: String?): String` is the SHA-256 hex (first 16 chars) of `"${modelFile.name}|${modelFile.length()}|${modelFile.lastModified()}|${systemPrompt.hashCode()}|${adapterId ?: "none"}"`.
    - `fun fileFor(key: String): File` returns `kv-<key>.bin`.
    - `fun save(engine: LlmEngine, key: String): Boolean`
    - `fun restore(engine: LlmEngine, key: String): Boolean`
    - `fun pruneExcept(key: String): Int`
  - `adapterId` is the expert router's active summary plus the active model version id. Any change to either must change the key.

- [ ] **Step 1: Write the failing JVM tests**
  - `key changes with prompt, model size, and adapter`
  - `restore with missing file returns false without calling engine`
  - `pruneExcept removes other kv files only`: `kv-a.bin`, `kv-b.bin` and `other.bin` with key a leave `kv-a.bin` and `other.bin`.
- [ ] **Step 2: Write the device test** `stateRoundtripSkipsPrefill`:
  1. Load the model and generate once with `prompt(emptyList(), "hello")`.
  2. `saveState(f)`, unload, load again, `loadState(f) == true`.
  3. Generate with `prompt(emptyList(), "what is two plus two")`.
  4. Assert `stats.cachedTokens >= 100`.

  Also `corruptStateFileIsRejected`: write 100 random bytes → `loadState == false`, and generating afterwards still works.
- [ ] **Step 3: Run** `./gradlew testDebugUnitTest --tests '*KvStateManagerTest*'`. Expected: FAIL.
- [ ] **Step 4: Implement** the JNI functions, the `LlmEngine` defaults and `KvStateManager`.
- [ ] **Step 5: Run** the JVM tests (PASS), then `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.jarvis.edge.brain.LlmEngineDeviceTest` on the phone. Expected: PASS, with first-token time after restore ≤ 1500 ms.
- [ ] **Step 6: Commit** `feat(brain): save/restore llama KV cache keyed by model+prompt+adapter`

### Task 6: Wire it into JarvisService

**Files:**
- Modify: `app/src/main/java/com/jarvis/edge/service/JarvisService.kt` (only after the Prerequisite)

**Interfaces:**
- Consumes: everything from Tasks 2–5.
- Behaviour:
  - **Start**, in this order:
    1. `Recovery.onStart()`
    2. load the model
    3. `KvStateManager.restore`, before the existing warm-up `generate`
    4. `WorkingStateStore.load()`, then `Brain.restoreHistory`, `TurnController.restorePending` and `InitiativeEngine.restoreMute`
    5. if `report.unclean`, set `SelfModel.recoveredAtMs`
  - **Each turn:**
    - `onTranscript` → `beginEpisode` (store the id in `openEpisodeId`).
    - `onEpisodeComplete` → `completeEpisode(openEpisodeId, …)` instead of `addEpisode`.
    - Then `WorkingStateStore.save` on `Dispatchers.IO`.
  - **Idle save:** 3 s after the state returns to IDLE with no new turn, call `KvStateManager.save` on the LLM thread, with the same single-flight guard as generation. Skip it if `evolutionRunning` is set.
  - **After the evolution cycle** activates a version: recompute the key and `pruneExcept`.
  - **`onTrimMemory(level)`** at `TRIM_MEMORY_RUNNING_LOW` or above: `WorkingStateStore.save` and `KvStateManager.save`.
  - **`onDestroy`:** `WorkingStateStore.save`, then `KvStateManager.save` with a 2 s timeout, then `Recovery.onCleanStop()`.
  - **Initiative:** if `report.unclean` and the user is present, it may say once: `"I was shut down unexpectedly, but I've restored where we were."` This is subject to the existing quiet-hours and rate rules.

- [ ] **Step 1: Implement the wiring.**
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest assembleDebug`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 3: Manual crash test on the phone**
  1. Say "my favourite colour is green", then "what did I just say?" → it answers.
  2. Run `adb -s $ANDROID_SERIAL shell am force-stop com.jarvis.edge`, then restart the service.
  3. Expected:
     - the Mind screen journal shows the recovery entry;
     - "what's my favourite colour?" within 2 min answers green, from history and memory;
     - logcat shows the KV restore with `cached>=100` on the first turn.
- [ ] **Step 4: Abrupt-mid-reply test:** force-stop while it is speaking a long answer. After restart, the Mind screen shows the episode with an empty reply, and no fact was learned from it.
- [ ] **Step 5: Commit** `feat(service): crash-safe startup, checkpoints on turn/idle/trim/destroy`
