package io.hwpbyhwp.core.preview

import io.hwpbyhwp.core.layout.PageLayouter
import io.hwpbyhwp.core.parser.DocumentLoader
import java.io.File
import kotlin.test.Test

/**
 * Renders the first pages of each document in $HWP_SAMPLES to PNGs in $HWP_PREVIEW_OUT.
 * Optional $HWP_PREVIEW_FILTER limits it to file names containing that text, and
 * $HWP_PREVIEW_PAGES picks a 1-based page range such as "5-20" (default: the first 3);
 * $HWP_PREVIEW_DPI sets the resolution (default 96).
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
            val dpi = System.getenv("HWP_PREVIEW_DPI")?.toIntOrNull() ?: 96
            val range = System.getenv("HWP_PREVIEW_PAGES")?.split("-")?.let { (a, b) -> a.toInt()..b.toInt() } ?: 1..3
            pages.forEachIndexed { i, page ->
                if (i + 1 in range) Java2DPreview.render(page, doc.binaries, File(out, "${f.nameWithoutExtension}_p${i + 1}.png"), dpi)
            }
        }
    }
}
