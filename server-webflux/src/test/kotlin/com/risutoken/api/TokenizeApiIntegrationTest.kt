package com.risutoken.api

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * End-to-end API test against a real server + local Redis.
 * Requires redis-server on localhost:6379 (see repo README).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TokenizeApiIntegrationTest {

    @Autowired
    private lateinit var client: WebTestClient

    private val body = """
        {"text":"integration test payload ${System.nanoTime()}","tokenizer":"cl100k_base"}
    """.trimIndent()

    @Test
    fun `first request computes, second hits L1`() {
        val first = post(body)
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.cache").isEqualTo("COMPUTE")
            .jsonPath("$.count").isNumber
            .jsonPath("$.ids[0]").isNumber
            .returnResult()

        post(body)
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.cache").isEqualTo("L1")
            .jsonPath("$.count").isNumber
            .returnResult()
    }

    @Test
    fun `batch returns per-item results`() {
        val batch = """
            {"texts":["a","bb","ccc"],"tokenizer":"o200k_base","mode":"COUNT"}
        """.trimIndent()

        client.post().uri("/api/tokenize/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(batch)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.results.length()").isEqualTo(3)
            .jsonPath("$.results[0].index").isEqualTo(0)
            .returnResult()
    }

    @Test
    fun `unknown tokenizer returns 400 not 500`() {
        val bad = """{"text":"hi","tokenizer":"does-not-exist"}"""
        client.post().uri("/api/tokenize")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(bad)
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `tokenizer list contains all engines`() {
        client.get().uri("/api/tokenizers")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.tokenizers").isArray
            .jsonPath("$.tokenizers[0]").exists()
    }

    private fun post(json: String) =
        client.post().uri("/api/tokenize")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(json)
            .exchange()
}
