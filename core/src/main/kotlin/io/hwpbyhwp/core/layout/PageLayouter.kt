package io.hwpbyhwp.core.layout

import io.hwpbyhwp.core.model.ApplyPage
import io.hwpbyhwp.core.model.AutoNumber
import io.hwpbyhwp.core.model.AutoNumberKind
import io.hwpbyhwp.core.model.Border
import io.hwpbyhwp.core.model.ColumnDefine
import io.hwpbyhwp.core.model.HeaderFooter
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
import io.hwpbyhwp.core.model.Tab
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

    fun layout(): List<Page> {
        val first = runAll()
        if (!usesTotalPages()) return first
        totalPages = first.size
        return runAll()
    }

    private fun runAll(): List<Page> {
        pageNumber = 1
        val decorations = Decorations()
        return document.sections.flatMap { SectionFlow(it.page, decorations).run(it.paragraphs) }
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
        /** Running vertical position for paragraphs that carry no stored segments. */
        private var synthCursor = 0f

        private var columns = listOf(Column(0f, bodyWidth))
        private var columnIndex = 0
        private val column get() = columns[columnIndex.coerceAtMost(columns.lastIndex)]

        fun run(paragraphs: List<Paragraph>): List<Page> {
            paragraphs.forEachIndexed { index, p ->
                p.inlines.forEach { inline ->
                    decorations.register(inline)
                    if (inline is ColumnDefine) setColumns(inline)
                }
                if (index > 0 && (p.pageBreakBefore || document.paraShape(p.paraShapeId).pageBreakBefore) && hasContent()) newPage()
                val segs = p.lineSegs.ifEmpty { synthesize(p) }
                placeParagraph(p, segs)
            }
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
            items = mutableListOf()
            usedBottom = 0f
            pageHasLines = false
            lastVertPos = null
            columnIndex = 0
        }

        private fun finishPage() {
            val decorated = mutableListOf<PageItem>()
            decorations.pick(decorations.headers, pageNumber)?.let {
                layoutFrame(it.paragraphs, bodyLeft, setup.marginTop.toFloat(), bodyWidth, decorated)
            }
            decorations.pick(decorations.footers, pageNumber)?.let {
                layoutFrame(it.paragraphs, bodyLeft, (setup.effectiveHeight - setup.marginBottom - setup.marginFooter).toFloat(), bodyWidth, decorated)
            }
            decorations.pageNumber?.let { drawPageNumber(it, decorated) }
            pages += Page(setup.effectiveWidth, setup.effectiveHeight, decorated + items)
            pageNumber++
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

        private fun placeParagraph(p: Paragraph, segs: List<LineSeg>) {
            val shape = document.paraShape(p.paraShapeId)
            var paraTop: Float? = null
            var paraColumn = column
            segs.forEachIndexed { i, seg ->
                // Hangul flags the first line of each page. Within a page, a stored vertical position that
                // jumps back up means the text moved on to the next column (or, past the last one, a new page).
                val jumpedBack = lastVertPos?.let { seg.vertPos < it - PAGE_TOLERANCE } == true
                if (pageHasLines) {
                    if (seg.firstLineOfPage) newPage()
                    else if (jumpedBack) { if (columnIndex + 1 < columns.size) columnIndex++ else newPage() }
                }
                lastVertPos = seg.vertPos
                if (paraTop == null) { paraTop = bodyTop + seg.vertPos; paraColumn = column }
                val end = segs.getOrNull(i + 1)?.textStart ?: Int.MAX_VALUE
                placeLine(p, shape, seg, isFirst = i == 0, isLast = i == segs.lastIndex, end = end,
                    left = bodyLeft + column.x, top = bodyTop, out = items, inlineObject = ::placeInlineObjectOnPage)
                pageHasLines = true
                usedBottom = maxOf(usedBottom, (seg.vertPos + seg.height).toFloat())
                if (p.lineSegs.isNotEmpty()) synthCursor = (seg.vertPos + seg.height + seg.spacing).toFloat()
            }
            // Objects not placed in the text flow are positioned relative to the paragraph or the page.
            val anchorTop = paraTop ?: (bodyTop + usedBottom)
            p.inlines.forEach { inline ->
                val placement = inline.placement() ?: return@forEach
                if (placement.treatAsChar) return@forEach
                placeFloating(inline, placement, anchorTop, shape, paraColumn)
            }
        }

        private fun placeInlineObjectOnPage(obj: Inline, x: Float, top: Float) {
            when (obj) {
                is TableObject -> placeTableOnPage(obj, x, top)
                is PictureObject -> items += ImageItem(obj.binId, x, top, obj.width.toFloat(), obj.height.toFloat())
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
                else -> Unit
            }
        }

        /** Places a table, continuing its rows on following pages when it runs past the body bottom. */
        private fun placeTableOnPage(table: TableObject, x: Float, top: Float) {
            val grid = TableGrid.of(table)
            val headerRows = if (table.repeatHeader) table.cells.filter { it.isHeader }.maxOfOrNull { it.row + it.rowSpan } ?: 0 else 0
            var rowStart = 0
            var y = top
            while (rowStart < grid.rowHeights.size) {
                val available = bodyTop + bodyHeight - y
                var rowEnd = rowStart
                var height = 0f
                while (rowEnd < grid.rowHeights.size && height + grid.rowHeights[rowEnd] <= available + PAGE_TOLERANCE) {
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
                rowStart = rowEnd
                if (rowStart < grid.rowHeights.size) {
                    newPage()
                    y = bodyTop + repeatHeader(table, grid, headerRows, x, rowStart)
                }
            }
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
    ) {
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
                    atoms += Atom(inline.start, '\t', document.charShape(inline.charShapeId), Script.LATIN, 0f, isTab = true)
                }
                is LineBreak -> if (inline.start in from until to) {
                    atoms += Atom(inline.start, '\n', document.charShape(inline.charShapeId), Script.LATIN, 0f, isBreak = true)
                }
                is TableObject, is PictureObject -> if (inline.start in from until to && inline.placement()?.treatAsChar == true) {
                    val pl = inline.placement()!!
                    val w = inline.objWidth() + pl.marginLeft + pl.marginRight
                    atoms += Atom(inline.start, OBJECT_CHAR, CharShape.DEFAULT, Script.OTHER, w.toFloat(), obj = inline)
                }
                is AutoNumber -> if (inline.start in from until to) {
                    val text = when (inline.kind) {
                        AutoNumberKind.PAGE -> pageNumber.toString()
                        AutoNumberKind.TOTAL_PAGES -> totalPages.takeIf { it > 0 }?.toString() ?: "#"
                        AutoNumberKind.OTHER -> ""
                    }
                    val shape = document.charShape(inline.charShapeId)
                    val widths = measure(text, shape)
                    text.forEachIndexed { i, c -> atoms += Atom(inline.start, c, shape, scriptOf(c), widths[i]) }
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
            val adv = measurer.advances(text.substring(i, j), font)
            val ratio = shape.ratioFor(script) / 100f
            val extra = font.size * shape.spacingFor(script) / 100f
            for (k in i until j) out[k] = adv[k - i] * ratio + extra
            i = j
        }
        return out
    }

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
        val x0 = left + seg.horzPos + indent
        val available = seg.width - indent
        val atoms = atomsOf(p, seg.textStart, end)

        // Tabs advance to the next default tab stop measured from the segment start.
        var x = 0f
        for (a in atoms) {
            if (a.isTab) a.width = (((x / DEFAULT_TAB).toInt() + 1) * DEFAULT_TAB - x).coerceAtLeast(1f)
            x += a.width
        }

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
            HAlign.JUSTIFY -> if (!isLast && !endsWithBreak && spaces > 0) perSpace = slack / spaces
            HAlign.DISTRIBUTE -> if (visible > 1) perChar = slack / (visible - 1)
            HAlign.DISTRIBUTE_SPACE -> if (spaces > 0) perSpace = slack / spaces
        }
        // Substitute fonts are often wider than the document's own. Hangul's stored break says the
        // line fits, so squeeze it into the segment instead of running past the margin.
        val overflow = natural - available
        if (overflow > 0f && visible > 1 && available > 0f) {
            offset = 0f
            perSpace = 0f
            perChar = -overflow / (visible - 1)
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
            emitText(sb.toString(), xs.toFloatArray(), cx, baseline, a.shape, a.script, out)
            i = j
        }
    }

    private fun emitText(text: String, xs: FloatArray, endX: Float, baseline: Float, shape: CharShape, script: Script, out: MutableList<PageItem>) {
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
            out += TextItem(text, xs, y, font, shape.textColor, shape.ratioFor(script) / 100f)
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
    private fun layoutFrame(paragraphs: List<Paragraph>, left: Float, top: Float, width: Float, out: MutableList<PageItem>) =
        placeFrame(frameSegments(paragraphs, width), left, top, width, out)

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

    private fun placeFrame(frame: List<Pair<Paragraph, List<LineSeg>>>, left: Float, top: Float, width: Float, out: MutableList<PageItem>) {
        for ((p, segs) in frame) {
            val shape = document.paraShape(p.paraShapeId)
            segs.forEachIndexed { i, seg ->
                val s = if (seg.width <= 0) seg.copy(width = (width - seg.horzPos - shape.marginRight).toInt()) else seg
                placeLine(p, shape, s, isFirst = i == 0, isLast = i == segs.lastIndex, end = segs.getOrNull(i + 1)?.textStart ?: Int.MAX_VALUE,
                    left = left, top = top, out = out) { obj, ox, oy -> placeNested(obj, ox, oy, out) }
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
                val grid = TableGrid.of(obj)
                drawTableRows(obj, grid, 0 until grid.rowHeights.size, x, y, out)
            }
            is PictureObject -> out += ImageItem(obj.binId, x, y, obj.width.toFloat(), obj.height.toFloat())
            else -> Unit
        }
    }

    private fun border(b: Border, x1: Float, y1: Float, x2: Float, y2: Float, out: MutableList<PageItem>) {
        if (b.style == BorderStyle.NONE) return
        // Width is stored in 1/100 mm; 1 mm = 7200 / 25.4 HWPUNIT.
        out += LineItem(x1, y1, x2, y2, b.width * 72f / 25.4f, b.color, b.style)
    }

    private fun Inline.placement(): ObjectPlacement? = when (this) {
        is TableObject -> placement
        is PictureObject -> placement
        else -> null
    }

    private fun Inline.objWidth(): Int = when (this) {
        is TableObject -> if (width > 0) width else TableGrid.of(this).width
        is PictureObject -> width
        else -> 0
    }

    private fun Inline.objHeight(): Int = when (this) {
        is TableObject -> if (height > 0) height else TableGrid.of(this).height
        is PictureObject -> height
        else -> 0
    }

    private companion object {
        /** Hangul's default tab interval: 40 pt. */
        const val DEFAULT_TAB = 4000f
        const val SCRIPT_SCALE = 0.7f
        /** Slack when comparing stored vertical positions, in HWPUNIT. */
        const val PAGE_TOLERANCE = 50f
        const val OBJECT_CHAR = '\uFFFC'
    }
}
