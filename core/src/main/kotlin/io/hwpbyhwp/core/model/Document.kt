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
                is AutoNumber -> if (inline.kind == AutoNumberKind.PAGE) append('#')
                is ShapeObject -> inline.text?.paragraphs?.forEach { appendParagraph(it) }
                is PictureObject, is ColumnDefine, is HeaderFooter, is PageNumberPosition -> Unit
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
    /** Line layout Hangul computed when it saved the file; empty if the writer did not store one. */
    val lineSegs: List<LineSeg> = emptyList(),
    /** Length of the paragraph in HWP character units (see [Inline.start]). */
    val length: Int = 0,
)

/**
 * One laid-out line (or line segment) as stored in the file. Vertical positions are relative to the
 * top of the text area of the page (or cell) the line sits in; horizontal ones to the column start.
 */
data class LineSeg(
    /** Start of the line in HWP character units. */
    val textStart: Int,
    val vertPos: Int,
    val height: Int,
    val textHeight: Int,
    /** Distance from the line top to the baseline. */
    val baseline: Int,
    val spacing: Int,
    val horzPos: Int,
    val width: Int,
    val flags: Int,
) {
    val firstLineOfPage: Boolean get() = flags and 1 != 0
    val firstLineOfColumn: Boolean get() = flags and 2 != 0
}

sealed interface Inline {
    /**
     * Offset within the paragraph in HWP character units: an ordinary character counts 1,
     * a control (tab, table, picture, section definition, field...) counts 8, a line break 1.
     * Line segments refer to text by these offsets.
     */
    val start: Int
}

data class TextRun(val text: String, val charShapeId: Int, override val start: Int = 0) : Inline
data class Tab(val charShapeId: Int, override val start: Int = 0) : Inline
data class LineBreak(val charShapeId: Int, override val start: Int = 0) : Inline

/** Starts a multi-column zone (단 정의). Applies from the paragraph that holds it onward. */
data class ColumnDefine(
    val count: Int,
    val gap: Int,
    /** Per-column widths; empty when all columns are equal. Proportional if they do not sum to the body width. */
    val widths: List<Int> = emptyList(),
    val gaps: List<Int> = emptyList(),
    override val start: Int = 0,
) : Inline

enum class HeaderFooterKind { HEADER, FOOTER }
enum class ApplyPage { BOTH, EVEN, ODD }

/** 머리말/꼬리말: applies to pages from the one holding this control onward. */
data class HeaderFooter(
    val kind: HeaderFooterKind,
    val applyPage: ApplyPage,
    val paragraphs: List<Paragraph>,
    override val start: Int = 0,
) : Inline

enum class PageNumberPlace { NONE, LEFT_TOP, CENTER_TOP, RIGHT_TOP, LEFT_BOTTOM, CENTER_BOTTOM, RIGHT_BOTTOM, OUTSIDE_TOP, OUTSIDE_BOTTOM, INSIDE_TOP, INSIDE_BOTTOM }

/** 쪽 번호 위치: prints the page number in the header/footer area from this page on. */
data class PageNumberPosition(
    val place: PageNumberPlace,
    val prefix: String = "",
    val suffix: String = "",
    override val start: Int = 0,
) : Inline

enum class AutoNumberKind { PAGE, TOTAL_PAGES, OTHER }

/** A number filled in at layout time, e.g. the page number inside a footer. */
data class AutoNumber(
    val kind: AutoNumberKind,
    val charShapeId: Int,
    override val start: Int = 0,
) : Inline

/** 2D affine transform: x' = a*x + b*y + c, y' = d*x + e*y + f (HWP's 3x2 matrix layout). */
data class Affine(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {
    fun x(x: Double, y: Double) = a * x + b * y + c
    fun y(x: Double, y: Double) = d * x + e * y + f

    /** this ∘ other: apply [other] first. */
    operator fun times(o: Affine) = Affine(
        a * o.a + b * o.d, a * o.b + b * o.e, a * o.c + b * o.f + c,
        d * o.a + e * o.d, d * o.b + e * o.e, d * o.c + e * o.f + f,
    )

    companion object {
        val IDENTITY = Affine(1.0, 0.0, 0.0, 0.0, 1.0, 0.0)
    }
}

data class Pt(val x: Double, val y: Double)

enum class ShapeKind { LINE, RECTANGLE, ELLIPSE, ARC, POLYGON, CURVE, CONTAINER, PICTURE }
enum class ArcKind { ARC, PIE, CHORD }

data class Stroke(
    /** ARGB. */
    val color: Int,
    /** HWPUNIT. */
    val width: Int,
    val style: BorderStyle,
)

/** Text inside a drawing object (글상자). */
data class TextBox(
    val paragraphs: List<Paragraph>,
    val marginLeft: Int,
    val marginRight: Int,
    val marginTop: Int,
    val marginBottom: Int,
    val vertAlign: VertAlign,
)

/**
 * A drawing object (그리기 개체). Geometry is in the shape's own coordinates; [transform] maps it
 * into the object's box (or, for a child, into its container's coordinates).
 */
data class ShapeObject(
    val kind: ShapeKind,
    val width: Int,
    val height: Int,
    val placement: ObjectPlacement,
    val transform: Affine,
    /** Rectangle: 4 corners; line: 2 ends; ellipse/arc: center, axis 1, axis 2; polygon/curve: vertices. */
    val points: List<Pt>,
    val stroke: Stroke?,
    /** ARGB, or null when unfilled. */
    val fill: Int?,
    /** Rounded-corner ratio of a rectangle, percent of half the shorter side. */
    val roundRatio: Int = 0,
    val arcKind: ArcKind = ArcKind.ARC,
    val text: TextBox? = null,
    val children: List<ShapeObject> = emptyList(),
    /** For [ShapeKind.PICTURE] children of a group. */
    val binId: String? = null,
    /** Size in the shape's own coordinates (before [transform]). */
    val originalWidth: Int = width,
    val originalHeight: Int = height,
    override val start: Int = 0,
) : Inline

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
    override val start: Int = 0,
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
    override val start: Int = 0,
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
    /** Space between the paragraph text and its border. */
    val borderOffsetLeft: Int = 0,
    val borderOffsetRight: Int = 0,
    val borderOffsetTop: Int = 0,
    val borderOffsetBottom: Int = 0,
    /** Merge the border with neighbouring paragraphs that use the same border (테두리 연결). */
    val borderConnect: Boolean = false,
    /** Draw the border across the paragraph margins (여백 무시). */
    val borderIgnoreMargin: Boolean = false,
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
