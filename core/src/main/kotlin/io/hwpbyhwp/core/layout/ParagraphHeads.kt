package io.hwpbyhwp.core.layout

import io.hwpbyhwp.core.model.Document
import io.hwpbyhwp.core.model.HeadType
import io.hwpbyhwp.core.model.NumberShape
import io.hwpbyhwp.core.model.Paragraph
import io.hwpbyhwp.core.model.Section

/**
 * Produces paragraph-head labels (개요 번호, 문단 번호, 글머리표). Stateful: call [label] for
 * paragraphs in reading order so the counters advance as in Hangul.
 */
class ParagraphHeads(private val document: Document) {

    private val counters = HashMap<Pair<HeadType, Int>, IntArray>()

    /** A label and the char shape to draw it with (null: the paragraph's first character's). */
    data class Head(val text: String, val charShapeId: Int?)

    /** The label to print before [p], or null when the paragraph has none. */
    fun label(p: Paragraph, section: Section): Head? {
        val shape = document.paraShape(p.paraShapeId)
        return when (shape.headType) {
            HeadType.NONE -> null
            HeadType.BULLET -> document.bullets[shape.headId]?.let { Head(bulletGlyph(it.char), it.charShapeId) }
            HeadType.OUTLINE -> number(HeadType.OUTLINE, section.outlineNumberingId, shape.level)
            HeadType.NUMBER -> number(HeadType.NUMBER, shape.headId, shape.level)
        }
    }

    private fun number(type: HeadType, id: Int, level: Int): Head? {
        val def = document.numberings[id] ?: return null
        val lv = def.levels.getOrNull(level) ?: return null
        val c = counters.getOrPut(type to id) { IntArray(MAX_LEVELS) }
        c[level] = if (c[level] == 0) lv.start else c[level] + 1
        for (k in level + 1 until MAX_LEVELS) c[k] = 0
        val text = LEVEL_REF.replace(lv.format) { m ->
            val n = m.groupValues[1].toInt() - 1
            val ref = def.levels.getOrNull(n) ?: return@replace ""
            format(if (c[n] == 0) ref.start else c[n], ref.shape)
        }
        return Head(text, lv.charShapeId)
    }

    companion object {
        private const val MAX_LEVELS = 10
        private val LEVEL_REF = Regex("\\^(10|[1-9])")

        private const val HANGUL = "가나다라마바사아자차카타파하"
        private const val JAMO = "ㄱㄴㄷㄹㅁㅂㅅㅇㅈㅊㅋㅌㅍㅎ"
        private const val HANGUL_DIGITS = "일이삼사오육칠팔구"
        private const val HANJA_DIGITS = "一二三四五六七八九"

        fun format(n: Int, shape: NumberShape): String = when (shape) {
            NumberShape.DIGIT -> n.toString()
            NumberShape.CIRCLED_DIGIT -> circledDigit(n)
            NumberShape.ROMAN_UPPER -> roman(n)
            NumberShape.ROMAN_LOWER -> roman(n).lowercase()
            NumberShape.LATIN_UPPER -> latin(n)
            NumberShape.LATIN_LOWER -> latin(n).lowercase()
            NumberShape.CIRCLED_LATIN_UPPER -> if (n in 1..26) (0x24B6 + n - 1).toChar().toString() else latin(n)
            NumberShape.CIRCLED_LATIN_LOWER -> if (n in 1..26) (0x24D0 + n - 1).toChar().toString() else latin(n).lowercase()
            NumberShape.HANGUL_SYLLABLE -> cycle(HANGUL, n)
            NumberShape.CIRCLED_HANGUL_SYLLABLE -> if (n in 1..14) (0x326E + n - 1).toChar().toString() else cycle(HANGUL, n)
            NumberShape.HANGUL_JAMO -> cycle(JAMO, n)
            NumberShape.CIRCLED_HANGUL_JAMO -> if (n in 1..14) (0x3260 + n - 1).toChar().toString() else cycle(JAMO, n)
            NumberShape.HANGUL_NUMBER -> sinoKorean(n, HANGUL_DIGITS, "십", "백", "천")
            NumberShape.HANJA_NUMBER -> sinoKorean(n, HANJA_DIGITS, "十", "百", "千")
            NumberShape.CIRCLED_HANJA_NUMBER -> if (n in 1..10) (0x3280 + n - 1).toChar().toString() else sinoKorean(n, HANJA_DIGITS, "十", "百", "千")
        }

        private fun cycle(chars: String, n: Int) = chars[((n - 1) % chars.length + chars.length) % chars.length].toString()

        private fun circledDigit(n: Int): String = when (n) {
            in 1..20 -> (0x2460 + n - 1).toChar().toString()
            in 21..35 -> (0x3251 + n - 21).toChar().toString()
            in 36..50 -> (0x32B1 + n - 36).toChar().toString()
            else -> n.toString()
        }

        private fun roman(n: Int): String {
            if (n !in 1..3999) return n.toString()
            val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
            val symbols = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
            var rest = n
            return buildString { values.forEachIndexed { i, v -> while (rest >= v) { append(symbols[i]); rest -= v } } }
        }

        private fun latin(n: Int): String {
            if (n < 1) return n.toString()
            var rest = n
            return buildString { while (rest > 0) { rest--; insert(0, 'A' + rest % 26); rest /= 26 } }
        }

        private fun sinoKorean(n: Int, digits: String, ten: String, hundred: String, thousand: String): String {
            if (n !in 1..9999) return n.toString()
            val units = listOf(thousand to 1000, hundred to 100, ten to 10)
            var rest = n
            return buildString {
                for ((name, v) in units) {
                    val d = rest / v
                    if (d > 0) { if (d > 1) append(digits[d - 1]); append(name) }
                    rest %= v
                }
                if (rest > 0) append(digits[rest - 1])
            }
        }

        /**
         * Hangul stores most bullets as Wingdings code points in the private use area; map the
         * common ones to Unicode so they render with ordinary fonts.
         */
        fun bulletGlyph(c: String): String {
            val code = c.firstOrNull()?.code ?: return "•"
            if (code !in 0xF000..0xF0FF) return c.ifBlank { "•" }
            return when (code and 0xFF) {
                0x6C, 0x9F -> "●"; 0x6D -> "❍"; 0x6E -> "■"; 0x6F -> "□"; 0x71 -> "❑"; 0x75 -> "◆"; 0x76 -> "❖"
                0x77 -> "◆"; 0xA7 -> "▪"; 0xA8 -> "◻"; 0xD8 -> "➢"; 0xE8 -> "➔"; 0xFC -> "✓"; 0xFE -> "☑"; 0xA1 -> "○"
                else -> "•"
            }
        }
    }
}
