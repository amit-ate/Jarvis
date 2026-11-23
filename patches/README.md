# Jarvis changes to llama.cpp

`third_party/llama.cpp` is pinned to official llama.cpp **v1.0.0** (`b1b770d`). Jarvis's own changes live here as
patches and are applied by `scripts/setup-llama.sh` (run it once after cloning, before building). The app build
stops with a message if they are missing.

| Patch | What it does |
|---|---|
| `llama.cpp/0001-…` | ARMv8.0 (no `sdot`) fallback dot product accumulates with `sadalp` |
| `llama.cpp/0002-…` | Repacked weights are backed by a file when `GGML_REPACK_FILE_DIR` is set, so low-RAM phones can page them out |
| `llama.cpp/0003-…` | Faster Q4_0 and Q8_0 dot products on ARMv8.0 CPUs without `sdot` (the SDM636's Cortex-A73/A53): products are summed in 16-bit lanes before widening, and Q4_0 gets a 2 x 2 kernel so each unpacked weight block serves two tokens. The vision model's threads stay on the big cores. |

Measured on the Redmi Note 5 Pro (Gemma 3 1B Q4_0, 4 threads), patch 0003: prompt 15.5 -> 23.2 tokens/s, generation
7.7 -> 8.2 tokens/s, checked with `test-quantize-fns` (including nrc=2) and greedy output identical to the stock build.

To change a patch: edit the code in `third_party/llama.cpp`, commit it there on top of v1.0.0, then regenerate with
`git -C third_party/llama.cpp format-patch -o ../../patches/llama.cpp b1b770d..HEAD`.
