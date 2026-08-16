package com.risutoken.config

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.risutoken.tokenizer.TokenizerEngine
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.security.MessageDigest
import java.time.Duration

@Configuration
@EnableConfigurationProperties(RisuTokenProperties::class)
class CacheConfig {

    /**
     * L1 cache: process-local, high-throughput, evicts by size + recency.
     * `recordStats()` enables hit-rate gauges for Micrometer/Prometheus.
     */
    @Bean
    fun l1Cache(props: RisuTokenProperties): Cache<String, IntArray> =
        Caffeine.newBuilder()
            .maximumSize(props.cache.l1MaxSize)
            .expireAfterWrite(Duration.ofMinutes(props.cache.l1ExpireAfterWriteMinutes))
            .recordStats()
            .build()

    /**
     * Dedicated scheduler for CPU-bound tokenization. The Netty event loop must
     * never run BPE merges (it would stall every in-flight request); CPU work is
     * hopped to a bounded elastic pool sized to the machine's cores.
     */
    @Bean
    fun tokenizerScheduler(props: RisuTokenProperties): Scheduler =
        Schedulers.newBoundedElastic(
            props.tokenizer.cpuThreads,
            props.tokenizer.cpuThreads * 10_000,
            "tokenizer",
        )
}

/** SHA-256 hex helper used to build compact, collision-safe cache keys. */
object CacheKeyUtil {
    /**
     * Bump this when engine BEHAVIOR changes (not the model file): the model
     * file hash alone is not enough. The DJL truncation fix changed token
     * output for the same files, and without a schema bump stale L2 entries
     * kept serving the truncated results.
     */
    const val CACHE_SCHEMA_VERSION = 2

    fun sha256Hex(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (p in parts) digest.update(p.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Cache key for a tokenize result.
     *
     * Binds the result to the exact tokenizer + model version that produced it.
     * Without this, switching tokenizers or upgrading a model file would serve
     * stale entries from the previous engine.
     */
    fun tokenizeKey(engine: TokenizerEngine, text: String, prefix: String): String =
        prefix + sha256Hex(
            CACHE_SCHEMA_VERSION.toString(),
            text,
            "\u0000",
            engine.name,
            "\u0000",
            engine.version,
        )
}
