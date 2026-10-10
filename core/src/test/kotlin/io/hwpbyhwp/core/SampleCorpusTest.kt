package io.hwpbyhwp.core

import io.hwpbyhwp.core.parser.DocumentLoader
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Loads every .hwp/.hwpx file under $HWP_SAMPLES (if set) and fails listing the files that
 * could not be converted. Sample documents are not checked in; point it at a local corpus.
 */
class SampleCorpusTest {
    @Test
    fun loadsCorpus() {
        val dir = System.getenv("HWP_SAMPLES")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val files = dir.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("hwp", "hwpx") }.toList()
        val failures = files.mapNotNull { f ->
            runCatching { DocumentLoader.load(f) }.exceptionOrNull()?.let { "${f.name}: ${it.cause ?: it}" }
        }
        println("corpus: ${files.size} files, ${failures.size} failed")
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }
}
