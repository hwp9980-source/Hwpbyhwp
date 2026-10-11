package io.hwpbyhwp.core

import io.hwpbyhwp.core.layout.ParagraphHeads
import io.hwpbyhwp.core.model.NumberShape
import kotlin.test.Test
import kotlin.test.assertEquals

class ParagraphHeadsTest {
    @Test
    fun formatsNumberShapes() {
        val f = ParagraphHeads::format
        assertEquals("12", f(12, NumberShape.DIGIT))
        assertEquals("③", f(3, NumberShape.CIRCLED_DIGIT))
        assertEquals("XIV", f(14, NumberShape.ROMAN_UPPER))
        assertEquals("ix", f(9, NumberShape.ROMAN_LOWER))
        assertEquals("AB", f(28, NumberShape.LATIN_UPPER))
        assertEquals("다", f(3, NumberShape.HANGUL_SYLLABLE))
        assertEquals("가", f(15, NumberShape.HANGUL_SYLLABLE))
        assertEquals("㉰", f(3, NumberShape.CIRCLED_HANGUL_SYLLABLE))
        assertEquals("ㄹ", f(4, NumberShape.HANGUL_JAMO))
        assertEquals("이십일", f(21, NumberShape.HANGUL_NUMBER))
        assertEquals("百五", f(105, NumberShape.HANJA_NUMBER))
    }

    @Test
    fun mapsWingdingsBullets() {
        assertEquals("●", ParagraphHeads.bulletGlyph(""))
        assertEquals("-", ParagraphHeads.bulletGlyph("-"))
    }
}
