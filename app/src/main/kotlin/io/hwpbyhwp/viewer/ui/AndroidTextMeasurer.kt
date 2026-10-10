package io.hwpbyhwp.viewer.ui

import android.graphics.Paint
import android.graphics.Typeface
import io.hwpbyhwp.core.layout.FontSpec
import io.hwpbyhwp.core.layout.TextMeasurer

/** Typeface lookup shared by measuring and drawing so both agree on glyph widths. */
object Fonts {
    private val cache = HashMap<Pair<String, Int>, Typeface>()

    fun typeface(spec: FontSpec): Typeface {
        val style = (if (spec.bold) Typeface.BOLD else 0) or (if (spec.italic) Typeface.ITALIC else 0)
        return synchronized(cache) {
            cache.getOrPut(spec.name to style) { Typeface.create(family(spec.name), style) }
        }
    }

    /** Until Hangul fonts are bundled, map families onto the closest system generic. */
    private fun family(name: String): Typeface = when {
        listOf("바탕", "명조", "Batang", "Myeongjo", "Times", "Serif").any { name.contains(it, ignoreCase = true) } -> Typeface.SERIF
        listOf("Courier", "Mono", "Consolas").any { name.contains(it, ignoreCase = true) } -> Typeface.MONOSPACE
        else -> Typeface.SANS_SERIF
    }
}

/** Measures at a fixed reference size and scales, so results do not depend on screen density. */
class AndroidTextMeasurer : TextMeasurer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = REFERENCE_SIZE }

    @Synchronized
    override fun advances(text: String, font: FontSpec): FloatArray {
        paint.typeface = Fonts.typeface(font)
        val widths = FloatArray(text.length)
        paint.getTextWidths(text, widths)
        val k = font.size / REFERENCE_SIZE
        for (i in widths.indices) widths[i] *= k
        return widths
    }

    private companion object {
        const val REFERENCE_SIZE = 100f
    }
}
