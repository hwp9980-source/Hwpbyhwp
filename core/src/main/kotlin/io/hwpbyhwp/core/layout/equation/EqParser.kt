package io.hwpbyhwp.core.layout.equation

/** Parse tree for Hangul equation scripts (한글 수식 편집기 문법). */
sealed interface Eq

data class EqRow(val items: List<Eq>) : Eq
data class EqAtom(val text: String, val style: AtomStyle) : Eq
data class EqSpace(val em: Float) : Eq
data class EqScripts(val base: Eq, val sub: Eq?, val sup: Eq?) : Eq
data class EqFrac(val num: Eq, val den: Eq, val bar: Boolean = true) : Eq
data class EqRoot(val body: Eq, val index: Eq?) : Eq
data class EqFenced(val left: String, val body: Eq, val right: String) : Eq
data class EqAccent(val kind: String, val body: Eq) : Eq
/** Large operator; [limits] places scripts above/below instead of to the right. */
data class EqBigOp(val symbol: String, val limits: Boolean) : Eq
data class EqMatrix(val rows: List<List<Eq>>, val left: String, val right: String, val align: Char) : Eq

enum class AtomStyle { ITALIC, ROMAN, BOLD, SYMBOL }

/**
 * Recursive-descent parser for Hangul's equation script. Unknown words fall back to italic text,
 * so a script never fails to parse; it may only typeset less faithfully.
 */
class EqParser private constructor(private val tokens: List<String>) {
    private var pos = 0
    private var roman = false
    private var bold = false

    companion object {
        fun parse(script: String): Eq {
            val p = EqParser(tokenize(script))
            val rows = p.parseRows(setOf())
            return if (rows.size == 1) rows[0] else EqMatrix(rows.map { listOf(it) }, "", "", 'c')
        }

        private val MULTI = listOf("<->", "<=>", "->", "<-", "=>", "<=", ">=", "!=", "==", "+-", "-+", "<<", ">>", "...")

        fun tokenize(s: String): List<String> {
            val out = mutableListOf<String>()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c.isWhitespace() -> i++
                    c == '"' -> {
                        val end = s.indexOf('"', i + 1).let { if (it < 0) s.length else it }
                        out += "\"" + s.substring(i + 1, end)
                        i = end + 1
                    }
                    MULTI.any { s.startsWith(it, i) } -> MULTI.first { s.startsWith(it, i) }.also { out += it; i += it.length }
                    c.isLetter() && c.code < 0x250 -> {
                        val start = i
                        while (i < s.length && s[i].isLetter() && s[i].code < 0x250) i++
                        out += s.substring(start, i)
                    }
                    c.isDigit() -> {
                        val start = i
                        while (i < s.length && (s[i].isDigit() || s[i] == '.' && i + 1 < s.length && s[i + 1].isDigit())) i++
                        out += s.substring(start, i)
                    }
                    c.code >= 0x250 && !c.isWhitespace() -> {
                        // Korean and other text: keep runs together, written upright.
                        val start = i
                        while (i < s.length && s[i].code >= 0x250 && !s[i].isWhitespace()) i++
                        out += "\"" + s.substring(start, i)
                    }
                    else -> { out += c.toString(); i++ }
                }
            }
            return out
        }
    }

    private fun peek(): String? = tokens.getOrNull(pos)
    private fun next(): String? = tokens.getOrNull(pos++)
    private fun peekIs(vararg words: String): Boolean = peek()?.let { t -> words.any { it.equals(t, ignoreCase = true) } } == true

    /** Rows separated by '#'; stops before any token in [stop] (without consuming it). */
    private fun parseRows(stop: Set<String>): List<Eq> {
        val rows = mutableListOf<Eq>()
        while (true) {
            rows += parseSeq(stop + "#")
            if (peek() == "#") { next(); continue }
            return rows
        }
    }

    /** A sequence of terms up to a token in [stop]; handles infix over/atop. */
    private fun parseSeq(stop: Set<String>): Eq {
        val savedRoman = roman
        val savedBold = bold
        val items = mutableListOf<Eq>()
        while (true) {
            val t = peek() ?: break
            if (t in stop || stop.any { it.equals(t, ignoreCase = true) && it.length > 1 }) break
            if (t.equals("over", true) || t.equals("atop", true)) {
                next()
                val num = items.removeLastOrNull() ?: EqRow(emptyList())
                val den = parseScripted() ?: EqRow(emptyList())
                items += EqFrac(num, den, bar = t.equals("over", true))
                continue
            }
            parseScripted()?.let { items += it }
        }
        roman = savedRoman
        bold = savedBold
        return if (items.size == 1) items[0] else EqRow(items)
    }

    private fun parseScripted(): Eq? {
        var base = parsePrimary() ?: return null
        var sub: Eq? = null
        var sup: Eq? = null
        while (true) {
            when {
                peek() == "_" || peekIs("sub", "from") -> { next(); sub = parsePrimary() }
                peek() == "^" || peekIs("sup", "to") -> { next(); sup = parsePrimary() }
                else -> break
            }
        }
        if (sub != null || sup != null) base = EqScripts(base, sub, sup)
        return base
    }

    private fun parsePrimary(): Eq? {
        val t = next() ?: return null
        val lower = t.lowercase()
        return when {
            t == "{" -> parseSeq(setOf("}")).also { if (peek() == "}") next() }
            t == "}" -> EqRow(emptyList())
            t.startsWith("\"") -> EqAtom(t.substring(1), AtomStyle.ROMAN)
            lower == "left" -> {
                val l = delimiter(next())
                val body = parseSeq(setOf("right"))
                val r = if (peekIs("right")) { next(); delimiter(next()) } else ""
                EqFenced(l, body, r)
            }
            lower == "sqrt" -> EqRoot(parseScripted() ?: EqRow(emptyList()), null)
            lower == "root" -> {
                val index = parseScripted()
                if (peekIs("of")) next()
                EqRoot(parseScripted() ?: EqRow(emptyList()), index)
            }
            lower in ACCENTS -> EqAccent(lower, parseScripted() ?: EqRow(emptyList()))
            lower in MATRICES -> parseMatrix(lower)
            lower == "rm" -> { roman = true; bold = false; parsePrimary() }
            lower == "it" -> { roman = false; bold = false; parsePrimary() }
            lower == "bold" -> { bold = true; parsePrimary() }
            t == "~" -> EqSpace(0.3f)
            t == "`" -> EqSpace(0.12f)
            t in BIG_OPS -> EqBigOp(BIG_OPS.getValue(t), limits = t.lowercase() !in INTEGRALS)
            lower in BIG_OPS -> EqBigOp(BIG_OPS.getValue(lower), limits = lower !in INTEGRALS)
            lower in LIMIT_FUNCTIONS -> EqBigOp(t, limits = true)
            t in SYMBOLS -> EqAtom(SYMBOLS.getValue(t), AtomStyle.SYMBOL)
            lower in SYMBOLS && t != t.uppercase() -> EqAtom(SYMBOLS.getValue(lower), AtomStyle.SYMBOL)
            lower in FUNCTIONS -> EqAtom(t, AtomStyle.ROMAN)
            t[0].isDigit() -> EqAtom(t, if (bold) AtomStyle.BOLD else AtomStyle.ROMAN)
            t[0].isLetter() -> EqAtom(t, when { bold -> AtomStyle.BOLD; roman -> AtomStyle.ROMAN; else -> AtomStyle.ITALIC })
            else -> EqAtom(OPERATORS[t] ?: t, AtomStyle.SYMBOL)
        }
    }

    private fun parseMatrix(kind: String): Eq {
        if (peek() != "{") return EqAtom(kind, AtomStyle.ITALIC)
        next()
        val rows = mutableListOf<List<Eq>>()
        var row = mutableListOf<Eq>()
        while (true) {
            row += parseSeq(setOf("&", "#", "}"))
            when (next()) {
                "&" -> continue
                "#" -> { rows += row; row = mutableListOf() }
                else -> { rows += row; break }
            }
        }
        val (l, r, align) = when (kind) {
            "pmatrix" -> Triple("(", ")", 'c')
            "bmatrix" -> Triple("[", "]", 'c')
            "dmatrix" -> Triple("|", "|", 'c')
            "cases" -> Triple("{", "", 'l')
            "lpile" -> Triple("", "", 'l')
            "rpile" -> Triple("", "", 'r')
            else -> Triple("", "", 'c')
        }
        return EqMatrix(rows, l, r, align)
    }

    private fun delimiter(t: String?): String = when (t?.lowercase()) {
        null, "." -> ""
        "lbrace" -> "{"
        "rbrace" -> "}"
        "langle", "<" -> "⟨"
        "rangle", ">" -> "⟩"
        "lceil" -> "⌈"; "rceil" -> "⌉"; "lfloor" -> "⌊"; "rfloor" -> "⌋"
        "dline", "||" -> "‖"
        else -> t
    }
}

private val ACCENTS = setOf("bar", "hat", "vec", "dot", "ddot", "tilde", "under", "acute", "grave", "check", "dyad", "arch")
private val MATRICES = setOf("matrix", "pmatrix", "bmatrix", "dmatrix", "cases", "pile", "lpile", "rpile")
private val INTEGRALS = setOf("int", "dint", "tint", "oint", "odint", "otint")

private val BIG_OPS = mapOf(
    "sum" to "∑", "prod" to "∏", "coprod" to "∐", "int" to "∫", "dint" to "∬", "tint" to "∭", "oint" to "∮",
    "odint" to "∯", "otint" to "∰", "union" to "⋃", "inter" to "⋂", "bigcup" to "⋃", "bigcap" to "⋂",
    "bigoplus" to "⨁", "bigotimes" to "⨂", "bigodot" to "⨀", "SUM" to "∑", "PROD" to "∏", "INT" to "∫",
)
private val LIMIT_FUNCTIONS = setOf("lim", "max", "min", "liminf", "limsup")
private val FUNCTIONS = setOf(
    "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh", "coth",
    "log", "ln", "lg", "exp", "det", "gcd", "mod", "deg", "dim", "ker", "arg", "hom", "if", "for", "and",
)

private val SYMBOLS = mapOf(
    "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε", "zeta" to "ζ", "eta" to "η",
    "theta" to "θ", "iota" to "ι", "kappa" to "κ", "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ",
    "omicron" to "ο", "pi" to "π", "rho" to "ρ", "sigma" to "σ", "tau" to "τ", "upsilon" to "υ", "phi" to "φ",
    "chi" to "χ", "psi" to "ψ", "omega" to "ω", "vartheta" to "ϑ", "varphi" to "ϕ", "varepsilon" to "ϵ",
    "ALPHA" to "Α", "BETA" to "Β", "GAMMA" to "Γ", "DELTA" to "Δ", "EPSILON" to "Ε", "ZETA" to "Ζ", "ETA" to "Η",
    "THETA" to "Θ", "IOTA" to "Ι", "KAPPA" to "Κ", "LAMBDA" to "Λ", "MU" to "Μ", "NU" to "Ν", "XI" to "Ξ",
    "OMICRON" to "Ο", "PI" to "Π", "RHO" to "Ρ", "SIGMA" to "Σ", "TAU" to "Τ", "UPSILON" to "Υ", "PHI" to "Φ",
    "CHI" to "Χ", "PSI" to "Ψ", "OMEGA" to "Ω",
    "times" to "×", "div" to "÷", "pm" to "±", "mp" to "∓", "cdot" to "·", "circ" to "∘", "bullet" to "∙",
    "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥", "ne" to "≠", "neq" to "≠", "approx" to "≈",
    "equiv" to "≡", "sim" to "∼", "simeq" to "≃", "cong" to "≅", "propto" to "∝", "inf" to "∞", "infinity" to "∞",
    "partial" to "∂", "nabla" to "∇", "in" to "∈", "notin" to "∉", "ni" to "∋", "subset" to "⊂", "supset" to "⊃",
    "subseteq" to "⊆", "supseteq" to "⊇", "cup" to "∪", "cap" to "∩", "forall" to "∀", "exist" to "∃",
    "exists" to "∃", "therefore" to "∴", "because" to "∵", "angle" to "∠", "prime" to "′", "emptyset" to "∅",
    "perp" to "⊥", "parallel" to "∥", "oplus" to "⊕", "otimes" to "⊗", "odot" to "⊙", "triangle" to "△",
    "cdots" to "⋯", "ldots" to "…", "vdots" to "⋮", "ddots" to "⋱", "dots" to "…", "neg" to "¬", "lnot" to "¬",
    "wedge" to "∧", "vee" to "∨", "rarrow" to "→", "larrow" to "←", "lrarrow" to "↔", "uparrow" to "↑",
    "downarrow" to "↓", "RARROW" to "⇒", "LARROW" to "⇐", "LRARROW" to "⇔", "hbar" to "ℏ", "aleph" to "ℵ",
    "deg" to "°", "DEG" to "°", "star" to "⋆", "smallint" to "∫",
)

private val OPERATORS = mapOf(
    "<->" to "↔", "<=>" to "⇔", "->" to "→", "<-" to "←", "=>" to "⇒", "<=" to "≤", ">=" to "≥", "!=" to "≠",
    "==" to "≡", "+-" to "±", "-+" to "∓", "<<" to "≪", ">>" to "≫", "..." to "…", "-" to "−", "*" to "∗",
)
