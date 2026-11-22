#pragma once
// Finds which CPU cores are fastest on whatever phone the app runs on, instead of assuming a fixed layout.
// Cores are ranked by the kernel's cpu_capacity (it reflects both the core design and its top clock), falling back to
// cpuinfo_max_freq. The chat model's threads use the top-ranked cores; audio threads use the rest.

#include <sched.h>
#include <unistd.h>

#include <algorithm>
#include <cstdlib>
#include <atomic>
#include <cstdio>
#include <string>
#include <vector>

namespace cputopo {

// Cores the kernel has switched on, from /sys/devices/system/cpu/online ("0-4,6-7"). Some phones switch cores off
// for long periods (the Redmi Note 5 Pro keeps core 5 off), and a thread pinned to an offline core has to share another.
inline std::vector<bool> online_cores(int possible) {
  std::vector<bool> on(static_cast<size_t>(possible), false);
  FILE *f = std::fopen("/sys/devices/system/cpu/online", "r");
  char buf[256] = {0};
  bool ok = f && std::fgets(buf, sizeof(buf), f);
  if (f) std::fclose(f);
  if (!ok) return std::vector<bool>(static_cast<size_t>(possible), true);
  const char *p = buf;
  while (*p) {
    char *end = nullptr;
    long a = std::strtol(p, &end, 10);
    if (end == p) break;
    long b = a;
    p = end;
    if (*p == '-') {
      b = std::strtol(p + 1, &end, 10);
      p = end;
    }
    for (long c = a; c <= b && c < possible; ++c) {
      if (c >= 0) on[static_cast<size_t>(c)] = true;
    }
    if (*p == ',') ++p; else break;
  }
  return on;
}

struct Topology {
  int possible = 1;  // cores the chip has
  int ncpu = 1;      // cores online when detected (the ones ranked)
  std::vector<int> ranked;  // core ids, fastest first
  std::vector<long> score;  // per core id
  std::string signature;    // "id:score,..." - identifies the layout in the tuning cache
  int top_class = 1;        // how many cores share the best score (the "big" cluster)
};

inline long read_long(const char *path) {
  FILE *f = std::fopen(path, "r");
  if (!f) return -1;
  long v = -1;
  if (std::fscanf(f, "%ld", &v) != 1) v = -1;
  std::fclose(f);
  return v;
}

inline Topology detect() {
  Topology t;
  long n = sysconf(_SC_NPROCESSORS_CONF);
  t.possible = static_cast<int>(std::max(1L, std::min(n, 64L)));
  t.score.assign(t.possible, 0);
  const std::vector<bool> on = online_cores(t.possible);
  char path[128];
  bool have_capacity = true;
  for (int c = 0; c < t.possible; ++c) {
    std::snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpu_capacity", c);
    if (on[c] && read_long(path) <= 0) { have_capacity = false; break; }
  }
  for (int c = 0; c < t.possible; ++c) {
    long s;
    if (have_capacity) {
      std::snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpu_capacity", c);
    } else {
      std::snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", c);
    }
    s = read_long(path);
    t.score[c] = s > 0 ? s : 0;
  }
  for (int c = 0; c < t.possible; ++c) {
    if (on[c]) t.ranked.push_back(c);
  }
  if (t.ranked.empty()) t.ranked.push_back(0);
  t.ncpu = static_cast<int>(t.ranked.size());
  // Fastest first; ties keep the higher core id first (big clusters are usually numbered last).
  std::stable_sort(t.ranked.begin(), t.ranked.end(), [&](int a, int b) {
    if (t.score[a] != t.score[b]) return t.score[a] > t.score[b];
    return a > b;
  });
  t.top_class = 0;
  for (int c : t.ranked) {
    if (t.score[c] == t.score[t.ranked[0]]) ++t.top_class;
  }
  t.top_class = std::max(1, t.top_class);
  t.signature = have_capacity ? "cap" : "freq";
  for (int c : t.ranked) t.signature += "," + std::to_string(c) + ":" + std::to_string(t.score[c]);
  return t;
}

inline const Topology &get() {
  static const Topology t = detect();
  return t;
}

// How many of the top-ranked cores the chat model uses. Set by the tuner; before that, the big cluster
// (at least 2 and at most 4 cores), which is what the original 4+4 phone used.
inline std::atomic<int> &llm_core_count_ref() {
  static std::atomic<int> n{0};
  return n;
}

// Chat-model cores before tuning: the big cluster when it has 3-4 online cores, otherwise up to 4 of the fastest.
inline int default_llm_cores() {
  const Topology &t = get();
  if (t.top_class >= 3) return std::min(4, t.top_class);
  return std::min(4, t.ncpu);
}

inline int llm_cores() {
  int n = llm_core_count_ref().load();
  return n > 0 ? n : default_llm_cores();
}

inline void set_llm_cores(int n) { llm_core_count_ref().store(std::max(1, std::min(n, get().ncpu))); }

// The k fastest cores that are online right now (a core switched off since start-up is skipped).
inline cpu_set_t top_cores_mask(int k) {
  const Topology &t = get();
  const std::vector<bool> on = online_cores(t.possible);
  cpu_set_t mask;
  CPU_ZERO(&mask);
  k = std::max(1, std::min(k, t.ncpu));
  int taken = 0;
  for (int c : t.ranked) {
    if (taken >= k) break;
    if (!on[static_cast<size_t>(c)]) continue;
    CPU_SET(c, &mask);
    ++taken;
  }
  if (taken == 0) CPU_SET(t.ranked[0], &mask);
  return mask;
}

// Cores left over for audio: everything the chat model does not use, or the slower half if it uses them all.
inline cpu_set_t audio_cores_mask() {
  const Topology &t = get();
  int k = llm_cores();
  cpu_set_t mask;
  CPU_ZERO(&mask);
  if (k >= t.ncpu) {
    for (int i = t.ncpu / 2; i < t.ncpu; ++i) CPU_SET(t.ranked[i], &mask);
  } else {
    for (int i = k; i < t.ncpu; ++i) CPU_SET(t.ranked[i], &mask);
  }
  return mask;
}

inline std::string describe() {
  const Topology &t = get();
  std::string s = "cores fastest-first:";
  for (int c : t.ranked) s += " " + std::to_string(c) + "(" + std::to_string(t.score[c]) + ")";
  s += " big-cluster=" + std::to_string(t.top_class);
  return s;
}

}  // namespace cputopo
