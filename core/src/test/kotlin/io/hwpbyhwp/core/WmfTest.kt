package io.hwpbyhwp.core

import io.hwpbyhwp.core.layout.ImageItem
import io.hwpbyhwp.core.layout.PathItem
import io.hwpbyhwp.core.layout.PathOp
import io.hwpbyhwp.core.layout.TextItem
import io.hwpbyhwp.core.layout.TextMeasurer
import io.hwpbyhwp.core.layout.Wmf
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WmfTest {

    private val mono = TextMeasurer { text, font -> FloatArray(text.length) { font.size / 2 } }

    /** Builds a placeable metafile with logical extent 1000 x 500 from (function, params) records. */
    private fun wmf(vararg records: Pair<Int, ShortArray>): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        fun u32(v: Long) { u16((v and 0xFFFF).toInt()); u16(((v shr 16) and 0xFFFF).toInt()) }
        u32(0x9AC6CDD7L); u16(0); u16(0); u16(0); u16(1000); u16(500); u16(1440); u32(0); u16(0)
        u16(1); u16(9); u16(0x300); u32(0); u16(4); u32(0); u16(0)
        for ((function, params) in records) {
            u32(3L + params.size); u16(function); params.forEach { u16(it.toInt()) }
        }
        u32(3); u16(0)
        return out.toByteArray()
    }

    private fun shorts(vararg v: Int) = ShortArray(v.size) { v[it].toShort() }

    /** Text bytes packed into 16-bit words, padded to an even length. */
    private fun text(bytes: ByteArray): IntArray {
        val padded = if (bytes.size % 2 == 0) bytes else bytes + 0
        return IntArray(padded.size / 2) { (padded[it * 2].toInt() and 0xFF) or ((padded[it * 2 + 1].toInt() and 0xFF) shl 8) }
    }

    @Test
    fun recognisesPlaceableMetafiles() {
        assertTrue(Wmf.isWmf(wmf()))
        assertTrue(!Wmf.isWmf(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())))
    }

    @Test
    fun scalesShapesIntoTheFrame() {
        val data = wmf(
            0x02FC to shorts(0, 0x00FF, 0, 0), // red solid brush
            0x012D to shorts(0),
            0x041B to shorts(250, 500, 0, 0), // rectangle (bottom, right, top, left)
        )
        val items = assertNotNull(Wmf.render(data, "1", 100f, 200f, 2000f, 1000f, mono))
        val rect = items.filterIsInstance<PathItem>().single()
        assertEquals(0xFFFF0000.toInt(), rect.fill)
        val corner = rect.ops.filterIsInstance<PathOp.LineTo>()[1]
        // Logical (500, 250) of a 1000 x 500 picture is the middle of the 2000 x 1000 frame.
        assertEquals(100f + 1000f, corner.x)
        assertEquals(200f + 500f, corner.y)
    }

    @Test
    fun decodesKoreanTextFromTheFontCharset() {
        val face = "굴림".toByteArray(charset("x-windows-949"))
        val font = ByteArray(50).also { b ->
            b[0] = (-40).toByte(); b[1] = 0xFF.toByte() // height -40
            b[8] = (400 and 0xFF).toByte(); b[9] = (400 shr 8).toByte()
            b[13] = 129.toByte() // HANGEUL_CHARSET
            face.copyInto(b, 18)
        }
        val textBytes = "가나".toByteArray(charset("x-windows-949"))
        val data = wmf(
            0x02FB to ShortArray(25) { i -> ((font[i * 2].toInt() and 0xFF) or ((font[i * 2 + 1].toInt() and 0xFF) shl 8)).toShort() },
            0x012D to shorts(0),
            0x0521 to shorts(textBytes.size, *text(textBytes), 100, 200), // TextOut: count, text, y, x
        )
        val items = assertNotNull(Wmf.render(data, "1", 0f, 0f, 1000f, 500f, mono))
        val t = items.filterIsInstance<TextItem>().single()
        assertEquals("가나", t.text)
        assertEquals("굴림", t.font.name)
        assertEquals(200f, t.xs[0])
    }

    @Test
    fun bitmapsInsideBecomeDecodableImages() {
        // A 2 x 2, 24-bit DIB.
        val dib = ByteArrayOutputStream().apply {
            fun u32(v: Int) { for (k in 0..3) write((v shr (8 * k)) and 0xFF) }
            fun u16(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF) }
            u32(40); u32(2); u32(2); u16(1); u16(24); u32(0); u32(16); u32(0); u32(0); u32(0); u32(0)
            repeat(2) { write(byteArrayOf(0, 0, -1, 0, -1, 0, 0, 0)) }
        }.toByteArray()
        val words = IntArray(dib.size / 2) { (dib[it * 2].toInt() and 0xFF) or ((dib[it * 2 + 1].toInt() and 0xFF) shl 8) }
        // StretchDIB: rop (2 words), usage, srcH, srcW, ySrc, xSrc, destH, destW, yDest, xDest, DIB.
        val data = wmf(0x0F43 to shorts(0x0020, 0x00CC, 0, 2, 2, 0, 0, 500, 1000, 0, 0, *words))
        val bitmaps = Wmf.embeddedBitmaps(data)
        assertEquals(1, bitmaps.size)
        val image = assertNotNull(ImageIO.read(bitmaps[0].inputStream()))
        assertEquals(2, image.width)
        val items = assertNotNull(Wmf.render(data, "7", 0f, 0f, 1000f, 500f, mono))
        val placed = items.filterIsInstance<ImageItem>().single()
        assertEquals(Wmf.bitmapKey("7", 0), placed.binId)
        assertEquals(1000f, placed.width)
    }
}
