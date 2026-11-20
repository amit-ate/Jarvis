# Jarvis Expert Stitching, Reverse-Engineering & Mobile Hardware Architecture

> **Target Hardware:** Xiaomi Redmi Note 5 Pro (`whyred`, Qualcomm `SDM636`, 6 GB LPDDR4X / `3,506,924 kB` observed `MemAvailable`, Android 9 API 28, ARMv8.0-A without `dotprod`/`i8mm`)  
> **Primary Runtime:** `llama.cpp` (`b12359` / `v1.0.0`) + `Sherpa-ONNX` + Android Hardware HALs (`JNI`, `OpenCL`, `ConsumerIrManager`, `SensorManager`)

---

## 1. Native MoE Expert Extraction & Pruning (`blk.L.ffn_*_exps`)

Modern Mixture-of-Experts (MoE) checkpoints serialized into GGUF v2/v3 pack all `E` experts per layer into stacked 3D tensors alongside a learned router gate:

- **Router gate:** `blk.{L}.ffn_gate_inp.weight` with shape `[n_embd, expert_count]`
- **Stacked SwiGLU expert tensors:**
  - `blk.{L}.ffn_gate_exps.weight` (`[n_embd, n_ff_exp, expert_count]`)
  - `blk.{L}.ffn_up_exps.weight` (`[n_embd, n_ff_exp, expert_count]`)
  - `blk.{L}.ffn_down_exps.weight` (`[n_ff_exp, n_embd, expert_count]`)
- **GGUF Metadata Keys:** `{arch}.expert_count` and `{arch}.expert_used_count` (inspected directly by `scripts/expert_stitcher/inspect_gguf.py`).

### REAP & Activation-Frequency Pruning in GGUF

When deploying a large MoE model (e.g., `E = 64`, `top-k = 8`) onto a 6 GB mobile target, inactive experts waste mmap virtual address space and page-cache pressure. Domain-specific **REAP (Router-weighted Expert Activation Pruning)** profiles calibration prompts across Jarvis's domains (`TOOL_USE`, `REASONING`, `KNOWLEDGE`, `CONVERSATION`) and ranks each expert $e \in \{0, \dots, E-1\}$ at layer $L$ by:

$$\text{REAP}(L, e) = \sum_{t \in \mathcal{D}_{\text{calib}}} p_L(e \mid x_t) \cdot \| f_{L,e}(x_t) \|_2$$

Experts falling below the retention threshold are sliced out along the `expert_count` axis of `blk.{L}.ffn_*_exps.weight` and `blk.{L}.ffn_gate_inp.weight`, updating `{arch}.expert_count` in the GGUF KV header while preserving dense attention and `token_embd.weight`.

---

## 2. FrankenMoE Upcycling, Router Calibration & Why Cross-Family Layer Stitching Fails

### 2.1 FrankenMoE Upcycling & Hidden Centroid Initialization (`mergekit-moe`)

Dense same-family specialists ($M_1, \dots, M_k$) sharing an identical base checkpoint $M_0$ (e.g., `Qwen2.5-0.5B-Instruct` tuned separately on tool-calling, reasoning, and chat) can be upcycled into a sparse MoE (`mergekit-moe`) by:
1. Averaging or keeping shared attention (`attn_q/k/v/output`) and RMSNorm layers from $M_0$.
2. Packing each specialist's MLP (`ffn_gate`, `ffn_up`, `ffn_down`) as expert $e$ in `blk.{L}.ffn_*_exps`.
3. **Router Gate Initialization (`hidden` centroids):** Instead of random router weights (which collapse routing onto a single expert), each row $r_{L,e}$ of `blk.{L}.ffn_gate_inp.weight` is initialized to the normalized mean hidden representation $\bar{h}_L(\mathcal{D}_e)$ computed over positive domain prompts, optionally refined by 1–2 epochs of cross-entropy router calibration with load-balancing auxiliary loss.

### 2.2 Why Cross-Family Layer Stitching Fails

Attempting to splice transformer layers or subtract weights between heterogeneous model families (for example, `Qwen2.5` + `Llama-3.2` + `Gemma-3` + `LFM2`) fails **structurally and mathematically**:

1. **Orthogonal Residual Stream Bases:** Even if two models happened to share hidden dimension $d$, each pretrained model learns an arbitrary orthogonal rotation $Q \in O(d)$ of its residual stream $\mathbb{R}^d$. Layer $L+1$ of model $B$ expects activations in $B$'s coordinate frame $Q_B$, not $A$'s frame $Q_A$:
   $$h_{L}^{(A)} \in \text{span}(Q_A) \implies W_{L+1}^{(B)} h_{L}^{(A)} \approx \text{noise}$$
2. **Mismatched Dimensions & GQA Geometry:** `qwen2` (`0.5B`, `d=896`, `24` layers, `14/2` heads, `vocab=151,936`) vs `llama` (`1B`, `d=2048`, `16` layers, `32/8` heads, `vocab=128,256`) vs `lfm2` (hybrid conv/attention) cannot share KV caches, attention heads, or weight tensors.
3. **Incompatible Tokenizers:** Divergent BPE merges and special tokens (`<|im_start|>` vs `<|begin_of_text|>`) make logit-space or embedding-space composition undefined without a trained cross-tokenizer projection.

`scripts/expert_stitcher/extract_lora_svd.py --inspect-tensors <base.gguf> <expert.gguf>` enforces these invariants before any delta extraction is attempted.

---

## 3. SVD Task-Delta Extraction $\rightarrow$ Runtime LoRA & Control Vectors (Why This Wins on `whyred`)

Rather than storing $k$ full `~409 MB` GGUF copies or a `~1.3 GB` FrankenMoE model in RAM, **SVD Task-Delta Extraction** isolates *only* what a same-family specialist learned beyond the base model:

$$\Delta W = W_{\text{specialist}} - W_{\text{base}}$$

Computing the truncated SVD $\Delta W = U \Sigma V^\top$ and keeping the top $r$ singular components ($r \in \{4, 8, 16\}$):

$$\Delta W \approx (U_r \sqrt{\Sigma_r}) (\sqrt{\Sigma_r} V_r^\top) = A B, \quad A \in \mathbb{R}^{m \times r}, \; B \in \mathbb{R}^{r \times n}$$

### Why Runtime LoRA + Control Vectors Win on 6 GB ARMv8.0-A

| Approach | Resident Weights (6 Experts) | Switching Latency | `whyred` (`SDM636`) Fit |
| :--- | :--- | :--- | :--- |
| 6 Separate `0.5B Q4_0` GGUFs | `~2.4 GB` disk / thrashing mmap | Seconds (reload context) | Poor (thermal & page-fault spikes) |
| 6-Expert FrankenMoE `Q4_0` | `~1.1–1.4 GB` | Zero (router per token) | Moderate (no `dotprod`; slower prefill) |
| **1 Base `Q4_0` + Rank-8 LoRAs + CVecs** | **`~409 MB` base + `~6–15 MB` / LoRA** | **Sub-millisecond (`llama_set_adapters_lora`)** | **Optimal (`~1,150 MB` full stack)** |

- **JNI Runtime Activation (`llama_jni.cpp` / `ExpertRouter`):**
  - `llama_adapter_lora_init(model, path_lora)` lazily memory-maps tiny rank-8 GGUF LoRA deltas (`~6–15 MB` each).
  - `llama_set_adapters_lora(ctx, adapters, n_adapters, scales)` swaps or blends domain specialists (`TOOL_USE`, `REASONING`, `KNOWLEDGE`, `MEMORY`, `VISION`, `CONVERSATION`) on the single resident `qwen2.5-0.5b-instruct-q4_0.gguf` base model.
  - `llama_set_adapter_cvec(ctx, data, len, n_embd, il_start, il_end)` injects PCA-derived **Control Vectors** (generated offline via `bench/out/tools/cvector-generator`) across upper transformer blocks (e.g., layers `8..22`) with **zero extra weight RAM** to suppress echoing and enforce concise spoken replies.
  - `bench/out/tools/export-lora` (`llama-export-lora`) can permanently bake validated high-performing LoRAs into a fused base GGUF when desired.

The complete domain routing manifest is managed by `scripts/expert_stitcher/stitch_experts.py` (`experts.json`, `max_ram_budget_mb = 2600`, `estimated_resident_mb = 1150`).

---

## 4. Redmi Note 5 Pro (`whyred` / `SDM636`) All-Resources Utilization Map

| Hardware Resource | Verified Device Spec (`HARDWARE_AND_JARVIS_REPORT.md` & `bench/results.md`) | Jarvis Assignment & Affinity |
| :--- | :--- | :--- |
| **4× Little Cores (`cpu0..3`)** | Kryo 260 Silver (`0x801` Cortex-A53), `633.6–1612.8 MHz`, ARMv8.0-A (`fp asimd`) | Pin KWS (`OpenWakeWord`), `Silero VAD`, audio ring-buffer, and NEON `simd_remove_dc_and_normalize` / `simd_pcm16_to_float_and_rms` (`taskset 0x0f`) |
| **4× Big Cores (`cpu4..7`)** | Kryo 260 Gold (`0x800` Cortex-A73), `1113.6–1804.8 MHz`, `-march=armv8-a` | Pin `llama.cpp` (`-t 4`, `poll=0`, `taskset 0xf0` -> `17.49–18.04 tok/s` `tg64` on `Qwen2.5-0.5B-Q4_0`), Moonshine/Zipformer ASR, and Piper TTS |
| **Adreno 509 GPU** | `/vendor/lib64/libOpenCL.so`, Vulkan 1.1.0 (`vulkan.sdm660.so`), OpenGL ES 3.2 | Offload `MediaPipe` / `TFLite` GPU delegate vision (presence/face/OCR pre-pass) and optional OpenCL prompt prefill |
| **Hexagon 680 DSP** | `/vendor/dsp`, `init.svc.adsprpcd=running`, `libcdsprpc.so` / `libadsprpc.so` | Audio front-end DSP & low-power SNPE/QNN INT8 always-on vision triggers offloaded from CPU |
| **33 Hardware Sensors** | `BMI120` 6-axis IMU (`1–400 Hz`), `LTR578` ALS/Proximity, `AK09918` Mag, QTI Pick-Up | Auto-dim HUD on low lux, wake-on-pickup/hand-wave, desk-vs-handheld posture state |
| **Dual Microphones + Audio DSP** | Bottom Mic (`id:14`) + Back Mic (`id:15`), `16–192 kHz`, hardware `NoiseSuppressor` & `AcousticEchoCanceler` | Far-field capture + dual-ASR (`Zipformer` streaming + `Moonshine` accurate) arbitrated by `TranscriptArbiter` |
| **Dual Rear + Front Flash Cameras** | 4 devices (`0` Rear 12MP + flash, `1` Front 20MP w/ physical LED flash, `2` Aux 5MP, `3` Dual) | On-demand `SmolVLM-256M-Instruct-Q8_0` (`look`) & MLKit OCR (`read_text`) with low-lux front/rear flash |
| **Consumer IR Blaster** | `android.hardware.consumerir` (`ConsumerIrManager`) | Zero-Wi-Fi appliance control (`ir_send` for TV/AC via `app/src/main/assets/ir/codes.json`) |

---

## 5. Stage 4 Overnight Self-Evolution Memory Math & Eval-Gated Rollback

### 5.1 Why On-Device Full FP32 `llama_opt` Backprop Does Not Fit Alongside Resident Stack

Suppose a model has $P \approx 630\text{M}$ parameters (`qwen2.5-0.5b-instruct`). Naive full-parameter AdamW (`llama_opt` in FP32) requires:
- FP32 master weights: $4P \approx 2,520\text{ MB}$
- FP32 gradients: $4P \approx 2,520\text{ MB}$
- AdamW first & second moments ($m, v$): $8P \approx 5,040\text{ MB}$
- Activations (even with gradient checkpointing at `ctx=512`): $\sim 300\text{–}600\text{ MB}$
- **Total:** **$\sim 10.4\text{ GB}$** — over **3×** the device's `3,506 MB` `MemAvailable` (even with `2,560 MB` ZRAM, thrashing eMMC/ZRAM would destroy latency and thermals).

Even **on-device QLoRA / LoRA-only rank-8 training** on frozen `Q4_0` weights drops optimizer state to trainable adapter parameters $P_{\text{LoRA}} \approx 2.2\text{M}$ ($16\text{ B/param} \approx 35\text{ MB}$), but still requires dequantized FP32 activations ($\sim 350\text{–}550\text{ MB}$) and sustained `>15 min` big-core compute that hits `whyred`'s `60°C` thermal-engine virtual cluster limit (`tsens_tz_sensor3..7,12`).

### 5.2 Hybrid Two-Tier Overnight Self-Evolution + Eval-Gated Rollback

Jarvis splits self-evolution into two safe, memory-bounded stages:

1. **Tier A — On-Device Instant & Idle Reflection (`~0 extra model RAM`, `< 50 ms`):**
   - `ReflectionWorker` runs over unreflected `episodes` in `jarvis_mind.db` only when idle + cool (`HealthGuard.Level.NORMAL`) + learning unpaused.
   - Extracts `Fact`, `VocabEntry` (compiled into `asr-zipformer` BPE `hotwords.txt` via `HotwordsWriter`), `AsrCorrection` (`heard -> meant`), and promotes repeated (`>= 3`) successful tool trajectories into zero-LLM `LearnedShortcut` rules.
2. **Tier B — Host-Distilled LoRA Adapter + On-Device 60-Prompt Gate (`<= 2,600 MB` budget):**
   - Exported `jarvis_mind.json` episodes are distilled on the host Mac into a rank-8 task-delta LoRA (`extract_lora_svd.py` / `llama-export-lora` / `cvector-generator`) and pushed via ADB (`stitch_experts.py --push-adb`).
   - Before activating a new `ModelVersion` in `MindStore`, the candidate adapter is scored against `bench/eval/eval_prompts.json` (`60` prompts across 6 categories) using `bench/eval/score_eval.py`:
     - **Hard Gate:** `echo_rate_pct <= 5.0%` **AND** `tool_accuracy_pct >= 80.0%` **AND** no regression vs active `ModelVersion`.
     - **Automatic Rollback:** If the gate fails—or if live post-activation `EchoGuard` rate spikes—`MindStore.rollbackModelVersion()` (and the Mind HUD `Experts` tab) atomically reverts `experts.json` / active adapter to the last known-good version.
