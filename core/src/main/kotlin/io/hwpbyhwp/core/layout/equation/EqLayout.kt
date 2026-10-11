package io.hwpbyhwp.core.layout.equation

import io.hwpbyhwp.core.layout.FontSpec
import io.hwpbyhwp.core.layout.LineItem
import io.hwpbyhwp.core.layout.PageItem
import io.hwpbyhwp.core.layout.PathItem
import io.hwpbyhwp.core.layout.PathOp
import io.hwpbyhwp.core.layout.TextItem
import io.hwpbyhwp.core.layout.TextMeasurer
import io.hwpbyhwp.core.model.BorderStyle
import io.hwpbyhwp.core.model.Script

/**
 * A typeset piece of an equation. Coordinates of [items] are relative to the box origin: x = 0 at the
 * left edge, y = 0 on the baseline (negative is up).
 */
class EqBox(val width: Float, val ascent: Float, val descent: Float, val items: List<PageItem>) {
    val height get() = ascent + descent

    fun placed(dx: Float, dy: Float): List<PageItem> = items.map { it.offset(dx, dy) }
}

internal fun PageItem.offset(dx: Float, dy: Float): PageItem = when (this) {
    is TextItem -> TextItem(text, FloatArray(xs.size) { xs[it] + dx }, baseline + dy, font, color, scaleX)
    is LineItem -> LineItem(x1 + dx, y1 + dy, x2 + dx, y2 + dy, width, color, style)
    is PathItem -> PathItem(ops.map { it.offset(dx, dy) }, fill, stroke, strokeWidth, style)
    else -> this
}

private fun PathOp.offset(dx: Float, dy: Float): PathOp = when (this) {
    is PathOp.MoveTo -> PathOp.MoveTo(x + dx, y + dy)
    is PathOp.LineTo -> PathOp.LineTo(x + dx, y + dy)
    is PathOp.CubicTo -> PathOp.CubicTo(x1 + dx, y1 + dy, x2 + dx, y2 + dy, x + dx, y + dy)
    PathOp.Close -> PathOp.Close
}

/** Typesets an [Eq] tree with TeX-like rules simplified to what Hangul equations use. */
class EqLayout(private val measurer: TextMeasurer, private val color: Int, private val fontName: String = EQUATION_FONT) {

    fun layout(eq: Eq, size: Float): EqBox = box(eq, size)

    private fun axis(size: Float) = size * 0.25f
    private fun rule(size: Float) = (size * 0.05f).coerceAtLeast(8f)

    private fun box(eq: Eq, s: Float): EqBox = when (eq) {
        is EqRow -> row(eq.items.map { box(it, s) }, s)
        is EqAtom -> atom(eq, s)
        is EqSpace -> EqBox(eq.em * s, 0f, 0f, emptyList())
        is EqScripts -> scripts(eq, s)
        is EqFrac -> frac(eq, s)
        is EqRoot -> root(eq, s)
        is EqFenced -> fenced(eq.left, box(eq.body, s), eq.right, s)
        is EqAccent -> accent(eq, s)
        is EqBigOp -> bigOp(eq, s)
        is EqMatrix -> matrix(eq, s)
    }

    private fun text(text: String, s: Float, style: AtomStyle): EqBox {
        if (text.isEmpty()) return EqBox(0f, 0f, 0f, emptyList())
        val font = FontSpec(fontName, s, bold = style == AtomStyle.BOLD, italic = style == AtomStyle.ITALIC, script = Script.LATIN)
        val adv = measurer.advances(text, font)
        var x = 0f
        val xs = FloatArray(text.length) { i -> x.also { x += adv[i] } }
        return EqBox(x, s * 0.8f, s * 0.2f, listOf(TextItem(text, xs, 0f, font, color, 1f)))
    }

    private fun atom(a: EqAtom, s: Float): EqBox {
        val b = text(a.text, s, a.style)
        // Relations and binary operators get a little room on both sides.
        if (a.style != AtomStyle.SYMBOL || a.text !in SPACED) return b
        val pad = s * 0.18f
        return EqBox(b.width + 2 * pad, b.ascent, b.descent, b.placed(pad, 0f))
    }

    private fun row(boxes: List<EqBox>, s: Float): EqBox {
        if (boxes.isEmpty()) return EqBox(0f, s * 0.8f, s * 0.2f, emptyList())
        var x = 0f
        val items = mutableListOf<PageItem>()
        boxes.forEach { b -> items += b.placed(x, 0f); x += b.width }
        return EqBox(x, boxes.maxOf { it.ascent }, boxes.maxOf { it.descent }, items)
    }

    private fun scripts(e: EqScripts, s: Float): EqBox {
        if (e.base is EqBigOp && e.base.limits) return limits(e.base, e.sub, e.sup, s)
        val base = box(e.base, s)
        val ss = s * SCRIPT_SCALE
        val sup = e.sup?.let { box(it, ss) }
        val sub = e.sub?.let { box(it, ss) }
        val items = base.items.toMutableList()
        var ascent = base.ascent
        var descent = base.descent
        val supShift = maxOf(s * 0.42f, base.ascent - ss * 0.35f)
        val subShift = maxOf(s * 0.2f, base.descent + ss * 0.1f)
        sup?.let { items += it.placed(base.width, -supShift); ascent = maxOf(ascent, supShift + it.ascent) }
        sub?.let { items += it.placed(base.width, subShift); descent = maxOf(descent, subShift + it.descent) }
        val width = base.width + maxOf(sup?.width ?: 0f, sub?.width ?: 0f) + s * 0.05f
        return EqBox(width, ascent, descent, items)
    }

    private fun frac(e: EqFrac, s: Float): EqBox {
        val num = box(e.num, s * FRAC_SCALE)
        val den = box(e.den, s * FRAC_SCALE)
        val pad = s * 0.1f
        val width = maxOf(num.width, den.width) + 2 * pad
        val ax = axis(s)
        val gap = s * 0.15f
        val numBase = -ax - gap - num.descent
        val denBase = -ax + gap + den.ascent
        val items = mutableListOf<PageItem>()
        items += num.placed((width - num.width) / 2, numBase)
        items += den.placed((width - den.width) / 2, denBase)
        if (e.bar) items += LineItem(0f, -ax, width, -ax, rule(s), color, BorderStyle.SOLID)
        return EqBox(width, -numBase + num.ascent, denBase + den.descent, items)
    }

    private fun root(e: EqRoot, s: Float): EqBox {
        val body = box(e.body, s)
        val top = -(body.ascent + s * 0.15f)
        val bottom = body.descent
        val signW = s * 0.6f
        val r = rule(s)
        val items = mutableListOf<PageItem>()
        var dx = 0f
        val index = e.index?.let { box(it, s * 0.5f) }
        if (index != null) dx = maxOf(0f, index.width - signW * 0.4f)
        // Radical sign: short upstroke, long downstroke to the bottom, then up to the overbar.
        val mid = bottom - (bottom - top) * 0.45f
        items += PathItem(
            listOf(
                PathOp.MoveTo(dx, mid + s * 0.05f),
                PathOp.LineTo(dx + signW * 0.25f, mid - s * 0.05f),
                PathOp.LineTo(dx + signW * 0.55f, bottom),
                PathOp.LineTo(dx + signW, top),
                PathOp.LineTo(dx + signW + body.width + s * 0.1f, top),
            ),
            fill = null, stroke = color, strokeWidth = r, style = BorderStyle.SOLID,
        )
        index?.let { items += it.placed(0f, mid - s * 0.1f - it.descent) }
        items += body.placed(dx + signW + s * 0.05f, 0f)
        val ascent = maxOf(-top + r, index?.let { -(mid - s * 0.1f - it.descent) + it.ascent } ?: 0f)
        return EqBox(dx + signW + body.width + s * 0.15f, ascent, bottom + r, items)
    }

    private fun accent(e: EqAccent, s: Float): EqBox {
        val body = box(e.body, s)
        val items = body.items.toMutableList()
        val r = rule(s)
        val y = -body.ascent - s * 0.08f
        var ascent = body.ascent + s * 0.15f
        var descent = body.descent
        when (e.kind) {
            "bar" -> items += LineItem(0f, y, body.width, y, r, color, BorderStyle.SOLID)
            "under" -> { val uy = body.descent + s * 0.08f; items += LineItem(0f, uy, body.width, uy, r, color, BorderStyle.SOLID); descent += s * 0.12f }
            "vec", "dyad" -> {
                items += LineItem(0f, y, body.width, y, r, color, BorderStyle.SOLID)
                items += PathItem(listOf(PathOp.MoveTo(body.width - s * 0.15f, y - s * 0.08f), PathOp.LineTo(body.width, y), PathOp.LineTo(body.width - s * 0.15f, y + s * 0.08f)),
                    null, color, r, BorderStyle.SOLID)
            }
            else -> {
                val mark = when (e.kind) { "hat" -> "^"; "tilde" -> "~"; "dot" -> "˙"; "ddot" -> "¨"; "acute" -> "´"; "grave" -> "`"; "check" -> "ˇ"; else -> "⌒" }
                val m = text(mark, s * 0.8f, AtomStyle.ROMAN)
                items += m.placed((body.width - m.width) / 2, -body.ascent + s * 0.45f)
                ascent += s * 0.1f
            }
        }
        return EqBox(body.width, ascent, descent, items)
    }

    private fun bigOp(e: EqBigOp, s: Float): EqBox {
        if (e.symbol.length > 1) return text(e.symbol, s, AtomStyle.ROMAN) // lim, max ...
        val b = text(e.symbol, s * BIG_OP_SCALE, AtomStyle.ROMAN)
        // Center the enlarged symbol on the math axis.
        val shift = (b.ascent - b.descent) / 2 - axis(s)
        return EqBox(b.width + s * 0.1f, b.ascent - shift, b.descent + shift, b.placed(s * 0.05f, shift))
    }

    private fun limits(op: EqBigOp, sub: Eq?, sup: Eq?, s: Float): EqBox {
        val base = bigOp(op, s)
        val lo = sub?.let { box(it, s * SCRIPT_SCALE) }
        val hi = sup?.let { box(it, s * SCRIPT_SCALE) }
        val width = maxOf(base.width, lo?.width ?: 0f, hi?.width ?: 0f)
        val gap = s * 0.1f
        val items = mutableListOf<PageItem>()
        items += base.placed((width - base.width) / 2, 0f)
        var ascent = base.ascent
        var descent = base.descent
        hi?.let { val y = -base.ascent - gap - it.descent; items += it.placed((width - it.width) / 2, y); ascent = -y + it.ascent }
        lo?.let { val y = base.descent + gap + it.ascent; items += it.placed((width - it.width) / 2, y); descent = y + it.descent }
        return EqBox(width + s * 0.1f, ascent, descent, items)
    }

    private fun matrix(e: EqMatrix, s: Float): EqBox {
        val cells = e.rows.map { r -> r.map { box(it, s) } }
        val cols = cells.maxOfOrNull { it.size } ?: 0
        val colW = FloatArray(cols) { c -> cells.maxOf { it.getOrNull(c)?.width ?: 0f } }
        val colGap = s * 0.6f
        val rowGap = s * 0.25f
        val items = mutableListOf<PageItem>()
        var y = 0f
        val rowsTop = mutableListOf<Float>()
        cells.forEach { r ->
            val asc = r.maxOfOrNull { it.ascent } ?: s * 0.8f
            val desc = r.maxOfOrNull { it.descent } ?: s * 0.2f
            rowsTop += y
            var x = 0f
            r.forEachIndexed { c, b ->
                val dx = when (e.align) { 'l' -> 0f; 'r' -> colW[c] - b.width; else -> (colW[c] - b.width) / 2 }
                items += b.placed(x + dx, y + asc)
                x += colW[c] + colGap
            }
            y += asc + desc + rowGap
        }
        val totalH = (y - rowGap).coerceAtLeast(0f)
        val width = colW.sum() + colGap * (cols - 1).coerceAtLeast(0)
        // Center the block on the math axis.
        val shift = -axis(s) - totalH / 2
        val body = EqBox(width, -shift, totalH + shift, items.map { it.offset(0f, shift) })
        return if (e.left.isEmpty() && e.right.isEmpty()) body else fenced(e.left, body, e.right, s)
    }

    private fun fenced(left: String, body: EqBox, right: String, s: Float): EqBox {
        val top = -maxOf(body.ascent, s * 0.8f)
        val bottom = maxOf(body.descent, s * 0.2f)
        val l = delimiter(left, top, bottom, s, isLeft = true)
        val r = delimiter(right, top, bottom, s, isLeft = false)
        val items = mutableListOf<PageItem>()
        items += l.placed(0f, 0f)
        items += body.placed(l.width, 0f)
        items += r.placed(l.width + body.width, 0f)
        return EqBox(l.width + body.width + r.width, maxOf(body.ascent, l.ascent), maxOf(body.descent, l.descent), items)
    }

    /** A delimiter stretched to span [top]..[bottom]; short ones use the font glyph. */
    private fun delimiter(d: String, top: Float, bottom: Float, s: Float, isLeft: Boolean): EqBox {
        if (d.isEmpty()) return EqBox(0f, 0f, 0f, emptyList())
        val h = bottom - top
        if (h <= s * 1.25f) return text(d, s, AtomStyle.ROMAN)
        val w = s * 0.35f
        val r = rule(s)
        val ops: List<PathOp> = when (d) {
            "(" -> listOf(PathOp.MoveTo(w * 0.85f, top), PathOp.CubicTo(-w * 0.15f, top + h * 0.25f, -w * 0.15f, bottom - h * 0.25f, w * 0.85f, bottom))
            ")" -> listOf(PathOp.MoveTo(w * 0.15f, top), PathOp.CubicTo(w * 1.15f, top + h * 0.25f, w * 1.15f, bottom - h * 0.25f, w * 0.15f, bottom))
            "[" -> listOf(PathOp.MoveTo(w * 0.8f, top), PathOp.LineTo(w * 0.3f, top), PathOp.LineTo(w * 0.3f, bottom), PathOp.LineTo(w * 0.8f, bottom))
            "]" -> listOf(PathOp.MoveTo(w * 0.2f, top), PathOp.LineTo(w * 0.7f, top), PathOp.LineTo(w * 0.7f, bottom), PathOp.LineTo(w * 0.2f, bottom))
            "|" -> listOf(PathOp.MoveTo(w * 0.5f, top), PathOp.LineTo(w * 0.5f, bottom))
            "‖" -> listOf(PathOp.MoveTo(w * 0.35f, top), PathOp.LineTo(w * 0.35f, bottom), PathOp.MoveTo(w * 0.65f, top), PathOp.LineTo(w * 0.65f, bottom))
            "{", "}" -> {
                val m = (top + bottom) / 2
                val (a, b) = if (d == "{") w * 0.85f to w * 0.15f else w * 0.15f to w * 0.85f
                val c = (a + b) / 2
                listOf(
                    PathOp.MoveTo(a, top), PathOp.CubicTo(c, top, c, top, c, top + h * 0.15f), PathOp.LineTo(c, m - h * 0.08f),
                    PathOp.CubicTo(c, m, c, m, b, m), PathOp.CubicTo(c, m, c, m, c, m + h * 0.08f), PathOp.LineTo(c, bottom - h * 0.15f),
                    PathOp.CubicTo(c, bottom, c, bottom, a, bottom),
                )
            }
            "⟨" -> listOf(PathOp.MoveTo(w * 0.8f, top), PathOp.LineTo(w * 0.2f, (top + bottom) / 2), PathOp.LineTo(w * 0.8f, bottom))
            "⟩" -> listOf(PathOp.MoveTo(w * 0.2f, top), PathOp.LineTo(w * 0.8f, (top + bottom) / 2), PathOp.LineTo(w * 0.2f, bottom))
            else -> return text(d, s, AtomStyle.ROMAN)
        }
        val pad = if (isLeft) 0f else 0f
        return EqBox(w + pad, -top, bottom, listOf(PathItem(ops, null, color, r, BorderStyle.SOLID)))
    }

    companion object {
        /** Mapped to the bundled serif face by genericFamilyOf(). */
        const val EQUATION_FONT = "Equation Serif"
        const val SCRIPT_SCALE = 0.7f
        const val FRAC_SCALE = 0.9f
        const val BIG_OP_SCALE = 1.5f
        private val SPACED = setOf("+", "−", "=", "×", "÷", "±", "∓", "≤", "≥", "≠", "≈", "≡", "<", ">", "→", "←", "⇒", "⇔", "↔", "∈", "⊂", "⊃", "∪", "∩", "·")
    }
}
