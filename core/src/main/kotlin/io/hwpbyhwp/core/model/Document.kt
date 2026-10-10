package io.hwpbyhwp.core.model

/**
 * Format-independent document model shared by the HWP and HWPX parsers.
 *
 * All lengths are HWPUNIT (1/7200 inch, so 1 pt = 100 HWPUNIT) unless noted.
 * Style ids keep the numbering of the source file, which is why the style
 * tables are maps rather than lists.
 */
data class Document(
    val sections: List<Section>,
    val charShapes: Map<Int, CharShape>,
    val paraShapes: Map<Int, ParaShape>,
    val borderFills: Map<Int, BorderFill>,
    /** Embedded binary data (mostly images), keyed by [Picture.binId]. */
    val binaries: Map<String, ByteArray>,
) {
    fun charShape(id: Int): CharShape = charShapes[id] ?: CharShape.DEFAULT
    fun paraShape(id: Int): ParaShape = paraShapes[id] ?: ParaShape.DEFAULT
    fun borderFill(id: Int): BorderFill? = borderFills[id]

    /** Plain text of the whole document, one line per paragraph. Tables are flattened cell by cell. */
    fun plainText(): String = buildString {
        sections.forEach { section -> section.paragraphs.forEach { appendParagraph(it) } }
    }.trimEnd()

    private fun StringBuilder.appendParagraph(p: Paragraph) {
        p.inlines.forEach { inline ->
            when (inline) {
                is TextRun -> append(inline.text)
                is Tab -> append('\t')
                is LineBreak -> append('\n')
                is TableObject -> {
                    if (isNotEmpty() && last() != '\n') append('\n')
                    inline.cells.forEach { cell -> cell.paragraphs.forEach { appendParagraph(it) } }
                }
                is PictureObject -> Unit
            }
        }
        append('\n')
    }
}

data class Section(
    val page: PageSetup,
    val paragraphs: List<Paragraph>,
)

data class PageSetup(
    val width: Int,
    val height: Int,
    val landscape: Boolean,
    val marginLeft: Int,
    val marginRight: Int,
    val marginTop: Int,
    val marginBottom: Int,
    val marginHeader: Int,
    val marginFooter: Int,
    val marginGutter: Int,
) {
    /** Paper size as laid out; HWP stores portrait dimensions and a landscape flag. */
    val effectiveWidth: Int get() = if (landscape) height else width
    val effectiveHeight: Int get() = if (landscape) width else height

    companion object {
        /** A4 with Hangul's default margins. */
        val A4 = PageSetup(
            width = 59528, height = 84188, landscape = false,
            marginLeft = 8504, marginRight = 8504, marginTop = 5668, marginBottom = 4252,
            marginHeader = 4252, marginFooter = 4252, marginGutter = 0,
        )
    }
}

data class Paragraph(
    val paraShapeId: Int,
    val inlines: List<Inline>,
    val pageBreakBefore: Boolean = false,
    val columnBreakBefore: Boolean = false,
)

sealed interface Inline

data class TextRun(val text: String, val charShapeId: Int) : Inline
data class Tab(val charShapeId: Int) : Inline
data class LineBreak(val charShapeId: Int) : Inline

/** How an object sits relative to the text around it. */
data class ObjectPlacement(
    /** Placed in the line like a character (글자처럼 취급). */
    val treatAsChar: Boolean,
    val wrap: TextWrap,
    val vertRelTo: RelTo,
    val horzRelTo: RelTo,
    val vertOffset: Int,
    val horzOffset: Int,
    val marginLeft: Int = 0,
    val marginRight: Int = 0,
    val marginTop: Int = 0,
    val marginBottom: Int = 0,
) {
    companion object {
        val INLINE = ObjectPlacement(true, TextWrap.TOP_AND_BOTTOM, RelTo.PARA, RelTo.PARA, 0, 0)
    }
}

enum class TextWrap { SQUARE, TOP_AND_BOTTOM, BEHIND_TEXT, IN_FRONT_OF_TEXT }
enum class RelTo { PAPER, PAGE, PARA, COLUMN }

data class TableObject(
    val rowCount: Int,
    val colCount: Int,
    val width: Int,
    val height: Int,
    val cellSpacing: Int,
    val borderFillId: Int,
    val placement: ObjectPlacement,
    val cells: List<TableCell>,
    /** Repeat the first row(s) marked as header on every page the table spans. */
    val repeatHeader: Boolean = false,
) : Inline

data class TableCell(
    val row: Int,
    val col: Int,
    val rowSpan: Int,
    val colSpan: Int,
    val width: Int,
    val height: Int,
    val marginLeft: Int,
    val marginRight: Int,
    val marginTop: Int,
    val marginBottom: Int,
    val borderFillId: Int,
    val vertAlign: VertAlign,
    val paragraphs: List<Paragraph>,
    val isHeader: Boolean = false,
)

enum class VertAlign { TOP, CENTER, BOTTOM }

data class PictureObject(
    val binId: String,
    val width: Int,
    val height: Int,
    val placement: ObjectPlacement,
) : Inline

data class CharShape(
    /** Font family per script; see [Script]. */
    val fontNames: Map<Script, String>,
    /** Base size in HWPUNIT (1000 = 10 pt). */
    val baseSize: Int,
    /** Per-script relative size in percent. */
    val relativeSizes: Map<Script, Int> = emptyMap(),
    /** Per-script width ratio (장평) in percent. */
    val ratios: Map<Script, Int> = emptyMap(),
    /** Per-script letter spacing (자간) in percent of the font size. */
    val spacings: Map<Script, Int> = emptyMap(),
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Underline = Underline.NONE,
    val strikeout: Boolean = false,
    val superscript: Boolean = false,
    val subscript: Boolean = false,
    /** ARGB. */
    val textColor: Int = BLACK,
    /** ARGB, or null when transparent. */
    val shadeColor: Int? = null,
    val underlineColor: Int = BLACK,
    val strikeoutColor: Int = BLACK,
) {
    fun fontName(script: Script): String = fontNames[script] ?: fontNames[Script.HANGUL] ?: DEFAULT_FONT
    fun sizeFor(script: Script): Int = baseSize * (relativeSizes[script] ?: 100) / 100
    fun ratioFor(script: Script): Int = ratios[script] ?: 100
    fun spacingFor(script: Script): Int = spacings[script] ?: 0

    companion object {
        const val BLACK = 0xFF000000.toInt()
        const val DEFAULT_FONT = "함초롬바탕"
        val DEFAULT = CharShape(fontNames = emptyMap(), baseSize = 1000)
    }
}

enum class Script { HANGUL, LATIN, HANJA, JAPANESE, OTHER, SYMBOL, USER }

enum class Underline { NONE, BOTTOM, TOP }

data class ParaShape(
    val align: HAlign = HAlign.JUSTIFY,
    val marginLeft: Int = 0,
    val marginRight: Int = 0,
    /** First-line indent; negative means hanging indent (내어쓰기). */
    val indent: Int = 0,
    val spaceBefore: Int = 0,
    val spaceAfter: Int = 0,
    val lineSpacingType: LineSpacingType = LineSpacingType.PERCENT,
    /** Percent for [LineSpacingType.PERCENT], HWPUNIT otherwise. */
    val lineSpacing: Int = 160,
    val borderFillId: Int = 0,
    val keepWithNext: Boolean = false,
    val pageBreakBefore: Boolean = false,
) {
    companion object {
        val DEFAULT = ParaShape()
    }
}

enum class HAlign { JUSTIFY, LEFT, RIGHT, CENTER, DISTRIBUTE, DISTRIBUTE_SPACE }

enum class LineSpacingType { PERCENT, FIXED, BETWEEN_LINES, AT_LEAST }

data class BorderFill(
    val left: Border,
    val right: Border,
    val top: Border,
    val bottom: Border,
    /** ARGB background, or null when none. */
    val fillColor: Int?,
)

data class Border(
    val style: BorderStyle,
    /** Line width in 1/100 mm. */
    val width: Int,
    /** ARGB. */
    val color: Int,
) {
    companion object {
        val NONE = Border(BorderStyle.NONE, 0, CharShape.BLACK)
    }
}

enum class BorderStyle { NONE, SOLID, DASH, DOT, DASH_DOT, DASH_DOT_DOT, LONG_DASH, CIRCLE, DOUBLE, THIN_THICK, THICK_THIN, THIN_THICK_THIN, WAVE, DOUBLE_WAVE }
