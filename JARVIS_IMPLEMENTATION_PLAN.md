# Jarvis On-Device Assistant Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the Redmi Note 5 Pro (`whyred`, serial `<serial>`) into an always-on voice-and-vision assistant that runs entirely on the phone, with no Mac or server running.

**Architecture:** One native Kotlin app (`com.jarvis.edge`) with a foreground service that owns the microphone and runs wake word → speech segmenting → transcription → brain → speech. The brain answers common commands with a rule-based fast path in well under a second, and sends everything else to a small language model running in-process through llama.cpp, constrained by a grammar so it can only reply in speech or call a known tool. A full-screen HUD shows state; the camera and vision model load only on request.

**Tech Stack:** Kotlin, Jetpack Compose, Android SDK (compileSdk 34, targetSdk 28, minSdk 28), NDK + CMake, llama.cpp (JNI, CPU only), sherpa-onnx Android AAR (wake word, VAD, speech-to-text, Piper text-to-speech), CameraX, ML Kit text recognition (bundled model), JUnit 5 for JVM tests.

**Spec:** `HARDWARE_AND_JARVIS_REPORT.md` (hardware facts and Mode 1 pipeline, section 8.2) plus the "Design Decisions" section below, which records the calls made where the report left a choice open or was wrong.

## Design Decisions

These are my calls, not things you agreed to. Each one is here so you can overrule it.

| Decision | Choice | Why the alternatives lose |
| :--- | :--- | :--- |
| Where the brain runs | On the phone only | You don't want the Mac running. The report's recommended Mode 2 is dropped. |
| App shape | One APK with llama.cpp linked in through JNI | Termux + `llama-server` is two apps that MIUI 11 kills independently, and it needs manual restarts. One foreground service survives. |
| Model acceleration | CPU only, 4 threads pinned to `cpu4`–`cpu7` | llama.cpp's OpenCL backend targets newer Adreno chips than the 509; Vulkan on Android 9 Adreno 5xx drivers is unreliable. Not worth the risk. |
| Model choice | Decided by measurement in Task 2, not picked up front | The report's speeds are estimates. The rule is in Task 2. |
| Common commands | Rule-based fast path, no model involved | A 1–1.5B model on this CPU takes seconds and sometimes picks the wrong tool. Time, timers, TV, torch and volume must be instant and never wrong. |
| Model output | Grammar-constrained: either `SAY: <text>` or one tool-call JSON | Small models produce broken JSON without a grammar. |
| After a tool runs | The tool returns a ready-to-speak sentence; no second model pass | Halves latency. Only `read_text` and `look` feed results back to the model. |
| System prompt | 200 tokens or fewer, kept in the model's cache between turns | Prompt reading on this CPU is roughly 20–40 tokens/s, so a long prompt would add 10+ seconds to every turn. |
| Wake word | sherpa-onnx keyword spotter with "hey jarvis" | Same library as the rest of the speech stack. Fallback if it fails the Task 5 gate: openWakeWord's pretrained `hey_jarvis` model on ONNX Runtime. |
| Talking over Jarvis | Mic is closed while Jarvis speaks; tap the screen to interrupt | Echo cancellation good enough for voice barge-in is a project of its own on this hardware. |
| Presence sensing | Proximity sensor and ambient light only | Always-on camera vision heats the phone and buys little. |
| Vision | On request only: ML Kit for reading text, SmolVLM-500M for "what is this" | The vision model is loaded for the request and unloaded after, so it never competes for RAM. |
| Internet | Not required. Nothing in v1 calls a network service | Keeps "everything on the mobile" literal. A cloud brain could be added later behind the `LlmEngine` interface. |
| Root / custom ROM | Not used | Stock MIUI 11 with a locked bootloader is enough. Unlocking wipes the phone. |

## Global Constraints

- Device: Android 9 (API 28), MIUI `V11.0.5.0.PEIMIXM`, `arm64-v8a` only, user build, no root.
- CPU features are `fp asimd aes pmull sha1 sha2 crc32` only. Native code is built with `-march=armv8-a`; no `dotprod`, `fp16` or `i8mm`.
- Heavy inference uses exactly 4 threads with affinity mask `0xf0` (`cpu4`–`cpu7`). Wake word and VAD run on `cpu0`–`cpu3` (mask `0x0f`).
- Model weights are loaded natively via `mmap`, never into a Java `byte[]` (Dalvik heap limit is 256 MB).
- Models are not bundled in the APK. They live in `/sdcard/Jarvis/models/` (survives uninstalling the app; read with `READ_EXTERNAL_STORAGE`) and are pushed with `adb`.
- Total resident model RAM stays at or below 1.7 GB in normal operation and 2.6 GB while the vision model is also loaded (3.38 GB was available when checked on 2026-11-16).
- The chat model stays loaded with its cached system prompt. It is unloaded only when available memory drops below 900 MB.
- Audio capture is 16 kHz, mono, `PCM_16_BIT`, source `VOICE_RECOGNITION`.
- The camera stays closed unless a vision tool is running.
- No network calls at runtime.
- Latency targets, measured from the moment the speaker actually stops talking (so the silence wait counts) to first audio out: fast path 1.0 s or less; model path 3.5 s or less.
- Package name `com.jarvis.edge`. All `adb` commands pass `-s <serial>`.

## Review Focus

1. **Empty or garbage transcript** (cough, TV noise after a false wake). Expected: Jarvis says "Sorry, I didn't catch that" once and returns to idle without calling the model. Test in Task 9.
2. **Model names a tool or argument value that doesn't exist** (e.g. `ir_send` for a device not configured). Expected: a spoken explanation, no crash, no IR sent. Tests in Tasks 7 and 10.
3. **A model file is missing or corrupt at start.** Expected: the HUD names the missing file, and the fast path still works without the language model. Test in Task 9.
4. **Microphone unavailable** (phone call, another app holds it). Expected: HUD shows "Mic unavailable", service retries every 5 s and recovers without a restart. Test in Task 5.
5. **Service killed or phone rebooted.** Expected: Jarvis is listening again within 60 s of boot or kill with no touch needed. Test in Task 13.

## File Structure

```
Jarvis/
  HARDWARE_AND_JARVIS_REPORT.md        existing spec
  JARVIS_IMPLEMENTATION_PLAN.md        this file
  scripts/
    setup-toolchain.sh                 JDK 17, Android SDK/NDK/CMake on the Mac
    device-prep.sh                     one-time phone settings over adb
    fetch-models.sh                    download models to ./models (git-ignored)
    push-models.sh                     adb push ./models to the phone
  bench/
    build-llama-android.sh             cross-compile llama-bench for arm64
    run-llm-bench.sh                   run candidates on the phone, print table
    results.md                         measured numbers and the chosen models
  third_party/llama.cpp                git submodule, pinned commit
  app/
    build.gradle.kts
    libs/sherpa-onnx.aar
    src/main/AndroidManifest.xml
    src/main/cpp/CMakeLists.txt
    src/main/cpp/llama_jni.cpp
    src/main/java/com/jarvis/edge/
      core/AssistantState.kt           state enum + allowed transitions
      core/SentenceSplitter.kt         token stream -> whole sentences
      core/TurnController.kt           one voice turn, start to finish
      core/ModelPaths.kt               locate and validate model files
      audio/MicStream.kt               AudioRecord -> Flow<FloatArray>
      audio/WakeWord.kt                sherpa keyword spotter wrapper
      audio/SpeechSegmenter.kt         Silero VAD: one utterance after wake
      audio/Transcriber.kt             sherpa offline speech-to-text
      audio/Speaker.kt                 Piper TTS + AudioTrack
      brain/ToolCall.kt                data types + model-output parser
      brain/FastIntents.kt             rule-based command matcher
      brain/PromptBuilder.kt           system prompt, history, grammar
      brain/LlmEngine.kt               interface + JNI implementation
      brain/Brain.kt                   fast path, then model, then tools
      tools/Tool.kt, ToolRegistry.kt
      tools/TimeTools.kt, TimerTools.kt, DeviceTools.kt, NotesTools.kt
      tools/ir/IrCodes.kt, IrEncoder.kt, IrTool.kt
      tools/VisionTools.kt
      vision/CameraSnapshot.kt, Ocr.kt, Vlm.kt
      hud/HudActivity.kt, Orb.kt, SetupActivity.kt
      service/JarvisService.kt, BootReceiver.kt, HealthGuard.kt
    src/test/java/com/jarvis/edge/...  JVM unit tests
    src/androidTest/java/com/jarvis/edge/...  on-device tests
```

---

## Phase 0 — Prove it on the hardware

### Task 1: Toolchain, repository and phone preparation

**Files:**
- Create: `scripts/setup-toolchain.sh`, `scripts/device-prep.sh`, `.gitignore`

**Produces:** `ANDROID_HOME=$HOME/Library/Android/sdk` with `platforms;android-34`, `build-tools;34.0.0`, `platform-tools`, `cmake;3.22.1` and the current LTS NDK; `JAVA_HOME` pointing at JDK 17; a git repository in this folder.

- [ ] **Step 1:** `git init`, add `.gitignore` covering `models/`, `build/`, `.gradle/`, `local.properties`, `bench/out/`. Commit the report and this plan.
- [ ] **Step 2:** Write `scripts/setup-toolchain.sh`: `brew install openjdk@17`, `brew install --cask android-commandlinetools`, then `sdkmanager` for the packages above. Record the exact NDK version installed in `bench/results.md`.
- [ ] **Step 3:** Run it. Verify: `java -version` prints 17 and `ls $ANDROID_HOME/ndk` lists one version.
- [ ] **Step 4:** Write `scripts/device-prep.sh` with these commands, each followed by a read-back check:

```bash
S="adb -s $ANDROID_SERIAL shell"
$S cmd appops set com.google.android.googlequicksearchbox RECORD_AUDIO ignore
$S am force-stop com.google.android.googlequicksearchbox
$S settings put global stay_on_while_plugged_in 7
$S dumpsys deviceidle whitelist +com.jarvis.edge      # re-run after first install
$S cmd appops set com.jarvis.edge RUN_ANY_IN_BACKGROUND allow
```

- [ ] **Step 5:** Run it. Verify: `adb -s $ANDROID_SERIAL shell dumpsys media.audio_policy | grep -c "HOTWORD"` prints `0`.
- [ ] **Step 6:** Commit.

**Manual steps on the phone (MIUI has no adb switch for these):** Security app → Permissions → Autostart → enable Jarvis; Settings → Battery → App battery saver → Jarvis → No restrictions; lock the app in the recents screen. Do these after the first install in Task 4.

### Task 2: Measure language-model speed and pick the model

**Files:**
- Create: `bench/build-llama-android.sh`, `bench/run-llm-bench.sh`, `bench/results.md`, `scripts/fetch-models.sh`
- Add submodule: `third_party/llama.cpp`

**Produces:** `bench/results.md` naming `LLM_MODEL` (one GGUF filename) used by every later task.

- [ ] **Step 1:** Add llama.cpp as a submodule at its latest release tag; record the commit in `bench/results.md`.
- [ ] **Step 2:** Write `bench/build-llama-android.sh`: CMake with the NDK toolchain file, `-DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DCMAKE_C_FLAGS=-march=armv8-a -DCMAKE_CXX_FLAGS=-march=armv8-a -DBUILD_SHARED_LIBS=OFF`, plus `-DCMAKE_BUILD_TYPE=Release`, target `llama-bench`. Output to `bench/out/`. Build a second copy with `-march=armv8-a -mtune=cortex-a73` into `bench/out-a73/`; Step 5 runs the first candidate with both, and the faster build's flags are used from then on (also in Task 8).
- [ ] **Step 3:** Write `scripts/fetch-models.sh` to download the candidates below from Hugging Face into `models/llm/`. Confirm each repository and filename exists at download time; drop any that don't.

| Candidate | Quantizations to fetch |
| :--- | :--- |
| Qwen2.5-0.5B-Instruct | Q4_0, Q4_K_M |
| Llama-3.2-1B-Instruct | Q4_0, Q4_K_M |
| Gemma-3-1B-it | Q4_0, Q4_K_M |
| LFM2-1.2B | Q4_0, Q4_K_M |
| Qwen2.5-1.5B-Instruct | Q4_0, Q4_K_M |
| Qwen3-1.7B (thinking off) | Q4_0, Q4_K_M |

Do not assume Q4_0 wins: llama.cpp's repacked Q4_0 fast path needs the `dotprod` instruction, which this CPU lacks. The measurement decides.

- [ ] **Step 4:** Write `bench/run-llm-bench.sh`: push `llama-bench` and each model to `/data/local/tmp/jarvis/`, run `taskset f0 ./llama-bench -m <model> -t 4 -p 256 -n 64 -r 3 -o md`, read battery temperature before and after, append rows to `bench/results.md`, delete the model from `/data/local/tmp` afterwards.
- [ ] **Step 5:** Run it. Verify: `bench/results.md` has one row per file with prompt tokens/s, generation tokens/s and temperature rise.
- [ ] **Step 6:** Apply the selection rule and write the result into `bench/results.md` as `LLM_MODEL=`:
  - Keep candidates with generation of 7 tokens/s or more and prompt reading of 25 tokens/s or more.
  - Among those, take the one with the most parameters; between quantizations of the same model, take the faster.
  - If none qualifies, take Qwen2.5-0.5B.
- [ ] **Step 7:** Thermal check: run the winner in a loop for 10 minutes with 20 s idle between runs. Verify generation speed in the last run is within 15% of the first and battery temperature stays below 42°C. If not, step down one model size and repeat.
- [ ] **Step 8:** Commit scripts and results.

### Task 3: Fetch and push the speech and vision models

**Files:**
- Modify: `scripts/fetch-models.sh`
- Create: `scripts/push-models.sh`

**Produces:** on the phone, under `/sdcard/Jarvis/models/`: `kws/`, `vad/silero_vad.onnx`, `asr-moonshine/`, `asr-zipformer/`, `tts/`, `llm/<LLM_MODEL>`, `vlm/` (model + mmproj).

- [ ] **Step 1:** Extend `fetch-models.sh` with the sherpa-onnx release assets: keyword spotter `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01`, `silero_vad.onnx`, `sherpa-onnx-moonshine-tiny-en-int8`, `sherpa-onnx-streaming-zipformer-en-20M-2023-02-17`, `vits-piper-en_GB-alan-medium`; and SmolVLM-500M-Instruct GGUF (Q8_0) with its mmproj file. Confirm names against the sherpa-onnx releases page at download time.
- [ ] **Step 2:** Write `push-models.sh` using `adb push --sync`. Add `pm grant com.jarvis.edge android.permission.READ_EXTERNAL_STORAGE` to `scripts/device-prep.sh` (it takes effect once the app is installed in Task 4).
- [ ] **Step 3:** Run both. Verify: `adb -s $ANDROID_SERIAL shell du -sh /sdcard/Jarvis/models/*` lists all seven entries.
- [ ] **Step 4:** Commit.

---

## Phase 1 — Hear and speak

### Task 4: App skeleton, state machine and service

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `core/AssistantState.kt`, `core/ModelPaths.kt`, `service/JarvisService.kt`, `hud/HudActivity.kt`
- Test: `app/src/test/java/com/jarvis/edge/core/AssistantStateTest.kt`, `ModelPathsTest.kt`

**Produces:**
- `enum class AssistantState { IDLE, LISTENING, THINKING, SPEAKING, ERROR }`
- `fun AssistantState.canMoveTo(next: AssistantState): Boolean`
- `class ModelPaths(root: File) { fun require(relative: String): File; fun missing(): List<String> }` — `require` throws `MissingModelException(relative)`.
- `JarvisService.state: StateFlow<AssistantState>` and `JarvisService.message: StateFlow<String>`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `idle can only start listening or error`() {
    assertTrue(IDLE.canMoveTo(LISTENING)); assertTrue(IDLE.canMoveTo(ERROR))
    assertFalse(IDLE.canMoveTo(SPEAKING)); assertFalse(IDLE.canMoveTo(THINKING))
}
@Test fun `every state can return to idle`() {
    AssistantState.values().forEach { assertTrue(it.canMoveTo(IDLE)) }
}
@Test fun `missing lists absent model files by relative path`() {
    val paths = ModelPaths(tempDir)           // empty directory
    assertTrue("vad/silero_vad.onnx" in paths.missing())
}
```

- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest`. Expected: compile failure.
- [ ] **Step 3:** Implement. Allowed moves: `IDLE→LISTENING`, `LISTENING→THINKING`, `THINKING→SPEAKING`, `SPEAKING→LISTENING` (follow-up), any→`IDLE`, any→`ERROR`. Manifest: permissions `RECORD_AUDIO`, `CAMERA`, `FOREGROUND_SERVICE`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `TRANSMIT_IR`, `WRITE_SETTINGS`, `READ_EXTERNAL_STORAGE`; `android:largeHeap="true"`; ABI filter `arm64-v8a`. `JarvisService` is a foreground service holding a partial wake lock; `HudActivity` shows the state name as text for now.
- [ ] **Step 4:** Run the tests. Expected: PASS.
- [ ] **Step 5:** `./gradlew :app:installDebug`, start the app, re-run `scripts/device-prep.sh`, do the manual MIUI steps from Task 1. Verify: `adb -s $ANDROID_SERIAL shell dumpsys activity services com.jarvis.edge | grep isForeground` prints `isForeground=true`.
- [ ] **Step 6:** Commit.

### Task 5: Microphone, wake word and utterance capture

**Files:**
- Create: `audio/MicStream.kt`, `audio/WakeWord.kt`, `audio/SpeechSegmenter.kt`; add `app/libs/sherpa-onnx.aar` (latest release; record the version in `bench/results.md`)
- Test: `app/src/test/.../audio/SpeechSegmenterTest.kt`, `app/src/androidTest/.../audio/WakeWordDeviceTest.kt`

**Produces:**
- `class MicStream { fun frames(): Flow<FloatArray> }` — 16 kHz mono, 512-sample frames, values in −1..1; emits `MicUnavailable` as an exception the service catches and retries every 5 s.
- `interface WakeWord { fun accept(frame: FloatArray): Boolean; fun reset() }`
- `class GatedWakeWord(vad: Vad, inner: WakeWord, preRollMs: Int = 500, hangoverMs: Int = 1000) : WakeWord` — while the room is silent only the VAD runs; on voice it replays the buffered pre-roll into `inner` and keeps feeding it until `hangoverMs` after speech ends.
- `class Earcon { fun playWake() }` — a 100 ms chime on `IDLE→LISTENING`; mic frames captured while it plays are discarded.
- `class SpeechSegmenter(vad: Vad, maxSeconds: Float = 15f, trailingSilenceMs: Int = 700, shortTrailingSilenceMs: Int = 450, noSpeechTimeoutMs: Int = 4000) { fun accept(frame: FloatArray, shortTail: Boolean = false): Result }` where `Result` is `Pending`, `Done(samples: FloatArray)` or `NoSpeech`. The caller passes `shortTail = true` once the live transcript is already a complete fast-path command.
- `interface Vad { fun isSpeech(frame: FloatArray): Boolean }` with a Silero implementation.

- [ ] **Step 1: Write the failing JVM tests** with a fake `Vad` driven by a boolean script:

```kotlin
@Test fun `returns Done after 700 ms of trailing silence`()      // speech x30 frames, silence x22 -> Done, samples.size == 30*512
@Test fun `returns NoSpeech when nothing is said for 4 seconds`() // silence x125 -> NoSpeech
@Test fun `caps a never-ending utterance at 15 seconds`()        // speech x600 -> Done at frame 469
@Test fun `shortTail ends after 450 ms of silence`()             // speech x30, silence x15 with shortTail=true -> Done
@Test fun `gated wake word never calls inner during silence`()   // silence x300 -> inner.accept call count == 0
@Test fun `gated wake word replays pre-roll so the phrase onset is not lost`()   // silence x50, speech x40 -> inner received 16 pre-roll frames before the first speech frame
```

- [ ] **Step 2:** Run. Expected: FAIL (class missing).
- [ ] **Step 3:** Implement the three classes. Keyword file contains "hey jarvis" tokenized for the keyword model, starting threshold `0.25`, boost `1.5`. The wake-word thread sets affinity mask `0x0f`.
- [ ] **Step 4:** Run JVM tests. Expected: PASS.
- [ ] **Step 5: Device test** `WakeWordDeviceTest`: feed a recorded WAV of "hey jarvis" (record three takes on the phone into `androidTest/assets/`) and a 60 s WAV of speech without the phrase. Assert three detections and zero detections. Run `./gradlew :app:connectedDebugAndroidTest`. Expected: PASS.
- [ ] **Step 6: Mic-unavailable test (Review Focus 4).** Start a recording in the stock Recorder app, start Jarvis. Verify the HUD shows "Mic unavailable", then stop the Recorder and verify state returns to `IDLE` within 10 s without restarting the app.
- [ ] **Step 7: Acceptance gate.** With the phone on a desk: say "hey jarvis" 20 times from 2 m, expect 18 or more detections. Then play 2 hours of TV or podcast audio near it, expect 1 or fewer false wakes. Run the gate once with `GatedWakeWord` and once with the bare keyword spotter, and record idle CPU for each with `adb -s $ANDROID_SERIAL shell top -n 1 | grep jarvis`; keep gating unless it loses detections. Tune threshold and boost. If the gate can't be met, replace the `WakeWord` implementation with openWakeWord `hey_jarvis` on ONNX Runtime and repeat.
- [ ] **Step 8:** Record results in `bench/results.md`. Commit.

### Task 6: Transcription and speech output

**Files:**
- Create: `audio/Transcriber.kt`, `audio/Speaker.kt`, `core/SentenceSplitter.kt`
- Test: `app/src/test/.../core/SentenceSplitterTest.kt`, `app/src/androidTest/.../audio/TranscriberDeviceTest.kt`

**Produces:**
- `interface Transcriber { fun transcribe(samples: FloatArray): String }` — offline, more accurate; returns trimmed text, empty string when nothing was recognized.
- `interface LiveTranscriber { fun accept(frame: FloatArray): String; fun finish(): String; fun reset() }` — streaming Zipformer fed during `LISTENING`; `accept` returns the transcript so far, so nothing is left to decode when speech ends.
- `interface Speaker { suspend fun speak(sentence: String); fun stop() }` — `speak` returns when playback of that sentence ends. Synthesized audio is cached on disk keyed by voice and sentence text, and the fixed sentences ("Sorry, I didn't catch that.", "Let me take a look.", "Your timer is done.", "One moment.", "I can't do that yet.") are synthesized on first start.
- `class SentenceSplitter { fun push(token: String): List<String>; fun flush(): String? }`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `emits a sentence when terminal punctuation is followed by space`() {
    val s = SentenceSplitter()
    assertEquals(emptyList<String>(), s.push("It is 5"))
    assertEquals(listOf("It is 5 p.m."), s.push(" p.m. The"))   // abbreviation dots do not split
    assertEquals("The", s.flush())
}
@Test fun `does not split inside decimals`()   // "It is 21.5 degrees. Nice." -> two sentences
@Test fun `flush returns null when empty`()
```

- [ ] **Step 2:** Run. Expected: FAIL.
- [ ] **Step 3:** Implement `SentenceSplitter`; split on `.`, `!`, `?` followed by whitespace, except after a single letter or digit.
- [ ] **Step 4:** Implement `Transcriber` (sherpa offline recognizer, 4 threads, affinity `0xf0`) and `Speaker` (sherpa offline TTS with the Piper voice, speed `1.0`, `AudioTrack` in stream mode, `USAGE_ASSISTANT`).
- [ ] **Step 5: Device test.** Record 10 spoken commands from the list in Task 9 into `androidTest/assets/`. `TranscriberDeviceTest` transcribes each with both speech models and logs word error rate and real-time factor. The streaming model is always the `LiveTranscriber` and its result is what the fast path uses. Rule for the model path: if Moonshine's error rate is 3 points or more below the streaming model's and its real-time factor is 0.5 or less, the captured audio is re-transcribed with Moonshine before going to the language model (`ASR_MODEL=moonshine`); otherwise the streaming result is used everywhere (`ASR_MODEL=streaming`) and Moonshine is not loaded. Write the result into `bench/results.md`.
- [ ] **Step 6: Echo loop check.** Temporary wiring in `JarvisService`: wake → capture → transcribe → speak the transcript back. Verify by voice with five phrases; first audio starts within 1.5 s of the end of speech.
- [ ] **Step 7:** Commit.

---

## Phase 2 — Think and act

### Task 7: Tool types, fast intents and core tools

**Files:**
- Create: `brain/ToolCall.kt`, `brain/FastIntents.kt`, `tools/Tool.kt`, `tools/ToolRegistry.kt`, `tools/TimeTools.kt`, `tools/TimerTools.kt`, `tools/DeviceTools.kt`, `tools/NotesTools.kt`
- Test: `app/src/test/.../brain/FastIntentsTest.kt`, `ToolCallParserTest.kt`, `tools/ToolRegistryTest.kt`

**Produces:**
- `data class ToolCall(val name: String, val args: Map<String, String>)`
- `sealed interface ModelOutput { data class Say(val text: String); data class Call(val call: ToolCall) }`
- `fun parseModelOutput(raw: String): ModelOutput` — text after `SAY:` or a JSON object `{"tool": "...", "args": {...}}`; anything else becomes `Say(raw.trim())`.
- `data class ToolResult(val spoken: String, val ok: Boolean, val feedToModel: String? = null)`
- `interface Tool { val name: String; val description: String; val params: Map<String, List<String>?>; suspend fun run(args: Map<String, String>): ToolResult }` — `params` maps argument name to its allowed values, or `null` for free text.
- `class ToolRegistry(tools: List<Tool>) { suspend fun run(call: ToolCall): ToolResult; fun all(): List<Tool> }`
- `object FastIntents { fun match(text: String): ToolCall? }`

Tools in this task:

| Tool | Arguments | Behavior |
| :--- | :--- | :--- |
| `get_time` | — | "It's 9:42 PM." |
| `get_date` | — | "It's Monday, November 16th." |
| `set_timer` | `minutes`, `seconds` | In-app timer via `AlarmManager`; chime and "Your timer is done." |
| `set_alarm` | `hour`, `minute` | Same mechanism, exact alarm |
| `cancel_timers` | — | Cancels all |
| `torch` | `state`: on/off | Rear flash via `CameraManager.setTorchMode`. This phone's cameras run on the legacy HAL, so verify on the device first; if it throws, hold a CameraX session and use `CameraControl.enableTorch` |
| `set_volume` | `percent` | Media stream |
| `set_brightness` | `percent` | Screen brightness |
| `battery_status` | — | Level and temperature |
| `light_level` | — | Lux from the LTR578 |
| `remember` | `text` | Appends to a notes table (SQLite) |
| `recall` | `query` | Keyword match over notes; returns matches as `feedToModel` |

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `fast intents`() {
    assertEquals(ToolCall("get_time", emptyMap()), FastIntents.match("what time is it"))
    assertEquals(ToolCall("set_timer", mapOf("minutes" to "5", "seconds" to "0")), FastIntents.match("Set a timer for five minutes."))
    assertEquals(ToolCall("set_timer", mapOf("minutes" to "1", "seconds" to "30")), FastIntents.match("timer 1 minute 30 seconds"))
    assertEquals(ToolCall("torch", mapOf("state" to "on")), FastIntents.match("turn on the flashlight"))
    assertEquals(ToolCall("set_volume", mapOf("percent" to "40")), FastIntents.match("volume to 40 percent"))
    assertNull(FastIntents.match("what time does the moon rise in Tokyo"))
    assertNull(FastIntents.match("tell me a joke about timers"))
}
@Test fun `parser`() {
    assertEquals(Say("Hello there."), parseModelOutput("SAY: Hello there."))
    assertEquals(Call(ToolCall("torch", mapOf("state" to "off"))), parseModelOutput("""{"tool":"torch","args":{"state":"off"}}"""))
    assertEquals(Say("just text"), parseModelOutput("just text"))
}
@Test fun `unknown tool gives a spoken failure and does not throw`() {
    val r = runBlocking { ToolRegistry(emptyList()).run(ToolCall("nope", emptyMap())) }
    assertFalse(r.ok); assertEquals("I can't do that yet.", r.spoken)
}
@Test fun `argument outside allowed values is rejected before the tool runs`()   // torch state "blue" -> ok=false, tool.run never called
```

- [ ] **Step 2:** Run. Expected: FAIL.
- [ ] **Step 3:** Implement. `FastIntents` normalizes (lowercase, strip punctuation, number words to digits) and matches whole-utterance patterns only, so a longer sentence that merely contains a keyword falls through to the model.
- [ ] **Step 4:** Run. Expected: PASS.
- [ ] **Step 5:** Commit.

### Task 8: Language model engine

**Files:**
- Create: `app/src/main/cpp/CMakeLists.txt`, `app/src/main/cpp/llama_jni.cpp`, `brain/LlmEngine.kt`, `brain/PromptBuilder.kt`
- Test: `app/src/test/.../brain/PromptBuilderTest.kt`, `app/src/androidTest/.../brain/LlmEngineDeviceTest.kt`

**Consumes:** `Tool` (Task 7), `LLM_MODEL` (Task 2).

**Produces:**
- `interface LlmEngine { fun load(modelFile: File, contextTokens: Int = 2048); fun generate(prompt: String, grammar: String?, maxTokens: Int = 160, onToken: (String) -> Boolean): GenStats; fun unload(); val isLoaded: Boolean }` — `onToken` returns `false` to stop.
- `data class GenStats(val promptTokens: Int, val cachedTokens: Int, val outputTokens: Int, val firstTokenMs: Long, val tokensPerSecond: Float)`
- `class PromptBuilder(tools: List<Tool>, templateName: String) { fun system(): String; fun prompt(history: List<Turn>, user: String): String; fun grammar(): String }`
- `data class Turn(val user: String, val assistant: String)`

- [ ] **Step 1: Write the failing JVM tests**

```kotlin
@Test fun `system prompt stays within 200 tokens by the 4-chars-per-token estimate`() {
    assertTrue(builder.system().length <= 800)
}
@Test fun `prompt for a new turn starts with the previous prompt's system block`() {
    assertTrue(builder.prompt(emptyList(), "hi").startsWith(builder.system()))
}
@Test fun `history is limited to the last 3 turns`()
@Test fun `grammar restricts tool names and enumerated argument values`() {
    val g = builder.grammar()
    assertTrue("\"torch\"" in g.replace("\\", "")); assertTrue("on" in g && "off" in g)
}
```

- [ ] **Step 2:** Run. Expected: FAIL.
- [ ] **Step 3:** Implement `PromptBuilder`. System text: one sentence of persona (brief, spoken style, two sentences or fewer per answer), then one line per tool as `name(arg|arg): description`. Grammar (GBNF) root is `"SAY: " text | toolcall`, with one alternative per tool and enumerated values taken from `Tool.params`. The chat template is chosen by `templateName` to match `LLM_MODEL`.
- [ ] **Step 4:** Implement the JNI layer. Requirements the signature does not decide:
  - Call `sched_setaffinity` with mask `0xf0` on the calling thread before creating the context, so llama.cpp's worker threads inherit it; 4 threads for both generation and batch.
  - `use_mmap = true`, no GPU layers.
  - Keep one context alive between calls. On each call, find the longest common token prefix with the previous prompt, remove only the cached tokens after it, and evaluate the remainder. Report the reused count as `cachedTokens`.
  - Sampling: grammar sampler first, then temperature `0.3`, top-p `0.9`.
  - `CMakeLists.txt` adds `third_party/llama.cpp` as a subdirectory with the same flags as Task 2.
- [ ] **Step 5: Device tests**

```kotlin
@Test fun secondCallReusesSystemPrompt() {
    engine.generate(builder.prompt(emptyList(), "hello"), builder.grammar()) { true }
    val s = engine.generate(builder.prompt(emptyList(), "what is two plus two"), builder.grammar()) { true }
    assertTrue(s.cachedTokens >= 100)
    assertTrue(s.firstTokenMs <= 1500)
}
@Test fun outputAlwaysParsesUnderGrammar()   // 20 varied prompts; parseModelOutput never falls to the raw-text branch
@Test fun stopsWhenCallbackReturnsFalse()
```

Run `./gradlew :app:connectedDebugAndroidTest`. Expected: PASS, with generation speed within 20% of the Task 2 figure.
- [ ] **Step 6:** Commit.

### Task 9: Brain and the full voice turn

**Files:**
- Create: `brain/Brain.kt`, `core/TurnController.kt`
- Modify: `service/JarvisService.kt` (replace the echo loop)
- Test: `app/src/test/.../brain/BrainTest.kt`, `core/TurnControllerTest.kt`

**Consumes:** everything from Tasks 5–8, through their interfaces.

**Produces:**
- `class Brain(fast: FastIntents, llm: LlmEngine?, prompts: PromptBuilder, tools: ToolRegistry) { suspend fun respond(userText: String, onSentence: suspend (String) -> Unit) }`
- `class TurnController(segmenter, live: LiveTranscriber, accurate: Transcriber?, brain, speaker, setState: (AssistantState) -> Unit) { suspend fun runTurn(frames: Flow<FloatArray>) }` — each frame goes to both `live` and `segmenter`; `shortTail` is `FastIntents.match(liveText) != null`. On `Done`: if the live text is a fast intent it is used as is; otherwise `accurate` (when present) re-transcribes the samples.

- [ ] **Step 1: Write the failing tests** using fakes for every interface:

```kotlin
@Test fun `fast intent never touches the model`()                  // fake llm throws if called; "what time is it" -> one sentence
@Test fun `model SAY output is streamed sentence by sentence`()    // fake emits "SAY: One. Two." -> onSentence called twice, in order
@Test fun `model tool call runs the tool and speaks its result`()
@Test fun `tool with feedToModel triggers exactly one second model pass`()
@Test fun `brain works with no model loaded`() {                   // llm = null
    // fast intent still answers; other text -> "My language model isn't loaded, so I can only handle basic commands."
}
@Test fun `empty transcript says sorry once and skips the brain`() // Review Focus 1: speaker got "Sorry, I didn't catch that."
@Test fun `NoSpeech returns to idle silently`()
@Test fun `fast intent in the live transcript skips the accurate transcriber`()   // fake accurate throws if called
@Test fun `non-command speech is re-transcribed by the accurate transcriber`()
@Test fun `states go LISTENING, THINKING, SPEAKING, IDLE in order`()
@Test fun `speaker stop during speaking ends the turn and returns to idle`()
```

- [ ] **Step 2:** Run. Expected: FAIL.
- [ ] **Step 3:** Implement. The mic flow is not collected while state is `SPEAKING`. A transcript shorter than 2 characters counts as empty. Conversation history is kept in memory, last 3 turns, cleared after 2 minutes of idle.
- [ ] **Step 4:** Run. Expected: PASS.
- [ ] **Step 5:** Wire into `JarvisService`. On start, `ModelPaths.missing()` decides: language model missing → `Brain(llm = null)` and HUD message naming the file; speech model missing → `ERROR` state naming the file.
- [ ] **Step 6: Missing-model check (Review Focus 3).** Rename the GGUF on the phone, restart the app. Verify the HUD names the file and "hey jarvis, what time is it" still answers. Rename it back.
- [ ] **Step 7: Acceptance by voice.** Twenty commands: ten fast-path (time, date, three timers, torch on/off, volume, brightness, battery) and ten open (two general-knowledge questions, two follow-ups, "remember that the wifi password is on the fridge", "where is the wifi password", three chit-chat, one nonsense). Log end-of-speech to first-audio for each into `bench/results.md`. Gate: all ten fast-path correct at 1.0 s or less; nine or more open ones sensible at a median of 3.5 s or less.
- [ ] **Step 8:** Commit.

### Task 10: Infrared control

**Files:**
- Create: `tools/ir/IrEncoder.kt`, `tools/ir/IrCodes.kt`, `tools/ir/IrTool.kt`, `hud/SetupActivity.kt`, `app/src/main/assets/ir/*.json`
- Modify: `brain/FastIntents.kt`
- Test: `app/src/test/.../tools/ir/IrEncoderTest.kt`, `IrCodesTest.kt`, `IrToolTest.kt`

**Produces:**
- `object IrEncoder { fun nec(address: Int, command: Int): IntArray; fun samsung(address: Int, command: Int): IntArray }` — microsecond on/off pattern.
- `data class IrCode(val carrierHz: Int, val pattern: IntArray)`
- `class IrCodes(json: String) { fun code(device: String, command: String): IrCode?; fun devices(): List<String>; fun commands(device: String): List<String> }`
- `class IrTool(codes: IrCodes, transmit: (Int, IntArray) -> Unit) : Tool` — name `ir_send`, arguments `device` and `command`, allowed values taken from the configured devices.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `NEC frame shape`() {
    val p = IrEncoder.nec(address = 0x04, command = 0x08)
    assertEquals(67, p.size)
    assertEquals(listOf(9000, 4500), p.take(2))
    assertEquals(562, p[2])                    // bit mark
    assertEquals(562, p.last())                // stop mark
    assertEquals(16, p.count { it == 1687 })   // address, ~address, command, ~command hold sixteen 1-bits in total
}
@Test fun `Samsung frame uses a 4500-4500 header`()
@Test fun `unknown device returns null`()
@Test fun `ir_send for an unconfigured device speaks an error and transmits nothing`()   // Review Focus 2
@Test fun `fast intents for TV`() {
    assertEquals(ToolCall("ir_send", mapOf("device" to "tv", "command" to "power")), FastIntents.match("turn off the tv"))
    assertEquals(ToolCall("ir_send", mapOf("device" to "tv", "command" to "volume_up")), FastIntents.match("tv volume up"))
}
```

- [ ] **Step 2:** Run. Expected: FAIL.
- [ ] **Step 3:** Implement. NEC timing: carrier 38000 Hz, header 9000/4500, mark 562, zero space 562, one space 1687, LSB first. Code files hold either `{protocol, address, command}` or a raw `{carrierHz, pattern}`. Ship code sets for common TV brands (LG, Samsung, Sony, TCL, Xiaomi, Panasonic) and AC power on/off for common brands, taken from the Flipper-IRDB project with its license file included. Commands for a TV: `power`, `volume_up`, `volume_down`, `mute`, `channel_up`, `channel_down`, `input`.
- [ ] **Step 4:** `SetupActivity`: pick device type and brand, press "Test power", answer "Did it react?", step to the next code set on "No". The saved choice becomes the `tv` / `ac` entry.
- [ ] **Step 5:** Run tests. Expected: PASS.
- [ ] **Step 6:** Device check: `adb -s $ANDROID_SERIAL shell dumpsys consumer_ir` shows a transmit after "hey jarvis, turn on the TV", and the real TV reacts from the planned mounting position.
- [ ] **Step 7:** Commit.

---

## Phase 3 — See

### Task 11: Camera, text reading and scene description

**Files:**
- Create: `vision/CameraSnapshot.kt`, `vision/Ocr.kt`, `vision/Vlm.kt`, `tools/VisionTools.kt`
- Modify: `app/src/main/cpp/llama_jni.cpp` (multimodal entry point), `brain/FastIntents.kt`
- Test: `app/src/androidTest/.../vision/VisionDeviceTest.kt`, `app/src/test/.../tools/VisionToolsTest.kt`

**Produces:**
- `class CameraSnapshot { suspend fun capture(facing: Facing, maxEdge: Int = 768, lowLight: Boolean): Bitmap }` — opens the camera, waits 600 ms for exposure, captures, closes.
- `interface Ocr { suspend fun read(image: Bitmap): String }`
- `interface Vlm { suspend fun describe(image: Bitmap, question: String, onToken: (String) -> Boolean) }`
- Tools: `read_text(camera: front|back)` returns the text as `feedToModel` with spoken summary when longer than 40 words; `look(camera: front|back, question)` streams the description.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `read_text with no text found says so`()            // fake Ocr returns "" -> "I don't see any text."
@Test fun `look refuses when HealthGuard says the phone is hot`()   // spoken: "I'm too warm for that right now. Give me a few minutes."
@Test fun `fast intents for vision`() {
    assertEquals(ToolCall("read_text", mapOf("camera" to "back")), FastIntents.match("read this"))
    assertEquals(ToolCall("look", mapOf("camera" to "front", "question" to "what am i holding")), FastIntents.match("what am I holding"))
}
```

- [ ] **Step 2:** Run. Expected: FAIL.
- [ ] **Step 3:** Implement. CameraX `ImageCapture` (works on this phone's LEGACY camera level). When lux is below 15: the front camera uses its physical LED flash (camera `1` reports a flash unit), falling back to a full-white screen at maximum brightness if the flash request fails; the back camera uses its flash. `Ocr` uses ML Kit's bundled Latin model. `Vlm` loads SmolVLM and its projector through llama.cpp's multimodal library alongside the chat model, answers, and unloads only itself; the chat model and its cached prompt stay in memory. Jarvis says "Let me take a look." before capture so the wait is covered.
- [ ] **Step 4:** Run JVM tests. Expected: PASS.
- [ ] **Step 5: Device test.** Printed page → `read_text` returns 90% or more of the words. Three objects (mug, remote, book) → `look` names each. Log time from capture to first spoken word; expect 15 s or less. If it exceeds 25 s, switch to SmolVLM-256M and re-measure. Check `MemAvailable` stays above 800 MB throughout.
- [ ] **Step 6:** Commit.

---

## Phase 4 — Live on the desk

### Task 12: HUD

**Files:**
- Create: `hud/Orb.kt`
- Modify: `hud/HudActivity.kt`
- Test: `app/src/test/.../hud/HudBrightnessTest.kt`

**Produces:** `fun hudBrightness(lux: Float, state: AssistantState): Float` (0..1).

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun `dark room and idle dims to the floor`()   { assertEquals(0.02f, hudBrightness(1f, IDLE)) }
@Test fun `bright room and idle`()                   { assertEquals(0.35f, hudBrightness(300f, IDLE)) }
@Test fun `active states are never below 0_25`()     { assertTrue(hudBrightness(1f, LISTENING) >= 0.25f) }
```

- [ ] **Step 2:** Run. Expected: FAIL.
- [ ] **Step 3:** Implement. Full-screen immersive, black background, landscape or portrait by accelerometer. One animated orb drawn on a Compose `Canvas`: slow breathing when idle, radius follows mic level when listening, rotating arc when thinking, pulses with output level when speaking, red ring on error. Below it: the last transcript and the reply text. Small clock in idle. Tap anywhere calls `Speaker.stop()`; long-press opens `SetupActivity`. Proximity sensor "near" for under a second while idle starts listening without the wake word. `FLAG_KEEP_SCREEN_ON`.
- [ ] **Step 4:** Run. Expected: PASS.
- [ ] **Step 5:** Visual check on the phone in a lit and a dark room; `adb -s $ANDROID_SERIAL shell dumpsys gfxinfo com.jarvis.edge | grep "Janky frames"` reports under 5% during the speaking animation.
- [ ] **Step 6:** Commit.

### Task 13: Staying alive: boot, recovery, heat and battery

**Files:**
- Create: `service/BootReceiver.kt`, `service/HealthGuard.kt`
- Modify: `service/JarvisService.kt`
- Test: `app/src/test/.../service/HealthGuardTest.kt`

**Produces:** `class HealthGuard(batteryTempC: () -> Float, availableMb: () -> Long) { fun level(): Level }` with `enum class Level { NORMAL, WARM, HOT }`, plus `fun shouldUnloadChatModel(): Boolean`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `below 40 C is normal`()                  // 39.9 -> NORMAL
@Test fun `40 to 44_9 C is warm`()                  // WARM: vision refused, max reply tokens 80
@Test fun `45 C and above is hot`()                 // HOT: fast path only; Jarvis says "I'm overheating, so I'll keep to basic commands."
@Test fun `under 500 MB available counts as warm`()
@Test fun `shouldUnloadChatModel is true only under 900 MB available`()   // 899 -> true, 900 -> false
```

- [ ] **Step 2:** Run. Expected: FAIL.
- [ ] **Step 3:** Implement. `BootReceiver` starts the service and `HudActivity` on `BOOT_COMPLETED`. Service returns `START_STICKY` and schedules an `AlarmManager` watchdog every 5 minutes that restarts it if it is not running. `Brain` and `VisionTools` consult `HealthGuard` before any model call. The language model stays loaded. It is unloaded only when `availableMb()` falls below 900, and reloaded on the next model-path turn (Jarvis says "One moment." while it loads).
- [ ] **Step 4:** Run. Expected: PASS.
- [ ] **Step 5: Recovery check (Review Focus 5).** `adb -s $ANDROID_SERIAL shell am kill com.jarvis.edge` → listening again within 60 s. `adb -s $ANDROID_SERIAL reboot` → after unlock, HUD up and listening within 60 s, no touch.
- [ ] **Step 6: Soak test.** Leave it running on charge for 24 hours, using it normally. Verify afterwards: service uptime equals the test duration, battery temperature peak below 42°C, `MemAvailable` above 1 GB, zero crashes in `adb logcat -b crash`.
- [ ] **Step 7:** Commit. Unplug the Mac.

---

## Things outside the code

- **Battery on permanent charge.** Stock MIUI cannot cap charging without root. A phone held at 100% on a warm desk will eventually swell its battery. Put the charger on a simple plug-in timer (about 3 hours on, twice a day) and use a 5 V / 1 A charger, not a fast charger.
- **Lock screen.** Auto-start after a reboot needs the phone unlocked once, because storage is encrypted. Either remove the screen lock on this phone or accept one unlock after power cuts.
- **IR brands.** Task 10 ships common brands. If your TV or AC isn't covered, the setup screen will run out of code sets and the brand's codes need adding to `assets/ir/`.

## Later, if wanted

Not in this plan: voice barge-in with echo cancellation, a Hindi or bilingual voice and speech model, weather and other internet lookups, a cloud model as an optional brain when Wi-Fi is up, face recognition for per-person answers, Bluetooth speaker output.
