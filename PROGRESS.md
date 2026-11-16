# Jarvis On-Device Assistant — Progress Tracker

> **Last Updated:** 2026-11-17 02:35 IST  
> **Device:** Xiaomi Redmi Note 5 Pro (`whyred`, serial `<serial>`, 6 GB RAM, Snapdragon 636, Android 9 / MIUI 11)  
> **Current Phase:** Phase 0 **Done**; Phase 1–2 Track B (Tasks 4–7) **Host Verified**; implementing Tasks 8–13 while awaiting USB reconnect (`<serial>` disconnected after `push-models.sh`)

---

## Phase & Task Status

| Phase | Task | Status | Notes |
| :--- | :--- | :--- | :--- |
| **Audit** | Hardware Audit & `HARDWARE_AND_JARVIS_REPORT.md` | **Done** | Fixed `Q4_0_4_4` / `dotprod` note in Section 3.2 |
| **Phase 0** | Task 1: Toolchain, Git Repo & Device Prep | **Done** | Git initialized (`9b38260`, `f2a091d`); JDK 17.0.21, NDK `26.3.11579264`, CMake `3.22.1`, `stay_on_while_plugged_in=7`, `HOTWORD_COUNT=0` |
| **Phase 0** | Task 2: Benchmark LLMs & Thermal Soak | **Done** | 12 candidates benchmarked (`--poll 0`); winner **`qwen2.5-0.5b-instruct-q4_0.gguf`** (`33.86–34.09` / `17.49–18.04 t/s`). 10-min thermal soak (`614s`, 12 iters): battery flat at `36.0°C`, `pp256` `33.45 -> 33.71 t/s`, `tg64` peaked at `16.54–17.46 t/s` (`3.75 -> 3.74` when `60°C` cluster limit tripped) |
| **Phase 0** | Task 3: Fetch & Push Speech + Vision Models | **Done** | `push-models.sh` exited `0`; `/sdcard/Jarvis/models/*`: `asr-moonshine` (`119M`), `asr-zipformer` (`130M`), `kws` (`19M`), `llm` (`409M`), `tts` (`79M`), `vad` (`636K`), `vlm` (`520M`) |
| **Phase 1** | Task 4: App Skeleton, State Machine & Service | **Host Verified** | Gradle 8.7 + `sherpa-onnx-1.13.8.aar`; `AssistantState`, `ModelPaths`, `JarvisService`, `HudActivity`; `19/19` JVM tests pass across Tasks 4–7 & `app-debug.apk` built; on-device install pending USB reconnect |
| **Phase 1** | Task 5: Microphone, Wake Word & Utterance Capture | **Host Verified** | `MicStream`, `WakeWord` (`SherpaWakeWord`, `GatedWakeWord`, `Earcon`), `SpeechSegmenter` (`SileroVad`); `6/6` JVM tests pass; device tests pending USB reconnect |
| **Phase 1** | Task 6: Transcription & Speech Output | **Host Verified** | `SentenceSplitter`, `Transcriber` (`SherpaLiveTranscriber`, `SherpaMoonshineTranscriber`), `Speaker` (`SherpaSpeaker` w/ disk cache); `3/3` JVM tests pass; device tests pending USB reconnect |
| **Phase 2** | Task 7: Tool Types, Fast Intents & Core Tools | **Host Verified** | `ToolCall`, `FastIntents`, `Tool`, `ToolRegistry`, `TimeTools`, `TimerTools`, `DeviceTools`, `NotesTools`; `4/4` JVM tests pass |
| **Phase 2** | Task 8: Language Model Engine (`llama_jni.cpp`) | **Pending** | Will set `tpp.poll = 0` via `ggml_threadpool_params_default(4)` |
| **Phase 2** | Task 9: Brain & Full Voice Turn | **Pending** | — |
| **Phase 2** | Task 10: Infrared Control (`consumerir`) | **Pending** | — |
| **Phase 3** | Task 11: Camera, OCR & SmolVLM Scene Description | **Pending** | — |
| **Phase 4** | Task 12: Full-Screen Jarvis HUD | **Pending** | — |
| **Phase 4** | Task 13: Boot Recovery, `HealthGuard` & 24h Soak | **Pending** | — |

---

## JVM Unit Test Results (`./gradlew :app:testDebugUnitTest`)

- **Total:** `19` tests run, `0` failures, `0` skipped:
  - `AssistantStateTest`: `3/3` passed
  - `ModelPathsTest`: `3/3` passed
  - `SpeechSegmenterTest` (`SpeechSegmenter` + `GatedWakeWord`): `6/6` passed
  - `SentenceSplitterTest`: `3/3` passed
  - `FastIntentsTest`: `1/1` passed
  - `ToolCallParserTest`: `1/1` passed
  - `ToolRegistryTest`: `2/2` passed

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
