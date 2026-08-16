Benchmark results: k6 v2.2.0, single machine (Windows, 6-core / 12 logical processors), localhost
Targets: WebFlux :8080 (8 tokenizer threads, Caffeine L1 10k + Redis L2) vs Node :8081

## Matrix A: short texts (~180 chars)

| scenario | vus | rps | med_ms | p90_ms | p99_ms | avg_ms |
|---|---|---|---|---|---|---|
| java-compute | 200 | 24085 | 6.00 | 10.00 | 11.51 | 6.43 |
| java-l1 | 200 | 23591 | 6.98 | 10.00 | 12.00 | 6.75 |
| node-compute | 200 | 5499 | 37.29 | 39.28 | 40.00 | 36.26 |
| node-lru | 200 | 5537 | 37.22 | 39.95 | 41.04 | 36.02 |

## Matrix B: long texts (~5KB)

| scenario | vus | rps | med_ms | p90_ms | p99_ms | avg_ms |
|---|---|---|---|---|---|---|
| java-compute | 200 | 15768 | 8.76 | 15.00 | 18.00 | 8.78 |
| java-l1 | 200 | 17620 | 7.00 | 13.60 | 17.00 | 7.92 |
| node-compute | 200 | 2194 | 90.83 | 96.50 | 98.72 | 90.75 |
| node-lru | 200 | 2234 | 89.82 | 93.85 | 95.59 | 89.15 |

## Matrix C: long texts (~83KB, 16K tokens, compute-dominated)

| scenario | vus | rps | med_ms | p90_ms | p99_ms | avg_ms |
|---|---|---|---|---|---|---|
| java-compute | 100 | 4048 | ~ | ~ | 37.2 | 13.7 |
| java-l1 | 100 | 4230 | ~ | ~ | 35.6 | 13.2 |
| node-compute | 100 | 110 | ~ | ~ | 915.3 | 881.9 |
| node-lru | 100 | 3251 | 29.0 | 32.1 | 33.8 | 29.8 |

> NOTE: k6 `__ITER` is per-VU, so texts repeat across VUs and cache absorption is large
> for BOTH servers (`java-compute`/`java-l1` are likewise ~99% L1-absorbed).
> `node-lru` re-measured 2025-08-16 (MODE=unique, VU=100, LEN=1000, 15s).

## Memory (working set, idle after load)

| server | process | working set |
|---|---|---|
| WebFlux | java (PID 9680) | 930 MB |
| Node baseline | node (PID 28112) | 69 MB |

## Headline deltas (Matrix C, compute-dominated)

- WebFlux vs Node (no cache, the original single-thread WASM architecture): 4048 vs 110 RPS = **36.8x** throughput; p99 37ms vs 915ms = **24.6x** tail latency
- **Fairness note**: the same workload with an in-memory LRU on Node measures 3,251 RPS,
  so cache-to-cache the gap is ~1.2x. The 37x headline is a before/after of the ORIGINAL
  architecture (single thread, no cache), not a claim that WebFlux is intrinsically faster.
  Pure-compute gap is per-core 6.9x (824 vs 119).
- Pure-compute E2E (both sides cache-less, globally unique texts): WebFlux 2,716 vs Node 110 RPS = **24.7x**
- WebFlux L1-cache-hit vs Node recompute (same text repeated): 4230 vs 110 RPS = **38.5x**
- Memory trade-off: WebFlux ~13x Node. JVM default heap sizing; can be reduced with -Xmx tuning.

## Where the gap comes from (per-core measurement)

Same 83K-char text (16,007 tokens), encode only, no HTTP:

| engine | encode time (single thread) | per-core max RPS |
|---|---|---|
| Node @dqbd/tiktoken (WASM) | 8.42ms avg | 119 |
| Java jtokkit | 1.21ms avg | 824 |

Decomposition of the ~37x service-level gap:

- **~7x per core**: tokenizer implementation. @dqbd/tiktoken is a WASM port with JS glue (regex pretokenization runs in JS); jtokkit is pure Java whose hot BPE-merge loops JIT-compile to native. Same CPU, same text.
- **8x parallelism (config)**: Node event loop computes synchronously on 1 thread (measured 110 RPS matches the 119/core ceiling); WebFlux runs encodes on an 8-thread scheduler (824/core x 8 = 6592 theoretical ceiling). NOTE: measured java-compute/java-l1 (~4k RPS) were L1-absorbed (99.7% hit rate), i.e. HTTP-bound, not a validation of the 6,592 ceiling; a cache-miss re-run (globally unique texts, tokenize-miss.js) measured 2,716 RPS on the same machine.

Honest caveat: a Node baseline using a faster native tokenizer WASM (e.g. tokenizers-rs) would close part of the per-core gap. The takeaway is not "WebFlux is 37x faster" but "isolating tokenization as a service unlocks (1) a faster engine and (2) thread scaling", both impossible while it ran on the client's single thread.
