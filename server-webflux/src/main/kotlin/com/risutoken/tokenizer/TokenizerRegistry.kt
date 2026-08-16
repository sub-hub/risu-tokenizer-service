package com.risutoken.tokenizer

import com.risutoken.config.RisuTokenProperties
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

/**
 * Registers all available tokenizer engines. Model files live in the repo
 * `models/` directory (copied 1:1 from RisuAI public/token/ and src/etc/).
 */
@Component
class TokenizerRegistry(
    props: RisuTokenProperties,
) {
    private val modelsDir: Path = Path.of(props.modelsDir)
    private val engines: Map<String, TokenizerEngine> = buildMap {
        // tiktoken encodings (embedded in jtokkit, no file needed)
        put("cl100k_base", JtokkitEngine.cl100kBase())
        put("o200k_base", JtokkitEngine.o200kBase())

        // HuggingFace tokenizer.json models
        val jsonModels = listOf(
            "llama3" to modelsDir.resolve("llama3.json"),
            "claude" to modelsDir.resolve("claude.json"),
            "deepseek" to modelsDir.resolve("deepseek.json"),
        )
        for ((name, file) in jsonModels) {
            if (Files.exists(file)) {
                put(name, DjlEngine.fromJson(name, file))
            }
        }
    }

    fun get(name: String): TokenizerEngine =
        engines[name] ?: throw IllegalArgumentException(
            "Unknown tokenizer '$name'. Available: ${engines.keys.sorted().joinToString(", ")}"
        )

    fun names(): List<String> = engines.keys.sorted()
}
