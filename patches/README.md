# Local llama.cpp patches

`third_party/llama.cpp` is not committed in this repo, so its local changes are kept here. Apply with
`git -C third_party/llama.cpp apply ../../patches/<file>.patch`.

- `llama.cpp-armv8.0-neon-q4_0-q8_0.patch`: faster Q4_0 and Q8_0 dot products on ARMv8.0 CPUs without
  `sdot` (the phone's SDM636: Cortex-A73/A53). Products are summed in 16-bit lanes before widening (q4 x q8: 4 per
  lane, q8 x q8: 2 per lane), and Q4_0 gets a 2 rows x 2 columns kernel so each unpacked weight block serves two
  tokens. On the phone (Gemma 3 1B Q4_0, 4 threads): prompt 15.5 -> 23.2 tokens/s, generation 7.7 -> 8.2 tokens/s.
  Checked with `test-quantize-fns` (incl. nrc=2) built for armv8-a, and greedy output identical to the stock build.
