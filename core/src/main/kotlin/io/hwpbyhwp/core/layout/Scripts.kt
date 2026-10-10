package io.hwpbyhwp.core.layout

import io.hwpbyhwp.core.model.Script

/** Which of Hangul's seven per-script font slots a character uses. */
internal fun scriptOf(c: Char): Script {
    val code = c.code
    return when {
        code in 0xAC00..0xD7A3 || code in 0x1100..0x11FF || code in 0x3130..0x318F || code in 0xA960..0xA97F || code in 0xD7B0..0xD7FF -> Script.HANGUL
        code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || code in 0xF900..0xFAFF -> Script.HANJA
        code in 0x3040..0x30FF || code in 0x31F0..0x31FF -> Script.JAPANESE
        code < 0x0250 || code in 0x1E00..0x1EFF -> Script.LATIN
        code in 0x2000..0x2BFF || code in 0x3000..0x303F || code in 0xFF00..0xFFEF || code in 0x25A0..0x25FF -> Script.SYMBOL
        else -> Script.OTHER
    }
}
