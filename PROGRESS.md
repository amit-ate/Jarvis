# Jarvis On-Device Assistant — Progress Tracker

> **Last Updated:** 2026-11-17 20:25 IST  
> **Device:** Xiaomi Redmi Note 5 Pro (`whyred`, serial `<serial>`, 6 GB RAM, Snapdragon 636, Android 9 / MIUI 11)  
> **Current Phase:** Phases 0–4 **Device Verified** on `<serial>` (all 3 instrumentation suites pass, live service/HUD/degraded/kill-recovery checks pass). Remaining items need a human: 20-command live voice acceptance, physical TV IR test, HUD dark/lit visual check, 24h soak

---

## Phase & Task Status

| Phase | Task | Status | Notes |
| :--- | :--- | :--- | :--- |
| **Audit** | Hardware Audit & `HARDWARE_AND_JARVIS_REPORT.md` | **Done** | Fixed `Q4_0_4_4` / `dotprod` note in Section 3.2 |
| **Phase 0** | Task 1: Toolchain, Git Repo & Device Prep | **Done** | Git initialized (`9b38260`, `f2a091d`); JDK 17.0.21, NDK `26.3.11579264`, CMake `3.22.1`, `stay_on_while_plugged_in=7`, `HOTWORD_COUNT=0` |
| **Phase 0** | Task 2: Benchmark LLMs & Thermal Soak | **Done** | 12 candidates benchmarked (`--poll 0`); winner **`qwen2.5-0.5b-instruct-q4_0.gguf`** (`33.86–34.09` / `17.49–18.04 t/s`). 10-min thermal soak (`614s`, 12 iters): battery flat at `36.0°C`, `pp256` `33.45 -> 33.71 t/s`, `tg64` peaked at `16.54–17.46 t/s` (`3.75 -> 3.74` when `60°C` cluster limit tripped) |
| **Phase 0** | Task 3: Fetch & Push Speech + Vision Models | **Done** | `push-models.sh` exited `0`; `/sdcard/Jarvis/models/*`: `asr-moonshine` (`119M`), `asr-zipformer` (`130M`), `kws` (`19M`), `llm` (`409M`), `tts` (`79M`), `vad` (`636K`), `vlm` (now `266M`: `SmolVLM-256M-Instruct-Q8_0` + mmproj; 500M files removed). Expanded `kws/keywords.txt` variations. |
| **Phase 1** | Task 4: App Skeleton, State Machine & Service | **Device Verified** | Installed on `<serial>`; `JarvisService` `isForeground=true` 8s after `HudActivity` launch; process alive; `0` crashes. Fixed HUD tap handling (stops TTS only when `SPEAKING`, manual wake when `IDLE`). |
| **Phase 1** | Task 5: Microphone, Wake Word & Utterance Capture | **Device Verified** | Audio pipeline overhaul: disabled Android AGC in `MicStream` to eliminate noise floor pumping, clean `softwareGain = 3.0f` + hardware `NoiseSuppressor`. Split Silero VAD into dual instances: wake gate (`threshold = 0.22, minRms = 0.005`) vs turn segmentation (`threshold = 0.35/0.38, minRms = 0.010/0.012`). Added 6-frame (192ms) pre-roll buffer in `SpeechSegmenter`. `SherpaWakeWord` dynamically loads fp32 ONNX, `keywordsThreshold = 0.08`, `keywordsScore = 2.5`, `maxActiveFrames = 187` (6s timeout). `SpeechDeviceTest` passed in `14.54s`. |
| **Phase 1** | Task 6: Transcription & Speech Output | **Device Verified** | Covered by `SpeechDeviceTest` above. Added `speaker.clearStop()` in `TurnController` to prevent stale stop states from muting turns. |
| **Phase 2** | Task 7: Tool Types, Fast Intents & Core Tools | **Host & Device Verified** | 17 tools registered. Added `GetWeatherTool` (`get_weather`) and `WebSearchTool` (`web_search`). Expanded `FastIntents` with relative volume (`volume up/down`), relative brightness (`brighter/dimmer`), notes (`remember that...`), and conversational torch variants (`turn the light on`, `torch on`). Added `IntentAccuracyHarnessTest` (67 test cases, 100% precision, 3.15 µs/query). |
| **Phase 2** | Task 8: Language Model Engine (`llama_jni.cpp`) | **Device Verified** | `LlmEngineDeviceTest` `OK (3 tests)`. SIMD and assembly optimizations: enabled Cortex-A73 compiler tuning flags (`-mcpu=cortex-a73 -mtune=cortex-a73 -fvectorize`); optimized ARMv8.0-A dot-product kernel (`ggml_vdotq_s32`) using `vpadalq_s16` (`sadalp` assembly); implemented GBNF grammar sampler caching (`llama_sampler_clone`) eliminating 200ms grammar compilation per turn. Added native NEON SIMD audio processing (`SimdAudio`, `simd_audio.cpp`). |
| **Phase 2** | Task 9: Brain & Full Voice Turn | **Device Verified (partial)** | Missing-model degraded mode verified. Live Brain logging added (`JarvisBrain`, `JarvisTurn`). 20-command live voice acceptance needs a human speaker. |
| **Phase 2** | Task 10: Infrared Control (`consumerir`) | **Device Verified (partial)** | IR transmit step of `VisionAndIrDeviceTest` passes; physical TV test needs a human |
| **Phase 3** | Task 11: Camera, OCR & SmolVLM Scene Description | **Device Verified** | `VisionAndIrDeviceTest` `OK (2 tests)` in `29.668s` (rear + front camera snapshots, OCR, IR blaster, SmolVLM describe). Live voice verification confirmed: "What do you see?" -> rear camera scene description; "What am I holding?" -> front camera scene description; "Read this" -> OCR text recognition. `SmolVLM-256M-Instruct-Q8_0` with `image_min/max_tokens = 64`, `maxEdge = 384`, big-core affinity pinning (`cores 4–7`) during `describe`. |
| **Phase 4** | Task 12: Full-Screen Jarvis HUD | **Device Verified (partial)** | `gfxinfo` steady state: `55/1207` janky = `4.56%` (gate `< 5%`); window overlapping startup warm-up: `77/1226` = `6.28%`. HUD tap initiates manual wake when `IDLE`. Dark/lit visual check needs a human |
| **Phase 4** | Task 13: Boot Recovery, `HealthGuard` & 24h Soak | **Device Verified (partial)** | Hard-killed process (`run-as kill -9`); MIUI `Reject RestartService` ×2; `WatchdogReceiver` (45s alarm + activity relaunch) restarted it, `isForeground=true` after `36s`; `0` crashes. 24h soak pending |

---

## Host Build & JVM Unit Test Results

- **Builds (`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`):**
  - `libllama_jni.so` (`arm64-v8a`): compiled with `-march=armv8-a -mtune=cortex-a73 -O3 -DNDEBUG -fvectorize`, linking `llama`, `ggml`, `mtmd`, and `simd_audio`
  - `app/build/outputs/apk/debug/app-debug.apk`: built and verified
  - `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`: built and verified
- **JVM Tests Total:** `52` tests run, `0` failures, `0` skipped:
  - `IntentAccuracyHarnessTest`: `1/1` passed (67 test cases, 100% precision, 3.15 µs/query)
  - `SpeechSegmenterTest`: `6/6` passed
  - `BrainTest`: `5/5` passed
  - `FastIntentsTest`: `1/1` passed (includes weather & web search routing)
  - `PromptBuilderTest`: `4/4` passed (includes all 17 tools)
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

## On-Device Instrumentation Status (Latest Verification on `<serial>`)

All 3 instrumentation test suites pass cleanly on-device:
1. **`SpeechDeviceTest`**: `OK (1 test)` in **`14.540s`** (Silero VAD + Sherpa KWS wake + Moonshine ASR + TTS prewarm cache).
2. **`LlmEngineDeviceTest`**: `OK (3 tests)` in **`245.752s`** (`outputAlwaysParsesUnderGrammar` across 20 prompts with 17 tool definitions, `secondCallReusesSystemPrompt`, `stopsWhenCallbackReturnsFalse`). Verified fix for CFS cpuset spin livelock (`tpp.strict_cpu = false`).
3. **`VisionAndIrDeviceTest`**: `OK (2 tests)` in **`29.668s`** (IR pattern transmit, ML Kit OCR text reading, SmolVLM-256M image description under big-core pinning, and front camera snapshot capture).

---

## Recent Architectural Enhancements & Fixes

### 1. Online Capabilities & FastIntents Routing
- **`GetWeatherTool` (`get_weather`)**: Directly queries `wttr.in` for real-time weather and temperature; converts units and symbols (e.g. `°C` -> "degrees Celsius", `%` -> "percent") for natural TTS output.
- **`WebSearchTool` (`web_search`)**: Queries DuckDuckGo Instant Answer API with automatic Wikipedia search and summary fallback (`/api/rest_v1/page/summary/`), condensed to 1–2 sentences for spoken output.
- **FastIntents Enhancements**: Added `weatherHereRegex`, `weatherInCityRegex`, and `webSearchRegex`.
- **Command Segmentation Separation (`isClosedCommand`)**: Added `FastIntents.isClosedCommand()` to differentiate instant closed-domain commands (e.g., volume, timer, flashlight, local weather) from open-ended speech input (e.g., web searches and location-specific weather queries). Closed commands trigger short trailing silence (`350ms`), while open queries retain standard pause limits (`800ms`) preventing premature turn cutoffs.
- **Permissions Added**: `android.permission.INTERNET` and `android.permission.ACCESS_NETWORK_STATE` in `AndroidManifest.xml`.

### 2. Audio & Wake Word Pipeline Robustness
- **AGC & Gain Staging**: Disabled Android system AGC in `MicStream.kt` to prevent noise floor pumping; replaced with hardware `NoiseSuppressor` and clean linear digital software gain (`softwareGain = 3.0f`) clamped to `[-1.0, 1.0]`.
- **Dual Silero VAD Tuning**: Decoupled VAD configurations for waking vs turn segmentation:
  - Wake Gate VAD: Sensitive gate (`threshold = 0.22`, `minRms = 0.005`) for immediate trigger without missing whispery speech.
  - Turn Segmentation VAD: Robust gate (`threshold = 0.35/0.38`, `minRms = 0.010/0.012`) preventing room background noise from artificially extending speech turns.
- **Pre-Roll Audio Ring Buffer**: Added a 6-frame (192ms) circular pre-roll buffer in `SpeechSegmenter.kt`, prepending pre-speech frames to the utterance buffer upon speech detection so leading syllables are never clipped.
- **Sherpa KWS Keyword Spotting**: Added dynamic loading of fp32 ONNX models with fallback to `.int8.onnx` (`pickOnnx`), tuned `keywordsThreshold = 0.08`, `keywordsScore = 2.5`, `maxActivePaths = 12`, `numTrailingBlanks = 0`, and added a 6-second (`187` frames) active timeout in `GatedWakeWord`.
- **HUD & Turn Controller Stop State Handling**: Fixed permanent mute bug where tapping the HUD while not speaking set `stopped = true` indefinitely. HUD tap now only stops playback when state is `SPEAKING` and triggers manual wake when `IDLE`. Added `speaker.clearStop()` at the beginning of each turn in `TurnController`.

### 3. LLM Threadpool CFS Livelock Fix
- **CFS Cpuset Spin Livelock Resolution**: In `app/src/main/cpp/llama_jni.cpp`, changed `tpp.strict_cpu = true;` to `tpp.strict_cpu = false;` in `create_big_core_threadpool()`. On Android Linux CFS, strict thread pinning combined with threadpool barrier synchronization causes livelock when threads compete or get preempted across cpusets.
- **Prompt Index Logging**: Added sequential prompt index logging (`prompt idx/total 'query'`) in `LlmEngineDeviceTest.kt` for clear visibility during long instrumentation runs.

---

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
