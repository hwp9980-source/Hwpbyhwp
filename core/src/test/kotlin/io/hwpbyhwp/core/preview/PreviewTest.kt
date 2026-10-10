package io.hwpbyhwp.core.preview

import io.hwpbyhwp.core.layout.PageLayouter
import io.hwpbyhwp.core.parser.DocumentLoader
import java.io.File
import kotlin.test.Test

/**
 * Renders the first pages of each document in $HWP_SAMPLES to PNGs in $HWP_PREVIEW_OUT.
 * Optional $HWP_PREVIEW_FILTER limits it to file names containing that text.
 */
class PreviewTest {
    @Test
    fun renderPreviews() {
        val dir = System.getenv("HWP_SAMPLES")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val out = System.getenv("HWP_PREVIEW_OUT")?.let(::File) ?: return
        val filter = System.getenv("HWP_PREVIEW_FILTER").orEmpty()
        out.mkdirs()
        dir.listFiles()!!.filter { it.extension.lowercase() in setOf("hwp", "hwpx") && filter in it.name && it.length() < 3_000_000 }.sorted().forEach { f ->
            val doc = DocumentLoader.load(f)
            val pages = PageLayouter(doc, Java2DPreview.measurer).layout()
            println("${f.name}: ${pages.size} pages")
            pages.take(3).forEachIndexed { i, page ->
                Java2DPreview.render(page, doc.binaries, File(out, "${f.nameWithoutExtension}_p${i + 1}.png"))
            }
        }
    }
}
