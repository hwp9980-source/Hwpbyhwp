package io.hwpbyhwp.core

import io.hwpbyhwp.core.layout.GenericFamily.MONO
import io.hwpbyhwp.core.layout.GenericFamily.SANS
import io.hwpbyhwp.core.layout.GenericFamily.SERIF
import io.hwpbyhwp.core.layout.genericFamilyOf
import kotlin.test.Test
import kotlin.test.assertEquals

class FontFamiliesTest {
    @Test
    fun classifiesCommonFonts() {
        mapOf(
            "함초롬바탕" to SERIF, "휴먼명조" to SERIF, "HY신명조" to SERIF, "궁서" to SERIF, "Times New Roman" to SERIF,
            "함초롬돋움" to SANS, "맑은 고딕" to SANS, "굴림" to SANS, "나눔고딕" to SANS, "Arial" to SANS, "Microsoft Sans Serif" to SANS,
            "Courier New" to MONO, "D2Coding" to MONO,
        ).forEach { (name, family) -> assertEquals(family, genericFamilyOf(name), name) }
    }
}
