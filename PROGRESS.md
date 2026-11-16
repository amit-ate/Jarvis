# Jarvis On-Device Assistant — Progress Tracker

> **Last Updated:** 2026-11-17 02:48 IST  
> **Device:** Xiaomi Redmi Note 5 Pro (`whyred`, serial `<serial>`, 6 GB RAM, Snapdragon 636, Android 9 / MIUI 11)  
> **Current Phase:** Phase 0 **Done**; Phases 1–4 (Tasks 4–13) **Host Verified** (`51/51` JVM tests pass; `libllama_jni.so` + `app-debug.apk` + `app-debug-androidTest.apk` built); on-device install & acceptance tests pending USB reconnect (`<serial>`)

---

## Phase & Task Status

| Phase | Task | Status | Notes |
| :--- | :--- | :--- | :--- |
| **Audit** | Hardware Audit & `HARDWARE_AND_JARVIS_REPORT.md` | **Done** | Fixed `Q4_0_4_4` / `dotprod` note in Section 3.2 |
| **Phase 0** | Task 1: Toolchain, Git Repo & Device Prep | **Done** | Git initialized (`9b38260`, `f2a091d`); JDK 17.0.21, NDK `26.3.11579264`, CMake `3.22.1`, `stay_on_while_plugged_in=7`, `HOTWORD_COUNT=0` |
| **Phase 0** | Task 2: Benchmark LLMs & Thermal Soak | **Done** | 12 candidates benchmarked (`--poll 0`); winner **`qwen2.5-0.5b-instruct-q4_0.gguf`** (`33.86–34.09` / `17.49–18.04 t/s`). 10-min thermal soak (`614s`, 12 iters): battery flat at `36.0°C`, `pp256` `33.45 -> 33.71 t/s`, `tg64` peaked at `16.54–17.46 t/s` (`3.75 -> 3.74` when `60°C` cluster limit tripped) |
| **Phase 0** | Task 3: Fetch & Push Speech + Vision Models | **Done** | `push-models.sh` exited `0`; `/sdcard/Jarvis/models/*`: `asr-moonshine` (`119M`), `asr-zipformer` (`130M`), `kws` (`19M`), `llm` (`409M`), `tts` (`79M`), `vad` (`636K`), `vlm` (`520M`) |
| **Phase 1** | Task 4: App Skeleton, State Machine & Service | **Host Verified** | Gradle 8.7 + `sherpa-onnx-1.13.8.aar`; `AssistantState`, `ModelPaths`, `JarvisService`, `HudActivity`; `6/6` JVM tests; APK built (`77M`), install pending USB reconnect |
| **Phase 1** | Task 5: Microphone, Wake Word & Utterance Capture | **Host Verified** | `MicStream`, `WakeWord` (`SherpaWakeWord`, `GatedWakeWord`, `Earcon`), `SpeechSegmenter` (`SileroVad`); `6/6` JVM tests pass; device tests pending USB reconnect |
| **Phase 1** | Task 6: Transcription & Speech Output | **Host Verified** | `SentenceSplitter`, `Transcriber` (`SherpaLiveTranscriber`, `SherpaMoonshineTranscriber`), `Speaker` (`SherpaSpeaker` w/ disk cache & `isStopped`); `3/3` JVM tests pass; device tests pending USB reconnect |
| **Phase 2** | Task 7: Tool Types, Fast Intents & Core Tools | **Host Verified** | `ToolCall`, `FastIntents`, `Tool`, `ToolRegistry`, `TimeTools`, `TimerTools`, `DeviceTools`, `NotesTools`; `4/4` JVM tests pass |
| **Phase 2** | Task 8: Language Model Engine (`llama_jni.cpp`) | **Host Verified** | Built `libllama_jni.so` (`arm64-v8a`, `-march=armv8-a -O3 -DNDEBUG`, `tpp.poll = 0`, `cpumask[4..7]`, `strict_cpu`, KV prefix reuse, GBNF grammar, `mtmd`); `PromptBuilderTest` `4/4` pass; `LlmEngineDeviceTest` compiled into `app-debug-androidTest.apk` (`930K`), device run pending USB reconnect |
| **Phase 2** | Task 9: Brain & Full Voice Turn | **Host Verified** | `Brain`, `TurnController` wired into `JarvisService`; `BrainTest` `5/5` + `TurnControllerTest` `6/6` pass; missing-model & voice acceptance pending USB reconnect |
| **Phase 2** | Task 10: Infrared Control (`consumerir`) | **Host Verified** | `IrEncoder` (`nec`, `samsung`), `IrCodes`, `IrTool`, `assets/ir/codes.json`, `SetupActivity`, IR `FastIntents`; `IrEncoderTest` `2/2`, `IrCodesTest` `2/2`, `IrToolTest` `2/2` pass; hardware blaster test pending USB reconnect |
| **Phase 3** | Task 11: Camera, OCR & SmolVLM Scene Description | **Host Verified** | `CameraSnapshot` (CameraX `1.3.4`), `MlKitOcr` (`16.0.1`), `LlamaMtmdVlm` (`nativeDescribeImage`), `ReadTextTool`, `LookTool`, vision `FastIntents`; `VisionToolsTest` `3/3` pass; device camera/VLM check pending USB reconnect |
| **Phase 4** | Task 12: Full-Screen Jarvis HUD | **Host Verified** | `JarvisOrb`, `hudBrightness`, updated `HudActivity` (immersive, `FLAG_KEEP_SCREEN_ON`, light/proximity sensors, tap-to-stop, long-press Setup); `HudBrightnessTest` `3/3` pass; visual/`gfxinfo` check pending USB reconnect |
| **Phase 4** | Task 13: Boot Recovery, `HealthGuard` & 24h Soak | **Host Verified** | `HealthGuard`, `BootReceiver`, `WatchdogReceiver` (5-min `AlarmManager`), `JarvisService` full pipeline + memory unload/reload; `HealthGuardTest` `5/5` pass; `am kill`/reboot & 24h soak pending USB reconnect |

---

## Host Build & JVM Unit Test Results

- **Builds (`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`):**
  - `libllama_jni.so` (`arm64-v8a`): `77M` (unstripped intermediate; compiled with `-march=armv8-a -O3 -DNDEBUG`, linking `llama`, `ggml`, `mtmd`)
  - `app/build/outputs/apk/debug/app-debug.apk`: `77M`
  - `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`: `930K`
- **JVM Tests Total:** `51` tests run, `0` failures, `0` skipped:
  - `SpeechSegmenterTest`: `6/6` passed
  - `BrainTest`: `5/5` passed
  - `FastIntentsTest`: `1/1` passed
  - `PromptBuilderTest`: `4/4` passed
  - `ToolCallParserTest`: `1/1` passed
  - `AssistantStateTest`: `3/3` passed
  - `ModelPathsTest`: `3/3` passed
  - `SentenceSplitterTest`: `3/3` passed
  - `TurnControllerTest`: `6/6` passed
  - `HudBrightnessTest`: `3/3` passed
  - `HealthGuardTest`: `5/5` passed
  - `ToolRegistryTest`: `2/2` passed
  - `VisionToolsTest`: `3/3` passed
  - `IrCodesTest`: `2/2` passed
  - `IrEncoderTest`: `2/2` passed
  - `IrToolTest`: `2/2` passed

---

## Complete Candidate LLM Benchmark Results (`taskset f0`, `-t 4 --poll 0 --no-warmup -r 1 --delay 5`)

Gate: `tg64 >= 7.0` t/s and `pp256 >= 25.0` t/s.

| Candidate GGUF | `pp256` (t/s) | `tg64` (t/s) | Temp Before -> After | Status |
| :--- | :--- | :--- | :--- | :--- |
| **`qwen2.5-0.5b-instruct-q4_0.gguf`** | **`33.86`** *(34.09 `-r 3`)* | **`17.49`** *(18.04 `-r 3`)* | `33.0°C -> 33.0°C` | **WINNER (`LLM_MODEL`)** |
| `qwen2.5-0.5b-instruct-q4_k_m.gguf` | `26.31` | `3.62` | `37.0°C -> 37.0°C` | Fails `tg64 >= 7.0` |
| `google_gemma-3-1b-it-Q4_0.gguf` | `17.67` | `5.04` | `37.0°C -> 37.0°C` | Fails `pp256` & `tg64` |
| `LFM2-1.2B-Q4_0.gguf` | `12.59` | `7.72` | `36.0°C -> 36.0°C` | Fails `pp256 >= 25.0` |
| `Llama-3.2-1B-Instruct-Q4_K_M.gguf` | `11.87` | `5.42` | `37.0°C -> 37.0°C` | Fails `pp256` & `tg64` |
| `Llama-3.2-1B-Instruct-Q4_0.gguf` | `11.81` | `7.12` | — | Fails `pp256 >= 25.0` |
| `google_gemma-3-1b-it-Q4_K_M.gguf` | `11.54` | `5.87` | `37.0°C -> 37.0°C` | Fails `pp256` & `tg64` |
| `LFM2-1.2B-Q4_K_M.gguf` | `11.26` | `5.81` | `37.0°C -> 37.0°C` | Fails `pp256` & `tg64` |
| `qwen2.5-1.5b-instruct-q4_0.gguf` | `7.55` | `5.60` | `37.0°C -> 37.0°C` | Fails `pp256` & `tg64` |
| `Qwen3-1.7B-Q4_0.gguf` | `7.01` | `0.88` | `37.0°C -> 37.0°C` | Fails `pp256` & `tg64` |
| `qwen2.5-1.5b-instruct-q4_k_m.gguf` | `6.93` | `2.35` | `37.0°C -> 37.0°C` | Fails `pp256` & `tg64` |
| `Qwen3-1.7B-Q4_K_M.gguf` | `6.47` | `0.51` | `37.0°C -> 38.0°C` | Fails `pp256` & `tg64` |
