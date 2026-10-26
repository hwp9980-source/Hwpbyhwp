package io.hwpbyhwp.core

import io.hwpbyhwp.core.model.Document
import io.hwpbyhwp.core.model.PageSetup
import io.hwpbyhwp.core.model.TableObject
import io.hwpbyhwp.core.model.TextRun
import io.hwpbyhwp.core.parser.DocumentFormat
import io.hwpbyhwp.core.parser.DocumentLoader
import io.hwpbyhwp.core.parser.UnsupportedDocumentException
import kr.dogfoot.hwplib.writer.HWPWriter
import kr.dogfoot.hwpxlib.`object`.HWPXFile
import kr.dogfoot.hwpxlib.writer.HWPXWriter
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kr.dogfoot.hwplib.tool.blankfilemaker.BlankFileMaker as HwpBlank
import kr.dogfoot.hwpxlib.tool.blankfilemaker.BlankFileMaker as HwpxBlank

class DocumentLoaderTest {

    private val dir: File = createTempDirectory("hwpbyhwp").toFile()

    @Test
    fun detectsFormatFromSignature() {
        assertEquals(DocumentFormat.HWP, DocumentLoader.detectFormat(writeHwp().readBytes()))
        assertEquals(DocumentFormat.HWPX, DocumentLoader.detectFormat(writeHwpx(HwpxBlank.make()).readBytes()))
        assertEquals(null, DocumentLoader.detectFormat("hello".toByteArray()))
    }

    @Test
    fun rejectsNonHangulFiles() {
        val txt = File(dir, "fake.hwp").apply { writeText("not a document") }
        assertFailsWith<UnsupportedDocumentException> { DocumentLoader.load(txt) }
    }

    @Test
    fun blankDocumentsUseA4Page() {
        for (file in listOf(writeHwp(), writeHwpx(HwpxBlank.make()))) {
            val doc = DocumentLoader.load(file)
            assertEquals(1, doc.sections.size, file.name)
            assertEquals(PageSetup.A4, doc.sections[0].page, file.name)
        }
    }

    @Test
    fun readsHwpText() {
        val doc = DocumentLoader.load(writeHwp("안녕하세요 HWP"))
        assertEquals("안녕하세요 HWP", doc.plainText())
    }

    @Test
    fun readsHwpxTextAndTable() {
        val hwpx = HwpxBlank.make()
        val section = hwpx.sectionXMLFileList().get(0)
        section.getPara(0).addNewRun().charPrIDRefAnd("0").addNewT().addText("첫 문단")
        val table = section.addNewPara().paraPrIDRefAnd("0").addNewRun().charPrIDRefAnd("0").addNewTable()
            .rowCntAnd(2.toShort()).colCntAnd(2.toShort()).borderFillIDRefAnd("1")
        for (r in 0 until 2) {
            val tr = table.addNewTr()
            for (c in 0 until 2) {
                val tc = tr.addNewTc().borderFillIDRefAnd("1")
                tc.createCellAddr(); tc.cellAddr().colAddrAnd(c.toShort()).rowAddrAnd(r.toShort())
                tc.createCellSpan(); tc.cellSpan().colSpanAnd(1.toShort()).rowSpanAnd(1.toShort())
                tc.createSubList()
                tc.subList().addNewPara().paraPrIDRefAnd("0").addNewRun().charPrIDRefAnd("0").addNewT().addText("R${r}C$c")
            }
        }

        val doc = DocumentLoader.load(writeHwpx(hwpx))
        val paragraphs = doc.sections.single().paragraphs
        assertTrue(paragraphs.flatMap { it.inlines }.filterIsInstance<TextRun>().any { it.text == "첫 문단" })
        val t = paragraphs.flatMap { it.inlines }.filterIsInstance<TableObject>().single()
        assertEquals(2 to 2, t.rowCount to t.colCount)
        assertEquals(listOf("R0C0", "R0C1", "R1C0", "R1C1"), t.cells.map { it.plain() })
        assertEquals(listOf(0 to 0, 0 to 1, 1 to 0, 1 to 1), t.cells.map { it.row to it.col })
    }

    @Test
    fun paragraphMarginsAgreeAcrossFormats() {
        // Both blank templates contain Hangul's footnote paragraph shape (hanging indent of 13.1 pt).
        // HWP stores paragraph margins doubled; after conversion both must read -1310.
        for (file in listOf(writeHwp(), writeHwpx(HwpxBlank.make()))) {
            val doc: Document = DocumentLoader.load(file)
            assertTrue(doc.paraShapes.values.any { it.indent == -1310 }, file.name)
        }
    }

    private fun io.hwpbyhwp.core.model.TableCell.plain(): String =
        paragraphs.flatMap { it.inlines }.filterIsInstance<TextRun>().joinToString("") { it.text }

    private fun writeHwp(text: String? = null): File {
        val hwp = HwpBlank.make()
        if (text != null) {
            val p = hwp.bodyText.sectionList[0].getParagraph(0)
            if (p.text == null) p.createText()
            // Insert before the paragraph-end character.
            p.text.insertString(0, text)
            p.header.characterCount = p.text.charSize.toLong()
        }
        return File(dir, "doc${System.nanoTime()}.hwp").also { HWPWriter.toFile(hwp, it.path) }
    }

    private fun writeHwpx(hwpx: HWPXFile): File =
        File(dir, "doc${System.nanoTime()}.hwpx").also { HWPXWriter.toFilepath(hwpx, it.path) }

    @Test
    fun unsignedAttributeValuesBecomeSigned() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val from = java.io.File(dir, "in.hwpx")
        java.util.zip.ZipOutputStream(from.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("mimetype")); zip.write("application/hwp+zip".toByteArray()); zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("Contents/section0.xml"))
            zip.write("""<hp:lineShape width="4294967291" color="#000000" id="1234567890"/>""".toByteArray())
            zip.closeEntry()
        }
        val to = java.io.File(dir, "out.hwpx")
        kotlin.test.assertTrue(DocumentLoader.signUnsignedAttributes(from, to))
        val xml = java.util.zip.ZipFile(to).use { z -> z.getInputStream(z.getEntry("Contents/section0.xml")).readBytes().decodeToString() }
        // Only values beyond the signed range change.
        kotlin.test.assertEquals("""<hp:lineShape width="-5" color="#000000" id="1234567890"/>""", xml)
        dir.deleteRecursively()
    }
}
