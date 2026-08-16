package com.risutoken.tokenizer

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * HuggingFace tokenizer.json engine backed by DJL tokenizers (Rust core via JNI).
 *
 * Loads the exact same model files RisuAI ships under public/token/
 * (llama3.json, claude.json, deepseek.json, ...) with zero conversion.
 */
class DjlEngine private constructor(
    override val name: String,
    modelFile: Path,
    private val tokenizer: HuggingFaceTokenizer,
) : TokenizerEngine {

    override val version: String = "djl-0.36.0:" + sha256(modelFile).take(12)

    override fun encode(text: String): IntArray {
        // DJL returns long[] ids; narrow to IntArray (token ids fit in Int)
        val ids = tokenizer.encode(text).ids
        return IntArray(ids.size) { ids[it].toInt() }
    }

    companion object {
        fun fromJson(name: String, modelFile: Path): DjlEngine {
            check(Files.exists(modelFile)) { "Tokenizer model not found: $modelFile" }
            return DjlEngine(
                name = name,
                modelFile = modelFile,
                // DJL defaults to modelMaxLength (often 512) and silently TRUNCATES
                // longer inputs. Tokenization must be lossless: never truncate.
                tokenizer = HuggingFaceTokenizer.newInstance(
                    modelFile,
                    mapOf("truncation" to "do_not_truncate"),
                ),
            )
        }

        private fun sha256(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
