package com.risutoken.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Application settings.
 *
 * `models-dir` points at the repo-level `models/` directory (1:1 copies of
 * RisuAI public/token/ + src/etc/ model files).
 */
@ConfigurationProperties(prefix = "risutoken")
data class RisuTokenProperties(
    val modelsDir: String = "../models",
    val cache: Cache = Cache(),
    val tokenizer: Tokenizer = Tokenizer(),
) {
    data class Cache(
        /** L1 (local, Caffeine) maximum entries. */
        var l1MaxSize: Long = 10_000,
        /** L1 expiry after write. */
        var l1ExpireAfterWriteMinutes: Long = 60,
        /** L2 (distributed, Redis) TTL. */
        var l2TtlHours: Long = 24,
        /** Prefix for Redis keys. */
        var keyPrefix: String = "risu:tok:",
        /** Cap for the async L2 write-back buffer (bounded sink). */
        var l2WriteBackBufferSize: Int = 2_000,
    )

    data class Tokenizer(
        /** Threads for the CPU-bound tokenize scheduler. */
        var cpuThreads: Int = Runtime.getRuntime().availableProcessors(),
        /** Bounded queue for the tokenize scheduler (memory bound: ~83KB x this). */
        var computeQueueSize: Int = 2_000,
        /** Fast-fail: requests waiting for compute longer than this are rejected. */
        var computeWaitTimeoutSeconds: Long = 5,
        /** Max characters per text payload. */
        var maxTextLength: Int = 1_000_000,
        /** Max texts per batch request. */
        var maxBatchSize: Int = 1_000,
        /** Max concurrent encodes inside a batch. */
        var batchConcurrency: Int = 16,
    )
}
