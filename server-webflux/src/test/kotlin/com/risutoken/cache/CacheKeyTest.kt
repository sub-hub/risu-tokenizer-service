package com.risutoken.cache

import com.risutoken.config.CacheKeyUtil
import com.risutoken.tokenizer.JtokkitEngine
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Cache key design.
 *
 *  - same text + same engine  -> same key (dedup works)
 *  - same text + DIFFERENT engine -> DIFFERENT key (no cross-tokenizer poisoning)
 *  - engine version bump -> DIFFERENT key (model upgrade must not serve stale ids)
 *
 * The key binds each cached result to the exact engine+version that produced
 * it, so cache entries are never shared across tokenizer/model boundaries.
 */
class CacheKeyTest {

    private val prefix = "risu:tok:"

    @Test
    fun `same text and engine produce the same key`() {
        val engine = JtokkitEngine.cl100kBase()
        val k1 = CacheKeyUtil.tokenizeKey(engine, "Hello world", prefix)
        val k2 = CacheKeyUtil.tokenizeKey(engine, "Hello world", prefix)
        assertEquals(k1, k2)
    }

    @Test
    fun `different texts produce different keys`() {
        val engine = JtokkitEngine.cl100kBase()
        assertNotEquals(
            CacheKeyUtil.tokenizeKey(engine, "Hello world", prefix),
            CacheKeyUtil.tokenizeKey(engine, "Hello world!", prefix),
        )
    }

    @Test
    fun `same text across different engines must NOT share a key`() {
        val cl100k = JtokkitEngine.cl100kBase()
        val o200k = JtokkitEngine.o200kBase()
        assertNotEquals(
            CacheKeyUtil.tokenizeKey(cl100k, "Hello world", prefix),
            CacheKeyUtil.tokenizeKey(o200k, "Hello world", prefix),
            "cross-tokenizer cache poisoning",
        )
    }

    @Test
    fun `key is a fixed-size sha256 hex, independent of text length`() {
        val engine = JtokkitEngine.cl100kBase()
        val short = CacheKeyUtil.tokenizeKey(engine, "a", prefix)
        val long = CacheKeyUtil.tokenizeKey(engine, "a".repeat(100_000), prefix)
        assertEquals(prefix.length + 64, short.length)
        assertEquals(prefix.length + 64, long.length)
        assertNotEquals(short, long)
    }
}
