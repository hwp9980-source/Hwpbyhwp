package io.hwpbyhwp.viewer.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.LruCache
import io.hwpbyhwp.core.layout.ClipItem
import io.hwpbyhwp.core.layout.ImageItem
import io.hwpbyhwp.core.layout.LineItem
import io.hwpbyhwp.core.layout.Page
import io.hwpbyhwp.core.layout.PageItem
import io.hwpbyhwp.core.layout.PathItem
import io.hwpbyhwp.core.layout.PathOp
import io.hwpbyhwp.core.layout.RectItem
import io.hwpbyhwp.core.layout.TextItem
import io.hwpbyhwp.core.model.BorderStyle

/** Draws a laid-out [Page] onto an Android canvas at [scale] px per HWPUNIT. */
class PagePainter(private val binaries: Map<String, ByteArray>) {

    private val fill = Paint()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val placeholder = Paint().apply { style = Paint.Style.STROKE; color = 0xFFCCCCCC.toInt() }
    private val bitmaps = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val undecodable = HashSet<String>()

    fun draw(canvas: Canvas, page: Page, scale: Float) {
        canvas.drawColor(0xFFFFFFFF.toInt())
        page.items.forEach { draw(canvas, it, scale) }
    }

    private fun draw(canvas: Canvas, item: PageItem, scale: Float) {
        when (item) {
            is ClipItem -> {
                canvas.save()
                canvas.clipRect(item.left * scale, item.top * scale, item.right * scale, item.bottom * scale)
                item.items.forEach { draw(canvas, it, scale) }
                canvas.restore()
            }
            is RectItem -> {
                fill.color = item.fill
                canvas.drawRect(item.left * scale, item.top * scale, item.right * scale, item.bottom * scale, fill)
            }
            is LineItem -> {
                val w = (item.width * scale).coerceAtLeast(1f)
                stroke.color = item.color
                stroke.strokeWidth = w
                stroke.pathEffect = dashFor(item.style, w)
                canvas.drawLine(item.x1 * scale, item.y1 * scale, item.x2 * scale, item.y2 * scale, stroke)
            }
            is TextItem -> {
                text.typeface = Fonts.typeface(item.font)
                text.textSize = item.font.size * scale
                text.textScaleX = item.scaleX
                text.color = item.color
                val y = item.baseline * scale
                for (i in item.text.indices) {
                    canvas.drawText(item.text, i, i + 1, item.xs[i] * scale, y, text)
                }
            }
            is PathItem -> {
                val path = Path()
                item.ops.forEach { op ->
                    when (op) {
                        is PathOp.MoveTo -> path.moveTo(op.x * scale, op.y * scale)
                        is PathOp.LineTo -> path.lineTo(op.x * scale, op.y * scale)
                        is PathOp.CubicTo -> path.cubicTo(op.x1 * scale, op.y1 * scale, op.x2 * scale, op.y2 * scale, op.x * scale, op.y * scale)
                        PathOp.Close -> path.close()
                    }
                }
                item.fill?.let { fill.color = it; canvas.drawPath(path, fill) }
                item.stroke?.let {
                    val w = (item.strokeWidth * scale).coerceAtLeast(1f)
                    stroke.color = it
                    stroke.strokeWidth = w
                    stroke.pathEffect = dashFor(item.style, w)
                    canvas.drawPath(path, stroke)
                }
            }
            is ImageItem -> {
                val dst = RectF(item.left * scale, item.top * scale, (item.left + item.width) * scale, (item.top + item.height) * scale)
                val bmp = bitmap(item.binId, dst.width().toInt(), dst.height().toInt())
                if (bmp != null) canvas.drawBitmap(bmp, null, dst, bitmapPaint) else canvas.drawRect(dst, placeholder)
            }
        }
    }

    private fun dashFor(style: BorderStyle, w: Float) = when (style) {
        BorderStyle.DOT, BorderStyle.CIRCLE -> DashPathEffect(floatArrayOf(w, w * 2), 0f)
        BorderStyle.DASH, BorderStyle.LONG_DASH -> DashPathEffect(floatArrayOf(w * 6, w * 3), 0f)
        BorderStyle.DASH_DOT, BorderStyle.DASH_DOT_DOT -> DashPathEffect(floatArrayOf(w * 6, w * 2, w, w * 2), 0f)
        else -> null
    }

    /** Decodes once per image, downsampled to roughly the size it is shown at. */
    private fun bitmap(id: String, targetW: Int, targetH: Int): Bitmap? {
        bitmaps.get(id)?.let { return it }
        if (id in undecodable) return null
        val bytes = binaries[id] ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetW.coerceAtLeast(1) && bounds.outHeight / (sample * 2) >= targetH.coerceAtLeast(1)) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        if (bmp == null) undecodable += id else bitmaps.put(id, bmp)
        return bmp
    }
}
