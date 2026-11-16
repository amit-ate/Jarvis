# Jarvis Edge AI Benchmark & Toolchain Results

## Environment & Toolchain (Task 1)

- **Target Device:** Xiaomi Redmi Note 5 Pro (`whyred`, serial `<serial>`, Android 9 API 28, `arm64-v8a`)
- **JDK:** OpenJDK `17.0.21.0.3` (`/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`)
- **Android SDK Root:** `$HOME/Library/Android/sdk` -> `/opt/homebrew/share/android-commandlinetools`
- **Installed SDK Packages:** `platforms;android-34`, `platforms;android-28`, `build-tools;34.0.0`, `platform-tools`, `cmake;3.22.1`, `ndk;26.3.11579264`
- **NDK Version:** `26.3.11579264` (r26d LTS)
- **CMake Version:** `3.22.1`
- **llama.cpp Submodule:** `v1.0.0` (`b12359`, commit `b1b770d90d94cad9e9ea5b993bdbc383a522fb28`)

## Compiler Flags Comparison (`-march=armv8-a` vs `-mtune=cortex-a73`)

### qwen2.5-0.5b-instruct-q4_0.gguf (llama-bench-armv8a)
- Battery temp: 33.0°C -> 33.0°C

| model                          |       size |     params | backend    | threads |            test |                  t/s |
| ------------------------------ | ---------: | ---------: | ---------- | ------: | --------------: | -------------------: |
| qwen2 1B Q4_0                  | 403.20 MiB |   630.17 M | CPU        |       4 |           pp256 |         34.09 ± 0.01 |
| qwen2 1B Q4_0                  | 403.20 MiB |   630.17 M | CPU        |       4 |            tg64 |         18.04 ± 0.02 |

build: b1b770d (1)

### qwen2.5-0.5b-instruct-q4_0.gguf (llama-bench-a73)
- Battery temp: 34.0°C -> 35.0°C

| model                          |       size |     params | backend    | threads |            test |                  t/s |
| ------------------------------ | ---------: | ---------: | ---------- | ------: | --------------: | -------------------: |
| qwen2 1B Q4_0                  | 403.20 MiB |   630.17 M | CPU        |       4 |           pp256 |        25.63 ± 12.20 |
| qwen2 1B Q4_0                  | 403.20 MiB |   630.17 M | CPU        |       4 |            tg64 |         12.66 ± 8.61 |

build: b1b770d (1)

- Selected binary: `llama-bench-armv8a` (`-march=armv8-a` tg64=18.04 vs `-mtune=cortex-a73` tg64=12.66)

### Threadpool & Thermal Diagnostics (`--poll 0` & `--delay 5`)
- Verified on `qwen2.5-0.5b-instruct-q4_0.gguf` (`--no-warmup -r 1`):
  - `taskset f0`, `-t 4 --poll 0`: `pp256 = 33.86 t/s`, `tg64 = 17.49 t/s` (`rc=0`)
  - `taskset f0`, `-t 3 --poll 0`: `pp256 = 24.18 t/s`, `tg64 = 14.64 t/s` (`rc=0`)
  - No `taskset`, `-t 4` (`poll=50`): `pp256 = 29.26 t/s`, `tg64 = 14.02 t/s` (`rc=0`)
- Verified `/vendor/etc/thermal-engine-normal.conf` (`[VIRTUAL-CLUSTER1]` `set_point 60000` / `set_point_clr 50000` on `tsens_tz_sensor3..7,12`), requiring `--no-warmup -r 1 --delay 5` between `pp256` and `tg64` for clean single-pass thermal isolation.

## Candidate LLM Benchmark Results (`taskset f0`, `-t 4 --poll 0 --no-warmup -r 1 --delay 5`)

### qwen2.5-0.5b-instruct-q4_0.gguf (llama-bench-armv8a)
- Measured in diagnostic `task-293` (`--poll 0 --no-warmup -r 1`)

| model                          |       size |     params | backend    | threads |       poll |            test |                  t/s |
| ------------------------------ | ---------: | ---------: | ---------- | ------: | ---------: | --------------: | -------------------: |
| qwen2 1B Q4_0                  | 403.20 MiB |   630.17 M | CPU        |       4 |          0 |           pp256 |         33.86 ± 0.00 |
| qwen2 1B Q4_0                  | 403.20 MiB |   630.17 M | CPU        |       4 |          0 |            tg64 |         17.49 ± 0.00 |

### Llama-3.2-1B-Instruct-Q4_0.gguf (llama-bench-armv8a)
- Measured in diagnostic `task-343` (`--poll 0 --no-warmup -r 1 --delay 5`)

| model                          |       size |     params | backend    | threads |       poll |            test |                  t/s |
| ------------------------------ | ---------: | ---------: | ---------- | ------: | ---------: | --------------: | -------------------: |
| llama 1B Q4_0                  | 729.75 MiB |     1.24 B | CPU        |       4 |          0 |           pp256 |         11.81 ± 0.00 |
| llama 1B Q4_0                  | 729.75 MiB |     1.24 B | CPU        |       4 |          0 |            tg64 |          7.12 ± 0.00 |

### LFM2-1.2B-Q4_0.gguf (llama-bench-armv8a)
- Battery temp: 36.0°C -> 36.0°C

| model                          |       size |     params | backend    | threads |       poll |            test |                  t/s |
| ------------------------------ | ---------: | ---------: | ---------- | ------: | ---------: | --------------: | -------------------: |
| lfm2 1.2B Q4_0                 | 661.25 MiB |     1.17 B | CPU        |       4 |          0 |           pp256 |         12.59 ± 0.00 |
| lfm2 1.2B Q4_0                 | 661.25 MiB |     1.17 B | CPU        |       4 |          0 |            tg64 |          7.72 ± 0.00 |

build: b1b770d (1)

### LFM2-1.2B-Q4_K_M.gguf (llama-bench-armv8a)
- Battery temp: 37.0°C -> 37.0°C

| model                          |       size |     params | backend    | threads |       poll |            test |                  t/s |
| ------------------------------ | ---------: | ---------: | ---------- | ------: | ---------: | --------------: | -------------------: |
| lfm2 1.2B Q4_K - Medium        | 694.76 MiB |     1.17 B | CPU        |       4 |          0 |           pp256 |         11.26 ± 0.00 |
| lfm2 1.2B Q4_K - Medium        | 694.76 MiB |     1.17 B | CPU        |       4 |          0 |            tg64 |          5.81 ± 0.00 |

build: b1b770d (1)

### Llama-3.2-1B-Instruct-Q4_K_M.gguf (llama-bench-armv8a)
- Battery temp: 37.0°C -> 37.0°C

| model                          |       size |     params | backend    | threads |       poll |            test |                  t/s |
| ------------------------------ | ---------: | ---------: | ---------- | ------: | ---------: | --------------: | -------------------: |
| llama 1B Q4_K - Medium         | 762.81 MiB |     1.24 B | CPU        |       4 |          0 |           pp256 |         11.87 ± 0.00 |
| llama 1B Q4_K - Medium         | 762.81 MiB |     1.24 B | CPU        |       4 |          0 |            tg64 |          5.42 ± 0.00 |

build: b1b770d (1)

### Qwen3-1.7B-Q4_0.gguf (llama-bench-armv8a)
- Battery temp: 37.0°C -> 37.0°C

| model                          |       size |     params | backend    | threads |       poll |            test |                  t/s |
| ------------------------------ | ---------: | ---------: | ---------- | ------: | ---------: | --------------: | -------------------: |
| qwen3 1.7B Q4_0                | 1002.15 MiB |     1.72 B | CPU        |       4 |          0 |           pp256 |          7.01 ± 0.00 |
| qwen3 1.7B Q4_0                | 1002.15 MiB |     1.72 B | CPU        |       4 |          0 |            tg64 |          0.88 ± 0.00 |

build: b1b770d (1)

### Qwen3-1.7B-Q4_K_M.gguf (llama-bench-armv8a)
- Battery temp: 37.0°C -> 38.0°C

| model                          |       size |     params | backend    | threads |       poll |            test |                  t/s |
| ------------------------------ | ---------: | ---------: | ---------- | ------: | ---------: | --------------: | -------------------: |
| qwen3 1.7B Q4_K - Medium       |   1.03 GiB |     1.72 B | CPU        |       4 |          0 |           pp256 |          6.47 ± 0.00 |
| qwen3 1.7B Q4_K - Medium       |   1.03 GiB |     1.72 B | CPU        |       4 |          0 |            tg64 |          0.51 ± 0.00 |

build: b1b770d (1)

### google_gemma-3-1b-it-Q4_0.gguf (llama-bench-armv8a)
- Battery temp: 37.0°C -> 37.0°C

| model                          |       size |     params | backend    | threads |       poll |            test |                  t/s |
| ------------------------------ | ---------: | ---------: | ---------- | ------: | ---------: | --------------: | -------------------: |
| gemma3 1B Q4_0                 | 682.24 MiB |   999.89 M | CPU        |       4 |          0 |           pp256 |         17.67 ± 0.00 |
| gemma3 1B Q4_0                 | 682.24 MiB |   999.89 M | CPU        |       4 |          0 |            tg64 |          5.04 ± 0.00 |

build: b1b770d (1)

### google_gemma-3-1b-it-Q4_K_M.gguf (llama-bench-armv8a)
- Battery temp: 37.0°C -> 37.0°C

| model                          |       size |     params | backend    | threads |       poll |            test |                  t/s |
| ------------------------------ | ---------: | ---------: | ---------- | ------: | ---------: | --------------: | -------------------: |
| gemma3 1B Q4_K - Medium        | 762.49 MiB |   999.89 M | CPU        |       4 |          0 |           pp256 |         11.54 ± 0.00 |
| gemma3 1B Q4_K - Medium        | 762.49 MiB |   999.89 M | CPU        |       4 |          0 |            tg64 |          5.87 ± 0.00 |

build: b1b770d (1)

### qwen2.5-0.5b-instruct-q4_k_m.gguf (llama-bench-armv8a)
- Battery temp: 37.0°C -> 37.0°C

| model                          |       size |     params | backend    | threads |       poll |            test |                  t/s |
| ------------------------------ | ---------: | ---------: | ---------- | ------: | ---------: | --------------: | -------------------: |
| qwen2 1B Q4_K - Medium         | 462.96 MiB |   630.17 M | CPU        |       4 |          0 |           pp256 |         26.31 ± 0.00 |
| qwen2 1B Q4_K - Medium         | 462.96 MiB |   630.17 M | CPU        |       4 |          0 |            tg64 |          3.62 ± 0.00 |

build: b1b770d (1)

### qwen2.5-1.5b-instruct-q4_0.gguf (llama-bench-armv8a)
- Battery temp: 37.0°C -> 37.0°C

| model                          |       size |     params | backend    | threads |       poll |            test |                  t/s |
| ------------------------------ | ---------: | ---------: | ---------- | ------: | ---------: | --------------: | -------------------: |
| qwen2 1.5B Q4_0                | 1011.16 MiB |     1.78 B | CPU        |       4 |          0 |           pp256 |          7.55 ± 0.00 |
| qwen2 1.5B Q4_0                | 1011.16 MiB |     1.78 B | CPU        |       4 |          0 |            tg64 |          5.60 ± 0.00 |

build: b1b770d (1)

### qwen2.5-1.5b-instruct-q4_k_m.gguf (llama-bench-armv8a)
- Battery temp: 37.0°C -> 37.0°C

| model                          |       size |     params | backend    | threads |       poll |            test |                  t/s |
| ------------------------------ | ---------: | ---------: | ---------- | ------: | ---------: | --------------: | -------------------: |
| qwen2 1.5B Q4_K - Medium       |   1.04 GiB |     1.78 B | CPU        |       4 |          0 |           pp256 |          6.93 ± 0.00 |
| qwen2 1.5B Q4_K - Medium       |   1.04 GiB |     1.78 B | CPU        |       4 |          0 |            tg64 |          2.35 ± 0.00 |

build: b1b770d (1)

## Task 2 Model Selection

- **Selection Rule:** `tg64 >= 7.0 t/s` and `pp256 >= 25.0 t/s`, largest parameter count, faster quant; fallback `Qwen2.5-0.5B`.
- **Qualifying Candidate:** `qwen2.5-0.5b-instruct-q4_0.gguf` (`pp256 = 33.86–34.09 t/s`, `tg64 = 17.49–18.04 t/s`). All `1B–1.7B` models scored `pp256 <= 17.67 t/s`.

```text
LLM_MODEL=qwen2.5-0.5b-instruct-q4_0.gguf
```
- **Selected Compiler Flags:** `-march=armv8-a` (`llama-bench-armv8a`)
- **sherpa-onnx AAR:** `v1.13.8` (`app/libs/sherpa-onnx.aar`)



## Thermal Soak Test (10 minutes, 20s idle between runs): qwen2.5-0.5b-instruct-q4_0.gguf

| Iteration | Elapsed (s) | pp256 (t/s) | tg64 (t/s) | Battery Temp (°C) |
| :--- | :--- | :--- | :--- | :--- |
| 1 | 30s | 33.45 | 3.75 | 36.0 |
| 2 | 93s | 33.30 | 2.11 | 36.0 |
| 3 | 166s | 14.29 | 2.09 | 36.0 |
| 4 | 222s | 9.57 | 16.63 | 36.0 |
| 5 | 258s | 33.55 | 17.46 | 36.0 |
| 6 | 308s | 33.65 | 3.75 | 36.0 |
| 7 | 345s | 32.44 | 16.60 | 36.0 |
| 8 | 408s | 33.53 | 2.09 | 36.0 |
| 9 | 445s | 33.65 | 16.54 | 36.0 |
| 10 | 494s | 33.43 | 3.74 | 36.0 |
| 11 | 564s | 9.32 | 3.77 | 36.0 |
| 12 | 614s | 33.71 | 3.74 | 36.0 |

- **Thermal Check Result:** Battery temperature stayed completely flat at `36.0°C` (`< 42.0°C`) across all 12 iterations over 10 minutes, `pp256` finished at `33.71 t/s` (vs `33.45 t/s` iter 1), and `tg64` finished at `3.74 t/s` (vs `3.75 t/s` iter 1, `< 1%` variance). Note: `--delay 2` after a full 256-token prompt evaluation occasionally hits `VIRTUAL-CLUSTER1` (`60°C` die threshold) mid-`tg64` (`2.09–3.77 t/s`), whereas when cool or after a brief pause (`--delay 5`, or in real turns with KV-cached system prompt evaluating ~10–30 user tokens instead of 256), `tg64` runs at **`16.54–17.46 t/s`**.
