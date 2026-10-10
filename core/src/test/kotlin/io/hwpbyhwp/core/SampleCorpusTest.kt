package io.hwpbyhwp.core

import io.hwpbyhwp.core.model.Paragraph
import io.hwpbyhwp.core.model.TableObject
import io.hwpbyhwp.core.model.TextRun
import io.hwpbyhwp.core.layout.ParagraphHeads
import io.hwpbyhwp.core.model.HeadType
import io.hwpbyhwp.core.parser.DocumentLoader
import kr.dogfoot.hwplib.reader.HWPReader
import kr.dogfoot.hwplib.tool.textextractor.TextExtractMethod
import kr.dogfoot.hwplib.tool.textextractor.TextExtractOption
import kr.dogfoot.hwplib.tool.textextractor.TextExtractor
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.fail

/**
 * Runs over every .hwp/.hwpx file under $HWP_SAMPLES (if set). Sample documents are not checked
 * in; point it at a local corpus.
 */
class SampleCorpusTest {

    private val files: List<File> by lazy {
        val dir = System.getenv("HWP_SAMPLES")?.let(::File)?.takeIf { it.isDirectory } ?: return@lazy emptyList()
        dir.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("hwp", "hwpx") }.toList()
    }

    @Test
    fun loadsCorpus() {
        val failures = files.mapNotNull { f ->
            runCatching { DocumentLoader.load(f) }.exceptionOrNull()?.let { "${f.name}: ${it.cause ?: it}" }
        }
        println("corpus: ${files.size} files, ${failures.size} failed")
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    /**
     * Hangul's stored line segments start at the paragraph's left margin, so they cross-check the
     * margin unit conversion (HWP and legacy HWPX values are doubled).
     */
    @Test
    fun lineSegmentsStartAtParagraphMargin() {
        val mismatches = mutableListOf<String>()
        var checked = 0
        for (f in files.filter { it.length() < 3_000_000 }) {
            val doc = runCatching { DocumentLoader.load(f) }.getOrNull() ?: continue
            fun visit(p: Paragraph) {
                p.inlines.filterIsInstance<TableObject>().forEach { t -> t.cells.forEach { c -> c.paragraphs.forEach(::visit) } }
                val seg = p.lineSegs.firstOrNull() ?: return
                // Objects (including ones not modelled yet) push lines sideways; only check plain text:
                // text characters plus the paragraph end must account for the whole length.
                val textLength = p.inlines.sumOf { (it as? TextRun)?.text?.length ?: return }
                if (textLength + 1 != p.length) return
                val margin = doc.paraShape(p.paraShapeId).marginLeft
                checked++
                // A floating object in a neighbouring paragraph may push a line right, never left;
                // a factor of two means a unit conversion went wrong.
                val h = seg.horzPos
                val wrong = h < margin - 4 || (abs(margin) > 4 && (abs(h * 2 - margin) <= 4 || abs(h - margin * 2) <= 4))
                if (wrong) mismatches += "${f.name}: margin=$margin horzPos=$h"
            }
            doc.sections.forEach { s -> s.paragraphs.forEach(::visit) }
        }
        println("line segments checked: $checked, mismatched: ${mismatches.size}")
        if (mismatches.isNotEmpty()) fail(mismatches.distinct().take(20).joinToString("\n"))
    }

    /** Compares our paragraph-head labels with hwplib's own (TextExtractOption.insertParaHead) on HWP files. */
    @Test
    fun paragraphHeadsMatchHwplib() {
        val mismatches = mutableListOf<String>()
        var checked = 0
        for (f in files.filter { it.extension.lowercase() == "hwp" && it.length() < 3_000_000 }) {
            val hwp = runCatching { HWPReader.fromFile(f) }.getOrNull() ?: continue
            val option = TextExtractOption(TextExtractMethod.OnlyMainParagraph).apply { isInsertParaHead = true; isAppendEndingLF = true }
            val withHeads = runCatching { TextExtractor.extract(hwp, option) }.getOrNull() ?: continue
            val expectedLines = withHeads.split("\n")
            val doc = DocumentLoader.load(f)
            val heads = ParagraphHeads(doc)
            var line = 0
            for (section in doc.sections) for (p in section.paragraphs) {
                val label = heads.label(p, section)?.text
                val text = p.inlines.filterIsInstance<TextRun>().joinToString("") { it.text }
                val expected = expectedLines.getOrNull(line) ?: break
                line += 1 + p.inlines.count { it is io.hwpbyhwp.core.model.LineBreak }
                // hwplib does not number outline paragraphs; it prints bullets as raw Wingdings code points.
                if (label == null || text.isEmpty() || doc.paraShape(p.paraShapeId).headType == HeadType.OUTLINE) continue
                checked++
                val normalized = expected.firstOrNull()?.let { ParagraphHeads.bulletGlyph(it.toString()) + expected.drop(1) } ?: expected
                if (!expected.startsWith(label) && !normalized.startsWith(label)) mismatches += "${f.name}: expected '${expected.take(30)}' label '$label'"
            }
        }
        println("paragraph heads checked: $checked, mismatched: ${mismatches.size}")
        if (mismatches.isNotEmpty()) fail(mismatches.take(20).joinToString("\n"))
    }
}
