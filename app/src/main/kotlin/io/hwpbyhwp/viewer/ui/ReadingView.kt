package io.hwpbyhwp.viewer.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.hwpbyhwp.core.layout.TableGrid
import io.hwpbyhwp.core.model.Border
import io.hwpbyhwp.core.model.BorderStyle
import io.hwpbyhwp.core.model.CharShape
import io.hwpbyhwp.core.model.Document
import io.hwpbyhwp.core.model.HAlign
import io.hwpbyhwp.core.model.LineBreak
import io.hwpbyhwp.core.model.LineSpacingType
import io.hwpbyhwp.core.model.Paragraph
import io.hwpbyhwp.core.model.PictureObject
import io.hwpbyhwp.core.model.Script
import io.hwpbyhwp.core.model.Tab
import io.hwpbyhwp.core.model.TableObject
import io.hwpbyhwp.core.model.TextRun
import io.hwpbyhwp.core.model.Underline
import io.hwpbyhwp.core.model.VertAlign

/**
 * Reflowing "reading mode": paragraphs, tables and pictures stacked vertically and scaled so the
 * page's text width fits the screen. Page-accurate layout is a separate renderer.
 */
@Composable
fun ReadingView(document: Document, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.background(Color(0xFFE8E8E8))) {
        val page = document.sections.firstOrNull()?.page
        val textWidthHwp = page?.let { it.effectiveWidth - it.marginLeft - it.marginRight }?.takeIf { it > 0 } ?: 42520
        val sidePadding = 12.dp
        val density = LocalDensity.current
        val availablePx = with(density) { (maxWidth - sidePadding * 2).toPx() }
        val scale = HwpScale(pxPerHwp = availablePx / textWidthHwp, density = density.density, fontScale = density.fontScale)
        val paragraphs = remember(document) { document.sections.flatMap { it.paragraphs } }

        CompositionLocalProvider(LocalHwp provides HwpContext(document, scale)) {
            LazyColumn(
                Modifier.fillMaxWidth().background(Color.White),
                contentPadding = PaddingValues(horizontal = sidePadding, vertical = 16.dp),
            ) {
                items(paragraphs) { ParagraphBlock(it) }
            }
        }
    }
}

private data class HwpScale(val pxPerHwp: Float, val density: Float, val fontScale: Float) {
    fun dp(hwp: Int): Dp = (hwp * pxPerHwp / density).dp
    fun px(hwp: Int): Float = hwp * pxPerHwp
    fun sp(hwp: Int): TextUnit = (hwp * pxPerHwp / density / fontScale).sp
}

private class HwpContext(val document: Document, val scale: HwpScale)

private val LocalHwp = staticCompositionLocalOf<HwpContext> { error("no document") }

/** A paragraph splits into text pieces and block objects (tables, pictures) stacked in order. */
@Composable
private fun ParagraphBlock(p: Paragraph) {
    val ctx = LocalHwp.current
    val shape = ctx.document.paraShape(p.paraShapeId)
    val pieces = remember(p) { splitPieces(p) }
    Column(
        Modifier.padding(
            start = ctx.scale.dp(shape.marginLeft.coerceAtLeast(0)),
            end = ctx.scale.dp(shape.marginRight.coerceAtLeast(0)),
            top = ctx.scale.dp(shape.spaceBefore.coerceAtLeast(0)),
            bottom = ctx.scale.dp(shape.spaceAfter.coerceAtLeast(0)),
        ),
    ) {
        pieces.forEach { piece ->
            when (piece) {
                is Piece.Text -> TextPiece(p, piece.inlines)
                is Piece.Table -> TableBlock(piece.table)
                is Piece.Picture -> PictureBlock(piece.picture)
            }
        }
    }
}

private sealed interface Piece {
    data class Text(val inlines: List<io.hwpbyhwp.core.model.Inline>) : Piece
    data class Table(val table: TableObject) : Piece
    data class Picture(val picture: PictureObject) : Piece
}

private fun splitPieces(p: Paragraph): List<Piece> {
    val out = mutableListOf<Piece>()
    val text = mutableListOf<io.hwpbyhwp.core.model.Inline>()
    fun flush() { out += Piece.Text(text.toList()); text.clear() }
    p.inlines.forEach { inline ->
        when (inline) {
            is TableObject -> { if (text.isNotEmpty()) flush(); out += Piece.Table(inline) }
            is PictureObject -> { if (text.isNotEmpty()) flush(); out += Piece.Picture(inline) }
            else -> text += inline
        }
    }
    // An empty paragraph still occupies one line.
    if (text.isNotEmpty() || out.isEmpty()) flush()
    return out
}

@Composable
private fun TextPiece(p: Paragraph, inlines: List<io.hwpbyhwp.core.model.Inline>) {
    val ctx = LocalHwp.current
    val doc = ctx.document
    val shape = doc.paraShape(p.paraShapeId)
    val firstShapeId = inlines.firstNotNullOfOrNull {
        when (it) { is TextRun -> it.charShapeId; is Tab -> it.charShapeId; is LineBreak -> it.charShapeId; else -> null }
    } ?: 0
    val baseChar = doc.charShape(firstShapeId)
    val fontHwp = baseChar.sizeFor(Script.HANGUL)

    val annotated: AnnotatedString = remember(inlines, ctx.scale) {
        buildAnnotatedString {
            inlines.forEach { inline ->
                when (inline) {
                    is TextRun -> {
                        val cs = doc.charShape(inline.charShapeId)
                        pushStyle(cs.toSpanStyle(ctx.scale))
                        append(inline.text)
                        pop()
                    }
                    is Tab -> append('\t')
                    is LineBreak -> append('\n')
                    else -> Unit
                }
            }
            // Keep empty paragraphs one line tall.
            if (length == 0) {
                pushStyle(baseChar.toSpanStyle(ctx.scale)); append(" "); pop()
            }
        }
    }

    val lineHeightHwp = when (shape.lineSpacingType) {
        LineSpacingType.PERCENT -> fontHwp * shape.lineSpacing / 100
        LineSpacingType.FIXED -> shape.lineSpacing
        LineSpacingType.BETWEEN_LINES -> fontHwp + shape.lineSpacing
        LineSpacingType.AT_LEAST -> maxOf(fontHwp, shape.lineSpacing)
    }.coerceAtLeast(fontHwp / 2)

    val style = TextStyle(
        color = Color.Black,
        fontSize = ctx.scale.sp(fontHwp),
        textAlign = when (shape.align) {
            HAlign.LEFT -> TextAlign.Start
            HAlign.RIGHT -> TextAlign.End
            HAlign.CENTER -> TextAlign.Center
            else -> TextAlign.Justify
        },
        lineHeight = ctx.scale.sp(lineHeightHwp),
        textIndent = if (shape.indent >= 0) {
            TextIndent(firstLine = ctx.scale.sp(shape.indent))
        } else {
            // Hanging indent: later lines are pushed right instead of the first line left.
            TextIndent(restLine = ctx.scale.sp(-shape.indent))
        },
    )
    BasicText(annotated, Modifier.fillMaxWidth(), style = style)
}

private fun CharShape.toSpanStyle(scale: HwpScale): SpanStyle {
    val decorations = buildList {
        if (underline != Underline.NONE) add(TextDecoration.Underline)
        if (strikeout) add(TextDecoration.LineThrough)
    }
    val size = sizeFor(Script.HANGUL)
    return SpanStyle(
        color = Color(textColor),
        fontSize = scale.sp(if (superscript || subscript) size * 7 / 10 else size),
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        fontFamily = fontFamilyFor(fontName(Script.HANGUL)),
        letterSpacing = scale.sp(size * spacingFor(Script.HANGUL) / 100),
        baselineShift = when {
            superscript -> BaselineShift.Superscript
            subscript -> BaselineShift.Subscript
            else -> null
        },
        textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
        background = shadeColor?.let { Color(it) } ?: Color.Unspecified,
    )
}

/** Until Hangul fonts are bundled, map families onto the closest system generic. */
private fun fontFamilyFor(name: String): FontFamily = when {
    listOf("바탕", "명조", "Batang", "Myeongjo", "Times", "Serif").any { name.contains(it, ignoreCase = true) } -> FontFamily.Serif
    listOf("Courier", "Mono", "Consolas").any { name.contains(it, ignoreCase = true) } -> FontFamily.Monospace
    else -> FontFamily.SansSerif
}

@Composable
private fun TableBlock(table: TableObject) {
    val ctx = LocalHwp.current
    val doc = ctx.document
    val scale = ctx.scale
    val baseGrid = remember(table) { TableGrid.of(table) }
    // Cell rectangles in px, filled in during measurement and read when drawing borders.
    val cellRects = remember(table) { arrayOfNulls<Rect>(table.cells.size) }

    Layout(
        content = {
            table.cells.forEach { cell ->
                val fill = doc.borderFill(cell.borderFillId)?.fillColor
                Column(
                    Modifier
                        .then(if (fill != null) Modifier.background(Color(fill)) else Modifier)
                        .padding(
                            start = scale.dp(cell.marginLeft), end = scale.dp(cell.marginRight),
                            top = scale.dp(cell.marginTop), bottom = scale.dp(cell.marginBottom),
                        ),
                ) {
                    cell.paragraphs.forEach { ParagraphBlock(it) }
                }
            }
        },
        modifier = Modifier.drawBehind {
            table.cells.forEachIndexed { i, cell ->
                val rect = cellRects[i] ?: return@forEachIndexed
                val bf = doc.borderFill(cell.borderFillId) ?: return@forEachIndexed
                drawBorder(bf.top, rect.topLeft, rect.topRight, scale)
                drawBorder(bf.bottom, rect.bottomLeft, rect.bottomRight, scale)
                drawBorder(bf.left, rect.topLeft, rect.bottomLeft, scale)
                drawBorder(bf.right, rect.topRight, rect.bottomRight, scale)
            }
        },
    ) { measurables, constraints ->
        // Shrink the whole table if it is wider than the screen.
        val naturalWidth = scale.px(baseGrid.width)
        val fit = if (constraints.hasBoundedWidth && naturalWidth > constraints.maxWidth) constraints.maxWidth / naturalWidth else 1f
        fun w(hwp: Int) = (scale.px(hwp) * fit).toInt()

        val placeables = measurables.mapIndexed { i, m ->
            val cell = table.cells[i]
            val width = w(baseGrid.cellWidth(cell)).coerceAtLeast(1)
            m.measure(Constraints(minWidth = width, maxWidth = width))
        }
        val contentHeightsHwp = IntArray(placeables.size) { (placeables[it].height / (scale.pxPerHwp * fit)).toInt() }
        val grid = baseGrid.withContentHeights(table.cells, contentHeightsHwp)

        layout(constraints.constrainWidth(w(grid.width)), constraints.constrainHeight(w(grid.height))) {
            table.cells.forEachIndexed { i, cell ->
                val x = w(grid.colX(cell.col))
                val y = w(grid.rowY(cell.row))
                val cellH = w(grid.cellHeight(cell))
                val p = placeables[i]
                val dy = when (cell.vertAlign) {
                    VertAlign.TOP -> 0
                    VertAlign.CENTER -> (cellH - p.height) / 2
                    VertAlign.BOTTOM -> cellH - p.height
                }.coerceAtLeast(0)
                cellRects[i] = Rect(x.toFloat(), y.toFloat(), (x + p.width).toFloat(), (y + cellH).toFloat())
                p.place(x, y + dy)
            }
        }
    }
}

private fun DrawScope.drawBorder(border: Border, from: Offset, to: Offset, scale: HwpScale) {
    if (border.style == BorderStyle.NONE) return
    // 1 mm = 7200 / 25.4 HWPUNIT; width is stored in 1/100 mm.
    val strokePx = (scale.px((border.width * 72 / 25.4f).toInt())).coerceAtLeast(1f)
    val effect = when (border.style) {
        BorderStyle.DOT, BorderStyle.CIRCLE -> PathEffect.dashPathEffect(floatArrayOf(strokePx, strokePx * 2))
        BorderStyle.DASH, BorderStyle.LONG_DASH -> PathEffect.dashPathEffect(floatArrayOf(strokePx * 6, strokePx * 3))
        BorderStyle.DASH_DOT, BorderStyle.DASH_DOT_DOT -> PathEffect.dashPathEffect(floatArrayOf(strokePx * 6, strokePx * 2, strokePx, strokePx * 2))
        else -> null
    }
    drawLine(Color(border.color), from, to, strokeWidth = strokePx, pathEffect = effect)
}

@Composable
private fun PictureBlock(picture: PictureObject) {
    val ctx = LocalHwp.current
    val bytes = ctx.document.binaries[picture.binId]
    val bitmap = remember(bytes) {
        bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
    }
    val sizeModifier = Modifier.size(ctx.scale.dp(picture.width), ctx.scale.dp(picture.height))
    if (bitmap != null) {
        Image(bitmap.asImageBitmap(), contentDescription = null, modifier = sizeModifier)
    } else {
        // WMF/EMF and other formats Android cannot decode yet.
        Box(sizeModifier.border(1.dp, Color.LightGray))
    }
}
