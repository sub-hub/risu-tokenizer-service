package com.risutoken.tokenizer

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.Encoding
import com.knuddels.jtokkit.api.EncodingType

/**
 * tiktoken-compatible engine backed by jtokkit (pure Java, zero deps).
 * Supports the encodings RisuAI uses via @dqbd/tiktoken (WASM):
 * cl100k_base (GPT-3.5/GPT-4) and o200k_base (GPT-4o).
 *
 * jtokkit embeds the same BPE rank tables as OpenAI's tiktoken, so golden
 * tests comparing against @dqbd/tiktoken output must match token-for-token.
 */
class JtokkitEngine(
    override val name: String,
    private val encodingType: EncodingType,
) : TokenizerEngine {

    override val version: String = "jtokkit-1.1.0"

    private val encoding: Encoding = Encodings.newDefaultEncodingRegistry().getEncoding(encodingType)

    override fun encode(text: String): IntArray =
        // jtokkit's IntArrayList exposes toArray(): IntArray
        encoding.encode(text).toArray()

    companion object {
        fun cl100kBase(): JtokkitEngine =
            JtokkitEngine("cl100k_base", EncodingType.CL100K_BASE)

        fun o200kBase(): JtokkitEngine =
            JtokkitEngine("o200k_base", EncodingType.O200K_BASE)
    }
}
