# In-app model download — design

Date: 2026-11-23 · Status: approved by the user (2026-11-23)

## Why

Jarvis needs about 2.3 GB of models in `/sdcard/Jarvis/models`. Today they get there only by
`scripts/push-models.sh` over USB from the Mac. The next phone, a Pixel 7a, cannot be attached to the laptop,
so Jarvis must be able to set itself up from the phone alone: install the APK, open it, and it fetches,
checks and installs everything it needs, showing progress, then starts.

## Goals

1. On a phone without the models, Jarvis downloads the full set below by itself, from the public sources the
   models are published at (Hugging Face, sherpa-onnx GitHub releases).
2. **Jarvis waits until everything is ready** (user decision): no listening, no chat model, until every file is
   downloaded and verified. A status screen shows progress the whole time. The one exception is an *extra* that
   keeps failing (see "Must-have and extras").
3. Every file is checked against a SHA-256 fingerprint before it is used. A wrong or damaged file is never
   loaded; it is fetched again.
4. Downloads resume after a dropped connection, the app being closed, or a reboot, from the byte where they stopped.
5. The status screen matches the HUD: **"Reactor charging"** (mockup A, picked by the user, see below).

## Non-goals

- No hosting of our own: no server, no repacked or recompressed files (measured: recompression saves only 6–9%
  on the quantized models, not worth a host).
- No model picker or catalogue UI; the set is fixed in the app.
- Not downloading the user's personal voice model (`asr-custom`); it is trained on the Mac and stays private.
- Not changing how Jarvis runs once models are present; existing phones keep working as today.
- Not solving APK delivery to the Pixel (wireless debugging or copying the APK covers that).

## Anti-goals (failure even if it "works")

- Loading a half-written or corrupted model (crash or nonsense answers).
- Restarting a 1 GB file from zero after a short Wi-Fi drop.
- Silently using mobile data for 2.3 GB.
- Blocking or re-downloading on the Redmi or the Pad, which already have their models.

## Constraints

- App targets SDK 28 (legacy external storage), minSdk 28, arm64 only, CPU only. Pixel 7a runs a much newer
  Android; `/sdcard/Jarvis/models` access there is unverified and must be checked on the device.
- Storage path stays `/sdcard/Jarvis/models` (`ModelPaths.DEFAULT_ROOT`); push-models.sh keeps working.
- One always-on foreground service (`JarvisService`) owns the pipeline; it already starts at boot and shows a notification.
- Correctness over speed (standing user preference).

## The download set (user decision: option B minus Gemma, Qwen2.5 as the chat model)

Same as the Redmi has today, except: no Gemma 3 1B, no `asr-custom`. About 2.3 GB.

| Group (screen label) | Files | Source | ~Size |
|---|---|---|---|
| Brain · Qwen2.5 1.5B | `llm/qwen2.5-1.5b-instruct-q4_0.gguf` | HF `Qwen/Qwen2.5-1.5B-Instruct-GGUF` | 1066 MB |
| Backup brain · Qwen2.5 0.5B | `llm/qwen2.5-0.5b-instruct-q4_0.gguf` | HF `Qwen/Qwen2.5-0.5B-Instruct-GGUF` | 429 MB |
| Wake word | `kws/` (tar.bz2) + `kws/keywords.txt` | sherpa-onnx `kws-models` release | 19 MB |
| Voice detector | `vad/silero_vad.onnx` | sherpa-onnx `asr-models` release | 1 MB |
| Live hearing | `asr-live/` kroko zipformer (tar.bz2) | sherpa-onnx `asr-models` | 68 MB |
| Accurate hearing | `asr-parakeet/` (tar.bz2) | sherpa-onnx `asr-models` | 136 MB |
| Hearing fallbacks | `asr-zipformer/`, `asr-moonshine/` (tar.bz2) | sherpa-onnx `asr-models` | 250 MB |
| Voices ×4 | `tts/` Alan, `tts-voices/{arctic,l2arctic,kusal}` (tar.bz2) | sherpa-onnx `tts-models` | 290 MB |
| Vision | `vlm/SmolVLM-256M-Instruct-Q8_0.gguf`, `vlm/mmproj-…` | HF `ggml-org/SmolVLM-256M-Instruct-GGUF` | 279 MB |
| Memory embedder | `embed/all-MiniLM-L6-v2-Q8_0.gguf` | HF `second-state/All-MiniLM-L6-v2-Embedding-GGUF` | 25 MB |

URLs are exactly those in `scripts/fetch-models.sh` (it is the reference). Archive handling also follows the
script: strip the top folder; for `tts-voices/*` delete the duplicate `espeak-ng-data`; write
`kws/keywords.txt` with the script's content.

**Shipped inside the APK, not downloaded** (they are not public and are tiny): the four expert vectors
`adapters/*.jvec` + `adapters/experts.json` (~330 KB; `experts.json` taken from the Redmi's copy, it is not in
the repo yet) and `kws/keywords.txt`. Copied into place on first run only if absent. The vectors were built for
Qwen2.5 0.5B (896 wide); llama.cpp refuses a vector whose width does not match the model, so on Qwen2.5 1.5B or
Gemma they are a harmless no-op and only help on the 0.5B fallback (user decision: ship them anyway, same on every
phone; 1.5B vectors are a separate later task). The overnight evolution trains its own vector for whichever model runs.

After everything is verified, write `llm/active_model.txt` = `qwen2.5-1.5b-instruct-q4_0.gguf` **only if the
file does not already exist** (so the Redmi stays on Gemma, the Pad on its own choice).

## Approaches considered

1. **Jarvis's own downloader inside JarvisService** — *picked by the user.* A built-in list (manifest) of every
   file with URL, size and SHA-256; the service downloads in priority order to `.part` files with HTTP Range
   resume, verifies, unpacks, then renames into place. Full control of order, checks, Wi-Fi-only, retries and
   error messages; unit-testable on the laptop like the rest of the app. Cost: more code (~400–600 lines + tests),
   one small dependency for bzip2.
2. **Android DownloadManager.** Less code; the system resumes across reboots and shows its own notification.
   But ordering, retries and error reasons are the system's, flaky on some OEM builds, a second notification
   next to Jarvis's, and the logic can only be tested on a phone. Rejected for the correctness preference.
3. **Hosted catalogue** (a list fetched from a server so it can change without an app update). Rejected: needs a
   server the user would run, and the user does not want files hosted.

## Design (decided parts)

**Manifest.** A JSON asset in the APK (`assets/models/manifest.json`), one entry per download: id, screen group,
URL, byte size, SHA-256 of the downloaded file (the archive itself for tar.bz2), kind (`file` or `tar.bz2`),
destination, and a list of the files that must exist afterwards (so "installed" can be checked without hashing).
Fingerprints are computed once on the laptop from the real upstream downloads and pinned; upstream files are
fetched by pinned URL (Hugging Face `resolve/<commit>` where possible rather than `main`) so a changed upstream
file fails the check instead of slipping in.

**When the ready screen shows (user decision).** At service start, check only the files Jarvis cannot start
without: `ModelPaths.speechRequired()` (wake word, voice detector, `asr-zipformer`, Alan voice) plus *any* chat
model resolvable by `ModelPaths.resolveLlmFileName`. "Present" = exists and non-empty, the same test as
`ModelPaths.require`; sizes are **not** compared with the manifest (files pushed over USB are never re-downloaded).
- All present → start normally, exactly as today (Redmi, Pad). **Nothing is downloaded in the background** on such
  a phone, not even missing extras.
- Anything missing → setup mode: the voice pipeline does not start; the downloader fetches every manifest item not
  already present; the HUD shows the ready screen. Once setup mode is entered it runs to the end (all items done or
  the extras rule below), so a fresh phone gets the full set.

**Must-have and extras (user decision).** Each manifest item has `required: true|false`.
- Must-have: wake word, voice detector, `asr-zipformer` (in `speechRequired`), live hearing (kroko), Alan voice,
  Qwen2.5 1.5B.
- Extras: accurate hearing (Parakeet), Moonshine, the three extra voices, vision, memory embedder, Qwen2.5 0.5B.
- When all is healthy Jarvis waits for everything. An extra that fails 3 checks turns red with Retry, and a
  "Start without it" button appears; the service retries failed extras later (next start / network change).
  A must-have that fails keeps the setup screen up with the reason and Retry; Jarvis cannot run without it.
- Hugging Face files are pinned by commit; sherpa-onnx GitHub release assets cannot be pinned and may be replaced
  upstream, which is why only the extras rule can unblock a permanently failing file.

**Downloader (in the service, own coroutine, IO dispatcher).**
- Order: wake word, voice detector, live hearing, accurate hearing, Qwen2.5 1.5B, voices, then the rest.
  One file at a time (the phone's link is the bottleneck; avoids partial states everywhere).
- Free-space check before starting (total remaining + 10% + the largest archive's unpacked size); if short,
  stop with a clear message, never start a download that cannot finish.
- Each file: `<dest>.part` + a small `.part.json` (URL, expected size, bytes written). Resume with
  `Range: bytes=N-`; if the server ignores Range (200 instead of 206), restart that file cleanly.
  Follow redirects (both hosts redirect to CDNs).
- After the last byte: SHA-256 of the `.part`; mismatch → delete and retry (max 3), then mark the row failed
  with the reason. Match → for `file`, fsync + atomic rename to the destination; for `tar.bz2`, unpack into
  `<dest>.tmp/`, apply the post-steps, then rename the folder into place and delete the archive.
- Network: Wi-Fi (unmetered) only by default; a switch allows mobile data. Losing the network pauses and
  resumes when it returns; transient errors retry with backoff (2 s → 60 s).
- Holds a partial wake lock while downloading; progress throttled to ~2 updates/s.
- Survives app close and reboot because the service already restarts (BootReceiver) and the `.part` files persist.

**Status.** The service exposes a `StateFlow<SetupProgress>` (per item: queued / downloading bytes / verifying /
unpacking / done / failed+reason; totals; ETA; network state; Wi-Fi-only flag) like its other flows. The
notification reads "Downloading models · 48%".

**Ready screen — mockup A "Reactor charging"** (picked by the user). In the existing HUD theme (`JarvisTheme`,
`Jx` colours, `GlassPanel`, `HudLabel`): title "Bringing Jarvis online", chips (on-device, Wi-Fi state, free
space); the reactor orb whose outer progress ring fills with overall percent, percent in the centre, current
step label ("installing brain") and "1.1 of 2.3 GB · ~9 min left" under it; a glass panel listing the items
(done ✓, active with its own bar, queued ○, failed in red with a Retry button), collapsed to the active few with
"tap to expand"; footer "Jarvis wakes when every module checks out" and the Wi-Fi-only switch. When all done:
the ring completes, "Ready", and Jarvis starts its normal pipeline.

**Dependencies.** `org.apache.commons:commons-compress` for bzip2/tar (pure Java). No new network library
(`HttpURLConnection`).

**Testing.**
- JVM unit tests: manifest parsing; "what's missing" planner; downloader against a local test HTTP server
  (resume with Range, server ignoring Range, checksum mismatch → retry → fail, redirect, disk-full check);
  tar.bz2 unpack + post-steps; atomic install (no destination file until verified); active_model.txt rule.
- Device test on the attached Redmi: install into a **separate test folder** (debug-only override of the
  model root, e.g. `/sdcard/Jarvis/models-dltest`) so its working models are untouched; kill the app and toggle
  Wi-Fi mid-download to prove resume; then confirm Jarvis starts from the test folder.
- Pixel 7a: install via APK file, run the real first-run flow, confirm storage access on new Android.

## Left to the builder

Class and file names; exact JSON field names; backoff numbers; how the debug model-root override is passed;
Compose details of the screen beyond the mockup; whether `ModelPaths` required lists are generated from the
manifest or kept in sync by a test.

## Calls made by me (not the user)

- If `/sdcard/Jarvis/models` is not usable on the Pixel 7a's newer Android, the device test will show it and it is
  handled then (likely low risk: targetSdk 28 with legacy storage permissions already requested in `HudActivity`).
- The debug model-root override (for the Redmi test folder) stays debug-only unless the Pixel test forces otherwise.
