package io.hwpbyhwp.core

import io.hwpbyhwp.core.layout.LineItem
import io.hwpbyhwp.core.layout.PageLayouter
import io.hwpbyhwp.core.layout.RectItem
import io.hwpbyhwp.core.layout.TextItem
import io.hwpbyhwp.core.layout.TextMeasurer
import io.hwpbyhwp.core.model.Border
import io.hwpbyhwp.core.model.BorderFill
import io.hwpbyhwp.core.model.BorderStyle
import io.hwpbyhwp.core.model.CharShape
import io.hwpbyhwp.core.model.Document
import io.hwpbyhwp.core.model.HAlign
import io.hwpbyhwp.core.model.LineSeg
import io.hwpbyhwp.core.model.PageSetup
import io.hwpbyhwp.core.model.ParaShape
import io.hwpbyhwp.core.model.Paragraph
import io.hwpbyhwp.core.model.Section
import io.hwpbyhwp.core.model.Tab
import io.hwpbyhwp.core.model.TabType
import io.hwpbyhwp.core.model.TextRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PageLayouterTest {

    /** Every character is half an em wide. */
    private val mono = TextMeasurer { text, font -> FloatArray(text.length) { font.size / 2 } }

    private val page = PageSetup.A4
    private val bodyLeft = (page.marginLeft + page.marginGutter).toFloat()
    private val bodyTop = (page.marginTop + page.marginHeader).toFloat()

    private fun doc(paragraphs: List<Paragraph>, align: HAlign = HAlign.LEFT) = Document(
        sections = listOf(Section(page, paragraphs)),
        charShapes = mapOf(0 to CharShape(emptyMap(), baseSize = 1000)),
        paraShapes = mapOf(0 to ParaShape(align = align)),
        borderFills = emptyMap(),
        binaries = emptyMap(),
    )

    private fun seg(start: Int, vert: Int, flags: Int = 0, width: Int = 42520) =
        LineSeg(start, vert, 1000, 1000, 850, 600, 0, width, flags)

    @Test
    fun followsStoredLineSegments() {
        val p = Paragraph(0, listOf(TextRun("abcdef", 0, 0)), lineSegs = listOf(seg(0, 0), seg(3, 1600)), length = 7)
        val pages = PageLayouter(doc(listOf(p)), mono).layout()
        val texts = pages.single().items.filterIsInstance<TextItem>()
        assertEquals(listOf("abc", "def"), texts.map { it.text })
        assertEquals(bodyTop + 850, texts[0].baseline)
        assertEquals(bodyTop + 1600 + 850, texts[1].baseline)
        assertEquals(bodyLeft, texts[1].xs[0])
        assertEquals(bodyLeft + 500, texts[1].xs[1])
    }

    @Test
    fun startsNewPageWhereHangulDid() {
        val p1 = Paragraph(0, listOf(TextRun("a", 0, 0)), lineSegs = listOf(seg(0, 0)), length = 2)
        val p2 = Paragraph(0, listOf(TextRun("b", 0, 0)), lineSegs = listOf(seg(0, 0, flags = 1)), length = 2)
        val pages = PageLayouter(doc(listOf(p1, p2)), mono).layout()
        assertEquals(2, pages.size)
    }

    @Test
    fun alignsRightAndCenter() {
        val p = Paragraph(0, listOf(TextRun("ab", 0, 0)), lineSegs = listOf(seg(0, 0, width = 10000)), length = 3)
        val right = PageLayouter(doc(listOf(p), HAlign.RIGHT), mono).layout().single().items.filterIsInstance<TextItem>().single()
        assertEquals(bodyLeft + 10000 - 1000, right.xs[0])
        val center = PageLayouter(doc(listOf(p), HAlign.CENTER), mono).layout().single().items.filterIsInstance<TextItem>().single()
        assertEquals(bodyLeft + 4500, center.xs[0])
    }

    @Test
    fun justifiesAllButLastLine() {
        val p = Paragraph(0, listOf(TextRun("a b c d", 0, 0)), lineSegs = listOf(seg(0, 0, width = 5000), seg(4, 1600, width = 5000)), length = 8)
        val items = PageLayouter(doc(listOf(p), HAlign.JUSTIFY), mono).layout().single().items.filterIsInstance<TextItem>()
        // First line "a b " -> visible "a b" is 1500 wide; the 3500 slack goes to its one space.
        val first = items.first { it.text.startsWith("a") }
        assertEquals(bodyLeft + 500 + 500 + 3500, first.xs[2])
        val last = items.first { it.text.startsWith("c") }
        assertEquals(bodyLeft + 500, last.xs[1])
    }

    @Test
    fun breaksLinesAndPagesWithoutStoredSegments() {
        val longText = "word ".repeat(2000)
        val p = Paragraph(0, listOf(TextRun(longText, 0, 0)), length = longText.length + 1)
        val pages = PageLayouter(doc(listOf(p)), mono).layout()
        assertTrue(pages.size > 1, "expected several pages, got ${pages.size}")
        val bodyRight = page.effectiveWidth - page.marginRight
        pages.flatMap { it.items }.filterIsInstance<TextItem>().forEach { t ->
            assertTrue(t.xs.last() + 500 <= bodyRight + 1, "text overflows the body: ${t.xs.last()}")
        }
    }

    @Test
    fun squeezesLinesWiderThanTheirStoredSegment() {
        // 10 chars x 500 = 5000 natural width in a 4000-wide segment.
        val p = Paragraph(0, listOf(TextRun("abcdefghij", 0, 0)), lineSegs = listOf(seg(0, 0, width = 4000)), length = 11)
        val t = PageLayouter(doc(listOf(p)), mono).layout().single().items.filterIsInstance<TextItem>().single()
        // Compressed proportionally: every advance and the glyphs themselves shrink to 80 %.
        assertEquals(bodyLeft, t.xs.first())
        assertEquals(bodyLeft + 9 * 400f, t.xs.last(), 0.01f)
        assertEquals(0.8f, t.scaleX, 0.001f)
    }

    @Test
    fun drawsParagraphBorderAndBackgroundBehindText() {
        val solid = Border(BorderStyle.SOLID, 12, CharShape.BLACK)
        val d = doc(listOf(Paragraph(0, listOf(TextRun("ab", 0, 0)), lineSegs = listOf(seg(0, 0)), length = 3))).copy(
            paraShapes = mapOf(0 to ParaShape(borderFillId = 5, borderOffsetLeft = 100, borderOffsetTop = 200)),
            borderFills = mapOf(5 to BorderFill(solid, solid, solid, solid, fillColor = 0xFFFFFF00.toInt())),
        )
        val items = PageLayouter(d, mono).layout().single().items
        val fill = items.filterIsInstance<RectItem>().single()
        assertTrue(items.indexOf(fill) < items.indexOfFirst { it is TextItem }, "background must be under the text")
        assertEquals(bodyLeft - 100, fill.left)
        assertEquals(bodyTop - 200, fill.top)
        assertEquals(bodyTop + 1000, fill.bottom)
        assertEquals(4, items.filterIsInstance<LineItem>().size)
    }

    @Test
    fun rightTabPutsFollowingTextAtLineEndWithDotLeader() {
        // "ab<TAB>12": the right tab pushes "12" to the end of the 10000-wide line.
        val p = Paragraph(
            0,
            listOf(TextRun("ab", 0, 0), Tab(0, 2, width = 500, leader = BorderStyle.DOT, type = TabType.RIGHT), TextRun("12", 0, 10)),
            lineSegs = listOf(seg(0, 0, width = 10000)), length = 13,
        )
        val items = PageLayouter(doc(listOf(p)), mono).layout().single().items
        val number = items.filterIsInstance<TextItem>().first { it.text == "12" }
        assertEquals(bodyLeft + 10000 - 1000, number.xs[0])
        val leader = items.filterIsInstance<LineItem>().single()
        assertEquals(BorderStyle.DOT, leader.style)
        assertTrue(leader.x1 >= bodyLeft + 1000 && leader.x2 <= bodyLeft + 9000)
    }

    @Test
    fun negativeLetterSpacingNeverCollapsesSpaces() {
        val tight = CharShape(emptyMap(), baseSize = 1000, spacings = io.hwpbyhwp.core.model.Script.entries.associateWith { -30 })
        val d = doc(listOf(Paragraph(0, listOf(TextRun("a b", 0, 0)), lineSegs = listOf(seg(0, 0)), length = 4)))
            .copy(charShapes = mapOf(0 to tight))
        val t = PageLayouter(d, mono).layout().single().items.filterIsInstance<TextItem>().single()
        assertTrue(t.xs[2] - t.xs[1] > 0f, "space width must stay positive: ${t.xs.toList()}")
    }

    private fun splitBox(): Paragraph {
        val cellParas = listOf(
            Paragraph(0, listOf(TextRun("first", 0, 0)), lineSegs = listOf(seg(0, 60000)), length = 6),
            Paragraph(0, listOf(TextRun("second", 0, 0)), lineSegs = listOf(seg(0, 0), seg(0, 60000)), length = 7),
        )
        val cell = io.hwpbyhwp.core.model.TableCell(0, 0, 1, 1, 40000, 566, 0, 0, 0, 0, 0, io.hwpbyhwp.core.model.VertAlign.TOP, cellParas)
        val table = io.hwpbyhwp.core.model.TableObject(1, 1, 40000, 566, 0, 0,
            io.hwpbyhwp.core.model.ObjectPlacement.INLINE.copy(treatAsChar = false), listOf(cell))
        return Paragraph(0, listOf(table), lineSegs = listOf(seg(0, 0)), length = 9)
    }

    @Test
    fun textAfterPageSpanningBoxStartsNextPageWherePositionRestarts() {
        // The box's continuation fills page 2; Hangul put the following paragraph at the top of page 3.
        val after = Paragraph(0, listOf(TextRun("after", 0, 0)), lineSegs = listOf(seg(0, 0)), length = 6)
        val pages = PageLayouter(doc(listOf(splitBox(), after)), mono).layout()
        assertEquals(3, pages.size)
        assertEquals(listOf("after"), pages[2].items.filterIsInstance<TextItem>().map { it.text })
    }

    @Test
    fun textJustBelowPageSpanningBoxStaysOnItsPage() {
        // A stored position a little above our measured box bottom is the text under the box, not a page break.
        val after = Paragraph(0, listOf(TextRun("after", 0, 0)), lineSegs = listOf(seg(0, 55000)), length = 6)
        val pages = PageLayouter(doc(listOf(splitBox(), after)), mono).layout()
        assertEquals(2, pages.size)
    }

    private val bodyHeight = page.height - page.marginTop - page.marginBottom - page.marginHeader - page.marginFooter

    /** Text at the page top, so a later paragraph's position is its own and not pushed down by objects. */
    private val lead = Paragraph(0, listOf(TextRun("x", 0, 0)), lineSegs = listOf(seg(0, 0)), length = 2)

    /** A one-cell floating table of [lines] text lines, 1600 apart, that may break inside its row. */
    private fun cuttableBox(lines: Int): io.hwpbyhwp.core.model.TableObject {
        val cellParas = List(lines) { k -> Paragraph(0, listOf(TextRun("l$k", 0, 0)), lineSegs = listOf(seg(0, k * 1600)), length = 3) }
        val height = lines * 1600
        val cell = io.hwpbyhwp.core.model.TableCell(0, 0, 1, 1, 40000, height, 0, 0, 0, 0, 0, io.hwpbyhwp.core.model.VertAlign.TOP, cellParas)
        return io.hwpbyhwp.core.model.TableObject(1, 1, 40000, height, 0, 0,
            io.hwpbyhwp.core.model.ObjectPlacement.INLINE.copy(treatAsChar = false), listOf(cell),
            pageBreak = io.hwpbyhwp.core.model.TableBreak.CELLS)
    }

    @Test
    fun cellBreakingTableIsCutAtThePageBottomAndContinues() {
        val anchor = Paragraph(0, listOf(cuttableBox(20)), lineSegs = listOf(seg(0, bodyHeight - 10000)), length = 9)
        val pages = PageLayouter(doc(listOf(lead, anchor)), mono).layout()
        assertEquals(2, pages.size)
        val first = pages[0].items.filterIsInstance<io.hwpbyhwp.core.layout.ClipItem>().single()
        val second = pages[1].items.filterIsInstance<io.hwpbyhwp.core.layout.ClipItem>().single()
        // The cut falls between lines, inside the body, and the rest starts at the next page's top.
        assertTrue(first.bottom <= bodyTop + bodyHeight)
        assertEquals(bodyTop, second.top)
        val cutLines = first.items.filterIsInstance<TextItem>().count { it.baseline < first.bottom }
        assertEquals(6, cutLines)
    }

    @Test
    fun floatingTableWithNoRoomForTwoLinesMovesToNextPage() {
        val anchor = Paragraph(0, listOf(TextRun("a", 0, 0), cuttableBox(5)), lineSegs = listOf(seg(0, bodyHeight - 2000)), length = 10)
        val pages = PageLayouter(doc(listOf(lead, anchor)), mono).layout()
        assertEquals(2, pages.size)
        assertEquals(listOf("x", "a"), pages[0].items.filterIsInstance<TextItem>().map { it.text })
        val moved = pages[1].items.filterIsInstance<io.hwpbyhwp.core.layout.ClipItem>().single().items.filterIsInstance<TextItem>().first()
        assertEquals("l0", moved.text)
        assertEquals(bodyTop + 850, moved.baseline)
    }

    @Test
    fun cellTextContinuesOnNextPageWhereLinePositionsRestart() {
        // A one-cell box whose second paragraph Hangul placed on the next page (vertPos back to 0).
        val cellParas = listOf(
            Paragraph(0, listOf(TextRun("first", 0, 0)), lineSegs = listOf(seg(0, 60000)), length = 6),
            Paragraph(0, listOf(TextRun("second", 0, 0)), lineSegs = listOf(seg(0, 0)), length = 7),
        )
        val cell = io.hwpbyhwp.core.model.TableCell(0, 0, 1, 1, 40000, 120000, 0, 0, 0, 0, 0, io.hwpbyhwp.core.model.VertAlign.TOP, cellParas)
        val table = io.hwpbyhwp.core.model.TableObject(1, 1, 40000, 120000, 0, 0,
            io.hwpbyhwp.core.model.ObjectPlacement.INLINE.copy(treatAsChar = false), listOf(cell))
        val anchor = Paragraph(0, listOf(table), lineSegs = listOf(seg(0, 0)), length = 9)
        val pages = PageLayouter(doc(listOf(anchor)), mono).layout()
        assertEquals(2, pages.size)
        assertEquals(listOf("first"), pages[0].items.filterIsInstance<TextItem>().map { it.text })
        val second = pages[1].items.filterIsInstance<TextItem>().single()
        assertEquals("second", second.text)
        assertEquals(bodyTop + 850, second.baseline)
    }
}
