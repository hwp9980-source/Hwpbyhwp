package io.hwpbyhwp.core.layout

import io.hwpbyhwp.core.model.BorderStyle
import java.nio.charset.Charset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Windows Metafile (WMF) pictures, which Android cannot decode: the drawing records are replayed
 * as page items (paths, text, bitmaps) scaled into the picture's frame.
 *
 * Bitmaps inside a metafile are pulled out as BMP files by [embeddedBitmaps] when the document is
 * loaded and drawn as [ImageItem]s keyed by [bitmapKey].
 */
object Wmf {

    fun isWmf(data: ByteArray?): Boolean = data != null && (isPlaceable(data) || isStandardHeader(data, 0))

    /** The bitmaps a metafile carries, as BMP files, in record order. */
    fun embeddedBitmaps(data: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        runCatching { records(data) { rec -> rec.dib()?.let { dibToBmp(rec.data, it, rec.end) }?.let(out::add) } }
        return out
    }

    fun bitmapKey(binId: String, index: Int) = "$binId#wmf$index"

    /**
     * Draws the metafile [data] (stored as [binId]) into the frame at ([left], [top]) of the given
     * size. Returns null when the data cannot be read.
     */
    fun render(
        data: ByteArray, binId: String, left: Float, top: Float, width: Float, height: Float, measurer: TextMeasurer,
    ): List<PageItem>? = runCatching { Player(data, binId, left, top, width, height, measurer).play() }.getOrNull()

    // ---------------------------------------------------------------------------------------------
    // Records

    private fun isPlaceable(d: ByteArray) =
        d.size >= 22 && d[0] == 0xD7.toByte() && d[1] == 0xCD.toByte() && d[2] == 0xC6.toByte() && d[3] == 0x9A.toByte()

    private fun isStandardHeader(d: ByteArray, at: Int) =
        d.size >= at + 18 && u16(d, at) in 1..2 && u16(d, at + 2) == 9 && u16(d, at + 4) in setOf(0x100, 0x300)

    private class Record(val data: ByteArray, val function: Int, val params: Int, val end: Int) {
        fun s16(i: Int) = Wmf.s16(data, params + i * 2)
        fun u16(i: Int) = Wmf.u16(data, params + i * 2)
        fun u32(i: Int) = Wmf.u32(data, params + i * 2)
        val words get() = (end - params) / 2

        /** Offset of the DIB a bitmap record carries, if any. */
        fun dib(): Int? {
            val offset = when (function) {
                STRETCH_DIB -> 22
                // The variants without a bitmap are 11 and 9 words long.
                DIB_STRETCH_BLT -> if (words > 11) 20 else return null
                DIB_BIT_BLT -> if (words > 9) 16 else return null
                SET_DIBITS_TO_DEVICE -> 18
                else -> return null
            }
            return (params + offset).takeIf { it + 40 <= end }
        }
    }

    private inline fun records(d: ByteArray, onRecord: (Record) -> Unit) {
        var pos = if (isPlaceable(d)) 22 else 0
        require(isStandardHeader(d, pos))
        pos += u16(d, pos + 2) * 2
        while (pos + 6 <= d.size) {
            val words = u32(d, pos)
            val function = u16(d, pos + 4)
            if (function == 0 || words < 3) break
            val end = (pos + words * 2).coerceAtMost(d.size.toLong()).toInt()
            onRecord(Record(d, function, pos + 6, end))
            pos = end
        }
    }

    private fun u16(d: ByteArray, i: Int) = (d[i].toInt() and 0xFF) or ((d[i + 1].toInt() and 0xFF) shl 8)
    private fun s16(d: ByteArray, i: Int) = u16(d, i).toShort().toInt()
    private fun u32(d: ByteArray, i: Int) = u16(d, i).toLong() or (u16(d, i + 2).toLong() shl 16)

    /** A DIB (BITMAPINFOHEADER + palette + pixels) as a BMP file Android can decode. */
    private fun dibToBmp(d: ByteArray, at: Int, end: Int): ByteArray? {
        val headerSize = u32(d, at).toInt()
        if (headerSize < 12 || at + headerSize > end) return null
        val bitCount: Int
        val compression: Long
        val colorsUsed: Long
        if (headerSize == 12) {
            bitCount = u16(d, at + 10); compression = 0; colorsUsed = 0
        } else {
            bitCount = u16(d, at + 14); compression = u32(d, at + 16); colorsUsed = u32(d, at + 32)
        }
        val entry = if (headerSize == 12) 3 else 4
        val paletteEntries = when {
            colorsUsed > 0 -> colorsUsed.toInt()
            bitCount in 1..8 -> 1 shl bitCount
            else -> 0
        }
        val masks = if (headerSize == 40 && compression == 3L) 12 else 0
        val pixelOffset = 14 + headerSize + masks + paletteEntries * entry
        val dibLength = end - at
        val out = ByteArray(14 + dibLength)
        out[0] = 'B'.code.toByte(); out[1] = 'M'.code.toByte()
        putU32(out, 2, out.size.toLong())
        putU32(out, 10, pixelOffset.toLong())
        System.arraycopy(d, at, out, 14, dibLength)
        return out
    }

    private fun putU32(b: ByteArray, i: Int, v: Long) {
        for (k in 0..3) b[i + k] = (v shr (8 * k)).toByte()
    }

    // ---------------------------------------------------------------------------------------------
    // Playback

    private class Pen(val color: Int, val width: Int, val style: BorderStyle?)
    private class Palette(val colors: IntArray)
    private class Brush(val color: Int?)
    private class Font(val height: Int, val weight: Int, val italic: Boolean, val underline: Boolean, val strikeout: Boolean, val charset: Int, val face: String)

    private data class DcState(
        var orgX: Int, var orgY: Int, var extX: Int, var extY: Int,
        var pen: Pen, var brush: Brush, var font: Font?, var palette: Palette?,
        var textColor: Int, var textAlign: Int, var curX: Int, var curY: Int,
    )

    private class Player(
        val data: ByteArray, val binId: String,
        val left: Float, val top: Float, val width: Float, val height: Float,
        val measurer: TextMeasurer,
    ) {
        val out = mutableListOf<PageItem>()
        val objects = ArrayList<Any?>()
        var dc: DcState
        val saved = ArrayDeque<DcState>()
        var bitmaps = 0

        init {
            // The placeable header gives the picture's extent; a window set in the records overrides it.
            val (ox, oy, ex, ey) = if (isPlaceable(data)) {
                val l = s16(data, 6); val t = s16(data, 8); val r = s16(data, 10); val b = s16(data, 12)
                listOf(l, t, r - l, b - t)
            } else listOf(0, 0, 0, 0)
            dc = DcState(ox, oy, ex, ey, Pen(BLACK, 0, BorderStyle.SOLID), Brush(WHITE), null, null, BLACK, 0, 0, 0)
            if (!isPlaceable(data)) fitToDrawing()
        }

        fun play(): List<PageItem> {
            records(data, ::record)
            return out
        }

        private val sx get() = if (dc.extX != 0) width / dc.extX else 1f
        private val sy get() = if (dc.extY != 0) height / dc.extY else 1f
        private fun x(v: Int) = left + (v - dc.orgX) * sx
        private fun y(v: Int) = top + (v - dc.orgY) * sy

        private fun record(r: Record) {
            when (r.function) {
                SET_WINDOW_ORG -> { dc.orgY = r.s16(0); dc.orgX = r.s16(1) }
                SET_WINDOW_EXT -> { dc.extY = r.s16(0); dc.extX = r.s16(1) }
                OFFSET_WINDOW_ORG -> { dc.orgY += r.s16(0); dc.orgX += r.s16(1) }
                SCALE_WINDOW_EXT -> {
                    val yDen = r.s16(0); val yNum = r.s16(1); val xDen = r.s16(2); val xNum = r.s16(3)
                    if (xDen != 0) dc.extX = dc.extX * xNum / xDen
                    if (yDen != 0) dc.extY = dc.extY * yNum / yDen
                }
                SAVE_DC -> saved.addLast(dc.copy())
                RESTORE_DC -> {
                    // Negative: that many states back; positive: the n-th saved state; out of range is ignored.
                    val n = r.s16(0)
                    val count = if (n < 0) -n else if (n > 0) saved.size - n + 1 else 0
                    if (count in 1..saved.size) repeat(count) { dc = saved.removeLast() }
                }
                SET_TEXT_COLOR -> dc.textColor = color(r, 0)
                SET_TEXT_ALIGN -> dc.textAlign = r.u16(0)

                CREATE_PEN -> add(Pen(color(r, 3), r.s16(1), penStyle(r.u16(0))))
                CREATE_BRUSH -> add(
                    when (r.u16(0)) {
                        BS_NULL -> Brush(null)
                        else -> Brush(color(r, 1)) // solid; hatches are shown as their colour
                    },
                )
                CREATE_FONT -> add(font(r))
                CREATE_PALETTE -> add(Palette(IntArray(r.u16(1)) { i -> rgb(r.u16(2 + i * 2), r.u16(3 + i * 2)) }))
                SELECT_PALETTE -> (objects.getOrNull(r.u16(0)) as? Palette)?.let { dc.palette = it }
                CREATE_PATTERN_BRUSH, CREATE_REGION -> add(Unit)
                CREATE_DIB_PATTERN_BRUSH -> add(Brush(GRAY))
                SELECT_OBJECT -> when (val o = objects.getOrNull(r.u16(0))) {
                    is Pen -> dc.pen = o
                    is Brush -> dc.brush = o
                    is Font -> dc.font = o
                }
                DELETE_OBJECT -> r.u16(0).let { if (it < objects.size) objects[it] = null }

                MOVE_TO -> { dc.curY = r.s16(0); dc.curX = r.s16(1) }
                LINE_TO -> {
                    val ny = r.s16(0); val nx = r.s16(1)
                    stroke(listOf(PathOp.MoveTo(x(dc.curX), y(dc.curY)), PathOp.LineTo(x(nx), y(ny))))
                    dc.curX = nx; dc.curY = ny
                }
                RECTANGLE -> {
                    val b = r.s16(0); val rt = r.s16(1); val t = r.s16(2); val l = r.s16(3)
                    shape(rect(x(l), y(t), x(rt), y(b)))
                }
                ROUND_RECT -> {
                    val h = r.s16(0); val w = r.s16(1); val b = r.s16(2); val rt = r.s16(3); val t = r.s16(4); val l = r.s16(5)
                    shape(roundRect(x(l), y(t), x(rt), y(b), abs(w * sx) / 2, abs(h * sy) / 2))
                }
                ELLIPSE -> {
                    val b = r.s16(0); val rt = r.s16(1); val t = r.s16(2); val l = r.s16(3)
                    shape(ellipse(x(l), y(t), x(rt), y(b)))
                }
                ARC, PIE, CHORD -> arc(r)
                POLYGON, POLYLINE -> {
                    val n = r.u16(0)
                    val ops = (0 until n).map { i ->
                        val px = x(r.s16(1 + i * 2)); val py = y(r.s16(2 + i * 2))
                        if (i == 0) PathOp.MoveTo(px, py) else PathOp.LineTo(px, py)
                    }
                    if (r.function == POLYGON) shape(ops + PathOp.Close) else stroke(ops)
                }
                POLY_POLYGON -> {
                    val polys = r.u16(0)
                    var at = 1 + polys
                    val ops = mutableListOf<PathOp>()
                    for (p in 0 until polys) {
                        val n = r.u16(1 + p)
                        for (i in 0 until n) {
                            val px = x(r.s16(at + i * 2)); val py = y(r.s16(at + i * 2 + 1))
                            ops += if (i == 0) PathOp.MoveTo(px, py) else PathOp.LineTo(px, py)
                        }
                        ops += PathOp.Close
                        at += n * 2
                    }
                    shape(ops)
                }
                TEXT_OUT -> {
                    val n = r.u16(0)
                    val bytes = r.data.copyOfRange(r.params + 2, r.params + 2 + n)
                    val words = (n + 1) / 2
                    text(r.s16(2 + words), r.s16(1 + words), bytes, null)
                }
                EXT_TEXT_OUT -> extTextOut(r)
                STRETCH_DIB -> bitmap(r, r.s16(7), r.s16(8), r.s16(9), r.s16(10))
                DIB_STRETCH_BLT -> bitmap(r, r.s16(6), r.s16(7), r.s16(8), r.s16(9))
                DIB_BIT_BLT -> bitmap(r, r.s16(4), r.s16(5), r.s16(6), r.s16(7))
                SET_DIBITS_TO_DEVICE -> bitmap(r, r.s16(5), r.s16(6), r.s16(7), r.s16(8))
            }
        }

        private fun add(o: Any) {
            val free = objects.indexOfFirst { it == null }
            if (free >= 0) objects[free] = o else objects += o
        }

        private fun color(r: Record, word: Int): Int {
            val rg = r.u16(word); val bf = r.u16(word + 1)
            // Flag 1: an index into the selected palette.
            if (bf shr 8 == 1) dc.palette?.colors?.getOrNull(rg)?.let { return it }
            return rgb(rg, bf)
        }

        private fun rgb(rg: Int, bf: Int) = (0xFF shl 24) or ((rg and 0xFF) shl 16) or (rg and 0xFF00) or (bf and 0xFF)

        /**
         * A metafile without a placeable header or a window extent has no stated size: frame the
         * area its drawing records cover.
         */
        private fun fitToDrawing() {
            var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
            var hasWindow = false
            fun point(px: Int, py: Int) {
                minX = minOf(minX, px); maxX = maxOf(maxX, px); minY = minOf(minY, py); maxY = maxOf(maxY, py)
            }
            records(data) { r ->
                when (r.function) {
                    SET_WINDOW_EXT -> hasWindow = true
                    MOVE_TO, LINE_TO -> point(r.s16(1), r.s16(0))
                    RECTANGLE, ELLIPSE -> { point(r.s16(3), r.s16(2)); point(r.s16(1), r.s16(0)) }
                    ROUND_RECT -> { point(r.s16(5), r.s16(4)); point(r.s16(3), r.s16(2)) }
                    ARC, PIE, CHORD -> { point(r.s16(7), r.s16(6)); point(r.s16(5), r.s16(4)) }
                    POLYGON, POLYLINE -> for (i in 0 until r.u16(0)) point(r.s16(1 + i * 2), r.s16(2 + i * 2))
                    STRETCH_DIB -> { point(r.s16(10), r.s16(9)); point(r.s16(10) + r.s16(8), r.s16(9) + r.s16(7)) }
                    DIB_STRETCH_BLT, DIB_BIT_BLT -> { point(r.s16(9), r.s16(8)); point(r.s16(9) + r.s16(7), r.s16(8) + r.s16(6)) }
                    EXT_TEXT_OUT -> point(r.s16(1), r.s16(0))
                }
            }
            if (hasWindow || minX > maxX || minY > maxY) return
            dc.orgX = minX; dc.orgY = minY
            dc.extX = (maxX - minX).coerceAtLeast(1); dc.extY = (maxY - minY).coerceAtLeast(1)
        }

        private fun font(r: Record): Font {
            val faceStart = r.params + 18
            val faceBytes = r.data.copyOfRange(faceStart, minOf(faceStart + 32, r.end)).takeWhile { it != 0.toByte() }.toByteArray()
            val charset = r.data.getOrNull(r.params + 13)?.toInt()?.and(0xFF) ?: 0
            return Font(
                height = r.s16(0), weight = r.s16(4),
                italic = r.data[r.params + 10] != 0.toByte(), underline = r.data[r.params + 11] != 0.toByte(),
                strikeout = r.data[r.params + 12] != 0.toByte(), charset = charset,
                face = String(faceBytes, charsetFor(charset)).trim(),
            )
        }

        private val strokeWidth get() = abs(dc.pen.width * sx)

        private fun stroke(ops: List<PathOp>) {
            val style = dc.pen.style ?: return
            out += PathItem(ops, null, dc.pen.color, strokeWidth, style)
        }

        private fun shape(ops: List<PathOp>) {
            val style = dc.pen.style
            if (style == null && dc.brush.color == null) return
            out += PathItem(ops, dc.brush.color, if (style != null) dc.pen.color else null, strokeWidth, style ?: BorderStyle.SOLID)
        }

        private fun arc(r: Record) {
            val ye = r.s16(0); val xe = r.s16(1); val ys = r.s16(2); val xs = r.s16(3)
            val b = r.s16(4); val rt = r.s16(5); val t = r.s16(6); val l = r.s16(7)
            val cx = (l + rt) / 2.0; val cy = (t + b) / 2.0
            val rx = abs(rt - l) / 2.0; val ry = abs(b - t) / 2.0
            // Counter-clockwise from the start radial to the end radial, as GDI draws by default.
            val a0 = atan2(-(ys - cy), xs - cx)
            var a1 = atan2(-(ye - cy), xe - cx)
            if (a1 <= a0) a1 += 2 * PI
            val steps = 48
            val pts = (0..steps).map { i ->
                val a = a0 + (a1 - a0) * i / steps
                x((cx + rx * cos(a)).toInt()) to y((cy - ry * sin(a)).toInt())
            }
            val ops = mutableListOf<PathOp>()
            when (r.function) {
                PIE -> {
                    ops += PathOp.MoveTo(x(cx.toInt()), y(cy.toInt()))
                    pts.forEach { ops += PathOp.LineTo(it.first, it.second) }
                    ops += PathOp.Close
                    shape(ops)
                }
                CHORD -> {
                    pts.forEachIndexed { i, p -> ops += if (i == 0) PathOp.MoveTo(p.first, p.second) else PathOp.LineTo(p.first, p.second) }
                    ops += PathOp.Close
                    shape(ops)
                }
                else -> {
                    pts.forEachIndexed { i, p -> ops += if (i == 0) PathOp.MoveTo(p.first, p.second) else PathOp.LineTo(p.first, p.second) }
                    stroke(ops)
                }
            }
        }

        private fun extTextOut(r: Record) {
            val yy = r.s16(0); val xx = r.s16(1); val n = r.u16(2); val options = r.u16(3)
            var at = r.params + 8
            if (options and (ETO_OPAQUE or ETO_CLIPPED) != 0) {
                if (options and ETO_OPAQUE != 0) {
                    val l = s16(r.data, at); val t = s16(r.data, at + 2); val rt = s16(r.data, at + 4); val b = s16(r.data, at + 6)
                    out += PathItem(rect(x(l), y(t), x(rt), y(b)), WHITE, null, 0f, BorderStyle.SOLID)
                }
                at += 8
            }
            if (at + n > r.end) return
            val bytes = r.data.copyOfRange(at, at + n)
            at += n + (n and 1)
            // ETO_PDY: the advances come as (dx, dy) pairs.
            val step = if (options and ETO_PDY != 0) 2 else 1
            val dx = if (at + n * 2 * step <= r.end) IntArray(n) { s16(r.data, at + it * 2 * step) } else null
            text(xx, yy, bytes, dx)
        }

        private fun text(lx: Int, ly: Int, bytes: ByteArray, dx: IntArray?) {
            val f = dc.font
            val charset = charsetFor(f?.charset ?: 1)
            val s = String(bytes, charset).trimEnd('\u0000')
            if (s.isBlank()) return
            val em = abs(f?.height ?: 12).let { if ((f?.height ?: -1) > 0) it * 0.85f else it.toFloat() } * abs(sy)
            val spec = FontSpec(f?.face?.ifEmpty { null } ?: "굴림", em, (f?.weight ?: 400) >= 600, f?.italic == true, scriptOf(s.first { !it.isWhitespace() }))
            val advances = measurer.advances(s, spec)
            // Advances from the record are per input byte; a two-byte Hangul character uses the sum of its pair.
            val widths = if (dx != null) charWidths(s, bytes, dx, charset).map { it * abs(sx) } else advances.toList()
            val total = widths.sum()
            val useCurrent = dc.textAlign and TA_UPDATECP != 0
            val ox = x(if (useCurrent) dc.curX else lx)
            val oy = y(if (useCurrent) dc.curY else ly)
            val startX = when (dc.textAlign and 6) {
                TA_RIGHT -> ox - total
                TA_CENTER -> ox - total / 2
                else -> ox
            }
            val baseline = when (dc.textAlign and 24) {
                TA_BASELINE -> oy
                TA_BOTTOM -> oy - em * 0.14f
                else -> oy + em * 0.86f
            }
            val xs = FloatArray(s.length)
            var cx = startX
            for (i in s.indices) { xs[i] = cx; cx += widths.getOrElse(i) { 0f } }
            // Squeeze the substitute font into the width the metafile gave the text.
            val natural = advances.sum()
            val scaleX = if (dx != null && natural > 0 && total < natural) total / natural else 1f
            out += TextItem(s, xs, baseline, spec, dc.textColor, scaleX)
            if (f?.underline == true) out += LineItem(startX, baseline + em * 0.1f, startX + total, baseline + em * 0.1f, em / 15, dc.textColor, BorderStyle.SOLID)
            if (f?.strikeout == true) out += LineItem(startX, baseline - em * 0.3f, startX + total, baseline - em * 0.3f, em / 15, dc.textColor, BorderStyle.SOLID)
            if (useCurrent) dc.curX = lx + (total / (if (sx != 0f) sx else 1f)).toInt()
        }

        private fun charWidths(s: String, bytes: ByteArray, dx: IntArray, charset: Charset): List<Float> {
            if (s.length == dx.size) return dx.map { it.toFloat() }
            // Multi-byte text: walk the bytes, giving each character the advances of its bytes.
            val result = ArrayList<Float>(s.length)
            var b = 0
            for (ch in s) {
                val len = ch.toString().toByteArray(charset).size.coerceAtLeast(1)
                result += (b until minOf(b + len, dx.size)).sumOf { dx[it] }.toFloat()
                b += len
            }
            return result
        }

        private fun bitmap(r: Record, dh: Int, dw: Int, dy: Int, dxp: Int) {
            if (r.dib() == null) return
            val key = bitmapKey(binId, bitmaps++)
            val x0 = x(dxp); val y0 = y(dy); val x1 = x(dxp + dw); val y1 = y(dy + dh)
            out += ImageItem(key, minOf(x0, x1), minOf(y0, y1), abs(x1 - x0), abs(y1 - y0))
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Geometry

    private fun rect(l: Float, t: Float, r: Float, b: Float) =
        listOf(PathOp.MoveTo(l, t), PathOp.LineTo(r, t), PathOp.LineTo(r, b), PathOp.LineTo(l, b), PathOp.Close)

    private const val K = 0.5523f

    private fun ellipse(l: Float, t: Float, r: Float, b: Float): List<PathOp> {
        val cx = (l + r) / 2; val cy = (t + b) / 2; val rx = (r - l) / 2; val ry = (b - t) / 2
        return listOf(
            PathOp.MoveTo(cx + rx, cy),
            PathOp.CubicTo(cx + rx, cy + ry * K, cx + rx * K, cy + ry, cx, cy + ry),
            PathOp.CubicTo(cx - rx * K, cy + ry, cx - rx, cy + ry * K, cx - rx, cy),
            PathOp.CubicTo(cx - rx, cy - ry * K, cx - rx * K, cy - ry, cx, cy - ry),
            PathOp.CubicTo(cx + rx * K, cy - ry, cx + rx, cy - ry * K, cx + rx, cy),
            PathOp.Close,
        )
    }

    private fun roundRect(l0: Float, t0: Float, r0: Float, b0: Float, rx0: Float, ry0: Float): List<PathOp> {
        val l = minOf(l0, r0); val r = maxOf(l0, r0); val t = minOf(t0, b0); val b = maxOf(t0, b0)
        val rx = rx0.coerceAtMost((r - l) / 2); val ry = ry0.coerceAtMost((b - t) / 2)
        return listOf(
            PathOp.MoveTo(l + rx, t), PathOp.LineTo(r - rx, t),
            PathOp.CubicTo(r - rx + rx * K, t, r, t + ry - ry * K, r, t + ry), PathOp.LineTo(r, b - ry),
            PathOp.CubicTo(r, b - ry + ry * K, r - rx + rx * K, b, r - rx, b), PathOp.LineTo(l + rx, b),
            PathOp.CubicTo(l + rx - rx * K, b, l, b - ry + ry * K, l, b - ry), PathOp.LineTo(l, t + ry),
            PathOp.CubicTo(l, t + ry - ry * K, l + rx - rx * K, t, l + rx, t), PathOp.Close,
        )
    }

    private fun penStyle(style: Int): BorderStyle? = when (style and 0x0F) {
        PS_NULL -> null
        1 -> BorderStyle.DASH
        2 -> BorderStyle.DOT
        3 -> BorderStyle.DASH_DOT
        4 -> BorderStyle.DASH_DOT_DOT
        else -> BorderStyle.SOLID
    }

    /** Windows code page of a font charset; Korean documents default to CP949. */
    private fun charsetFor(charset: Int): Charset = runCatching {
        Charset.forName(
            when (charset) {
                0, 2 -> "windows-1252"
                128 -> "Shift_JIS"
                134 -> "GBK"
                136 -> "Big5"
                161 -> "windows-1253"
                162 -> "windows-1254"
                204 -> "windows-1251"
                238 -> "windows-1250"
                else -> "x-windows-949"
            },
        )
    }.recoverCatching { Charset.forName("EUC-KR") }.getOrDefault(Charsets.ISO_8859_1)

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val GRAY = 0xFF808080.toInt()

    private const val PS_NULL = 5
    private const val BS_NULL = 1
    private const val ETO_OPAQUE = 2
    private const val ETO_CLIPPED = 4
    private const val ETO_PDY = 0x2000
    private const val TA_UPDATECP = 1
    private const val TA_RIGHT = 2
    private const val TA_CENTER = 6
    private const val TA_BOTTOM = 8
    private const val TA_BASELINE = 24

    private const val SAVE_DC = 0x001E
    private const val CREATE_PALETTE = 0x00F7
    private const val SELECT_PALETTE = 0x0234
    private const val RESTORE_DC = 0x0127
    private const val SELECT_OBJECT = 0x012D
    private const val SET_TEXT_ALIGN = 0x012E
    private const val CREATE_DIB_PATTERN_BRUSH = 0x0142
    private const val DELETE_OBJECT = 0x01F0
    private const val CREATE_PATTERN_BRUSH = 0x01F9
    private const val SET_TEXT_COLOR = 0x0209
    private const val SET_WINDOW_ORG = 0x020B
    private const val SET_WINDOW_EXT = 0x020C
    private const val OFFSET_WINDOW_ORG = 0x020F
    private const val LINE_TO = 0x0213
    private const val MOVE_TO = 0x0214
    private const val CREATE_PEN = 0x02FA
    private const val CREATE_FONT = 0x02FB
    private const val CREATE_BRUSH = 0x02FC
    private const val POLYGON = 0x0324
    private const val POLYLINE = 0x0325
    private const val SCALE_WINDOW_EXT = 0x0410
    private const val ELLIPSE = 0x0418
    private const val RECTANGLE = 0x041B
    private const val TEXT_OUT = 0x0521
    private const val POLY_POLYGON = 0x0538
    private const val ROUND_RECT = 0x061C
    private const val CREATE_REGION = 0x06FF
    private const val ARC = 0x0817
    private const val PIE = 0x081A
    private const val CHORD = 0x0830
    private const val DIB_BIT_BLT = 0x0940
    private const val EXT_TEXT_OUT = 0x0A32
    private const val DIB_STRETCH_BLT = 0x0B41
    private const val SET_DIBITS_TO_DEVICE = 0x0D33
    private const val STRETCH_DIB = 0x0F43
}
