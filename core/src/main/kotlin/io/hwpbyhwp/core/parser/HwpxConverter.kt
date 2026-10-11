package io.hwpbyhwp.core.parser

import io.hwpbyhwp.core.model.Affine
import io.hwpbyhwp.core.model.ApplyPage
import io.hwpbyhwp.core.model.ArcKind
import io.hwpbyhwp.core.model.Pt
import io.hwpbyhwp.core.model.ShapeKind
import io.hwpbyhwp.core.model.ShapeObject
import io.hwpbyhwp.core.model.Stroke
import io.hwpbyhwp.core.model.TextBox
import io.hwpbyhwp.core.model.AutoNumber
import io.hwpbyhwp.core.model.AutoNumberKind
import io.hwpbyhwp.core.model.Border
import io.hwpbyhwp.core.model.BulletDef
import io.hwpbyhwp.core.model.HeadType
import io.hwpbyhwp.core.model.NumberShape
import io.hwpbyhwp.core.model.NumberingDef
import io.hwpbyhwp.core.model.NumberingLevel
import io.hwpbyhwp.core.model.ColumnDefine
import io.hwpbyhwp.core.model.EquationObject
import io.hwpbyhwp.core.model.HeaderFooter
import io.hwpbyhwp.core.model.NewPageNumber
import io.hwpbyhwp.core.model.PageHide
import io.hwpbyhwp.core.model.Note
import io.hwpbyhwp.core.model.NoteKind
import io.hwpbyhwp.core.model.HeaderFooterKind
import io.hwpbyhwp.core.model.PageNumberPlace
import io.hwpbyhwp.core.model.PageNumberPosition
import io.hwpbyhwp.core.model.BorderFill
import io.hwpbyhwp.core.model.BorderStyle
import io.hwpbyhwp.core.model.CharShape
import io.hwpbyhwp.core.model.Document
import io.hwpbyhwp.core.model.HAlign
import io.hwpbyhwp.core.model.Inline
import io.hwpbyhwp.core.model.LineBreak
import io.hwpbyhwp.core.model.LineSeg
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
import io.hwpbyhwp.core.model.TabDef
import io.hwpbyhwp.core.model.TabStop
import io.hwpbyhwp.core.model.TabType
import io.hwpbyhwp.core.model.TableCell
import io.hwpbyhwp.core.model.TableBreak
import io.hwpbyhwp.core.model.TableObject
import io.hwpbyhwp.core.model.TextRun
import io.hwpbyhwp.core.model.TextWrap
import io.hwpbyhwp.core.model.Underline
import io.hwpbyhwp.core.model.VertAlign
import kr.dogfoot.hwpxlib.`object`.content.section_xml.enumtype.TablePageBreak
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
import kr.dogfoot.hwpxlib.`object`.content.header_xml.references.TabPr
import kr.dogfoot.hwpxlib.`object`.content.header_xml.references.tabpr.TabItem
import kr.dogfoot.hwpxlib.`object`.content.header_xml.enumtype.TabItemType
import kr.dogfoot.hwpxlib.`object`.content.header_xml.references.borderfill.Border as XBorder
import kr.dogfoot.hwpxlib.`object`.content.header_xml.references.charpr.ValuesByLanguage
import kr.dogfoot.hwpxlib.`object`.content.header_xml.references.Fontface
import kr.dogfoot.hwpxlib.`object`.content.section_xml.ParaListCore
import kr.dogfoot.hwpxlib.`object`.content.section_xml.enumtype.HorzRelTo
import kr.dogfoot.hwpxlib.`object`.content.section_xml.enumtype.PageDirection
import kr.dogfoot.hwpxlib.`object`.content.section_xml.enumtype.TextWrapMethod
import kr.dogfoot.hwpxlib.`object`.content.section_xml.enumtype.VertRelTo
import kr.dogfoot.hwpxlib.`object`.content.section_xml.enumtype.VerticalAlign2
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.Ctrl
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.CtrlItem
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.ctrl.AutoNum
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.ctrl.ColPr
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.ctrl.EndNote
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.ctrl.FootNote
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.ctrl.Footer
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.ctrl.Header
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.ctrl.NewNum
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.ctrl.PageHiding
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.ctrl.PageNum
import kr.dogfoot.hwpxlib.`object`.content.section_xml.enumtype.ApplyPageType
import kr.dogfoot.hwpxlib.`object`.content.section_xml.enumtype.NumType
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.Para
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.T
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.`object`.Picture
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.`object`.Container
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.`object`.Equation
import kr.dogfoot.hwpxlib.`object`.common.baseobject.Point as XPoint
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.`object`.Arc as XArc
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.`object`.Curve as XCurve
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.`object`.Ellipse as XEllipse
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.`object`.Line as XLine
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.`object`.Polygon as XPolygon
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.`object`.Rectangle as XRectangle
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.`object`.drawingobject.DrawingObject
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.`object`.shapecomponent.ShapeComponent
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.`object`.shapecomponent.RenderingInfo as XRenderingInfo
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.`object`.Table
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.`object`.shapeobject.ShapeObject as XShapeObject
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.secpr.pagepr.PagePr
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.t.FWSpace
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.t.Hyphen
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.t.NBSpace
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.t.NormalText
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.t.TitleMark
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
        var outline = 1
        val sections = hwpx.sectionXMLFileList().items().map { section ->
            // The first run of the first paragraph carries the section properties.
            section.paras().firstOrNull()?.runs()?.firstNotNullOfOrNull { it.secPr()?.pagePr() }
                ?.let { page = it.toPageSetup() }
            section.paras().firstOrNull()?.runs()?.firstNotNullOfOrNull { it.secPr()?.outlineShapeIDRef()?.toIntOrNull() }
                ?.let { outline = it }
            val pageStart = section.paras().firstOrNull()?.runs()?.firstNotNullOfOrNull { it.secPr()?.startNum()?.page() } ?: 0
            Section(page, convertParagraphs(section), outline, pageStart)
        }
        return Document(
            sections = sections,
            charShapes = refList?.charProperties().byId { it.id() }.mapValues { (_, v) -> v.toModel() },
            paraShapes = refList?.paraProperties().byId { it.id() }.mapValues { (_, v) -> v.toModel() },
            borderFills = refList?.borderFills().byId { it.id() }.mapValues { (_, v) -> v.toModel() },
            binaries = loadBinaries(),
            tabDefs = refList?.tabProperties().byId { it.id() }.mapValues { (_, t) -> t.toModel() },
            numberings = refList?.numberings().byId { it.id() }.mapValues { (_, n) ->
                NumberingDef(
                    levels = n.paraHeads().sortedBy { it.level() ?: 0 }.map { h ->
                        NumberingLevel(
                            format = h.text().orEmpty(),
                            shape = h.numFormat()?.name.toShape(),
                            start = h.start() ?: 1,
                            charShapeId = h.charPrIDRef()?.toLongOrNull()?.takeIf { it in 0 until 0xFFFFFFFFL }?.toInt(),
                        )
                    },
                    start = n.start() ?: 1,
                )
            },
            bullets = refList?.bullets().byId { it.id() }.mapValues { (_, b) ->
                BulletDef(b._char().orEmpty(), b.paraHead()?.charPrIDRef()?.toLongOrNull()?.takeIf { it in 0 until 0xFFFFFFFFL }?.toInt())
            },
        )
    }

    private fun String?.toShape(): NumberShape = when (this) {
        "CIRCLED_DIGIT" -> NumberShape.CIRCLED_DIGIT
        "ROMAN_CAPITAL" -> NumberShape.ROMAN_UPPER
        "ROMAN_SMALL" -> NumberShape.ROMAN_LOWER
        "LATIN_CAPITAL" -> NumberShape.LATIN_UPPER
        "LATIN_SMALL" -> NumberShape.LATIN_LOWER
        "CIRCLED_LATIN_CAPTION" -> NumberShape.CIRCLED_LATIN_UPPER
        "CIRCLED_LATIN_SMALL" -> NumberShape.CIRCLED_LATIN_LOWER
        "HANGUL_SYLLABLE" -> NumberShape.HANGUL_SYLLABLE
        "CIRCLED_HANGUL_SYLLABLE" -> NumberShape.CIRCLED_HANGUL_SYLLABLE
        "HANGUL_JAMO" -> NumberShape.HANGUL_JAMO
        "CIRCLED_HANGUL_JAMO" -> NumberShape.CIRCLED_HANGUL_JAMO
        "HANGUL_PHONETIC" -> NumberShape.HANGUL_NUMBER
        "IDEOGRAPH" -> NumberShape.HANJA_NUMBER
        "CIRCLED_IDEOGRAPH" -> NumberShape.CIRCLED_HANJA_NUMBER
        else -> NumberShape.DIGIT
    }

    private fun loadBinaries(): Map<String, ByteArray> =
        hwpx.contentHPFFile()?.manifest()?.items()?.toList().orEmpty()
            .mapNotNull { item ->
                val data = item.attachedFile()?.data() ?: return@mapNotNull null
                item.id() to BinaryData.decode(data)
            }
            .toMap()

    private fun convertParagraphs(list: ParaListCore): List<Paragraph> = list.paras().map(::convertParagraph)

    private fun convertParagraph(para: Para): Paragraph {
        val inlines = mutableListOf<Inline>()
        // Positions follow binary HWP so that line segments (textpos) line up: characters count 1,
        // controls and objects 8, line breaks 1.
        var position = 0
        for (run in para.runs()) {
            val shape = run.charPrIDRef().toIntId()
            if (run.secPr() != null) position += CONTROL_SIZE
            for (item in run.runItems()) {
                when (item) {
                    is T -> position = convertText(item, item.charPrIDRef()?.toIntId() ?: shape, position, inlines)
                    is Table -> { inlines += convertTable(item).copy(start = position); position += CONTROL_SIZE }
                    is Picture -> { convertPicture(item)?.let { inlines += it.copy(start = position) }; position += CONTROL_SIZE }
                    is Ctrl -> for (ctrl in item.ctrlItems() ?: emptyList()) {
                        convertCtrl(ctrl, shape, position)?.let { inlines += it }
                        position += CONTROL_SIZE
                    }
                    is Equation -> {
                        inlines += EquationObject(
                            script = item.script()?.text().orEmpty(),
                            fontSize = item.baseUnit() ?: 1000,
                            color = parseColor(item.textColor()) ?: CharShape.BLACK,
                            width = item.sz()?.width()?.toInt() ?: 0, height = item.sz()?.height()?.toInt() ?: 0,
                            baselinePercent = item.baseLine() ?: 85,
                            placement = item.toPlacement(),
                            start = position,
                        )
                        position += CONTROL_SIZE
                    }
                    is ShapeComponent<*> -> { convertShape(item)?.let { inlines += it.copy(start = position) }; position += CONTROL_SIZE }
                    else -> position += CONTROL_SIZE // equations, form controls, OLE
                }
            }
        }
        return Paragraph(
            paraShapeId = para.paraPrIDRef().toIntId(),
            inlines = inlines,
            pageBreakBefore = para.pageBreak() == true,
            columnBreakBefore = para.columnBreak() == true,
            lineSegs = para.lineSegArray()?.items()?.map {
                LineSeg(
                    textStart = it.textpos() ?: 0, vertPos = it.vertpos() ?: 0,
                    height = it.vertsize() ?: 0, textHeight = it.textheight() ?: 0,
                    baseline = it.baseline() ?: 0, spacing = it.spacing() ?: 0,
                    horzPos = it.horzpos() ?: 0, width = it.horzsize() ?: 0, flags = it.flags() ?: 0,
                )
            }.orEmpty(),
            // Binary HWP counts the paragraph-end character too.
            length = position + 1,
        )
    }

    private fun convertCtrl(ctrl: CtrlItem, shape: Int, position: Int): Inline? = when (ctrl) {
        is ColPr -> {
            val sizes = ctrl.colSzs()?.toList().orEmpty()
            ColumnDefine(
                count = (ctrl.colCount() ?: 1).coerceAtLeast(1),
                gap = ctrl.sameGap() ?: 0,
                widths = if (ctrl.sameSz() == false) sizes.map { it.width() ?: 0 } else emptyList(),
                gaps = if (ctrl.sameSz() == false) sizes.map { it.gap() ?: 0 } else emptyList(),
                start = position,
            )
        }
        is Header -> HeaderFooter(HeaderFooterKind.HEADER, ctrl.applyPageType().toModel(), ctrl.subList()?.let(::convertParagraphs).orEmpty(), position)
        is Footer -> HeaderFooter(HeaderFooterKind.FOOTER, ctrl.applyPageType().toModel(), ctrl.subList()?.let(::convertParagraphs).orEmpty(), position)
        is PageNum -> PageNumberPosition(
            place = PageNumberPlace.entries.getOrNull(ctrl.pos()?.ordinal ?: 0) ?: PageNumberPlace.NONE,
            prefix = ctrl.sideChar().orEmpty().trim(),
            suffix = ctrl.sideChar().orEmpty().trim(),
            start = position,
        )
        is AutoNum -> AutoNumber(
            when (ctrl.numType()) {
                NumType.PAGE -> AutoNumberKind.PAGE
                NumType.TOTAL_PAGE -> AutoNumberKind.TOTAL_PAGES
                NumType.FOOTNOTE -> AutoNumberKind.FOOTNOTE
                NumType.ENDNOTE -> AutoNumberKind.ENDNOTE
                else -> AutoNumberKind.OTHER
            },
            shape, position,
        )
        is NewNum -> if (ctrl.numType() == NumType.PAGE) NewPageNumber(ctrl.num() ?: 1, position) else null
        is PageHiding -> PageHide(
            ctrl.hideHeader() == true, ctrl.hideFooter() == true, ctrl.hidePageNum() == true,
            ctrl.hideBorder() == true, ctrl.hideFill() == true, position,
        )
        is FootNote -> Note(NoteKind.FOOTNOTE, ctrl.number() ?: 0, ctrl.suffixChar().orEmpty().ifEmpty { ")" },
            ctrl.subList()?.let(::convertParagraphs).orEmpty(), shape, position)
        is EndNote -> Note(NoteKind.ENDNOTE, ctrl.number() ?: 0, ctrl.suffixChar().orEmpty().ifEmpty { ")" },
            ctrl.subList()?.let(::convertParagraphs).orEmpty(), shape, position)
        else -> null
    }

    /** Drawing objects; children of a group carry their own transform into the group's space. */
    private fun convertShape(c: ShapeComponent<*>): ShapeObject? {
        fun pt(p: XPoint?) = Pt(p?.x()?.toDouble() ?: 0.0, p?.y()?.toDouble() ?: 0.0)
        val (kind, points) = when (c) {
            is XLine -> ShapeKind.LINE to listOf(pt(c.startPt()), pt(c.endPt()))
            is XRectangle -> ShapeKind.RECTANGLE to listOf(pt(c.pt0()), pt(c.pt1()), pt(c.pt2()), pt(c.pt3()))
            is XEllipse -> ShapeKind.ELLIPSE to listOf(pt(c.center()), pt(c.ax1()), pt(c.ax2()))
            is XArc -> ShapeKind.ARC to listOf(pt(c.center()), pt(c.ax1()), pt(c.ax2()))
            is XPolygon -> ShapeKind.POLYGON to c.pts().map(::pt)
            is XCurve -> ShapeKind.CURVE to c.segs().flatMap { seg ->
                listOf(Pt((seg.x1() ?: 0).toDouble(), (seg.y1() ?: 0).toDouble()), Pt((seg.x2() ?: 0).toDouble(), (seg.y2() ?: 0).toDouble()))
            }.distinct()
            is Container -> ShapeKind.CONTAINER to emptyList()
            is Picture -> ShapeKind.PICTURE to listOf(Pt(0.0, 0.0), Pt((c.orgSz()?.width() ?: 0L).toDouble(), (c.orgSz()?.height() ?: 0L).toDouble()))
            else -> return null
        }
        val drawing = c as? DrawingObject<*>
        val lineShape = drawing?.lineShape()
        val drawText = drawing?.drawText()
        val margin = drawText?.textMargin()
        return ShapeObject(
            kind = kind,
            width = c.sz()?.width()?.toInt() ?: c.curSz()?.width()?.toInt() ?: 0,
            height = c.sz()?.height()?.toInt() ?: c.curSz()?.height()?.toInt() ?: 0,
            placement = c.toPlacement(),
            transform = c.renderingInfo().toAffine(),
            points = points,
            stroke = lineShape?.takeIf { it.style() != null && it.style() != LineType2.NONE }?.let {
                Stroke(parseColor(it.color()) ?: CharShape.BLACK, it.width() ?: 0, it.style().toBorderStyle())
            },
            fill = drawing?.fillBrush()?.winBrush()?.faceColor()?.let(::parseColor),
            roundRatio = (c as? XRectangle)?.ratio()?.toInt() ?: 0,
            arcKind = when ((c as? XArc)?.type()?.name) {
                "PIE" -> ArcKind.PIE
                "CHORD" -> ArcKind.CHORD
                else -> ArcKind.ARC
            },
            text = drawText?.subList()?.let { sub ->
                TextBox(
                    paragraphs = convertParagraphs(sub),
                    marginLeft = margin?.left()?.toInt() ?: 0, marginRight = margin?.right()?.toInt() ?: 0,
                    marginTop = margin?.top()?.toInt() ?: 0, marginBottom = margin?.bottom()?.toInt() ?: 0,
                    vertAlign = when (sub.vertAlign()) {
                        VerticalAlign2.CENTER -> VertAlign.CENTER
                        VerticalAlign2.BOTTOM -> VertAlign.BOTTOM
                        else -> VertAlign.TOP
                    },
                )
            },
            children = (c as? Container)?.children()?.mapNotNull { convertShape(it) }.orEmpty(),
            binId = (c as? Picture)?.img()?.binaryItemIDRef(),
            originalWidth = c.orgSz()?.width()?.toInt() ?: 0,
            originalHeight = c.orgSz()?.height()?.toInt() ?: 0,
        )
    }

    /** Translation first, then scale/rotation pairs, in file order. */
    private fun XRenderingInfo?.toAffine(): Affine {
        val ms = this?.matrices()?.toList().orEmpty()
        return ms.fold(Affine.IDENTITY) { acc, m ->
            acc * Affine(
                (m.e1() ?: 1f).toDouble(), (m.e2() ?: 0f).toDouble(), (m.e3() ?: 0f).toDouble(),
                (m.e4() ?: 0f).toDouble(), (m.e5() ?: 1f).toDouble(), (m.e6() ?: 0f).toDouble(),
            )
        }
    }

    private fun LineType2?.toBorderStyleOrNone(): BorderStyle =
        if (this == null || this == LineType2.NONE) BorderStyle.NONE else toBorderStyle()

    private fun TabItemType?.toTabType(): TabType? = when (this) {
        TabItemType.LEFT -> TabType.LEFT
        TabItemType.RIGHT -> TabType.RIGHT
        TabItemType.CENTER -> TabType.CENTER
        TabItemType.DECIMAL -> TabType.DECIMAL
        null -> null
    }

    /** Tab stops sit inside <hp:switch> like margins: the HwpUnitChar case is exact, the rest doubled. */
    private fun TabPr.toModel(): TabDef {
        val exact = switchList().orEmpty().flatMap { sw ->
            sw.caseObjects()?.filter { it.requiredNamespace()?.endsWith(HWP_UNIT_CHAR) == true }
                ?.flatMap { c -> c.children()?.filterIsInstance<TabItem>().orEmpty() }.orEmpty()
        }
        val items = exact.map { it to 1 }.ifEmpty {
            (tabItems()?.toList().orEmpty() + switchList().orEmpty().flatMap { sw ->
                sw.defaultObject()?.children()?.filterIsInstance<TabItem>().orEmpty()
            }).map { it to 2 }
        }
        return TabDef(
            stops = items.map { (t, k) -> TabStop((t.pos() ?: 0) / k, t.type().toTabType() ?: TabType.LEFT, t.leader().toBorderStyleOrNone()) },
            autoRightEnd = autoTabRight() == true,
        )
    }

    private fun LineType2?.toBorderStyle(): BorderStyle = when (this) {
        LineType2.DOT -> BorderStyle.DOT
        LineType2.DASH -> BorderStyle.DASH
        LineType2.DASH_DOT -> BorderStyle.DASH_DOT
        LineType2.DASH_DOT_DOT -> BorderStyle.DASH_DOT_DOT
        LineType2.LONG_DASH -> BorderStyle.LONG_DASH
        LineType2.CIRCLE -> BorderStyle.CIRCLE
        LineType2.DOUBLE_SLIM -> BorderStyle.DOUBLE
        else -> BorderStyle.SOLID
    }

    private fun ApplyPageType?.toModel(): ApplyPage = when (this) {
        ApplyPageType.EVEN -> ApplyPage.EVEN
        ApplyPageType.ODD -> ApplyPage.ODD
        else -> ApplyPage.BOTH
    }

    /** Appends the text item's inlines and returns the position after it. */
    private fun convertText(t: T, shape: Int, startPos: Int, out: MutableList<Inline>): Int {
        var position = startPos
        if (t.isOnlyText) {
            val s = t.onlyText().orEmpty()
            if (s.isNotEmpty()) out += TextRun(s, shape, position)
            return position + s.length
        }
        val text = StringBuilder()
        var textStart = position
        fun flush() {
            if (text.isNotEmpty()) { out += TextRun(text.toString(), shape, textStart); text.clear() }
        }
        fun append(s: String) {
            if (text.isEmpty()) textStart = position
            text.append(s)
            position += s.length
        }
        for (item in t.items() ?: return position) {
            when (item) {
                is NormalText -> append(item.text().orEmpty())
                is XTab -> {
                    flush()
                    out += Tab(shape, position, item.width()?.takeIf { it > 0 }, item.leader().toBorderStyleOrNone(), item.type().toTabType())
                    position += CONTROL_SIZE
                }
                is XLineBreak -> { flush(); out += LineBreak(shape, position); position += 1 }
                is NBSpace -> append("\u00A0")
                is FWSpace -> append(" ")
                is Hyphen -> append("-")
                is TitleMark -> position += CONTROL_SIZE
                else -> Unit // track-change and markpen markers occupy no characters
            }
        }
        flush()
        return position
    }

    private fun convertTable(table: Table): TableObject {
        val cells = table.trs().flatMap { tr ->
            tr.tcs().map { tc ->
                val sub = tc.subList()
                val margins = if (tc.hasMargin() == true) tc.cellMargin() else null
                TableCell(
                    row = tc.cellAddr()?.rowAddr()?.toInt() ?: 0,
                    col = tc.cellAddr()?.colAddr()?.toInt() ?: 0,
                    rowSpan = tc.cellSpan()?.rowSpan()?.toInt() ?: 1,
                    colSpan = tc.cellSpan()?.colSpan()?.toInt() ?: 1,
                    width = tc.cellSz()?.width()?.toInt() ?: 0,
                    height = tc.cellSz()?.height()?.toInt() ?: 0,
                    // Cells use their own margins only when hasMargin is set; otherwise the table's.
                    marginLeft = (margins?.left() ?: table.inMargin()?.left())?.toInt() ?: 0,
                    marginRight = (margins?.right() ?: table.inMargin()?.right())?.toInt() ?: 0,
                    marginTop = (margins?.top() ?: table.inMargin()?.top())?.toInt() ?: 0,
                    marginBottom = (margins?.bottom() ?: table.inMargin()?.bottom())?.toInt() ?: 0,
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
            pageBreak = when (table.pageBreak()) {
                TablePageBreak.NONE -> TableBreak.NONE
                TablePageBreak.TABLE -> TableBreak.CELLS
                else -> TableBreak.ROWS
            },
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

    private fun XShapeObject<*>.toPlacement(): ObjectPlacement {
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
        // HWPUNIT values; the default branch, and plain elements outside any switch, hold legacy
        // values that are doubled like binary HWP.
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
            borderOffsetLeft = border()?.offsetLeft() ?: 0,
            borderOffsetRight = border()?.offsetRight() ?: 0,
            borderOffsetTop = border()?.offsetTop() ?: 0,
            borderOffsetBottom = border()?.offsetBottom() ?: 0,
            borderConnect = border()?.connect() == true,
            borderIgnoreMargin = border()?.ignoreMargin() == true,
            keepWithNext = breakSetting()?.keepWithNext() == true,
            pageBreakBefore = breakSetting()?.pageBreakBefore() == true,
            headType = when (heading()?.type()?.name) {
                "OUTLINE" -> HeadType.OUTLINE
                "NUMBER" -> HeadType.NUMBER
                "BULLET" -> HeadType.BULLET
                else -> HeadType.NONE
            },
            headId = heading()?.idRef().toIntId(),
            tabDefId = tabPrIDRef().toIntId(),
            level = heading()?.level()?.toInt() ?: 0,
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

    /**
     * Prefers the HwpUnitChar case of the object's <hp:switch> blocks, then [direct] or the default
     * branch, both of which carry legacy doubled values.
     */
    private inline fun <reified C : HWPXObject> SwitchableObject.switched(direct: C?): Resolved<C>? {
        for (sw in switchList().orEmpty()) {
            for (case in sw.caseObjects() ?: emptyList()) {
                if (case.requiredNamespace()?.endsWith(HWP_UNIT_CHAR) == true) {
                    case.children()?.filterIsInstance<C>()?.firstOrNull()?.let { return Resolved(it, false) }
                }
            }
        }
        if (direct != null) return Resolved(direct, true)
        for (sw in switchList().orEmpty()) {
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
        private const val CONTROL_SIZE = 8
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
