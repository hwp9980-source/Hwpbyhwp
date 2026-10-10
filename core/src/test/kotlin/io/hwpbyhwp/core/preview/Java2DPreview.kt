package io.hwpbyhwp.core.preview

import io.hwpbyhwp.core.layout.FontSpec
import io.hwpbyhwp.core.layout.GenericFamily
import io.hwpbyhwp.core.layout.genericFamilyOf
import io.hwpbyhwp.core.layout.ImageItem
import io.hwpbyhwp.core.layout.LineItem
import io.hwpbyhwp.core.layout.Page
import io.hwpbyhwp.core.layout.RectItem
import io.hwpbyhwp.core.layout.TextItem
import io.hwpbyhwp.core.layout.TextMeasurer
import io.hwpbyhwp.core.model.BorderStyle
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform
import java.awt.geom.Line2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/** Java2D stand-in for the Android renderer, used to eyeball layouts as PNGs on a desktop JVM. */
object Java2DPreview {

    private val frc = FontRenderContext(null, true, true)
    /** The app's bundled substitutes, so previews measure like the device does. */
    private val fontDir = File(System.getenv("HWP_FONTS_DIR") ?: "../app/src/main/assets/fonts")
    private val loaded = HashMap<String, Font>()

    private fun base(file: String): Font = loaded.getOrPut(file) {
        runCatching { Font.createFont(Font.TRUETYPE_FONT, File(fontDir, file)) }.getOrElse { Font("Dialog", Font.PLAIN, 1) }
    }

    private fun awtFont(spec: FontSpec): Font {
        val font = when (genericFamilyOf(spec.name)) {
            GenericFamily.SERIF -> base(if (spec.bold) "NotoSerifKR-Bold.otf" else "NotoSerifKR-Regular.otf")
            GenericFamily.SANS -> base(if (spec.bold) "NotoSansKR-Bold.otf" else "NotoSansKR-Regular.otf")
            GenericFamily.MONO -> Font(Font.MONOSPACED, if (spec.bold) Font.BOLD else Font.PLAIN, 1)
        }
        // Sizes are in HWPUNIT; the page transform scales them down.
        return font.deriveFont(if (spec.italic) Font.ITALIC else Font.PLAIN, spec.size)
    }

    val measurer = TextMeasurer { text, font ->
        val f = awtFont(font)
        FloatArray(text.length) { i -> f.getStringBounds(text, i, i + 1, frc).width.toFloat() }
    }

    fun render(page: Page, binaries: Map<String, ByteArray>, out: File, dpi: Int = 96) {
        val scale = dpi / 7200.0
        val img = BufferedImage((page.width * scale).toInt(), (page.height * scale).toInt(), BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, img.width, img.height)
        g.scale(scale, scale)
        for (item in page.items) {
            when (item) {
                is RectItem -> {
                    g.color = Color(item.fill, true)
                    g.fill(Rectangle2D.Float(item.left, item.top, item.right - item.left, item.bottom - item.top))
                }
                is LineItem -> {
                    g.color = Color(item.color, true)
                    val w = item.width.coerceAtLeast(7200f / dpi)
                    val dash = when (item.style) {
                        BorderStyle.DOT, BorderStyle.CIRCLE -> floatArrayOf(w, w * 2)
                        BorderStyle.DASH, BorderStyle.LONG_DASH, BorderStyle.DASH_DOT, BorderStyle.DASH_DOT_DOT -> floatArrayOf(w * 6, w * 3)
                        else -> null
                    }
                    g.stroke = BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, dash, 0f)
                    g.draw(Line2D.Float(item.x1, item.y1, item.x2, item.y2))
                }
                is TextItem -> {
                    g.color = Color(item.color, true)
                    var f = awtFont(item.font)
                    if (item.scaleX != 1f) f = f.deriveFont(AffineTransform.getScaleInstance(item.scaleX.toDouble(), 1.0))
                    g.font = f
                    item.text.forEachIndexed { i, c -> g.drawString(c.toString(), item.xs[i], item.baseline) }
                }
                is ImageItem -> {
                    val bytes = binaries[item.binId]
                    val bi = bytes?.let { runCatching { ImageIO.read(ByteArrayInputStream(it)) }.getOrNull() }
                    if (bi != null) {
                        g.drawImage(bi, item.left.toInt(), item.top.toInt(), item.width.toInt(), item.height.toInt(), null)
                    } else {
                        g.color = Color.LIGHT_GRAY
                        g.stroke = BasicStroke(20f)
                        g.draw(Rectangle2D.Float(item.left, item.top, item.width, item.height))
                    }
                }
            }
        }
        g.dispose()
        ImageIO.write(img, "png", out)
    }
}
