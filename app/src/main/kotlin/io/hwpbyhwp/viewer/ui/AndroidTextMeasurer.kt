package io.hwpbyhwp.viewer.ui

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Paint
import android.graphics.Typeface
import io.hwpbyhwp.core.layout.FontSpec
import io.hwpbyhwp.core.layout.GenericFamily
import io.hwpbyhwp.core.layout.genericFamilyOf
import io.hwpbyhwp.core.layout.TextMeasurer

/**
 * Typeface lookup shared by measuring and drawing so both agree on glyph widths.
 * Document fonts are substituted by the bundled Noto Sans/Serif KR (assets/fonts, SIL OFL 1.1).
 */
object Fonts {
    private lateinit var assets: AssetManager
    private val cache = HashMap<Pair<GenericFamily, Int>, Typeface>()

    fun init(context: Context) {
        assets = context.applicationContext.assets
    }

    fun typeface(spec: FontSpec): Typeface {
        val family = genericFamilyOf(spec.name)
        val style = (if (spec.bold) Typeface.BOLD else 0) or (if (spec.italic) Typeface.ITALIC else 0)
        return synchronized(cache) { cache.getOrPut(family to style) { load(family, style) } }
    }

    private fun load(family: GenericFamily, style: Int): Typeface {
        val bold = style and Typeface.BOLD != 0
        val file = when (family) {
            GenericFamily.SANS -> if (bold) "NotoSansKR-Bold.otf" else "NotoSansKR-Regular.otf"
            GenericFamily.SERIF -> if (bold) "NotoSerifKR-Bold.otf" else "NotoSerifKR-Regular.otf"
            GenericFamily.MONO -> null
        }
        val base = file?.let { runCatching { Typeface.createFromAsset(assets, "fonts/$it") }.getOrNull() }
            ?: return Typeface.create(Typeface.MONOSPACE, style)
        // Bold comes from its own file; only italic needs synthesising.
        return if (style and Typeface.ITALIC != 0) Typeface.create(base, Typeface.ITALIC) else base
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
