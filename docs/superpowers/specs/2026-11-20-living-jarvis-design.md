# Living Jarvis — Design

Date: 2026-11-20 · Status: draft for approval

## Intent

Jarvis runs on a Xiaomi Redmi Note 5 Pro (SDM636, 6 GB RAM, Android 9). Today it mishears words and often repeats the user's sentence back instead of answering. The user wants it to:

- hear accurately and answer for real;
- behave like an aware, "conscious" assistant that keeps running between requests, knows its own state, remembers experiences, reflects on them and **learns continuously**;
- speak up on its own **when it has a reason to**;
- do **everything on the phone** — no Mac or cloud compute;
- make everything it learns **visible** ("there should be a way to check what is evolved").

We are not claiming real consciousness. "Conscious" here means a set of behaviours: a self-model, memory of experiences, reflection, initiative and continuous learning.

## Goals

1. Correct transcription of open-ended speech, and confirmation when unsure.
2. Replies that answer, never echo the user.
3. Long-term memory of facts and conversations, used in replies.
4. Self-awareness: battery, temperature, loaded models, what it knows, when it last learned.
5. Idle-time reflection that turns experience into facts, vocabulary, corrections and instant shortcuts.
6. Overnight weight-level learning while charging, gated by an eval and fully reversible (**experimental — see Stage 4 risk**).
7. Unprompted speech when there is a reason, within limits.
8. A "Mind" screen plus voice commands to see, and undo, everything learned.

## Non-goals

- Offloading compute to the Mac or the cloud. The existing online tools (weather, web search) stay: they fetch data, they don't think.
- Watching people with the camera. Presence detection uses the mic and light sensor only.
- Training a new base model, or merging/"stitching" model experts (rejected: the current model is dense, and the CPU can't run anything bigger).

## Anti-goals (failure even if it "works")

- It learns something wrong silently, with no way to see or undo it.
- It talks too much, talks at night, or interrupts.
- It overheats, or drains the battery when not charging.
- Quick commands (torch, timer, volume) get slower than today.
- A model update makes it worse and stays.

## Constraints (measured, from `bench/results.md` and `PROGRESS.md`)

- CPU only. 4× Cortex-A73 big cores via the existing pinned llama.cpp threadpool. Adreno 509 and Hexagon 680 have no usable LLM path.
- Speed gate: `tg64 ≥ 7 t/s` and `pp256 ≥ 25 t/s`. Only `qwen2.5-0.5b-instruct-q4_0` passes today (18 t/s generation).
- Two different temperatures matter. The phone's own thermal engine throttles the CPU cluster at 60 °C. `HealthGuard` reads the battery temperature, which runs much cooler, and moves to WARM at 40 °C and HOT at 45 °C.
- RAM: about 5.6 GB usable. The speech stack, LLM, VLM (loaded on demand) and new embedding model must fit together.
- Existing stack: Kotlin and Compose, sherpa-onnx (VAD, KWS, Zipformer, Moonshine, Piper TTS), llama.cpp via JNI with GBNF grammars, SQLite.
- Verified in the repo: the sherpa `OnlineRecognizerConfig` supports `hotwordsFile` and `hotwordsScore`. The vendored llama.cpp has `examples/training/finetune.cpp` (full-parameter training via `llama_opt`); LoRA *training* is **not** present, only LoRA loading.

## Approaches considered

- **A. Learning through memory**: facts, vocabulary, corrections and shortcuts in a database, with no weight changes. Safe, instant, undoable. It gets better at the user, not smarter in general.
- **B. A plus overnight on-device training**: really adapts the model, but compute- and memory-heavy on this phone.
- **C. A picker routing to several specialist models**: the realistic version of "stitching experts". Model swapping on a 6 GB CPU-only phone adds seconds of delay, and the existing FastIntents and VLM split already covers the cheap version.

**Picked: A + B** (the user asked for continuous learning). B is built last and only if its feasibility spike passes. **C was rejected.**

## Design

Built in four stages. Each stage ships on its own and leaves Jarvis better than before.

### Stage 1 — Hear and answer correctly

**Choosing the transcript**
- Open-ended utterances (not `FastIntents.isClosedCommand`) always run Moonshine.
- When Zipformer and Moonshine disagree, they are combined using the existing `selectBestTranscript`.
- `preferStreaming` no longer short-circuits this. Closed commands keep the streaming fast path.

**Confidence**
- If the two transcripts disagree strongly (word-level distance above a threshold) and neither matches an intent, Jarvis asks: *"Did you say …?"*
- A yes/no answer is accepted.
- The confirmed text becomes a learning signal (Stage 2 corrections).

**Vocabulary biasing**
- Zipformer gets a hotwords file generated from learned vocabulary, using `modified_beam_search` (already configured).

**Stop echoing**
- Add a repetition-penalty sampler to the chain in `llama_jni.cpp`.
- Echo guard in `Brain`: if the reply is too similar to the user's sentence (token overlap above a threshold), regenerate once at a higher temperature with a "do not repeat the question" nudge. If it still echoes, fall back to an honest "I'm not sure."

**Prompt**
- Rewrite `PromptBuilder.system()` with:
  - a persona and rules;
  - 3–4 short few-shot examples (answer, tool call, "I don't know");
  - a **self-state line** and a **memory block** (both filled from Stage 2/3, empty until then).
- Keep the GBNF output rule.

**Model bake-off**
- Build a fixed eval set in `bench/eval/`: about 60 prompts covering factual questions, chit-chat, tool requests and echo traps, with expected behaviour.
- Benchmark the current model against Qwen3-0.6B (no-think), LFM2-700M, LFM2-350M and Gemma-3-270M-it, all Q4_0.
- Score answer quality, echo rate, tool-call accuracy and speed. Adopt the best one that passes the speed gate.
- The same eval set is reused as the Stage 4 gate.

**Conversation length**
- Replace the 3-turn / 2-minute history with as many recent turns as fit a token budget.
- Older context comes from memory (Stage 2).

### Stage 2 — Memory and the journal

**Storage:** one SQLite database, `jarvis_mind.db`, which replaces `jarvis_notes.db`; existing notes migrate as facts.

| Table | Contents |
|---|---|
| `episodes` | every turn: time, both transcripts, chosen transcript, reply, tool call, outcome signals |
| `facts` | text, embedding, source episode, confidence, status (active / undone) |
| `vocabulary` | word or phrase, source |
| `corrections` | heard → meant |
| `shortcuts` | example utterances and embedding → tool call, hit count |
| `journal` | type, human-readable summary, links to the affected rows, time, undone flag |
| `model_versions` | model and adapter files, eval scores, active flag |

**Embeddings**
- A small GGUF sentence-embedding model (about 25–35 MB, e.g. bge-small-en or all-MiniLM-L6, chosen by the builder), run through the existing llama.cpp JNI in embedding mode. No new runtime.
- Brute-force cosine search in Kotlin (thousands of rows is fine).

**Retrieval**
- Each turn injects the top facts and one relevant past episode into the prompt within a small token budget.

**Explicit memory**
- "Remember…" writes a fact plus a journal entry.
- "Forget that" / "forget …" undoes the matching item.

**Every learned change goes through one `Mind.learn(...)` path.** It writes the item and its journal entry in one transaction. Undo flips the status and also writes a journal entry.

### Stage 3 — Self-model, reflection, initiative

**SelfModel**
- Collects battery, charging state, temperature, `HealthGuard` level, loaded models and versions, counts of facts / words / shortcuts, last reflection time, and uptime.
- Feeds the prompt's self-state line.
- Answers "how are you", "what did you learn today", "what do you know about me".

**Reflection worker**
- **When it runs:** after the user has been idle for at least ~10 minutes, and only if the phone is charging or battery is above 50%, and only if `HealthGuard` is NORMAL. It stops immediately when the wake word fires.
- **What it reads:** episodes not yet reflected on. It uses:
  - *Heuristic signals:* the user repeating themselves within ~30 s, "no / wrong / I said", interruptions, confirmed "Did you say" answers.
  - *LLM extraction:* grammar-constrained JSON output of facts about the user, corrections, and vocabulary.
- **What it produces:**
  - facts, vocabulary (which regenerates the hotwords file) and corrections;
  - **shortcut promotion**: an utterance that led to the same successful tool call 3 or more times becomes a shortcut matched by embedding before the LLM runs;
  - a short daily summary journal entry ("Today I learned…").

**Initiative engine (speaks first when there's a reason)**
- **Triggers:**
  - due reminders (new `remind` tool: "remind me at 6 to…");
  - a greeting when presence is detected after a long absence (VAD speech or a light-sensor change; never the camera);
  - sharing the day's learnings, at most once a day and only when the user is present;
  - self-state warnings: hot, low battery, unplugged at night.
- **Limits:**
  - quiet hours (default 23:00–07:00, except reminders the user set);
  - a rate cap (a few per hour);
  - never while speaking or listening;
  - "be quiet" mutes initiatives for a period.
- Every unprompted utterance is journaled.

### Stage 4 — Overnight learning (experimental)

**Feasibility spike first.** Full-parameter training of even a 0.5B model needs roughly 4× the f32 weights in memory, which exceeds 6 GB. The spike decides between:

- (a) implementing LoRA training on top of `llama_opt`/ggml in the JNI;
- (b) training only a small slice (e.g. last layer and output norm);
- (c) dropping weight-level learning and keeping Stages 1–3.

The spike reports memory use, time per training step and temperature on the phone.

**If feasible**
- **When it runs:** charging, screen off, 01:00–06:00, temperature OK. It pauses when WARM and aborts when HOT.
- **Dataset:** episodes with good outcomes, plus failed episodes paired with their corrected answers.
- **Eval gate:** the new adapter is adopted only if it scores at least as well as the active one on the Stage 1 eval set and on held-out personal episodes, with no drop in tool-call accuracy.
- **Rollback:** older adapters are kept. Rolling back is one tap or "go back to yesterday's brain".
- Every training run, pass or fail, gets a journal entry with its scores.

### Surviving abrupt shutdown (applies to every stage)

Goal: if the app is killed, crashes, or the phone reboots or dies, Jarvis comes back with its memory, its train of thought and its learning progress intact. That means at most the sentence being spoken is lost.

- **Experiences are saved the moment they happen.**
  - `jarvis_mind.db` runs in SQLite WAL mode with `synchronous=NORMAL`.
  - Each turn's episode row is written right after transcription, before Jarvis speaks.
  - The reply is updated when speaking finishes.
  - All learning writes are transactions, so a crash never leaves half-learned state.
- **Working memory checkpoint.** A small `working_state` row holds:
  - the live conversation;
  - a pending "Did you say…?" confirmation;
  - the queue of things it wants to say on its own;
  - the reflection worker's last processed episode id;
  - the current mood/self-state.

  It is rewritten after every turn and every reflection step.
- **The model's working memory (its "neuron activity").**
  - The llama.cpp KV cache for the fixed system prompt is saved once per model/prompt version (`llama_state_seq_save_file`). Restarts skip re-reading the prompt.
  - The live conversation's KV cache is saved when the app goes idle after a turn, and through `onTrimMemory` / `onDestroy` when possible.
  - On restart it is restored with `llama_state_seq_load_file` if the model hash and prompt version match. Otherwise it is rebuilt from the working-state text, so this file is an optimization and never the only copy.
- **Reflection resumes** from the saved episode id instead of starting over.
- **Overnight training checkpoints** the adapter and optimizer state every N steps (Stage 4). After a crash it continues from the last checkpoint. A half-trained adapter is never activated.
- **Coming back.**
  - The service is already `START_STICKY`, and `BootReceiver` restarts it.
  - On start, Jarvis restores its state and writes a journal entry ("I was shut down unexpectedly at 14:02; restored conversation and learning progress").
  - It may mention this once if the user is present.
- **Integrity.**
  - Each checkpoint file is written to a temp file, fsynced, then atomically renamed.
  - A corrupt or mismatched file is discarded and journaled, never loaded.

### The Mind screen

A new Compose screen reachable from the HUD:

- **Tabs:** Journal (timeline, newest first), Facts, Words & corrections, Shortcuts, Brain versions (eval scores, active flag), and Status (live self-state).
- **Actions:**
  - every item has **Undo / Delete**;
  - brain versions have **Activate**;
  - a switch pauses all learning.
- **Voice equivalents:** "what did you learn today", "forget that", "stop learning", "go back to yesterday's brain".
- **Export:** a JSON dump to `/sdcard/Jarvis/mind-export.json` for inspection over adb.

## Testing

- JVM tests:
  - Mind store and undo;
  - echo guard;
  - transcript arbitration;
  - reflection signal detection, with a fake LLM;
  - shortcut promotion;
  - initiative rules (quiet hours, rate cap);
  - eval scoring.
- Device tests: embedding speed, end-to-end latency budgets, memory with all models loaded, and the Stage 4 spike measurements.
- `bench/eval/` runs on the phone and writes results to `bench/results.md`.

## Calls made after review (proposed — user can override)

- **Speed of open-ended answers:** the first spoken word must come within about 3 s of the user stopping (today it's about 1.7–3.5 s).
  - The fixed parts of the prompt (persona, few-shot examples) go first, so the existing prompt cache stays reusable.
  - The changing blocks (self-state, memory, history) go last, within a small token budget (about 150 tokens).
  - The echo guard regenerates at most once, with a short token cap.
- **Overnight-training data:** episodes with good outcomes, plus answers the user corrected out loud ("no, the answer is …", stored as a new `answer_corrections` row and journaled). The model never trains on its own unreviewed output.
- **Eval scoring is fully automatic on the phone:** tool name and arguments, keyword checks, echo overlap, "I don't know" detection. A separate richer human-judged rubric is out of scope.
- **"Unplugged at night" warning:** never spoken during quiet hours. It's journaled and shown on the HUD instead.

- **Autonomy:** the user confirmed that it learns and improves on its own, with no approval step. The journal and undo are the safety net.
- **Language:** English only for now, matching every existing speech model. Hindi/Hinglish support is a later change. *(Default; the user did not say.)*
- **Other voices:** it only learns facts and corrections from speech said right after the wake word, or in a conversation it is already in. Background speech and TV are never learned from. Voice recognition (learning only from the owner's voice) is a later option. *(Default; the user did not say.)*

## Left to the builder

- exact thresholds (similarity, echo overlap, idle time, rate caps);
- the embedding model choice;
- the schema details;
- the Mind screen layout;
- prompt wording and the few-shot examples;
- the eval prompt list.

Any choice that touches the anti-goals (latency of quick commands, unprompted speech limits, thermal) must be measured on the phone.
