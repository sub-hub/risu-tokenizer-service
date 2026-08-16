package com.risutoken.api

import com.risutoken.cache.TokenCacheService
import com.risutoken.config.RisuTokenProperties
import com.risutoken.tokenizer.TokenizerRegistry
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/api")
class TokenizeController(
    private val cacheService: TokenCacheService,
    private val registry: TokenizerRegistry,
    private val props: RisuTokenProperties,
) {

    /** Single-text tokenization with 2-tier cache. */
    @PostMapping("/tokenize")
    fun tokenize(@RequestBody req: TokenizeRequest): Mono<TokenizeResponse> {
        validateText(req.text)
        val engine = registry.get(req.tokenizer)
        return cacheService.tokenize(engine, req.text)
            .map { r ->
                TokenizeResponse(
                    tokenizer = engine.name,
                    modelVersion = engine.version,
                    count = r.ids.size,
                    ids = if (req.mode == Mode.IDS) r.ids.toList() else null,
                    cache = r.source.name,
                )
            }
    }

    /**
     * Batch tokenization. Texts are processed as a bounded-concurrency Flux so
     * a large batch cannot exhaust the CPU scheduler queue.
     */
    @PostMapping("/tokenize/batch")
    fun batch(@RequestBody req: TokenizeBatchRequest): Mono<TokenizeBatchResponse> {
        if (req.texts.isEmpty()) throw badRequest("texts must not be empty")
        if (req.texts.size > props.tokenizer.maxBatchSize) {
            throw badRequest("batch size ${req.texts.size} exceeds limit ${props.tokenizer.maxBatchSize}")
        }
        for (t in req.texts) validateText(t)

        val engine = registry.get(req.tokenizer)
        return Flux.fromIterable(req.texts.withIndex())
            // flatMapSequential preserves input order while still bounding
            // concurrency. Plain flatMap reorders results by completion time,
            // which breaks callers expecting index order.
            .flatMapSequential(
                { (i, text) ->
                    cacheService.tokenize(engine, text).map { r ->
                        BatchItem(
                            index = i,
                            count = r.ids.size,
                            ids = if (req.mode == Mode.IDS) r.ids.toList() else null,
                            cache = r.source.name,
                        )
                    }
                },
                props.tokenizer.batchConcurrency,
            )
            .collectList()
            .map { TokenizeBatchResponse(engine.name, engine.version, it) }
    }

    /** List available tokenizer names (e.g. for client-side dropdowns). */
    @GetMapping("/tokenizers")
    fun tokenizers(): TokenizerListResponse = TokenizerListResponse(registry.names())

    private fun validateText(text: String) {
        if (text.length > props.tokenizer.maxTextLength) {
            throw badRequest("text length ${text.length} exceeds limit ${props.tokenizer.maxTextLength}")
        }
    }

    private fun badRequest(msg: String): ResponseStatusException =
        ResponseStatusException(HttpStatus.BAD_REQUEST, msg)
}
