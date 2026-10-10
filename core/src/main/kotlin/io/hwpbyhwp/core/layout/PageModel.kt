package io.hwpbyhwp.core.layout

import io.hwpbyhwp.core.model.BorderStyle
import io.hwpbyhwp.core.model.Script

/**
 * Output of [PageLayouter]: absolutely positioned drawing items per page.
 * Coordinates are HWPUNIT from the top-left corner of the paper.
 */
class Page(
    val width: Int,
    val height: Int,
    val items: List<PageItem>,
)

sealed interface PageItem

/** A run of characters sharing one font; each character has its own x so justification is exact. */
class TextItem(
    val text: String,
    /** Left edge of each character. */
    val xs: FloatArray,
    val baseline: Float,
    val font: FontSpec,
    /** ARGB. */
    val color: Int,
    /** Horizontal glyph scale (장평), 1.0 = normal. */
    val scaleX: Float,
) : PageItem

class RectItem(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** ARGB fill. */
    val fill: Int,
) : PageItem

class LineItem(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    /** Stroke width in HWPUNIT. */
    val width: Float,
    val color: Int,
    val style: BorderStyle,
) : PageItem

class ImageItem(
    val binId: String,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) : PageItem

data class FontSpec(
    val name: String,
    /** Em size in HWPUNIT (100 = 1 pt). */
    val size: Float,
    val bold: Boolean,
    val italic: Boolean,
    val script: Script,
)

/** Supplies glyph advances; implemented with Android Paint on device and Java2D in tests. */
fun interface TextMeasurer {
    /** Advance width of each character of [text] in HWPUNIT, before 장평/자간 adjustments. */
    fun advances(text: String, font: FontSpec): FloatArray
}

/** A vector path (drawing objects). Coordinates are page HWPUNIT. */
class PathItem(
    val ops: List<PathOp>,
    /** ARGB fill, or null. */
    val fill: Int?,
    /** ARGB stroke, or null. */
    val stroke: Int?,
    val strokeWidth: Float,
    val style: BorderStyle,
) : PageItem

sealed interface PathOp {
    data class MoveTo(val x: Float, val y: Float) : PathOp
    data class LineTo(val x: Float, val y: Float) : PathOp
    data class CubicTo(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float) : PathOp
    data object Close : PathOp
}
