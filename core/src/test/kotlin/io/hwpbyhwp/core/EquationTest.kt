package io.hwpbyhwp.core

import io.hwpbyhwp.core.layout.TextItem
import io.hwpbyhwp.core.layout.TextMeasurer
import io.hwpbyhwp.core.layout.equation.EqAtom
import io.hwpbyhwp.core.layout.equation.EqBigOp
import io.hwpbyhwp.core.layout.equation.EqFrac
import io.hwpbyhwp.core.layout.equation.EqLayout
import io.hwpbyhwp.core.layout.equation.EqMatrix
import io.hwpbyhwp.core.layout.equation.EqParser
import io.hwpbyhwp.core.layout.equation.EqRoot
import io.hwpbyhwp.core.layout.equation.EqRow
import io.hwpbyhwp.core.layout.equation.EqScripts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EquationTest {
    private val mono = TextMeasurer { text, font -> FloatArray(text.length) { font.size / 2 } }

    @Test
    fun parsesFractionsRootsAndScripts() {
        val eq = EqParser.parse("{\"123\"} over {123 sqrt {3466}} sum _{34} ^{12}")
        assertIs<EqRow>(eq)
        val frac = assertIs<EqFrac>(eq.items[0])
        assertEquals(EqAtom("123", io.hwpbyhwp.core.layout.equation.AtomStyle.ROMAN), frac.num)
        assertIs<EqRoot>(assertIs<EqRow>(frac.den).items[1])
        val sum = assertIs<EqScripts>(eq.items[1])
        assertIs<EqBigOp>(sum.base)
    }

    @Test
    fun mapsSymbolsAndMatrices() {
        val eq = assertIs<EqRow>(EqParser.parse("alpha le x times y"))
        assertEquals(listOf("α", "≤", "x", "×", "y"), eq.items.map { (it as EqAtom).text })
        val m = assertIs<EqMatrix>(EqParser.parse("pmatrix{a & b # c & d}"))
        assertEquals(2, m.rows.size)
        assertEquals("(", m.left)
    }

    @Test
    fun fractionStacksNumeratorAboveDenominator() {
        val box = EqLayout(mono, 0).layout(EqParser.parse("a over b"), 1000f)
        val texts = box.items.filterIsInstance<TextItem>()
        val a = texts.first { it.text == "a" }
        val b = texts.first { it.text == "b" }
        assertTrue(a.baseline < 0 && b.baseline > 0, "a=${a.baseline} b=${b.baseline}")
        assertTrue(box.ascent > 1000f * 0.8f)
    }
}
