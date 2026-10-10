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
import io.hwpbyhwp.core.model.ColumnDefine
import io.hwpbyhwp.core.model.HeaderFooter
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
import io.hwpbyhwp.core.model.TableCell
import io.hwpbyhwp.core.model.TableObject
import io.hwpbyhwp.core.model.TextRun
import io.hwpbyhwp.core.model.TextWrap
import io.hwpbyhwp.core.model.Underline
import io.hwpbyhwp.core.model.VertAlign
import kr.dogfoot.hwplib.`object`.HWPFile
import kr.dogfoot.hwplib.`object`.bodytext.control.Control
import kr.dogfoot.hwplib.`object`.bodytext.control.ControlAutoNumber
import kr.dogfoot.hwplib.`object`.bodytext.control.ControlColumnDefine
import kr.dogfoot.hwplib.`object`.bodytext.control.ControlFooter
import kr.dogfoot.hwplib.`object`.bodytext.control.ControlHeader
import kr.dogfoot.hwplib.`object`.bodytext.control.ControlPageNumberPosition
import kr.dogfoot.hwplib.`object`.bodytext.control.ctrlheader.autonumber.NumberSort
import kr.dogfoot.hwplib.`object`.bodytext.control.ctrlheader.header.HeaderFooterApplyPage
import kr.dogfoot.hwplib.`object`.bodytext.control.ControlSectionDefine
import kr.dogfoot.hwplib.`object`.bodytext.control.ControlTable
import kr.dogfoot.hwplib.`object`.bodytext.control.ControlType
import kr.dogfoot.hwplib.`object`.bodytext.control.ctrlheader.CtrlHeaderGso
import kr.dogfoot.hwplib.`object`.bodytext.control.ctrlheader.gso.HorzRelTo
import kr.dogfoot.hwplib.`object`.bodytext.control.ctrlheader.gso.TextFlowMethod
import kr.dogfoot.hwplib.`object`.bodytext.control.ctrlheader.gso.VertRelTo
import kr.dogfoot.hwplib.`object`.bodytext.control.gso.ControlArc
import kr.dogfoot.hwplib.`object`.bodytext.control.gso.ControlContainer
import kr.dogfoot.hwplib.`object`.bodytext.control.gso.ControlCurve
import kr.dogfoot.hwplib.`object`.bodytext.control.gso.ControlEllipse
import kr.dogfoot.hwplib.`object`.bodytext.control.gso.ControlLine
import kr.dogfoot.hwplib.`object`.bodytext.control.gso.ControlPicture
import kr.dogfoot.hwplib.`object`.bodytext.control.gso.ControlPolygon
import kr.dogfoot.hwplib.`object`.bodytext.control.gso.ControlRectangle
import kr.dogfoot.hwplib.`object`.bodytext.control.gso.shapecomponent.ShapeComponentNormal
import kr.dogfoot.hwplib.`object`.bodytext.control.gso.shapecomponent.lineinfo.LineType
import kr.dogfoot.hwplib.`object`.bodytext.control.gso.shapecomponent.renderingnfo.Matrix
import kr.dogfoot.hwplib.`object`.bodytext.control.gso.shapecomponent.renderingnfo.RenderingInfo
import kr.dogfoot.hwplib.`object`.bodytext.control.gso.shapecomponenteach.arc.ArcType
import kr.dogfoot.hwplib.`object`.bodytext.control.gso.GsoControl
import kr.dogfoot.hwplib.`object`.bodytext.control.gso.GsoControlType
import kr.dogfoot.hwplib.`object`.bodytext.control.gso.textbox.TextVerticalAlignment
import kr.dogfoot.hwplib.`object`.bodytext.control.sectiondefine.PageDef
import kr.dogfoot.hwplib.`object`.bodytext.control.sectiondefine.PaperDirection
import kr.dogfoot.hwplib.`object`.bodytext.paragraph.Paragraph as HwpParagraph
import kr.dogfoot.hwplib.`object`.bodytext.paragraph.text.HWPCharNormal
import kr.dogfoot.hwplib.`object`.bodytext.paragraph.text.HWPCharType
import kr.dogfoot.hwplib.`object`.docinfo.FaceName
import kr.dogfoot.hwplib.`object`.docinfo.borderfill.BorderThickness
import kr.dogfoot.hwplib.`object`.docinfo.borderfill.BorderType
import kr.dogfoot.hwplib.`object`.docinfo.borderfill.EachBorder
import kr.dogfoot.hwplib.`object`.docinfo.charshape.UnderLineSort
import kr.dogfoot.hwplib.`object`.docinfo.parashape.Alignment
import kr.dogfoot.hwplib.`object`.docinfo.parashape.LineSpaceSort
import kr.dogfoot.hwplib.`object`.etc.Color4Byte
import kr.dogfoot.hwplib.`object`.docinfo.CharShape as HwpCharShape
import kr.dogfoot.hwplib.`object`.docinfo.ParaShape as HwpParaShape
import kr.dogfoot.hwplib.`object`.docinfo.BorderFill as HwpBorderFill

/** Converts a binary HWP 5.x document (read by hwplib) into the shared [Document] model. */
internal class HwpConverter(private val hwp: HWPFile) {

    private val docInfo = hwp.docInfo
    private val faceNameLists: Map<Script, List<FaceName>> = mapOf(
        Script.HANGUL to docInfo.hangulFaceNameList,
        Script.LATIN to docInfo.englishFaceNameList,
        Script.HANJA to docInfo.hanjaFaceNameList,
        Script.JAPANESE to docInfo.japaneseFaceNameList,
        Script.OTHER to docInfo.etcFaceNameList,
        Script.SYMBOL to docInfo.symbolFaceNameList,
        Script.USER to docInfo.userFaceNameList,
    )
    private val binaries = LinkedHashMap<String, ByteArray>()

    fun convert(): Document {
        loadBinaries()
        var page = PageSetup.A4
        val sections = hwp.bodyText.sectionList.map { section ->
            val paragraphs = section.paragraphs.orEmpty().toList()
            // The section definition lives in the first paragraph; it carries over to later sections that lack one.
            paragraphs.firstOrNull()?.controlList.orEmpty()
                .filterIsInstance<ControlSectionDefine>().firstOrNull()
                ?.let { page = it.pageDef.toPageSetup() }
            Section(page, paragraphs.map(::convertParagraph))
        }
        return Document(
            sections = sections,
            charShapes = docInfo.charShapeList.withIndex().associate { (i, cs) -> i to cs.toModel() },
            paraShapes = docInfo.paraShapeList.withIndex().associate { (i, ps) -> i to ps.toModel() },
            // Border/fill ids are 1-based in HWP.
            borderFills = docInfo.borderFillList.withIndex().associate { (i, bf) -> i + 1 to bf.toModel() },
            binaries = binaries,
        )
    }

    private fun loadBinaries() {
        val embedded = hwp.binData.embeddedBinaryDataList.associateBy { it.name.substringBefore('.').uppercase() }
        docInfo.binDataList.forEachIndexed { index, bin ->
            // Pictures reference bin data by 1-based index into DocInfo; storage names are BIN%04X.ext.
            val storageName = "BIN%04X".format(bin.binDataID)
            val data = embedded[storageName]?.data ?: return@forEachIndexed
            binaries[(index + 1).toString()] = BinaryData.decode(data)
        }
    }

    private fun convertParagraph(p: HwpParagraph): Paragraph {
        val inlines = mutableListOf<Inline>()
        val shapeChanges = p.charShape?.positonShapeIdPairList.orEmpty()
            .map { it.position.toInt() to it.shapeId.toInt() }
        val controls = p.controlList.orEmpty()
        var controlIndex = 0
        var position = 0
        var shapeIndex = 0
        var currentShape = shapeChanges.firstOrNull()?.second ?: 0
        val text = StringBuilder()
        var textStart = 0

        fun flushText() {
            if (text.isNotEmpty()) {
                inlines += TextRun(text.toString(), currentShape, textStart)
                text.clear()
            }
        }

        fun appendText(s: String) {
            if (text.isEmpty()) textStart = position
            text.append(s)
        }

        for (ch in p.text?.charList.orEmpty()) {
            while (shapeIndex + 1 < shapeChanges.size && shapeChanges[shapeIndex + 1].first <= position) {
                flushText()
                shapeIndex++
                currentShape = shapeChanges[shapeIndex].second
            }
            when (ch.type) {
                HWPCharType.Normal -> appendText((ch as HWPCharNormal).ch)
                HWPCharType.ControlChar -> when (ch.code) {
                    10 -> { flushText(); inlines += LineBreak(currentShape, position) }
                    24 -> appendText("-")
                    30 -> appendText("\u00A0")
                    31 -> appendText(" ")
                    else -> Unit // 13 = paragraph end
                }
                HWPCharType.ControlInline -> if (ch.code == 9) { flushText(); inlines += Tab(currentShape, position) }
                HWPCharType.ControlExtend -> {
                    // Extended control characters map one-to-one, in order, onto the paragraph's control list.
                    val control = controls.getOrNull(controlIndex++)
                    convertControl(control, position, currentShape)?.let { flushText(); inlines += it }
                }
                else -> Unit
            }
            position += ch.charSize
        }
        flushText()

        val divide = p.header.divideSort
        return Paragraph(
            paraShapeId = p.header.paraShapeId,
            inlines = inlines,
            pageBreakBefore = divide.isDividePage,
            columnBreakBefore = divide.isDivideColumn,
            lineSegs = p.lineSeg?.lineSegItemList.orEmpty().map {
                LineSeg(
                    textStart = it.textStartPosition.toInt(), vertPos = it.lineVerticalPosition,
                    height = it.lineHeight, textHeight = it.textPartHeight,
                    baseline = it.distanceBaseLineToLineVerticalPosition, spacing = it.lineSpace,
                    horzPos = it.startPositionFromColumn, width = it.segmentWidth, flags = it.tag.value.toInt(),
                )
            },
            length = position,
        )
    }

    private fun convertControl(control: Control?, position: Int, charShape: Int): Inline? = when {
        control == null -> null
        control is ControlColumnDefine -> control.header.let { h ->
            ColumnDefine(
                count = h.property.columnCount.toInt().coerceAtLeast(1),
                gap = h.gapBetweenColumn,
                widths = if (h.property.isSameWidth) emptyList() else h.columnInfoList.map { it.width },
                gaps = if (h.property.isSameWidth) emptyList() else h.columnInfoList.map { it.gap },
                start = position,
            )
        }
        control is ControlHeader -> HeaderFooter(
            HeaderFooterKind.HEADER, control.header.applyPage.toModel(),
            control.paragraphList.paragraphs.orEmpty().map(::convertParagraph), position,
        )
        control is ControlFooter -> HeaderFooter(
            HeaderFooterKind.FOOTER, control.header.applyPage.toModel(),
            control.paragraphList.paragraphs.orEmpty().map(::convertParagraph), position,
        )
        control is ControlPageNumberPosition -> control.header.let { h ->
            PageNumberPosition(
                place = PageNumberPlace.entries.getOrNull(h.property.numberPosition.ordinal) ?: PageNumberPlace.NONE,
                prefix = h.beforeDecorationLetter?.toUTF16LEString().orEmpty().trim('\u0000'),
                suffix = h.afterDecorationLetter?.toUTF16LEString().orEmpty().trim('\u0000'),
                start = position,
            )
        }
        control is ControlAutoNumber -> AutoNumber(
            if (control.header.property.numberSort == NumberSort.Page) AutoNumberKind.PAGE else AutoNumberKind.OTHER,
            charShape, position,
        )
        control.type == ControlType.Table -> convertTable(control as ControlTable).copy(start = position)
        control is GsoControl && control.gsoType == GsoControlType.Picture ->
            convertPicture(control as ControlPicture).copy(start = position)
        control is GsoControl -> convertShape(control)?.copy(start = position)
        else -> null
    }

    private fun convertTable(t: ControlTable): TableObject {
        val table = t.table
        val cells = t.rowList.flatMap { row ->
            row.cellList.map { cell ->
                val h = cell.listHeader
                TableCell(
                    row = h.rowIndex, col = h.colIndex, rowSpan = h.rowSpan, colSpan = h.colSpan,
                    width = h.width.toInt(), height = h.height.toInt(),
                    marginLeft = h.leftMargin, marginRight = h.rightMargin,
                    marginTop = h.topMargin, marginBottom = h.bottomMargin,
                    borderFillId = h.borderFillId,
                    vertAlign = when (h.property.textVerticalAlignment) {
                        TextVerticalAlignment.Center -> VertAlign.CENTER
                        TextVerticalAlignment.Bottom -> VertAlign.BOTTOM
                        else -> VertAlign.TOP
                    },
                    paragraphs = cell.paragraphList.paragraphs.orEmpty().map(::convertParagraph),
                    isHeader = h.property.isTitleCell,
                )
            }
        }
        return TableObject(
            rowCount = table.rowCount, colCount = table.columnCount,
            width = t.header.width.toInt(), height = t.header.height.toInt(),
            cellSpacing = table.cellSpacing,
            borderFillId = table.borderFillId,
            placement = t.header.toPlacement(),
            cells = cells,
            repeatHeader = table.property.isAutoRepeatTitleRow,
        )
    }

    private fun convertPicture(pic: ControlPicture): PictureObject = PictureObject(
        binId = pic.shapeComponentPicture.pictureInfo.binItemID.toString(),
        width = pic.header.width.toInt(),
        height = pic.header.height.toInt(),
        placement = pic.header.toPlacement(),
    )

    /** Drawing objects; children of a group carry their own transform into the group's space. */
    private fun convertShape(c: GsoControl): ShapeObject? {
        val comp = c.shapeComponent ?: return null
        val normal = comp as? ShapeComponentNormal
        val textBox = when (c) {
            is ControlRectangle -> c.textBox
            is ControlEllipse -> c.textBox
            is ControlPolygon -> c.textBox
            is ControlArc -> c.textBox
            is ControlCurve -> c.textBox
            else -> null
        }
        fun pts(vararg xy: Number) = xy.toList().chunked(2) { Pt(it[0].toDouble(), it[1].toDouble()) }
        val (kind, points) = when (c) {
            is ControlLine -> c.shapeComponentLine.let { ShapeKind.LINE to pts(it.startX, it.startY, it.endX, it.endY) }
            is ControlRectangle -> c.shapeComponentRectangle.let { ShapeKind.RECTANGLE to pts(it.x1, it.y1, it.x2, it.y2, it.x3, it.y3, it.x4, it.y4) }
            is ControlEllipse -> c.shapeComponentEllipse.let { ShapeKind.ELLIPSE to pts(it.centerX, it.centerY, it.axis1X, it.axis1Y, it.axis2X, it.axis2Y) }
            is ControlArc -> c.shapeComponentArc.let { ShapeKind.ARC to pts(it.centerX, it.centerY, it.axis1X, it.axis1Y, it.axis2X, it.axis2Y) }
            is ControlPolygon -> ShapeKind.POLYGON to c.shapeComponentPolygon.positionList.map { Pt(it.x.toDouble(), it.y.toDouble()) }
            is ControlCurve -> ShapeKind.CURVE to c.shapeComponentCurve.positionList.map { Pt(it.x.toDouble(), it.y.toDouble()) }
            is ControlContainer -> ShapeKind.CONTAINER to emptyList()
            is ControlPicture -> ShapeKind.PICTURE to pts(0, 0, comp.widthAtCreate, comp.heightAtCreate)
            else -> return null
        }
        val line = normal?.lineInfo
        return ShapeObject(
            kind = kind,
            // Children of a group have no control header; their size comes from the shape component.
            width = c.header?.width?.toInt() ?: comp.widthAtCurrent,
            height = c.header?.height?.toInt() ?: comp.heightAtCurrent,
            placement = c.header?.toPlacement() ?: ObjectPlacement.INLINE,
            transform = comp.renderingInfo.toAffine(),
            points = points,
            stroke = line?.takeIf { it.property.lineType != LineType.None }?.let {
                Stroke(it.color.toArgb(), it.thickness, it.property.lineType.toBorderStyle())
            },
            fill = normal?.fillInfo?.takeIf { it.type?.hasPatternFill() == true }?.patternFill?.backColor?.toArgbOrNull(),
            roundRatio = (c as? ControlRectangle)?.shapeComponentRectangle?.roundRate?.toInt() ?: 0,
            arcKind = when ((c as? ControlArc)?.shapeComponentArc?.arcType) {
                ArcType.CircularSector -> ArcKind.PIE
                ArcType.Bow -> ArcKind.CHORD
                else -> ArcKind.ARC
            },
            text = textBox?.let { tb ->
                val h = tb.listHeader
                TextBox(
                    paragraphs = tb.paragraphList.paragraphs.orEmpty().map(::convertParagraph),
                    marginLeft = h.leftMargin, marginRight = h.rightMargin, marginTop = h.topMargin, marginBottom = h.bottomMargin,
                    vertAlign = when (h.property.textVerticalAlignment) {
                        TextVerticalAlignment.Center -> VertAlign.CENTER
                        TextVerticalAlignment.Bottom -> VertAlign.BOTTOM
                        else -> VertAlign.TOP
                    },
                )
            },
            children = (c as? ControlContainer)?.childControlList.orEmpty().mapNotNull(::convertShape),
            binId = (c as? ControlPicture)?.shapeComponentPicture?.pictureInfo?.binItemID?.toString(),
            originalWidth = comp.widthAtCreate,
            originalHeight = comp.heightAtCreate,
        )
    }

    /** Translation, then each scale/rotation pair, as HWP stores them. */
    private fun RenderingInfo?.toAffine(): Affine {
        if (this == null) return Affine.IDENTITY
        var m = translationMatrix.toAffine()
        scaleRotateMatrixPairList.orEmpty().forEach { m = m * it.scaleMatrix.toAffine() * it.rotateMatrix.toAffine() }
        return m
    }

    private fun Matrix?.toAffine(): Affine {
        val v = this?.values ?: return Affine.IDENTITY
        return if (v.size >= 6) Affine(v[0], v[1], v[2], v[3], v[4], v[5]) else Affine.IDENTITY
    }

    private fun LineType.toBorderStyle(): BorderStyle = when (this) {
        LineType.Dash -> BorderStyle.DASH
        LineType.Dot -> BorderStyle.DOT
        LineType.DashDot -> BorderStyle.DASH_DOT
        LineType.DashDotDot -> BorderStyle.DASH_DOT_DOT
        LineType.LongDash -> BorderStyle.LONG_DASH
        LineType.CircleDot -> BorderStyle.CIRCLE
        LineType.Double -> BorderStyle.DOUBLE
        else -> BorderStyle.SOLID
    }

    private fun HeaderFooterApplyPage?.toModel(): ApplyPage = when (this) {
        HeaderFooterApplyPage.EvenPage -> ApplyPage.EVEN
        HeaderFooterApplyPage.OddPage -> ApplyPage.ODD
        else -> ApplyPage.BOTH
    }

    private fun CtrlHeaderGso.toPlacement(): ObjectPlacement {
        val prop = property
        return ObjectPlacement(
            treatAsChar = prop.isLikeWord,
            wrap = when (prop.textFlowMethod) {
                TextFlowMethod.FitWithText -> TextWrap.SQUARE
                TextFlowMethod.BehindText -> TextWrap.BEHIND_TEXT
                TextFlowMethod.InFrontOfText -> TextWrap.IN_FRONT_OF_TEXT
                else -> TextWrap.TOP_AND_BOTTOM
            },
            vertRelTo = when (prop.vertRelTo) {
                VertRelTo.Paper -> RelTo.PAPER
                VertRelTo.Page -> RelTo.PAGE
                else -> RelTo.PARA
            },
            horzRelTo = when (prop.horzRelTo) {
                HorzRelTo.Paper -> RelTo.PAPER
                HorzRelTo.Page -> RelTo.PAGE
                HorzRelTo.Column -> RelTo.COLUMN
                else -> RelTo.PARA
            },
            vertOffset = getyOffset().toInt(),
            horzOffset = getxOffset().toInt(),
            marginLeft = outterMarginLeft, marginRight = outterMarginRight,
            marginTop = outterMarginTop, marginBottom = outterMarginBottom,
        )
    }

    private fun PageDef.toPageSetup() = PageSetup(
        width = paperWidth.toInt(), height = paperHeight.toInt(),
        landscape = property.paperDirection == PaperDirection.Landscape,
        marginLeft = leftMargin.toInt(), marginRight = rightMargin.toInt(),
        marginTop = topMargin.toInt(), marginBottom = bottomMargin.toInt(),
        marginHeader = headerMargin.toInt(), marginFooter = footerMargin.toInt(),
        marginGutter = gutterMargin.toInt(),
    )

    private fun HwpCharShape.toModel(): CharShape {
        val ids = faceNameIds.array
        val scripts = Script.entries
        val prop = property
        return CharShape(
            fontNames = scripts.mapIndexedNotNull { i, s ->
                faceNameLists[s]?.getOrNull(ids[i])?.name?.let { s to it }
            }.toMap(),
            baseSize = baseSize,
            relativeSizes = scripts.zip(relativeSizes.array.map { it.toInt() }).toMap(),
            ratios = scripts.zip(ratios.array.map { it.toInt() }).toMap(),
            spacings = scripts.zip(charSpaces.array.map { it.toInt() }).toMap(),
            bold = prop.isBold,
            italic = prop.isItalic,
            underline = when (prop.underLineSort) {
                UnderLineSort.Bottom -> Underline.BOTTOM
                UnderLineSort.Top -> Underline.TOP
                else -> Underline.NONE
            },
            strikeout = prop.isStrikeLine,
            superscript = prop.isSuperScript,
            subscript = prop.isSubScript,
            textColor = charColor.toArgb(),
            shadeColor = shadeColor.toArgbOrNull(),
            underlineColor = underLineColor.toArgb(),
            strikeoutColor = strikeLineColor.toArgb(),
        )
    }

    private fun HwpParaShape.toModel(): ParaShape {
        val prop = property1
        return ParaShape(
            align = when (prop.alignment) {
                Alignment.Left -> HAlign.LEFT
                Alignment.Right -> HAlign.RIGHT
                Alignment.Center -> HAlign.CENTER
                Alignment.Distribute -> HAlign.DISTRIBUTE
                Alignment.Divide -> HAlign.DISTRIBUTE_SPACE
                else -> HAlign.JUSTIFY
            },
            // HWP stores paragraph margins doubled relative to HWPUNIT.
            marginLeft = leftMargin / 2,
            marginRight = rightMargin / 2,
            indent = indent / 2,
            spaceBefore = topParaSpace / 2,
            spaceAfter = bottomParaSpace / 2,
            lineSpacingType = when (prop.lineSpaceSort) {
                LineSpaceSort.FixedValue -> LineSpacingType.FIXED
                LineSpaceSort.OnlyMargin -> LineSpacingType.BETWEEN_LINES
                LineSpaceSort.AtLeast -> LineSpacingType.AT_LEAST
                else -> LineSpacingType.PERCENT
            },
            lineSpacing = lineSpaceValue(prop.lineSpaceSort),
            borderFillId = borderFillId,
            borderOffsetLeft = leftBorderSpace.toInt(),
            borderOffsetRight = rightBorderSpace.toInt(),
            borderOffsetTop = topBorderSpace.toInt(),
            borderOffsetBottom = bottomBorderSpace.toInt(),
            borderConnect = prop.isLinkBorder,
            borderIgnoreMargin = prop.isIgnoreParaMargin,
            keepWithNext = prop.isTogetherNextPara,
            pageBreakBefore = prop.isSplitPageBeforePara,
        )
    }

    private fun HwpParaShape.lineSpaceValue(sort: LineSpaceSort?): Int {
        // lineSpace2 supersedes lineSpace from format 5.0.2.5 on; older files leave it zero.
        val raw = if (lineSpace2 != 0L) lineSpace2.toInt() else lineSpace
        return if (sort == null || sort == LineSpaceSort.RatioForLetter) raw else raw / 2
    }

    private fun HwpBorderFill.toModel() = BorderFill(
        left = leftBorder.toModel(),
        right = rightBorder.toModel(),
        top = topBorder.toModel(),
        bottom = bottomBorder.toModel(),
        fillColor = fillInfo?.takeIf { it.type?.hasPatternFill() == true }
            ?.patternFill?.backColor?.toArgbOrNull(),
    )

    private fun EachBorder.toModel(): Border = Border(
        style = when (type) {
            BorderType.None, null -> BorderStyle.NONE
            BorderType.Solid -> BorderStyle.SOLID
            BorderType.Dash -> BorderStyle.DASH
            BorderType.Dot -> BorderStyle.DOT
            BorderType.DashDot -> BorderStyle.DASH_DOT
            BorderType.DashDotDot -> BorderStyle.DASH_DOT_DOT
            BorderType.LongDash -> BorderStyle.LONG_DASH
            BorderType.CircleDot -> BorderStyle.CIRCLE
            BorderType.Double -> BorderStyle.DOUBLE
            BorderType.ThinThick -> BorderStyle.THIN_THICK
            BorderType.ThickThin -> BorderStyle.THICK_THIN
            BorderType.ThinThickThin -> BorderStyle.THIN_THICK_THIN
            BorderType.Wave -> BorderStyle.WAVE
            BorderType.DoubleWave -> BorderStyle.DOUBLE_WAVE
            else -> BorderStyle.SOLID
        },
        width = thickness.toHundredthMm(),
        color = color.toArgb(),
    )

    private fun BorderThickness?.toHundredthMm(): Int =
        this?.name?.removePrefix("MM")?.replace('_', '.')?.toFloatOrNull()?.let { (it * 100).toInt() } ?: 10

    private fun Color4Byte?.toArgb(): Int =
        if (this == null) CharShape.BLACK else (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()

    /** HWP marks "no colour" with 0xFFFFFFFF (all four bytes set). */
    private fun Color4Byte?.toArgbOrNull(): Int? =
        if (this == null || value == 0xFFFFFFFFL) null else toArgb()
}
