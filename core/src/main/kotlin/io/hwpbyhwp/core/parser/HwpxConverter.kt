package io.hwpbyhwp.core.parser

import io.hwpbyhwp.core.model.Border
import io.hwpbyhwp.core.model.BorderFill
import io.hwpbyhwp.core.model.BorderStyle
import io.hwpbyhwp.core.model.CharShape
import io.hwpbyhwp.core.model.Document
import io.hwpbyhwp.core.model.HAlign
import io.hwpbyhwp.core.model.Inline
import io.hwpbyhwp.core.model.LineBreak
import io.hwpbyhwp.core.model.LineSpacingType
import io.hwpbyhwp.core.model.ObjectPlacement
import io.hwpbyhwp.core.model.PageSetup
import io.hwpbyhwp.core.model.ParaShape
import io.hwpbyhwp.core.model.Paragraph
import io.hwpbyhwp.core.model.PictureObject
import io.hwpbyhwp.core.model.RelTo
import io.hwpbyhwp.core.model.Script
import io.hwpbyhwp.core.model.Section
import io.hwpbyhwp.core.model.Tab
import io.hwpbyhwp.core.model.TableCell
import io.hwpbyhwp.core.model.TableObject
import io.hwpbyhwp.core.model.TextRun
import io.hwpbyhwp.core.model.TextWrap
import io.hwpbyhwp.core.model.Underline
import io.hwpbyhwp.core.model.VertAlign
import kr.dogfoot.hwpxlib.`object`.HWPXFile
import kr.dogfoot.hwpxlib.`object`.common.HWPXObject
import kr.dogfoot.hwpxlib.`object`.common.ObjectList
import kr.dogfoot.hwpxlib.`object`.common.SwitchableObject
import kr.dogfoot.hwpxlib.`object`.content.header_xml.enumtype.HorizontalAlign2
import kr.dogfoot.hwpxlib.`object`.content.header_xml.enumtype.LineSpacingType as XLineSpacingType
import kr.dogfoot.hwpxlib.`object`.content.header_xml.enumtype.LineType2
import kr.dogfoot.hwpxlib.`object`.content.header_xml.enumtype.LineWidth
import kr.dogfoot.hwpxlib.`object`.content.header_xml.enumtype.UnderlineType
import kr.dogfoot.hwpxlib.`object`.content.header_xml.references.BorderFill as XBorderFill
import kr.dogfoot.hwpxlib.`object`.content.header_xml.references.CharPr
import kr.dogfoot.hwpxlib.`object`.content.header_xml.references.ParaPr
import kr.dogfoot.hwpxlib.`object`.content.header_xml.references.borderfill.Border as XBorder
import kr.dogfoot.hwpxlib.`object`.content.header_xml.references.charpr.ValuesByLanguage
import kr.dogfoot.hwpxlib.`object`.content.header_xml.references.Fontface
import kr.dogfoot.hwpxlib.`object`.content.section_xml.ParaListCore
import kr.dogfoot.hwpxlib.`object`.content.section_xml.enumtype.HorzRelTo
import kr.dogfoot.hwpxlib.`object`.content.section_xml.enumtype.PageDirection
import kr.dogfoot.hwpxlib.`object`.content.section_xml.enumtype.TextWrapMethod
import kr.dogfoot.hwpxlib.`object`.content.section_xml.enumtype.VertRelTo
import kr.dogfoot.hwpxlib.`object`.content.section_xml.enumtype.VerticalAlign2
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.Para
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.T
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.`object`.Picture
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.`object`.Table
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.`object`.shapeobject.ShapeObject
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.secpr.pagepr.PagePr
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.t.FWSpace
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.t.Hyphen
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.t.NBSpace
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.t.NormalText
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.t.LineBreak as XLineBreak
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.t.Tab as XTab

/** Converts an OWPML (.hwpx) document (read by hwpxlib) into the shared [Document] model. */
internal class HwpxConverter(private val hwpx: HWPXFile) {

    private val refList = hwpx.headerXMLFile()?.refList()
    private val fontfaces: Map<Script, Fontface?> = refList?.fontfaces().let { f ->
        mapOf(
            Script.HANGUL to f?.hangulFontface(),
            Script.LATIN to f?.latinFontface(),
            Script.HANJA to f?.hanjaFontface(),
            Script.JAPANESE to f?.japaneseFontface(),
            Script.OTHER to f?.otherFontface(),
            Script.SYMBOL to f?.symbolFontface(),
            Script.USER to f?.userFontface(),
        )
    }

    fun convert(): Document {
        var page = PageSetup.A4
        val sections = hwpx.sectionXMLFileList().items().map { section ->
            // The first run of the first paragraph carries the section properties.
            section.paras().firstOrNull()?.runs()?.firstNotNullOfOrNull { it.secPr()?.pagePr() }
                ?.let { page = it.toPageSetup() }
            Section(page, convertParagraphs(section))
        }
        return Document(
            sections = sections,
            charShapes = refList?.charProperties().byId { it.id() }.mapValues { (_, v) -> v.toModel() },
            paraShapes = refList?.paraProperties().byId { it.id() }.mapValues { (_, v) -> v.toModel() },
            borderFills = refList?.borderFills().byId { it.id() }.mapValues { (_, v) -> v.toModel() },
            binaries = loadBinaries(),
        )
    }

    private fun loadBinaries(): Map<String, ByteArray> =
        hwpx.contentHPFFile()?.manifest()?.items()?.toList().orEmpty()
            .filter { it.hasAttachedFile() }
            .associate { it.id() to BinaryData.decode(it.attachedFile().data()) }

    private fun convertParagraphs(list: ParaListCore): List<Paragraph> = list.paras().map(::convertParagraph)

    private fun convertParagraph(para: Para): Paragraph {
        val inlines = mutableListOf<Inline>()
        for (run in para.runs()) {
            val shape = run.charPrIDRef().toIntId()
            for (item in run.runItems()) {
                when (item) {
                    is T -> convertText(item, item.charPrIDRef()?.toIntId() ?: shape, inlines)
                    is Table -> inlines += convertTable(item)
                    is Picture -> convertPicture(item)?.let { inlines += it }
                    else -> Unit // header/footer, fields, shapes: later phases
                }
            }
        }
        return Paragraph(
            paraShapeId = para.paraPrIDRef().toIntId(),
            inlines = inlines,
            pageBreakBefore = para.pageBreak() == true,
            columnBreakBefore = para.columnBreak() == true,
        )
    }

    private fun convertText(t: T, shape: Int, out: MutableList<Inline>) {
        if (t.isOnlyText) {
            t.onlyText()?.takeIf { it.isNotEmpty() }?.let { out += TextRun(it, shape) }
            return
        }
        val text = StringBuilder()
        fun flush() {
            if (text.isNotEmpty()) { out += TextRun(text.toString(), shape); text.clear() }
        }
        for (item in t.items() ?: return) {
            when (item) {
                is NormalText -> text.append(item.text().orEmpty())
                is XTab -> { flush(); out += Tab(shape) }
                is XLineBreak -> { flush(); out += LineBreak(shape) }
                is NBSpace -> text.append(' ')
                is FWSpace -> text.append(' ')
                is Hyphen -> text.append('-')
                else -> Unit // track-change and markpen markers
            }
        }
        flush()
    }

    private fun convertTable(table: Table): TableObject {
        val cells = table.trs().flatMap { tr ->
            tr.tcs().map { tc ->
                val sub = tc.subList()
                TableCell(
                    row = tc.cellAddr()?.rowAddr()?.toInt() ?: 0,
                    col = tc.cellAddr()?.colAddr()?.toInt() ?: 0,
                    rowSpan = tc.cellSpan()?.rowSpan()?.toInt() ?: 1,
                    colSpan = tc.cellSpan()?.colSpan()?.toInt() ?: 1,
                    width = tc.cellSz()?.width()?.toInt() ?: 0,
                    height = tc.cellSz()?.height()?.toInt() ?: 0,
                    marginLeft = (tc.cellMargin()?.left() ?: table.inMargin()?.left())?.toInt() ?: 0,
                    marginRight = (tc.cellMargin()?.right() ?: table.inMargin()?.right())?.toInt() ?: 0,
                    marginTop = (tc.cellMargin()?.top() ?: table.inMargin()?.top())?.toInt() ?: 0,
                    marginBottom = (tc.cellMargin()?.bottom() ?: table.inMargin()?.bottom())?.toInt() ?: 0,
                    borderFillId = tc.borderFillIDRef().toIntId(),
                    vertAlign = when (sub?.vertAlign()) {
                        VerticalAlign2.CENTER -> VertAlign.CENTER
                        VerticalAlign2.BOTTOM -> VertAlign.BOTTOM
                        else -> VertAlign.TOP
                    },
                    paragraphs = sub?.let(::convertParagraphs).orEmpty(),
                    isHeader = tc.header() == true,
                )
            }
        }
        return TableObject(
            rowCount = table.rowCnt()?.toInt() ?: 0,
            colCount = table.colCnt()?.toInt() ?: 0,
            width = table.sz()?.width()?.toInt() ?: 0,
            height = table.sz()?.height()?.toInt() ?: 0,
            cellSpacing = table.cellSpacing() ?: 0,
            borderFillId = table.borderFillIDRef().toIntId(),
            placement = table.toPlacement(),
            cells = cells,
            repeatHeader = table.repeatHeader() == true,
        )
    }

    private fun convertPicture(pic: Picture): PictureObject? {
        val binId = pic.img()?.binaryItemIDRef() ?: return null
        return PictureObject(
            binId = binId,
            width = pic.sz()?.width()?.toInt() ?: pic.curSz()?.width()?.toInt() ?: 0,
            height = pic.sz()?.height()?.toInt() ?: pic.curSz()?.height()?.toInt() ?: 0,
            placement = pic.toPlacement(),
        )
    }

    private fun ShapeObject<*>.toPlacement(): ObjectPlacement {
        val pos = pos()
        val margin = outMargin()
        return ObjectPlacement(
            treatAsChar = pos?.treatAsChar() ?: true,
            wrap = when (textWrap()) {
                TextWrapMethod.SQUARE, TextWrapMethod.TIGHT, TextWrapMethod.THROUGH -> TextWrap.SQUARE
                TextWrapMethod.BEHIND_TEXT -> TextWrap.BEHIND_TEXT
                TextWrapMethod.IN_FRONT_OF_TEXT -> TextWrap.IN_FRONT_OF_TEXT
                else -> TextWrap.TOP_AND_BOTTOM
            },
            vertRelTo = when (pos?.vertRelTo()) {
                VertRelTo.PAPER -> RelTo.PAPER
                VertRelTo.PAGE -> RelTo.PAGE
                else -> RelTo.PARA
            },
            horzRelTo = when (pos?.horzRelTo()) {
                HorzRelTo.PAPER -> RelTo.PAPER
                HorzRelTo.PAGE -> RelTo.PAGE
                HorzRelTo.COLUMN -> RelTo.COLUMN
                else -> RelTo.PARA
            },
            vertOffset = pos?.vertOffset()?.toInt() ?: 0,
            horzOffset = pos?.horzOffset()?.toInt() ?: 0,
            marginLeft = margin?.left()?.toInt() ?: 0,
            marginRight = margin?.right()?.toInt() ?: 0,
            marginTop = margin?.top()?.toInt() ?: 0,
            marginBottom = margin?.bottom()?.toInt() ?: 0,
        )
    }

    private fun PagePr.toPageSetup(): PageSetup {
        val m = margin()
        return PageSetup(
            width = width() ?: PageSetup.A4.width,
            height = height() ?: PageSetup.A4.height,
            // OWPML: WIDELY = portrait (넓게), NARROWLY = landscape (좁게).
            landscape = landscape() == PageDirection.NARROWLY,
            marginLeft = m?.left() ?: 0, marginRight = m?.right() ?: 0,
            marginTop = m?.top() ?: 0, marginBottom = m?.bottom() ?: 0,
            marginHeader = m?.header() ?: 0, marginFooter = m?.footer() ?: 0,
            marginGutter = m?.gutter() ?: 0,
        )
    }

    private fun CharPr.toModel(): CharShape {
        val fontRefs = fontRef()
        return CharShape(
            fontNames = Script.entries.mapNotNull { script ->
                val id = fontRefs?.get(script) ?: return@mapNotNull null
                fontfaces[script]?.fonts()?.firstOrNull { it.id() == id }?.face()?.let { script to it }
            }.toMap(),
            baseSize = height() ?: 1000,
            relativeSizes = relSz().toIntMap(),
            ratios = ratio().toIntMap(),
            spacings = spacing().toIntMap(),
            bold = bold() != null,
            italic = italic() != null,
            underline = when (underline()?.type()) {
                UnderlineType.BOTTOM -> Underline.BOTTOM
                UnderlineType.TOP -> Underline.TOP
                else -> Underline.NONE
            },
            strikeout = strikeout()?.shape().let { it != null && it != LineType2.NONE },
            superscript = supscript() != null,
            subscript = subscript() != null,
            textColor = parseColor(textColor()) ?: CharShape.BLACK,
            shadeColor = parseColor(shadeColor())?.takeUnless { it == WHITE },
            underlineColor = parseColor(underline()?.color()) ?: CharShape.BLACK,
            strikeoutColor = parseColor(strikeout()?.color()) ?: CharShape.BLACK,
        )
    }

    private fun ParaPr.toModel(): ParaShape {
        // Hangul writes margin and line spacing inside <hp:switch>: the HwpUnitChar case holds true
        // HWPUNIT values, the default branch holds legacy values that are doubled like binary HWP.
        val margin = switched(margin())
        val m = margin?.value
        val mScale = if (margin?.doubled == true) 2 else 1
        val spacingResolved = switched(lineSpacing())
        val spacing = spacingResolved?.value
        val type = when (spacing?.type()) {
            XLineSpacingType.FIXED -> LineSpacingType.FIXED
            XLineSpacingType.BETWEEN_LINES -> LineSpacingType.BETWEEN_LINES
            XLineSpacingType.AT_LEAST -> LineSpacingType.AT_LEAST
            else -> LineSpacingType.PERCENT
        }
        val sScale = if (spacingResolved?.doubled == true && type != LineSpacingType.PERCENT) 2 else 1
        return ParaShape(
            align = when (align()?.horizontal()) {
                HorizontalAlign2.LEFT -> HAlign.LEFT
                HorizontalAlign2.RIGHT -> HAlign.RIGHT
                HorizontalAlign2.CENTER -> HAlign.CENTER
                HorizontalAlign2.DISTRIBUTE -> HAlign.DISTRIBUTE
                HorizontalAlign2.DISTRIBUTE_SPACE -> HAlign.DISTRIBUTE_SPACE
                else -> HAlign.JUSTIFY
            },
            marginLeft = (m?.left()?.value() ?: 0) / mScale,
            marginRight = (m?.right()?.value() ?: 0) / mScale,
            indent = (m?.intent()?.value() ?: 0) / mScale,
            spaceBefore = (m?.prev()?.value() ?: 0) / mScale,
            spaceAfter = (m?.next()?.value() ?: 0) / mScale,
            lineSpacingType = type,
            lineSpacing = (spacing?.value() ?: 160) / sScale,
            borderFillId = border()?.borderFillIDRef().toIntId(),
            keepWithNext = breakSetting()?.keepWithNext() == true,
            pageBreakBefore = breakSetting()?.pageBreakBefore() == true,
        )
    }

    private fun XBorderFill.toModel() = BorderFill(
        left = leftBorder().toModel(),
        right = rightBorder().toModel(),
        top = topBorder().toModel(),
        bottom = bottomBorder().toModel(),
        fillColor = fillBrush()?.winBrush()?.faceColor()?.let(::parseColor)?.takeUnless { it == WHITE },
    )

    private fun XBorder?.toModel(): Border {
        if (this == null) return Border.NONE
        return Border(
            style = when (type()) {
                LineType2.NONE, null -> BorderStyle.NONE
                LineType2.SOLID -> BorderStyle.SOLID
                LineType2.DOT -> BorderStyle.DOT
                LineType2.DASH -> BorderStyle.DASH
                LineType2.DASH_DOT -> BorderStyle.DASH_DOT
                LineType2.DASH_DOT_DOT -> BorderStyle.DASH_DOT_DOT
                LineType2.LONG_DASH -> BorderStyle.LONG_DASH
                LineType2.CIRCLE -> BorderStyle.CIRCLE
                LineType2.DOUBLE_SLIM -> BorderStyle.DOUBLE
                LineType2.SLIM_THICK -> BorderStyle.THIN_THICK
                LineType2.THICK_SLIM -> BorderStyle.THICK_THIN
                LineType2.SLIM_THICK_SLIM -> BorderStyle.THIN_THICK_THIN
            },
            width = width().toHundredthMm(),
            color = parseColor(color()) ?: CharShape.BLACK,
        )
    }

    private class Resolved<T>(val value: T, val doubled: Boolean)

    /** Returns [direct] if present, otherwise the matching child from the object's <hp:switch> blocks. */
    private inline fun <reified C : HWPXObject> SwitchableObject.switched(direct: C?): Resolved<C>? {
        if (direct != null) return Resolved(direct, false)
        for (sw in switchList().orEmpty()) {
            for (case in sw.caseObjects() ?: emptyList()) {
                if (case.requiredNamespace()?.endsWith(HWP_UNIT_CHAR) == true) {
                    case.children()?.filterIsInstance<C>()?.firstOrNull()?.let { return Resolved(it, false) }
                }
            }
            sw.defaultObject()?.children()?.filterIsInstance<C>()?.firstOrNull()?.let { return Resolved(it, true) }
        }
        return null
    }

    private fun LineWidth?.toHundredthMm(): Int =
        this?.name?.removePrefix("MM_")?.replace('_', '.')?.toFloatOrNull()?.let { (it * 100).toInt() } ?: 10

    private fun <V> ValuesByLanguage<V>?.get(script: Script): V? = this?.let {
        when (script) {
            Script.HANGUL -> hangul()
            Script.LATIN -> latin()
            Script.HANJA -> hanja()
            Script.JAPANESE -> japanese()
            Script.OTHER -> other()
            Script.SYMBOL -> symbol()
            Script.USER -> user()
        }
    }

    private fun ValuesByLanguage<Short>?.toIntMap(): Map<Script, Int> =
        Script.entries.mapNotNull { s -> get(s)?.let { s to it.toInt() } }.toMap()

    private fun <T> ObjectList<T>?.byId(id: (T) -> String?): Map<Int, T> =
        this?.items()?.toList().orEmpty().mapNotNull { item -> id(item)?.toIntOrNull()?.let { it to item } }.toMap()

    private fun String?.toIntId(): Int = this?.toIntOrNull() ?: 0

    companion object {
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val HWP_UNIT_CHAR = "HwpUnitChar"

        /** Parses "#RRGGBB" / "#AARRGGBB"; "none" and blanks mean no colour. */
        fun parseColor(value: String?): Int? {
            val hex = value?.trim()?.removePrefix("#") ?: return null
            return when (hex.length) {
                6 -> hex.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
                8 -> hex.toLongOrNull(16)?.toInt()
                else -> null
            }
        }
    }
}
