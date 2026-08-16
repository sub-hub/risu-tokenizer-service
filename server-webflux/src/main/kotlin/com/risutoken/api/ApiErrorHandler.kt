package com.risutoken.api

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeoutException

/** Maps domain errors to clean HTTP responses instead of raw 500s. */
@RestControllerAdvice
class ApiErrorHandler {

    private val log = LoggerFactory.getLogger(ApiErrorHandler::class.java)

    /** Unknown tokenizer / bad input -> 400 Bad Request. */
    @ExceptionHandler(IllegalArgumentException::class)
    fun onIllegalArgument(e: IllegalArgumentException, exchange: ServerWebExchange): Map<String, String> {
        log.info("Bad request: {}", e.message)
        exchange.response.statusCode = HttpStatus.BAD_REQUEST
        return mapOf("error" to (e.message ?: "Invalid request"))
    }

    /** Let Spring's own status exceptions flow through with their status. */
    @ExceptionHandler(ResponseStatusException::class)
    fun onStatus(e: ResponseStatusException, exchange: ServerWebExchange): Map<String, String> {
        exchange.response.statusCode = e.statusCode
        return mapOf("error" to (e.reason ?: "Error"))
    }

    /**
     * Compute queue saturation fast-fail: a request waited longer than
     * `tokenizer.compute-wait-timeout-seconds` for a compute slot. Respond 503
     * instead of letting it pile up behind an overloaded queue.
     */
    @ExceptionHandler(TimeoutException::class)
    fun onTimeout(e: TimeoutException, exchange: ServerWebExchange): Map<String, String> {
        log.warn("Compute wait timeout (queue saturation): {}", e.message)
        exchange.response.statusCode = HttpStatus.SERVICE_UNAVAILABLE
        return mapOf("error" to "compute busy, retry later")
    }

    /**
     * Load shedding: the fixed pool's bounded queue is full, so the executor
     * rejected the task immediately. Respond 503 instead of queueing work
     * (and memory) without limit.
     */
    @ExceptionHandler(RejectedExecutionException::class)
    fun onRejected(e: RejectedExecutionException, exchange: ServerWebExchange): Map<String, String> {
        log.warn("Compute queue saturated, rejecting request: {}", e.message)
        exchange.response.statusCode = HttpStatus.SERVICE_UNAVAILABLE
        return mapOf("error" to "compute busy, retry later")
    }
}
