package com.risutoken.api

/** Response mode: full token ids, or just the count (smaller payload). */
enum class Mode { IDS, COUNT }

data class TokenizeRequest(
    val text: String = "",
    val tokenizer: String = "cl100k_base",
    val mode: Mode = Mode.IDS,
)

data class TokenizeResponse(
    val tokenizer: String,
    val modelVersion: String,
    val count: Int,
    val ids: List<Int>? = null,
    /** Cache tier that served this request: L1, L2 or COMPUTE. */
    val cache: String,
)

data class TokenizeBatchRequest(
    val texts: List<String> = emptyList(),
    val tokenizer: String = "cl100k_base",
    val mode: Mode = Mode.IDS,
)

data class BatchItem(
    val index: Int,
    val count: Int,
    val ids: List<Int>? = null,
    val cache: String,
)

data class TokenizeBatchResponse(
    val tokenizer: String,
    val modelVersion: String,
    val results: List<BatchItem>,
)

data class TokenizerListResponse(
    val tokenizers: List<String>,
)
