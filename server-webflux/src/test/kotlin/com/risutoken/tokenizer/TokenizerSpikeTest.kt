package com.risutoken.tokenizer

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Step 0 spike: verify both tokenizer libraries actually work on this machine
 * (jtokkit pure-Java, DJL tokenizers Rust core via JNI on Windows x64).
 */
class TokenizerSpikeTest {

    private val modelsDir: Path = Path(System.getProperty("user.dir")).parent.resolve("models")

    @Test
    fun `jtokkit cl100k encodes hello world with expected ids`() {
        val engine = JtokkitEngine.cl100kBase()
        val ids = engine.encode("Hello world")
        // GPT-3.5/GPT-4: "Hello" = 9906, " world" = 1917 (tiktoken reference)
        assertEquals(listOf(9906, 1917), ids.toList(), "cl100k_base must match OpenAI tiktoken output")
    }

    @Test
    fun `jtokkit o200k loads and encodes`() {
        val engine = JtokkitEngine.o200kBase()
        assertTrue(engine.encode("Hello world").isNotEmpty())
    }

    @Test
    fun `djl tokenizers loads llama3 json and encodes`() {
        val file = modelsDir.resolve("llama3.json")
        assertTrue(java.nio.file.Files.exists(file), "model file must exist: $file")
        assertDoesNotThrow {
            val engine = DjlEngine.fromJson("llama3", file)
            val ids = engine.encode("Hello world")
            assertTrue(ids.isNotEmpty(), "llama3 must produce tokens")
            println("llama3('Hello world') = ${ids.toList()}")
        }
    }

    @Test
    fun `djl tokenizers loads claude and deepseek json`() {
        for (name in listOf("claude", "deepseek")) {
            val file = modelsDir.resolve("$name.json")
            if (!java.nio.file.Files.exists(file)) {
                println("skip $name: model file missing")
                continue
            }
            assertDoesNotThrow {
                val engine = DjlEngine.fromJson(name, file)
                val ids = engine.encode("안녕하세요, 반갑습니다")
                assertTrue(ids.isNotEmpty())
                println("$name('안녕하세요') = ${ids.toList()}")
            }
        }
    }
}
