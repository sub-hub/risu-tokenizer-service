package com.risutoken.cache

import com.github.benmanes.caffeine.cache.Cache
import com.risutoken.config.CacheKeyUtil
import com.risutoken.config.RisuTokenProperties
import com.risutoken.tokenizer.TokenizerEngine
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import java.time.Duration

/** Where a result came from: L1 (local) cache, L2 (distributed) cache, or compute. */
enum class CacheSource { L1, L2, COMPUTE }

data class TokenizeResult(
    val ids: IntArray,
    val source: CacheSource,
) {
    override fun equals(other: Any?): Boolean =
        other is TokenizeResult && ids.contentEquals(other.ids) && source == other.source

    override fun hashCode(): Int = ids.contentHashCode() * 31 + source.hashCode()
}

/**
 * Cache-aside tokenization pipeline:
 *
 *   request -> L1 (Caffeine, in-process) -> L2 (Redis, distributed) -> compute -> write-back L1+L2
 *
 * The L2 hop makes the cache effective across multiple service instances,
 * which a single-process in-memory cache cannot provide.
 */
@Service
class TokenCacheService(
    private val l1Cache: Cache<String, IntArray>,
    private val redis: ReactiveStringRedisTemplate,
    private val props: RisuTokenProperties,
    private val scheduler: Scheduler,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(TokenCacheService::class.java)

    // ---- observability: cache hit/miss counters exposed via /actuator/prometheus ----
    private val l1Hits = meterRegistry.counter("risu.cache.hits", "tier", "l1")
    private val l2Hits = meterRegistry.counter("risu.cache.hits", "tier", "l2")
    private val computes = meterRegistry.counter("risu.cache.hits", "tier", "compute")
    private val l1Misses = meterRegistry.counter("risu.cache.misses", "tier", "l1")
    private val l2Misses = meterRegistry.counter("risu.cache.misses", "tier", "l2")
    private val computeMillis = meterRegistry.timer("risu.cache.compute.time")

    init {
        // Gauge the L1 hit rate + size for dashboards
        meterRegistry.gauge("risu.cache.l1.size", l1Cache) { it.estimatedSize().toDouble() }
        io.micrometer.core.instrument.Gauge.builder("risu.cache.l1.hit_rate") { l1Cache.stats().hitRate() }
            .register(meterRegistry)
    }

    private fun l2Ttl(): Duration = Duration.ofHours(props.cache.l2TtlHours)

    fun tokenize(engine: TokenizerEngine, text: String): Mono<TokenizeResult> {
        val key = CacheKeyUtil.tokenizeKey(engine, text, props.cache.keyPrefix)

        // ---- L1 lookup (no blocking: Caffeine is lock-free get) ----
        val l1 = l1Cache.getIfPresent(key)
        if (l1 != null) {
            l1Hits.increment()
            return Mono.just(TokenizeResult(l1, CacheSource.L1))
        }
        l1Misses.increment()

        // ---- L2 lookup (reactive Lettuce: non-blocking Redis round-trip) ----
        return redis.opsForValue().get(key)
            .flatMap { raw: String ->
                val parsed = parseTokens(raw)
                if (parsed == null) {
                    Mono.empty()
                } else {
                    l2Hits.increment()
                    l1Cache.put(key, parsed) // promote an L2 hit into L1
                    Mono.just(TokenizeResult(parsed, CacheSource.L2))
                }
            }
            .onErrorResume { e: Throwable ->
                // Redis down/unreachable: degrade to compute instead of failing
                // the request. The write-back already tolerates L2 failures.
                log.warn("L2 read failed, falling back to compute: {}", e.message)
                Mono.empty()
            }
            .switchIfEmpty(
                Mono.defer {
                    l2Misses.increment() // Redis returned empty or unparseable
                    computes.increment()
                    // CPU-bound encode runs on the tokenizer scheduler (see subscribeOn).
                    // Timing is measured around ONLY the encode call, not the pipeline.
                    val start = System.nanoTime()
                    val ids = engine.encode(text)
                    computeMillis.record(Duration.ofNanos(System.nanoTime() - start))
                    Mono.just(ids)
                }
                    .subscribeOn(scheduler) // CPU-bound work OFF the event loop
                    .doOnNext { ids: IntArray ->
                        l1Cache.put(key, ids)
                        // Async write-back to L2: fire-and-forget, never blocks the response
                        redis.opsForValue()
                            .set(key, serializeTokens(ids), l2Ttl())
                            .subscribe(
                                { },
                                { e: Throwable -> log.warn("L2 write-back failed for {}", key, e) },
                            )
                    }
                    .map { TokenizeResult(it, CacheSource.COMPUTE) }
            )
    }

    /** Compact, debuggable wire format: comma-separated decimal token ids. */
    private fun serializeTokens(ids: IntArray): String =
        ids.joinToString(",")

    private fun parseTokens(raw: String): IntArray? = try {
        if (raw.isEmpty()) IntArray(0)
        else raw.split(",").map { it.toInt() }.toIntArray()
    } catch (e: NumberFormatException) {
        null
    }
}
