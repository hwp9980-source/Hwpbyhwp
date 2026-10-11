package io.hwpbyhwp.core.layout

/** The bundled substitute a document font maps to (Noto Sans/Serif KR, or a system monospace). */
enum class GenericFamily { SANS, SERIF, MONO }

private val SERIF_HINTS = listOf(
    "바탕", "명조", "궁서", "신명", "견명", "청자", "바탕체", "batang", "myeongjo", "gungsuh",
    "times", "georgia", "garamond", "cambria", "book antiqua", "palatino", "serif", "세리프",
)
private val SANS_OVERRIDES = listOf("sans serif", "sans-serif", "sansserif")
private val MONO_HINTS = listOf("courier", "consolas", "mono", "lucida console", "d2coding", "코딩")

/** Classifies a document font name by the conventions of common Korean and Latin font names. */
fun genericFamilyOf(fontName: String): GenericFamily {
    val name = fontName.lowercase()
    return when {
        MONO_HINTS.any { it in name } -> GenericFamily.MONO
        SANS_OVERRIDES.any { it in name } -> GenericFamily.SANS
        SERIF_HINTS.any { it in name } -> GenericFamily.SERIF
        else -> GenericFamily.SANS // 돋움, 고딕, 굴림, 맑은 고딕, Arial ...
    }
}
