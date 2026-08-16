package com.risutoken.tokenizer

import kotlin.IntArray

/**
 * Tokenizer engine abstraction. Each engine wraps one concrete tokenizer model.
 *
 * `version` is part of the cache key. Two tokenizers (or two model files) must
 * never share cached entries, so the key binds the result to the exact engine
 * that produced it.
 */
interface TokenizerEngine {
    /** Stable identifier used in API requests, e.g. "cl100k_base", "llama3". */
    val name: String

    /** Model file hash / version. Part of the cache key. */
    val version: String

    /** Full tokenization: returns the token id sequence. */
    fun encode(text: String): IntArray

    /** Convenience: number of tokens for [text]. */
    fun count(text: String): Int = encode(text).size
}
