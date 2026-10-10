package io.hwpbyhwp.core.layout

import io.hwpbyhwp.core.model.Affine
import io.hwpbyhwp.core.model.ApplyPage
import io.hwpbyhwp.core.model.ShapeKind
import io.hwpbyhwp.core.model.ShapeObject
import io.hwpbyhwp.core.model.AutoNumber
import io.hwpbyhwp.core.model.AutoNumberKind
import io.hwpbyhwp.core.model.Border
import io.hwpbyhwp.core.model.ColumnDefine
import io.hwpbyhwp.core.model.EquationObject
import io.hwpbyhwp.core.model.HeaderFooter
import io.hwpbyhwp.core.model.NewPageNumber
import io.hwpbyhwp.core.model.PageHide
import io.hwpbyhwp.core.layout.equation.EqLayout
import io.hwpbyhwp.core.layout.equation.EqParser
import io.hwpbyhwp.core.model.Note
import io.hwpbyhwp.core.model.NoteKind
import io.hwpbyhwp.core.model.HeaderFooterKind
import io.hwpbyhwp.core.model.PageNumberPlace
import io.hwpbyhwp.core.model.PageNumberPosition
import io.hwpbyhwp.core.model.BorderStyle
import io.hwpbyhwp.core.model.CharShape
import io.hwpbyhwp.core.model.Document
import io.hwpbyhwp.core.model.HAlign
import io.hwpbyhwp.core.model.Inline
import io.hwpbyhwp.core.model.LineBreak
import io.hwpbyhwp.core.model.LineSeg
import io.hwpbyhwp.core.model.LineSpacingType
import io.hwpbyhwp.core.model.ObjectPlacement
import io.hwpbyhwp.core.model.PageSetup
import io.hwpbyhwp.core.model.ParaShape
import io.hwpbyhwp.core.model.Paragraph
import io.hwpbyhwp.core.model.PictureObject
import io.hwpbyhwp.core.model.RelTo
import io.hwpbyhwp.core.model.Script
import io.hwpbyhwp.core.model.Section
import io.hwpbyhwp.core.model.Tab
import io.hwpbyhwp.core.model.TabType
import io.hwpbyhwp.core.model.TableCell
import io.hwpbyhwp.core.model.TableObject
import io.hwpbyhwp.core.model.TextRun
import io.hwpbyhwp.core.model.Underline
import io.hwpbyhwp.core.model.VertAlign

/**
 * Lays a [Document] out into pages.
 *
 * Hangul stores the line layout it computed (line segments: where each line starts in the text and
 * where it sits vertically). When present those are followed, which reproduces Hangul's line and page
 * breaks; only the horizontal placement of characters inside a line is computed here. Paragraphs
 * without stored segments are broken into lines with [measurer].
 */
class PageLayouter(
    private val document: Document,
    private val measurer: TextMeasurer,
) {

    /** Page number being laid out (1-based, counted across sections); used by page-number fields. */
    private var pageNumber = 1
    /** Total page count, known on the second pass when the document prints it. */
    private var totalPages = 0
    private var heads = ParagraphHeads(document)
    /** Note whose text is being laid out; its number fills footnote/endnote number fields. */
    private var currentNote: Note? = null
    private val endnotes = mutableListOf<Note>()
    /** Section whose paragraphs are being laid out; outline numbering depends on it. */
    private var currentSection: Section? = null

    fun layout(): List<Page> {
        val first = runAll()
        if (!usesTotalPages()) return first
        totalPages = first.size
        return runAll()
    }

    private fun runAll(): List<Page> {
        pageNumber = 1
        heads = ParagraphHeads(document)
        endnotes.clear()
        val decorations = Decorations()
        return document.sections.flatMapIndexed { i, section ->
            currentSection = section
            if (section.pageStartNumber > 0) pageNumber = section.pageStartNumber
            SectionFlow(section.page, decorations).run(section.paragraphs, isLastSection = i == document.sections.lastIndex)
        }
    }

    private fun usesTotalPages(): Boolean {
        fun scan(ps: List<Paragraph>): Boolean = ps.any { p ->
            p.inlines.any { i ->
                (i is AutoNumber && i.kind == AutoNumberKind.TOTAL_PAGES) ||
                    (i is HeaderFooter && scan(i.paragraphs)) ||
                    (i is TableObject && i.cells.any { scan(it.paragraphs) })
            }
        }
        return document.sections.any { scan(it.paragraphs) }
    }

    /** Header, footer and page-number settings; they carry over into following sections. */
    private class Decorations {
        val headers = HashMap<ApplyPage, HeaderFooter>()
        val footers = HashMap<ApplyPage, HeaderFooter>()
        var pageNumber: PageNumberPosition? = null

        fun register(inline: Inline) {
            when (inline) {
                is HeaderFooter -> (if (inline.kind == HeaderFooterKind.HEADER) headers else footers)[inline.applyPage] = inline
                is PageNumberPosition -> pageNumber = inline
                else -> Unit
            }
        }

        fun pick(map: Map<ApplyPage, HeaderFooter>, page: Int): HeaderFooter? =
            map[if (page % 2 == 0) ApplyPage.EVEN else ApplyPage.ODD] ?: map[ApplyPage.BOTH]
    }

    private class Column(val x: Float, val width: Float)

    // ---------------------------------------------------------------------------------------------
    // Body flow with pagination

    private inner class SectionFlow(private val setup: PageSetup, private val decorations: Decorations) {
        private val pages = mutableListOf<Page>()
        private var items = mutableListOf<PageItem>()
        private val bodyLeft = (setup.marginLeft + setup.marginGutter).toFloat()
        private val bodyTop = (setup.marginTop + setup.marginHeader).toFloat()
        private val bodyWidth = (setup.effectiveWidth - setup.marginLeft - setup.marginRight - setup.marginGutter).toFloat()
        private val bodyHeight = (setup.effectiveHeight - setup.marginTop - setup.marginBottom - setup.marginHeader - setup.marginFooter).toFloat()

        /** Lowest point (relative to body top) used on the current page by lines or objects. */
        private var usedBottom = 0f
        private var pageHasLines = false
        private var lastVertPos: Int? = null
        /** Counts page breaks, so a table can tell whether it ran onto later pages. */
        private var pageSerial = 0
        /** [lastVertPos] is the bottom of a table that ran on from earlier pages, measured by us, not stored. */
        private var afterTableTail = false
        /** Running vertical position for paragraphs that carry no stored segments. */
        private var synthCursor = 0f

        private var columns = listOf(Column(0f, bodyWidth))
        private var columnIndex = 0
        private val column get() = columns[columnIndex.coerceAtMost(columns.lastIndex)]

        private val pageFootnotes = mutableListOf<Note>()
        /** 감추기 in effect on the current page. */
        private var hide: PageHide? = null

        /** Page-scoped controls act on the page where their paragraph starts. */
        private fun applyPageControls(p: Paragraph) {
            p.inlines.forEach { inline ->
                when (inline) {
                    is NewPageNumber -> pageNumber = inline.number
                    is PageHide -> hide = inline
                    else -> Unit
                }
            }
        }

        fun run(paragraphs: List<Paragraph>, isLastSection: Boolean): List<Page> {
            paragraphs.forEachIndexed { index, p ->
                p.inlines.forEach { inline ->
                    decorations.register(inline)
                    if (inline is ColumnDefine) setColumns(inline)
                }
                if (index > 0 && (p.pageBreakBefore || document.paraShape(p.paraShapeId).pageBreakBefore) && hasContent()) newPage()
                val segs = p.lineSegs.ifEmpty { synthesize(p) }
                placeParagraph(p, segs, connectsTo(p, paragraphs.getOrNull(index - 1)), connectsTo(p, paragraphs.getOrNull(index + 1)))
            }
            if (isLastSection) placeEndnotes()
            finishPage()
            return pages
        }

        private fun setColumns(def: ColumnDefine) {
            val n = def.count.coerceAtLeast(1)
            columns = if (def.widths.size == n && def.widths.sum() > 0) {
                // Widths either fill the body exactly or are proportions of it.
                val total = def.widths.sum() + def.gaps.take(n - 1).sum()
                val k = if (kotlin.math.abs(total - bodyWidth) < bodyWidth * 0.05f) 1f else bodyWidth / total
                var x = 0f
                def.widths.mapIndexed { i, w ->
                    Column(x, w * k).also { x += (w + (def.gaps.getOrNull(i) ?: def.gap)) * k }
                }
            } else {
                val w = (bodyWidth - def.gap * (n - 1)) / n
                List(n) { Column(it * (w + def.gap), w) }
            }
            columnIndex = 0
        }

        private fun hasContent() = items.isNotEmpty() || usedBottom > 0f

        private fun newPage() {
            finishPage()
            pageSerial++
            items = mutableListOf()
            usedBottom = 0f
            pageHasLines = false
            lastVertPos = null
            afterTableTail = false
            columnIndex = 0
            hide = null
        }

        private fun finishPage() {
            val decorated = mutableListOf<PageItem>()
            decorations.pick(decorations.headers, pageNumber)?.takeIf { hide?.header != true }?.let {
                layoutFrame(it.paragraphs, bodyLeft, setup.marginTop.toFloat(), bodyWidth, decorated)
            }
            decorations.pick(decorations.footers, pageNumber)?.takeIf { hide?.footer != true }?.let {
                layoutFrame(it.paragraphs, bodyLeft, (setup.effectiveHeight - setup.marginBottom - setup.marginFooter).toFloat(), bodyWidth, decorated)
            }
            decorations.pageNumber?.takeIf { hide?.pageNumber != true }?.let { drawPageNumber(it, decorated) }
            if (pageFootnotes.isNotEmpty()) {
                // Footnotes sit at the bottom of the body area; Hangul's stored line positions already
                // keep the body text clear of them.
                val height = notesHeight(pageFootnotes)
                placeNotes(pageFootnotes, bodyTop + bodyHeight - height, items)
                pageFootnotes.clear()
            }
            pages += Page(setup.effectiveWidth, setup.effectiveHeight, decorated + items)
            pageNumber++
        }

        private fun notesHeight(notes: List<Note>): Float =
            NOTE_SEPARATOR_SPACE + notes.sumOf { noteHeight(it).toDouble() }.toFloat() + NOTE_GAP * (notes.size - 1)

        private fun noteHeight(note: Note): Float =
            frameSegments(note.paragraphs, bodyWidth).flatMap { it.second }.maxOfOrNull { (it.vertPos + it.height).toFloat() } ?: 0f

        /** Separator line, then each note's paragraphs, starting at [top]. */
        private fun placeNotes(notes: List<Note>, top: Float, out: MutableList<PageItem>) {
            out += LineItem(bodyLeft, top + NOTE_SEPARATOR_SPACE / 2, bodyLeft + bodyWidth / 3, top + NOTE_SEPARATOR_SPACE / 2, 34f, CharShape.BLACK, BorderStyle.SOLID)
            var y = top + NOTE_SEPARATOR_SPACE
            for (note in notes) {
                currentNote = note
                // Notes normally start with a number field; print the label ourselves when one is missing.
                val hasNumber = note.paragraphs.firstOrNull()?.inlines?.any { it is AutoNumber } == true
                val head = if (hasNumber) null else ParagraphHeads.Head(note.label, note.charShapeId)
                layoutFrame(note.paragraphs, bodyLeft, y, bodyWidth, out, head)
                y += noteHeight(note) + NOTE_GAP
                currentNote = null
            }
        }

        /** Endnotes follow the last paragraph of the document, on a new page if they do not fit. */
        private fun placeEndnotes() {
            if (endnotes.isEmpty()) return
            var top = bodyTop + usedBottom + NOTE_GAP
            if (top + notesHeight(endnotes) > bodyTop + bodyHeight && hasContent()) {
                newPage()
                top = bodyTop
            }
            placeNotes(endnotes.toList(), top, items)
            endnotes.clear()
        }

        private fun drawPageNumber(pos: PageNumberPosition, out: MutableList<PageItem>) {
            if (pos.place == PageNumberPlace.NONE) return
            val text = pos.prefix + pageNumber + pos.suffix
            val shape = document.charShape(0)
            val font = fontFor(shape, Script.LATIN)
            val widths = measure(text, shape)
            val width = widths.sum()
            val odd = pageNumber % 2 == 1
            val place = pos.place
            val top = place in setOf(PageNumberPlace.LEFT_TOP, PageNumberPlace.CENTER_TOP, PageNumberPlace.RIGHT_TOP, PageNumberPlace.OUTSIDE_TOP, PageNumberPlace.INSIDE_TOP)
            val right = when (place) {
                PageNumberPlace.RIGHT_TOP, PageNumberPlace.RIGHT_BOTTOM -> true
                PageNumberPlace.OUTSIDE_TOP, PageNumberPlace.OUTSIDE_BOTTOM -> odd
                PageNumberPlace.INSIDE_TOP, PageNumberPlace.INSIDE_BOTTOM -> !odd
                else -> false
            }
            val center = place == PageNumberPlace.CENTER_TOP || place == PageNumberPlace.CENTER_BOTTOM
            val x = when {
                center -> bodyLeft + (bodyWidth - width) / 2
                right -> bodyLeft + bodyWidth - width
                else -> bodyLeft
            }
            // Centered vertically in the header or footer band.
            val baseline = if (top) setup.marginTop + setup.marginHeader / 2f + font.size * 0.35f
            else setup.effectiveHeight - setup.marginBottom - setup.marginFooter / 2f + font.size * 0.35f
            var cx = x
            val xs = FloatArray(text.length) { i -> cx.also { cx += widths[i] } }
            out += TextItem(text, xs, baseline, font, shape.textColor, 1f)
        }

        private fun synthesize(p: Paragraph): List<LineSeg> {
            val shape = document.paraShape(p.paraShapeId)
            var y = synthCursor + shape.spaceBefore
            val lines = breakLines(p, column.width)
            val segs = mutableListOf<LineSeg>()
            for (line in lines) {
                var flags = 0
                if (y + line.height > bodyHeight && (segs.isNotEmpty() || y > 0f)) {
                    y = 0f
                    flags = 1
                }
                segs += line.toSeg(y.toInt(), shape, flags, lineWidth(shape, column.width))
                y += line.height + line.spacing
            }
            synthCursor = y + shape.spaceAfter
            return segs
        }

        /** Whether [p]'s border merges with its neighbour [other] (same border, 테두리 연결 on both). */
        private fun connectsTo(p: Paragraph, other: Paragraph?): Boolean {
            if (other == null) return false
            val a = document.paraShape(p.paraShapeId)
            val b = document.paraShape(other.paraShapeId)
            return a.borderConnect && b.borderConnect && a.borderFillId == b.borderFillId && a.borderFillId != 0
        }

        private fun placeParagraph(p: Paragraph, segs: List<LineSeg>, connectPrev: Boolean, connectNext: Boolean) {
            val shape = document.paraShape(p.paraShapeId)
            val head = headOf(p)
            var paraTop: Float? = null
            var paraColumn = column
            // The part of the paragraph on the current page/column, for its border and background.
            var chunkStart = items.size
            var chunkTop: Float? = null
            var chunkBottom = 0f
            var chunkIsFirst = true
            fun flushBorder(isEnd: Boolean) {
                val top = chunkTop ?: return
                paragraphBorder(shape, column, top, chunkBottom, chunkStart,
                    drawTop = chunkIsFirst && !connectPrev, drawBottom = !(isEnd && connectNext),
                    padTop = chunkIsFirst && !connectPrev, padBottom = isEnd && !connectNext)
                chunkTop = null
                chunkIsFirst = false
            }
            segs.forEachIndexed { i, seg ->
                // Hangul flags the first line of each page. Within a page, a stored vertical position that
                // jumps back up means the text moved on to the next column (or, past the last one, a new page).
                val jumpedBack = lastVertPos?.let {
                    // Our height for a page-spanning table can exceed Hangul's, so after one only a
                    // clear jump towards the top counts; a small one is the text right below the table.
                    if (afterTableTail) seg.vertPos < it / 2 else seg.vertPos < it - PAGE_TOLERANCE
                } == true
                afterTableTail = false
                if (pageHasLines && (seg.firstLineOfPage || jumpedBack)) {
                    flushBorder(isEnd = false)
                    if (seg.firstLineOfPage || columnIndex + 1 >= columns.size) newPage() else columnIndex++
                }
                lastVertPos = seg.vertPos
                if (i == 0) applyPageControls(p)
                if (paraTop == null) { paraTop = bodyTop + seg.vertPos; paraColumn = column }
                if (chunkTop == null) {
                    chunkStart = items.size
                    chunkTop = bodyTop + seg.vertPos - (if (i == 0 && connectPrev) shape.spaceBefore else 0)
                }
                val end = segs.getOrNull(i + 1)?.textStart ?: Int.MAX_VALUE
                placeLine(p, shape, seg, isFirst = i == 0, isLast = i == segs.lastIndex, end = end,
                    left = bodyLeft + column.x, top = bodyTop, out = items, inlineObject = ::placeInlineObjectOnPage,
                    head = head.takeIf { i == 0 })
                p.inlines.filterIsInstance<Note>().filter { it.start in seg.textStart until end }.forEach {
                    if (it.kind == NoteKind.FOOTNOTE) pageFootnotes += it else endnotes += it
                }
                chunkBottom = bodyTop + seg.vertPos + seg.height +
                    (if (i == segs.lastIndex && connectNext) seg.spacing + shape.spaceAfter else 0)
                pageHasLines = true
                usedBottom = maxOf(usedBottom, (seg.vertPos + seg.height).toFloat())
                if (p.lineSegs.isNotEmpty()) synthCursor = (seg.vertPos + seg.height + seg.spacing).toFloat()
            }
            if (segs.isEmpty()) applyPageControls(p)
            flushBorder(isEnd = true)
            // Objects not placed in the text flow are positioned relative to the paragraph or the page.
            val anchorTop = paraTop ?: (bodyTop + usedBottom)
            p.inlines.forEach { inline ->
                val placement = inline.placement() ?: return@forEach
                if (placement.treatAsChar) return@forEach
                placeFloating(inline, placement, anchorTop, shape, paraColumn)
            }
        }

        /** Background goes under the paragraph's items (inserted at [insertAt]); lines go on top. */
        private fun paragraphBorder(
            shape: ParaShape, col: Column, top: Float, bottom: Float, insertAt: Int,
            drawTop: Boolean, drawBottom: Boolean, padTop: Boolean, padBottom: Boolean,
        ) {
            val bf = document.borderFill(shape.borderFillId) ?: return
            val visible = listOf(bf.left, bf.right, bf.top, bf.bottom).any { it.style != BorderStyle.NONE }
            if (!visible && bf.fillColor == null) return
            val marginL = if (shape.borderIgnoreMargin) 0 else shape.marginLeft.coerceAtLeast(0)
            val marginR = if (shape.borderIgnoreMargin) 0 else shape.marginRight.coerceAtLeast(0)
            val l = bodyLeft + col.x + marginL - shape.borderOffsetLeft
            val r = bodyLeft + col.x + col.width - marginR + shape.borderOffsetRight
            val t = top - (if (padTop) shape.borderOffsetTop else 0)
            val b = bottom + (if (padBottom) shape.borderOffsetBottom else 0)
            bf.fillColor?.let { items.add(insertAt.coerceIn(0, items.size), RectItem(l, t, r, b, it)) }
            if (drawTop) border(bf.top, l, t, r, t, items)
            if (drawBottom) border(bf.bottom, l, b, r, b, items)
            border(bf.left, l, t, l, b, items)
            border(bf.right, r, t, r, b, items)
        }

        private fun placeInlineObjectOnPage(obj: Inline, x: Float, top: Float) {
            when (obj) {
                is TableObject -> placeTableOnPage(obj, x, top)
                is PictureObject -> items += ImageItem(obj.binId, x, top, obj.width.toFloat(), obj.height.toFloat())
                is ShapeObject -> drawShape(obj, x, top, items)
                is EquationObject -> drawEquation(obj, x, top, items)
                else -> Unit
            }
        }

        private fun placeFloating(obj: Inline, pl: ObjectPlacement, paraTop: Float, shape: ParaShape, col: Column) {
            val x = pl.horzOffset + when (pl.horzRelTo) {
                RelTo.PAPER -> 0f
                RelTo.PAGE -> bodyLeft
                RelTo.COLUMN -> bodyLeft + col.x
                RelTo.PARA -> bodyLeft + col.x + shape.marginLeft
            }
            val y = pl.vertOffset + when (pl.vertRelTo) {
                RelTo.PAPER -> 0f
                RelTo.PAGE, RelTo.COLUMN -> bodyTop
                RelTo.PARA -> paraTop
            }
            when (obj) {
                is TableObject -> placeTableOnPage(obj, x, y)
                is PictureObject -> items += ImageItem(obj.binId, x, y, obj.width.toFloat(), obj.height.toFloat())
                is ShapeObject -> drawShape(obj, x, y, items)
                is EquationObject -> drawEquation(obj, x, y, items)
                else -> Unit
            }
        }

        /** Places a table, continuing its rows on following pages when it runs past the body bottom. */
        private fun placeTableOnPage(table: TableObject, x: Float, top: Float) {
            val grid = gridFor(table)
            val startSerial = pageSerial
            val headerRows = if (table.repeatHeader) table.cells.filter { it.isHeader }.maxOfOrNull { it.row + it.rowSpan } ?: 0 else 0
            var rowStart = 0
            var y = top
            while (rowStart < grid.rowHeights.size) {
                if (rowSplitsAcrossPages(table, rowStart)) {
                    // Hangul broke this row's cells across pages; follow its line positions. Checked
                    // before the height test: stored cell heights can be stale and look like they fit.
                    y = placeSplitRow(table, grid, rowStart, x, y, headerRows)
                    usedBottom = maxOf(usedBottom, y - bodyTop)
                    rowStart++
                    continue
                }
                val available = bodyTop + bodyHeight - y
                var rowEnd = rowStart
                var height = 0f
                while (rowEnd < grid.rowHeights.size && !rowSplitsAcrossPages(table, rowEnd) &&
                    height + grid.rowHeights[rowEnd] <= available + PAGE_TOLERANCE
                ) {
                    height += grid.rowHeights[rowEnd]
                    rowEnd++
                }
                if (rowEnd == rowStart) {
                    if (y > bodyTop + PAGE_TOLERANCE) {
                        // Not even one row fits: continue on the next page.
                        newPage()
                        y = bodyTop + repeatHeader(table, grid, headerRows, x, rowStart)
                        continue
                    }
                    rowEnd = rowStart + 1 // a row taller than the page is drawn as is
                }
                drawTableRows(table, grid, rowStart until rowEnd, x, y, items)
                usedBottom = maxOf(usedBottom, y + grid.rowY(rowEnd) - grid.rowY(rowStart) - bodyTop)
                y += grid.rowY(rowEnd) - grid.rowY(rowStart)
                rowStart = rowEnd
                if (rowStart < grid.rowHeights.size && !rowSplitsAcrossPages(table, rowStart)) {
                    newPage()
                    y = bodyTop + repeatHeader(table, grid, headerRows, x, rowStart)
                }
            }
            if (pageSerial != startSerial) {
                // The table's tail occupies this page: following text positions count from below it, and
                // a stored position jumping back up from there is the next page break.
                pageHasLines = true
                lastVertPos = (y - bodyTop).toInt()
                afterTableTail = true
            }
        }

        private fun rowSplitsAcrossPages(table: TableObject, row: Int): Boolean =
            table.cells.any { it.row == row && cellChunks(it).size > 1 }

        /**
         * Draws a row whose cell text continues on following pages. Hangul restarts the stored line
         * positions at 0 where a cell continues on a new page, so each run of lines between such
         * restarts is one page's share. The first share fills the rest of the current page, later
         * ones start at the body top. Returns the y below the row.
         */
        private fun placeSplitRow(table: TableObject, grid: TableGrid, row: Int, x: Float, startY: Float, headerRows: Int): Float {
            val cells = table.cells.filter { it.row == row }
            val chunks = cells.associateWith { cellChunks(it) }
            val parts = chunks.values.maxOf { it.size }
            var top = startY
            var bottom = startY
            for (k in 0 until parts) {
                val last = k == parts - 1
                val contentHeight = cells.maxOf { c ->
                    val lines = chunks.getValue(c).getOrNull(k).orEmpty()
                    (lines.maxOfOrNull { (p, i) -> p.lineSegs[i].let { it.vertPos + it.height } } ?: 0) + c.marginTop + c.marginBottom
                }.toFloat()
                bottom = if (last) top + contentHeight else bodyTop + bodyHeight
                for (c in cells) {
                    val cx = x + grid.colX(c.col)
                    val w = grid.cellWidth(c).toFloat()
                    val bf = document.borderFill(c.borderFillId)
                    bf?.fillColor?.let { items += RectItem(cx, top, cx + w, bottom, it) }
                    val lines = chunks.getValue(c).getOrNull(k).orEmpty()
                    val innerWidth = w - c.marginLeft - c.marginRight
                    for ((p, i) in lines) {
                        val shape = document.paraShape(p.paraShapeId)
                        val seg = p.lineSegs[i]
                        val fit = (innerWidth - seg.horzPos - shape.marginRight.coerceAtLeast(0)).toInt()
                        val sized = if (seg.width <= 0 || seg.width > fit) seg.copy(width = fit) else seg
                        placeLine(p, shape, sized, isFirst = i == 0, isLast = i == p.lineSegs.lastIndex,
                            end = p.lineSegs.getOrNull(i + 1)?.textStart ?: Int.MAX_VALUE,
                            left = cx + c.marginLeft, top = top + c.marginTop, out = items,
                            inlineObject = { obj, ox, oy -> placeNested(obj, ox, oy, items) },
                            head = if (i == 0) headOf(p) else null)
                    }
                    if (bf != null) {
                        border(bf.top, cx, top, cx + w, top, items)
                        border(bf.bottom, cx, bottom, cx + w, bottom, items)
                        border(bf.left, cx, top, cx, bottom, items)
                        border(bf.right, cx + w, top, cx + w, bottom, items)
                    }
                }
                if (!last) {
                    newPage()
                    top = bodyTop + repeatHeader(table, grid, headerRows, x, row)
                }
            }
            return bottom
        }

        private fun repeatHeader(table: TableObject, grid: TableGrid, headerRows: Int, x: Float, nextRow: Int): Float {
            if (headerRows == 0 || nextRow < headerRows) return 0f
            drawTableRows(table, grid, 0 until headerRows, x, bodyTop, items)
            return grid.rowY(headerRows).toFloat()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Lines

    /** A unit placed horizontally in a line: one character, a tab, or an inline object. */
    private class Atom(
        val pos: Int,
        val char: Char,
        val shape: CharShape,
        val script: Script,
        var width: Float,
        val obj: Inline? = null,
        val isTab: Boolean = false,
        val isBreak: Boolean = false,
        val tab: Tab? = null,
    ) {
        /** Fill drawn across a tab, decided when the tab is laid out. */
        var leader: BorderStyle = BorderStyle.NONE
        val isSpace get() = obj == null && !isTab && (char == ' ' || char == '\u00A0' || char == '\u3000')
    }

    private fun atomsOf(p: Paragraph, from: Int = 0, to: Int = Int.MAX_VALUE): List<Atom> {
        val atoms = mutableListOf<Atom>()
        for (inline in p.inlines) {
            when (inline) {
                is TextRun -> {
                    val shape = document.charShape(inline.charShapeId)
                    val startIdx = (from - inline.start).coerceAtLeast(0)
                    val endIdx = (to - inline.start).coerceAtMost(inline.text.length)
                    if (startIdx >= endIdx) continue
                    val text = inline.text.substring(startIdx, endIdx)
                    val widths = measure(text, shape)
                    text.forEachIndexed { i, c -> atoms += Atom(inline.start + startIdx + i, c, shape, scriptOf(c), widths[i]) }
                }
                is Tab -> if (inline.start in from until to) {
                    atoms += Atom(inline.start, '\t', document.charShape(inline.charShapeId), Script.LATIN, 0f, isTab = true, tab = inline)
                }
                is LineBreak -> if (inline.start in from until to) {
                    atoms += Atom(inline.start, '\n', document.charShape(inline.charShapeId), Script.LATIN, 0f, isBreak = true)
                }
                is TableObject, is PictureObject, is ShapeObject, is EquationObject -> if (inline.start in from until to && inline.placement()?.treatAsChar == true) {
                    val pl = inline.placement()!!
                    val w = inline.objWidth() + pl.marginLeft + pl.marginRight
                    atoms += Atom(inline.start, OBJECT_CHAR, CharShape.DEFAULT, Script.OTHER, w.toFloat(), obj = inline)
                }
                is AutoNumber -> if (inline.start in from until to) {
                    val text = when (inline.kind) {
                        AutoNumberKind.PAGE -> pageNumber.toString()
                        AutoNumberKind.TOTAL_PAGES -> totalPages.takeIf { it > 0 }?.toString() ?: "#"
                        AutoNumberKind.FOOTNOTE, AutoNumberKind.ENDNOTE -> currentNote?.label.orEmpty()
                        AutoNumberKind.OTHER -> ""
                    }
                    val shape = document.charShape(inline.charShapeId)
                    val widths = measure(text, shape)
                    text.forEachIndexed { i, c -> atoms += Atom(inline.start, c, shape, scriptOf(c), widths[i]) }
                }
                is Note -> if (inline.start in from until to) {
                    // The reference mark: the note number as superscript.
                    val shape = document.charShape(inline.charShapeId).copy(superscript = true)
                    val widths = measure(inline.label, shape)
                    inline.label.forEachIndexed { i, c -> atoms += Atom(inline.start, c, shape, scriptOf(c), widths[i]) }
                }
                else -> Unit
            }
        }
        return atoms
    }

    /** Widths of [text] in HWPUNIT with 장평 (ratio) and 자간 (spacing) applied, measured per script. */
    private fun measure(text: String, shape: CharShape): FloatArray {
        val out = FloatArray(text.length)
        var i = 0
        while (i < text.length) {
            val script = scriptOf(text[i])
            var j = i + 1
            while (j < text.length && scriptOf(text[j]) == script) j++
            val font = fontFor(shape, script)
            val adv = measurer.advances(displayText(text.substring(i, j)), font)
            val ratio = shape.ratioFor(script) / 100f
            val extra = font.size * shape.spacingFor(script) / 100f
            for (k in i until j) {
                val c = text[k]
                // Korean fonts give a space about a third of an em; the substitute's is narrower, and
                // documents tighten 자간 against the wider one. Keep spaces from collapsing.
                val base = if (c == ' ' || c == '\u00A0') maxOf(adv[k - i], font.size * SPACE_EM) else adv[k - i]
                out[k] = maxOf(base * ratio + extra, base * ratio * MIN_ADVANCE)
            }
            i = j
        }
        return out
    }

    private fun headOf(p: Paragraph): ParagraphHeads.Head? = currentSection?.let { heads.label(p, it) }

    internal fun fontFor(shape: CharShape, script: Script): FontSpec {
        var size = shape.sizeFor(script).toFloat()
        if (shape.superscript || shape.subscript) size *= SCRIPT_SCALE
        return FontSpec(shape.fontName(script), size, shape.bold, shape.italic, script)
    }

    /**
     * Places the characters of one stored line segment. [left]/[top] are the frame origin
     * (body or cell content box); the segment's own offsets are applied here.
     */
    private fun placeLine(
        p: Paragraph,
        shape: ParaShape,
        seg: LineSeg,
        isFirst: Boolean,
        isLast: Boolean,
        end: Int,
        left: Float,
        top: Float,
        out: MutableList<PageItem>,
        inlineObject: (Inline, Float, Float) -> Unit,
        head: ParagraphHeads.Head? = null,
    ) {
        val lineTop = top + seg.vertPos
        val baseline = lineTop + seg.baseline
        // Segments start at the paragraph's left margin; the first-line indent (or the hanging
        // indent of the other lines) is applied on top of that.
        val indent = when {
            isFirst && shape.indent > 0 -> shape.indent.toFloat()
            !isFirst && shape.indent < 0 -> -shape.indent.toFloat()
            else -> 0f
        }
        val atoms = atomsOf(p, seg.textStart, end)
        // The paragraph head (number or bullet) sits at the line start; the text follows it after a
        // gap of half the font size. Stored segments do not include it.
        var headWidth = 0f
        if (head != null && head.text.isNotEmpty()) {
            val headShape = head.charShapeId?.let { document.charShape(it) } ?: atoms.firstOrNull { it.obj == null }?.shape ?: CharShape.DEFAULT
            val widths = measure(head.text, headShape)
            var hx = left + seg.horzPos + indent
            val xs = FloatArray(head.text.length) { k -> hx.also { hx += widths[k] } }
            emitText(head.text, xs, hx, baseline, headShape, scriptOf(head.text.first()), out)
            headWidth = widths.sum() + headShape.baseSize * 0.5f
        }
        val x0 = left + seg.horzPos + indent + headWidth
        val available = seg.width - indent - headWidth

        layoutTabs(atoms, document.tabDef(shape.tabDefId), available, x0 - (left + shape.marginLeft.coerceAtLeast(0)))

        var visible = atoms.size
        while (visible > 0 && (atoms[visible - 1].isSpace || atoms[visible - 1].isBreak)) visible--
        val natural = (0 until visible).sumOf { atoms[it].width.toDouble() }.toFloat()
        val slack = (available - natural).coerceAtLeast(0f)
        val endsWithBreak = atoms.lastOrNull()?.isBreak == true
        val spaces = (0 until visible).count { atoms[it].isSpace }

        var offset = 0f
        var perSpace = 0f
        var perChar = 0f
        when (shape.align) {
            HAlign.LEFT -> Unit
            HAlign.RIGHT -> offset = slack
            HAlign.CENTER -> offset = slack / 2
            HAlign.JUSTIFY -> if (!isLast && !endsWithBreak && spaces > 0 && atoms.none { it.isTab }) perSpace = slack / spaces
            HAlign.DISTRIBUTE -> if (visible > 1) perChar = slack / (visible - 1)
            HAlign.DISTRIBUTE_SPACE -> if (spaces > 0) perSpace = slack / spaces
        }
        // Substitute fonts are often wider than the document's own. Hangul's stored break says the
        // line fits, so squeeze it into the segment instead of running past the margin.
        // Compress proportionally, like a narrower 장평, so glyphs never overlap.
        var squeeze = 1f
        if (natural > available && available > 0f) {
            squeeze = available / natural
            offset = 0f
            perSpace = 0f
            perChar = 0f
            for (a in atoms) if (a.obj == null) a.width *= squeeze
        }

        // Emit characters grouped by shape and script; each keeps its own x.
        var cx = x0 + offset
        var i = 0
        while (i < atoms.size) {
            val a = atoms[i]
            if (a.obj != null) {
                val pl = a.obj.placement()!!
                val h = a.obj.objHeight().toFloat()
                val objTop = (baseline - h).coerceAtLeast(lineTop)
                inlineObject(a.obj, cx + pl.marginLeft, objTop)
                cx += a.width + perChar
                i++
                continue
            }
            if (a.isTab && a.leader != BorderStyle.NONE && a.width > 0f) {
                val size = a.shape.baseSize.toFloat()
                val y = baseline - size * 0.1f
                out += LineItem(cx + size * 0.15f, y, cx + a.width - size * 0.15f, y, size * 0.08f, a.shape.textColor, a.leader)
            }
            if (a.isTab || a.isBreak) {
                cx += a.width + perChar
                i++
                continue
            }
            var j = i
            val xs = ArrayList<Float>()
            val sb = StringBuilder()
            while (j < atoms.size && atoms[j].obj == null && !atoms[j].isTab && !atoms[j].isBreak &&
                atoms[j].shape == a.shape && atoms[j].script == a.script
            ) {
                val b = atoms[j]
                xs += cx
                sb.append(b.char)
                cx += b.width + (if (b.isSpace && j < visible) perSpace else 0f) + (if (j < visible - 1) perChar else 0f)
                j++
            }
            emitText(sb.toString(), xs.toFloatArray(), cx, baseline, a.shape, a.script, out, squeeze)
            i = j
        }
    }

    /**
     * Sizes the tabs of one line. A right/center/decimal tab pushes the text up to the next tab (or
     * the line end) so that it ends at, or centers on, its stop; with no stop inside the line that is
     * the line end, which is how tables of contents put page numbers at the right margin. A left
     * tab advances to the next stop, else by the width Hangul stored, else to the default grid.
     * [lineOffset] is where the line starts relative to the paragraph's left margin, the origin of
     * tab stop positions.
     */
    private fun layoutTabs(atoms: List<Atom>, def: io.hwpbyhwp.core.model.TabDef?, available: Float, lineOffset: Float) {
        var x = 0f
        atoms.forEachIndexed { i, a ->
            if (!a.isTab) { x += a.width; return@forEachIndexed }
            val tab = a.tab
            val stop = def?.stops?.firstOrNull { it.position - lineOffset > x + 1f && it.position - lineOffset <= available + 1f }
            val type = stop?.type ?: tab?.type ?: TabType.LEFT
            a.leader = stop?.leader?.takeIf { it != BorderStyle.NONE } ?: tab?.leader ?: BorderStyle.NONE
            a.width = when (type) {
                TabType.LEFT -> when {
                    stop != null -> stop.position - lineOffset - x
                    tab?.width != null -> tab.width.toFloat()
                    else -> ((x / DEFAULT_TAB).toInt() + 1) * DEFAULT_TAB - x
                }
                else -> {
                    val target = stop?.let { it.position - lineOffset } ?: available
                    var following = 0f
                    for (k in i + 1 until atoms.size) {
                        if (atoms[k].isTab || atoms[k].isBreak) break
                        following += atoms[k].width
                    }
                    // Trailing spaces do not count toward the aligned text.
                    var trailing = 0f
                    var last = atoms.size - 1
                    while (last > i && (atoms[last].isSpace || atoms[last].isBreak)) { trailing += atoms[last].width; last-- }
                    if (atoms.subList(i + 1, atoms.size).none { it.isTab }) following -= trailing
                    when (type) {
                        TabType.CENTER -> target - x - following / 2
                        else -> target - x - following
                    }
                }
            }.coerceAtLeast(0f)
            x += a.width
        }
    }

    private fun emitText(
        text: String, xs: FloatArray, endX: Float, baseline: Float, shape: CharShape, script: Script,
        out: MutableList<PageItem>, squeeze: Float = 1f,
    ) {
        val font = fontFor(shape, script)
        val full = shape.sizeFor(script).toFloat()
        val y = when {
            shape.superscript -> baseline - full * 0.35f
            shape.subscript -> baseline + full * 0.15f
            else -> baseline
        }
        val startX = xs.first()
        shape.shadeColor?.let { out += RectItem(startX, baseline - full * 0.85f, endX, baseline + full * 0.15f, it) }
        if (text.isNotBlank()) {
            out += TextItem(displayText(text), xs, y, font, shape.textColor, shape.ratioFor(script) / 100f * squeeze)
        }
        val stroke = (full * 0.05f).coerceAtLeast(10f)
        when (shape.underline) {
            Underline.BOTTOM -> out += LineItem(startX, baseline + full * 0.1f, endX, baseline + full * 0.1f, stroke, shape.underlineColor, BorderStyle.SOLID)
            Underline.TOP -> out += LineItem(startX, baseline - full * 0.85f, endX, baseline - full * 0.85f, stroke, shape.underlineColor, BorderStyle.SOLID)
            Underline.NONE -> Unit
        }
        if (shape.strikeout) {
            out += LineItem(startX, baseline - full * 0.3f, endX, baseline - full * 0.3f, stroke, shape.strikeoutColor, BorderStyle.SOLID)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Line breaking for paragraphs without stored segments

    private class Line(val start: Int, val height: Float, val textHeight: Float, val spacing: Float)

    private fun lineWidth(shape: ParaShape, frameWidth: Float): Int =
        (frameWidth - shape.marginLeft.coerceAtLeast(0) - shape.marginRight.coerceAtLeast(0)).toInt()

    private fun Line.toSeg(vertPos: Int, shape: ParaShape, flags: Int, width: Int) = LineSeg(
        textStart = start, vertPos = vertPos, height = height.toInt(), textHeight = textHeight.toInt(),
        baseline = (textHeight * 0.85f).toInt() + (height - textHeight).toInt(), spacing = spacing.toInt(),
        horzPos = shape.marginLeft.coerceAtLeast(0), width = width, flags = flags,
    )

    private fun breakLines(p: Paragraph, frameWidth: Float): List<Line> {
        val shape = document.paraShape(p.paraShapeId)
        val width = frameWidth - shape.marginLeft.coerceAtLeast(0) - shape.marginRight.coerceAtLeast(0)
        val atoms = atomsOf(p)
        val lines = mutableListOf<Line>()
        var start = 0
        while (true) {
            val indent = if (start == 0) maxOf(shape.indent, 0) else maxOf(-shape.indent, 0)
            val avail = width - indent
            var x = 0f
            var end = start
            var lastBreak = -1
            while (end < atoms.size) {
                val a = atoms[end]
                if (a.isBreak) { end++; break }
                val w = if (a.isTab) DEFAULT_TAB - x % DEFAULT_TAB else a.width
                if (x + w > avail && end > start && !a.isSpace) {
                    if (lastBreak > start) end = lastBreak
                    break
                }
                x += w
                end++
                if (a.isSpace) lastBreak = end
            }
            val slice = atoms.subList(start, end)
            val fontHeight = (slice.filter { it.obj == null }.maxOfOrNull { it.shape.baseSize.toFloat() }
                ?: document.charShape(firstShapeId(p)).baseSize.toFloat())
            val objHeight = slice.mapNotNull { it.obj }.maxOfOrNull { it.objHeight().toFloat() } ?: 0f
            val height = maxOf(fontHeight, objHeight)
            val spacing = when (shape.lineSpacingType) {
                LineSpacingType.PERCENT -> fontHeight * (shape.lineSpacing - 100) / 100f
                LineSpacingType.FIXED -> shape.lineSpacing - height
                LineSpacingType.BETWEEN_LINES -> shape.lineSpacing.toFloat()
                LineSpacingType.AT_LEAST -> (shape.lineSpacing - height).coerceAtLeast(0f)
            }
            lines += Line(atoms.getOrNull(start)?.pos ?: 0, height, fontHeight, spacing)
            if (end >= atoms.size) break
            start = end
        }
        return lines
    }

    private fun firstShapeId(p: Paragraph): Int = p.inlines.firstNotNullOfOrNull {
        when (it) { is TextRun -> it.charShapeId; is Tab -> it.charShapeId; is LineBreak -> it.charShapeId; else -> null }
    } ?: 0

    // ---------------------------------------------------------------------------------------------
    // Tables

    private fun drawTableRows(table: TableObject, baseGrid: TableGrid, rows: IntRange, x: Float, y: Float, out: MutableList<PageItem>) {
        val grid = baseGrid
        val originY = y - grid.rowY(rows.first)
        val cells = table.cells.filter { it.row in rows }
        for (cell in cells) {
            val cx = x + grid.colX(cell.col)
            val cy = originY + grid.rowY(cell.row)
            val w = grid.cellWidth(cell).toFloat()
            // A cell spanning past the page break is cut at the last row on this page.
            val lastRow = minOf(cell.row + cell.rowSpan - 1, rows.last)
            val h = (grid.rowY(lastRow + 1) - grid.rowY(cell.row)).toFloat()
            document.borderFill(cell.borderFillId)?.fillColor?.let { out += RectItem(cx, cy, cx + w, cy + h, it) }
            placeCellContent(cell, cx, cy, w, h, out)
        }
        for (cell in cells) {
            val bf = document.borderFill(cell.borderFillId) ?: continue
            val cx = x + grid.colX(cell.col)
            val cy = originY + grid.rowY(cell.row)
            val lastRow = minOf(cell.row + cell.rowSpan - 1, rows.last)
            val w = grid.cellWidth(cell).toFloat()
            val h = (grid.rowY(lastRow + 1) - grid.rowY(cell.row)).toFloat()
            border(bf.top, cx, cy, cx + w, cy, out)
            border(bf.bottom, cx, cy + h, cx + w, cy + h, out)
            border(bf.left, cx, cy, cx, cy + h, out)
            border(bf.right, cx + w, cy, cx + w, cy + h, out)
        }
    }

    /**
     * A cell's lines grouped by page: a stored vertical position lower than the previous line's
     * marks where Hangul continued the cell on the next page. Each entry is (paragraph, line index).
     */
    private fun cellChunks(cell: TableCell): List<List<Pair<Paragraph, Int>>> {
        val chunks = mutableListOf(mutableListOf<Pair<Paragraph, Int>>())
        var prev: Int? = null
        for (p in cell.paragraphs) {
            p.lineSegs.forEachIndexed { i, seg ->
                if (prev != null && seg.vertPos < prev!! - PAGE_TOLERANCE) chunks += mutableListOf<Pair<Paragraph, Int>>()
                chunks.last() += p to i
                prev = seg.vertPos
            }
        }
        return chunks.filter { it.isNotEmpty() }
    }

    /**
     * Row heights from the stored cell sizes, grown where a cell's text needs more room: Hangul
     * enlarges rows to fit their content, and some writers store stale (smaller) cell heights.
     */
    private fun gridFor(table: TableObject): TableGrid {
        val base = TableGrid.of(table)
        val needed = IntArray(table.cells.size) { i ->
            val cell = table.cells[i]
            val width = (base.cellWidth(cell) - cell.marginLeft - cell.marginRight).toFloat()
            val content = frameSegments(cell.paragraphs, width).flatMap { it.second }.maxOfOrNull { it.vertPos + it.height } ?: 0
            if (content == 0) 0 else content + cell.marginTop + cell.marginBottom
        }
        return base.withContentHeights(table.cells, needed)
    }

    private fun placeCellContent(cell: TableCell, cx: Float, cy: Float, w: Float, h: Float, out: MutableList<PageItem>) {
        val innerWidth = w - cell.marginLeft - cell.marginRight
        val frame = frameSegments(cell.paragraphs, innerWidth)
        val contentHeight = frame.flatMap { it.second }.maxOfOrNull { (it.vertPos + it.height).toFloat() } ?: 0f
        val innerHeight = h - cell.marginTop - cell.marginBottom
        val dy = when (cell.vertAlign) {
            VertAlign.TOP -> 0f
            VertAlign.CENTER -> ((innerHeight - contentHeight) / 2).coerceAtLeast(0f)
            VertAlign.BOTTOM -> (innerHeight - contentHeight).coerceAtLeast(0f)
        }
        placeFrame(frame, cx + cell.marginLeft, cy + cell.marginTop + dy, innerWidth, out)
    }

    /** Lays [paragraphs] into a box without pagination (cells, headers, footers). */
    private fun layoutFrame(
        paragraphs: List<Paragraph>, left: Float, top: Float, width: Float, out: MutableList<PageItem>,
        firstHead: ParagraphHeads.Head? = null,
    ) = placeFrame(frameSegments(paragraphs, width), left, top, width, out, firstHead)

    /** Stored segments of each paragraph, or computed ones stacked from the frame top. */
    private fun frameSegments(paragraphs: List<Paragraph>, width: Float): List<Pair<Paragraph, List<LineSeg>>> {
        var cursor = 0f
        return paragraphs.map { p ->
            val segs = p.lineSegs.ifEmpty {
                val shape = document.paraShape(p.paraShapeId)
                var y = cursor + shape.spaceBefore
                breakLines(p, width).map { line ->
                    line.toSeg(y.toInt(), shape, 0, lineWidth(shape, width)).also { y += line.height + line.spacing }
                }.also { cursor = y + shape.spaceAfter }
            }
            segs.lastOrNull()?.let { cursor = maxOf(cursor, (it.vertPos + it.height + it.spacing).toFloat()) }
            p to segs
        }
    }

    private fun placeFrame(
        frame: List<Pair<Paragraph, List<LineSeg>>>, left: Float, top: Float, width: Float, out: MutableList<PageItem>,
        firstHead: ParagraphHeads.Head? = null,
    ) {
        for ((index, entry) in frame.withIndex()) {
            val (p, segs) = entry
            val shape = document.paraShape(p.paraShapeId)
            val head = if (index == 0 && firstHead != null) firstHead else headOf(p)
            segs.forEachIndexed { i, seg ->
                // Never wider than the box: stored widths can be stale when a writer resized cells.
                val fit = (width - seg.horzPos - shape.marginRight.coerceAtLeast(0)).toInt()
                val s = if (seg.width <= 0 || seg.width > fit) seg.copy(width = fit) else seg
                placeLine(p, shape, s, isFirst = i == 0, isLast = i == segs.lastIndex, end = segs.getOrNull(i + 1)?.textStart ?: Int.MAX_VALUE,
                    left = left, top = top, out = out, inlineObject = { obj, ox, oy -> placeNested(obj, ox, oy, out) },
                    head = head.takeIf { i == 0 })
            }
            p.inlines.forEach { inline ->
                val pl = inline.placement() ?: return@forEach
                if (!pl.treatAsChar) placeNested(inline, left + pl.horzOffset, top + (segs.firstOrNull()?.vertPos ?: 0) + pl.vertOffset, out)
            }
        }
    }

    private fun placeNested(obj: Inline, x: Float, y: Float, out: MutableList<PageItem>) {
        when (obj) {
            is TableObject -> {
                val grid = gridFor(obj)
                drawTableRows(obj, grid, 0 until grid.rowHeights.size, x, y, out)
            }
            is PictureObject -> out += ImageItem(obj.binId, x, y, obj.width.toFloat(), obj.height.toFloat())
            is ShapeObject -> drawShape(obj, x, y, out)
            is EquationObject -> drawEquation(obj, x, y, out)
            else -> Unit
        }
    }

    /**
     * Typesets an equation into its box at ([x], [top]): the baseline sits where Hangul says, and the
     * result shrinks to fit when our substitute font sets it wider than Hangul did.
     */
    private fun drawEquation(eq: EquationObject, x: Float, top: Float, out: MutableList<PageItem>) {
        val tree = runCatching { EqParser.parse(eq.script) }.getOrNull() ?: return
        val box = EqLayout(measurer, eq.color).layout(tree, eq.fontSize.toFloat())
        val k = if (eq.width > 0 && box.width > eq.width) eq.width / box.width else 1f
        val baseline = top + eq.height * eq.baselinePercent / 100f
        val dx = x + ((eq.width - box.width * k) / 2).coerceAtLeast(0f)
        box.items.forEach { out += it.scaled(k, dx, baseline) }
    }

    private fun PageItem.scaled(k: Float, dx: Float, dy: Float): PageItem = when (this) {
        is TextItem -> TextItem(text, FloatArray(xs.size) { xs[it] * k + dx }, baseline * k + dy, font.copy(size = font.size * k), color, scaleX)
        is LineItem -> LineItem(x1 * k + dx, y1 * k + dy, x2 * k + dx, y2 * k + dy, width * k, color, style)
        is PathItem -> PathItem(ops.map {
            when (it) {
                is PathOp.MoveTo -> PathOp.MoveTo(it.x * k + dx, it.y * k + dy)
                is PathOp.LineTo -> PathOp.LineTo(it.x * k + dx, it.y * k + dy)
                is PathOp.CubicTo -> PathOp.CubicTo(it.x1 * k + dx, it.y1 * k + dy, it.x2 * k + dx, it.y2 * k + dy, it.x * k + dx, it.y * k + dy)
                PathOp.Close -> PathOp.Close
            }
        }, fill, stroke, strokeWidth * k, style)
        else -> this
    }

    // ---------------------------------------------------------------------------------------------
    // Drawing objects

    /**
     * Draws a top-level drawing object into its box at ([x], [y]). Its own transform is fitted to
     * the box, so files that keep the size change outside the rendering matrix still come out right.
     */
    private fun drawShape(s: ShapeObject, x: Float, y: Float, out: MutableList<PageItem>) {
        val bounds = transformedBox(s, s.transform)
        val sx = if (bounds.width > 0.5 && s.width > 0) s.width / bounds.width else 1.0
        val sy = if (bounds.height > 0.5 && s.height > 0) s.height / bounds.height else 1.0
        val fit = Affine(sx, 0.0, x - bounds.left * sx, 0.0, sy, y - bounds.top * sy)
        drawShapeWith(s, fit * s.transform, out)
    }

    private fun drawShapeWith(s: ShapeObject, m: Affine, out: MutableList<PageItem>) {
        when (s.kind) {
            ShapeKind.CONTAINER -> s.children.forEach { drawShapeWith(it, m * it.transform, out) }
            ShapeKind.PICTURE -> {
                val b = transformedBox(s, m)
                s.binId?.let { out += ImageItem(it, b.left.toFloat(), b.top.toFloat(), b.width.toFloat(), b.height.toFloat()) }
            }
            else -> {
                ShapePaths.outline(s, m)?.let { ops ->
                    val stroke = s.stroke
                    out += PathItem(ops, s.fill.takeIf { s.kind != ShapeKind.LINE }, stroke?.color, (stroke?.width ?: 0).toFloat(), stroke?.style ?: BorderStyle.SOLID)
                }
            }
        }
        s.text?.let { tb ->
            val b = transformedBox(s, m)
            val width = (b.width - tb.marginLeft - tb.marginRight).toFloat()
            val frame = frameSegments(tb.paragraphs, width)
            val contentHeight = frame.flatMap { it.second }.maxOfOrNull { (it.vertPos + it.height).toFloat() } ?: 0f
            val inner = (b.height - tb.marginTop - tb.marginBottom).toFloat()
            val dy = when (tb.vertAlign) {
                VertAlign.TOP -> 0f
                VertAlign.CENTER -> ((inner - contentHeight) / 2).coerceAtLeast(0f)
                VertAlign.BOTTOM -> (inner - contentHeight).coerceAtLeast(0f)
            }
            placeFrame(frame, (b.left + tb.marginLeft).toFloat(), (b.top + tb.marginTop).toFloat() + dy, width, out)
        }
    }

    private class Box(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        val width get() = right - left
        val height get() = bottom - top
    }

    /** Page-space bounding box of the shape's original-size frame under [m]. */
    private fun transformedBox(s: ShapeObject, m: Affine): Box {
        val w = (if (s.originalWidth > 0) s.originalWidth else s.width).toDouble()
        val h = (if (s.originalHeight > 0) s.originalHeight else s.height).toDouble()
        val xs = listOf(m.x(0.0, 0.0), m.x(w, 0.0), m.x(0.0, h), m.x(w, h))
        val ys = listOf(m.y(0.0, 0.0), m.y(w, 0.0), m.y(0.0, h), m.y(w, h))
        return Box(xs.min(), ys.min(), xs.max(), ys.max())
    }

    private fun border(b: Border, x1: Float, y1: Float, x2: Float, y2: Float, out: MutableList<PageItem>) {
        if (b.style == BorderStyle.NONE) return
        // Width is stored in 1/100 mm; 1 mm = 7200 / 25.4 HWPUNIT.
        out += LineItem(x1, y1, x2, y2, b.width * 72f / 25.4f, b.color, b.style)
    }

    private fun Inline.placement(): ObjectPlacement? = when (this) {
        is TableObject -> placement
        is PictureObject -> placement
        is ShapeObject -> placement
        is EquationObject -> placement
        else -> null
    }

    private fun Inline.objWidth(): Int = when (this) {
        is TableObject -> if (width > 0) width else TableGrid.of(this).width
        is PictureObject -> width
        is ShapeObject -> width
        is EquationObject -> width
        else -> 0
    }

    private fun Inline.objHeight(): Int = when (this) {
        is TableObject -> if (height > 0) height else TableGrid.of(this).height
        is PictureObject -> height
        is ShapeObject -> height
        is EquationObject -> height
        else -> 0
    }

    private companion object {
        /** Hangul's default tab interval: 40 pt. */
        const val DEFAULT_TAB = 4000f
        const val SCRIPT_SCALE = 0.7f
        /** Slack when comparing stored vertical positions, in HWPUNIT. */
        const val PAGE_TOLERANCE = 50f
        const val OBJECT_CHAR = '\uFFFC'
        const val SPACE_EM = 0.33f
        /** Letter spacing never shrinks a character below this share of its advance. */
        const val MIN_ADVANCE = 0.4f

        /**
         * Characters Hangul documents use that the bundled fonts lack, mapped one-to-one (so text
         * offsets stay valid) to look-alikes. Private-use code units become spaces.
         */
        fun displayText(text: String): String {
            if (text.none { it == '\u2024' || it == '\u22C5' || it.isSurrogate() || it in '\uE000'..'\uF8FF' }) return text
            return buildString(text.length) {
                for (c in text) append(
                    when {
                        c == '\u2024' || c == '\u22C5' -> '\u00B7'
                        c.isSurrogate() || c in '\uE000'..'\uF8FF' -> ' '
                        else -> c
                    },
                )
            }
        }
        /** Room above notes for the separator line, and space between notes (HWPUNIT). */
        const val NOTE_SEPARATOR_SPACE = 850f
        const val NOTE_GAP = 280f
    }
}
