package app.lexet.domain.text

import java.text.Normalizer

/**
 * Amharic / Ethiopic normalisation for search and keyword matching.
 *
 * Mirrors backend/lexet/text/ethiopic.py; both are checked against
 * shared/ethiopic_vectors.json so that a search typed on the phone matches the
 * way the server grouped and labelled items.
 *
 *  - Homophone series are folded (ሐ/ኀ→ሀ, ሠ→ሰ, ዐ→አ, ፀ→ጸ), keeping the vowel order.
 *  - 4th order folds onto 1st in the h and glottal series (ሃ→ሀ, ኣ→አ).
 *  - Old labialised spellings fold (ቍ→ቁ, ኵ→ኩ, ጕ→ጉ, ኍ→ሁ).
 *  - Ge'ez numerals become digits (፲፬፻፳፯ → 1427).
 *  - Ethiopic punctuation and any other non letter/digit becomes a space; Latin is lower-cased.
 */
object EthiopicText {

    private val homophoneRows = mapOf(
        0x1210 to 0x1200, // ሐ → ሀ
        0x1280 to 0x1200, // ኀ → ሀ
        0x1220 to 0x1230, // ሠ → ሰ
        0x12D0 to 0x12A0, // ዐ → አ
        0x1340 to 0x1338, // ፀ → ጸ
    )

    private val removed = setOf(0x200B, 0x200C, 0x200D, 0xFEFF, 0x135D, 0x135E, 0x135F)

    private val fold: Map<Int, Int> = buildMap {
        for ((src, dst) in homophoneRows) for (i in 0 until 8) put(src + i, dst + i)
        for (cp in intArrayOf(0x1203, 0x1213, 0x1283)) put(cp, 0x1200) // ሃ ሓ ኃ → ሀ
        for (cp in intArrayOf(0x12A3, 0x12D3)) put(cp, 0x12A0)         // ኣ ዓ → አ
        put(0x124D, 0x1241) // ቍ → ቁ
        put(0x12B5, 0x12A9) // ኵ → ኩ
        put(0x1315, 0x1309) // ጕ → ጉ
        put(0x128D, 0x1201) // ኍ → ሁ
    }

    private val prefixes = listOf("እንደ", "ስለ", "ወደ", "የ", "በ", "ለ", "ከ")

    fun isEthiopic(cp: Int): Boolean =
        cp in 0x1200..0x139F || cp in 0x2D80..0x2DDF || cp in 0xAB00..0xAB2F

    fun isEthiopic(c: Char): Boolean = isEthiopic(c.code)

    private fun isGeezNumeral(cp: Int) = cp in 0x1369..0x137C

    /** Value of a run of Ge'ez numerals: ፳፻፲፰ → 2018. */
    fun geezToInt(s: String): Int {
        var total = 0L
        var current = 0L
        var group = 0L
        var seen = false
        for (ch in s) {
            val cp = ch.code
            when {
                cp in 0x1369..0x1371 -> { group += (cp - 0x1369 + 1); seen = true }
                cp in 0x1372..0x137A -> { group += (cp - 0x1372 + 1) * 10; seen = true }
                cp == 0x137B -> { current += (if (group == 0L) 1 else group) * 100; group = 0; seen = true }
                cp == 0x137C -> {
                    val v = total + current + group
                    total = (if (v == 0L) 1 else v) * 10000; current = 0; group = 0; seen = true
                }
                else -> throw IllegalArgumentException("not a Ge'ez numeral: $ch")
            }
        }
        require(seen) { "empty numeral" }
        return (total + current + group).toInt()
    }

    /** Fold homophones and convert numerals; keeps punctuation and case. */
    fun fold(text: String): String {
        if (text.isEmpty()) return text
        val nfc = Normalizer.normalize(text, Normalizer.Form.NFC)
        val out = StringBuilder(nfc.length)
        var i = 0
        while (i < nfc.length) {
            val cp = nfc.codePointAt(i)
            val n = Character.charCount(cp)
            if (isGeezNumeral(cp)) {
                var j = i
                while (j < nfc.length && isGeezNumeral(nfc.codePointAt(j))) j += Character.charCount(nfc.codePointAt(j))
                out.append(geezToInt(nfc.substring(i, j)))
                i = j
                continue
            }
            if (cp !in removed) out.appendCodePoint(fold[cp] ?: cp)
            i += n
        }
        return out.toString()
    }

    private fun isTokenChar(cp: Int): Boolean {
        val t = Character.getType(cp)
        return Character.isLetterOrDigit(cp) ||
            t == Character.NON_SPACING_MARK.toInt() ||
            t == Character.LETTER_NUMBER.toInt() || t == Character.OTHER_NUMBER.toInt()
    }

    /** Search key: folded, lower-cased, every non letter/digit turned into one space. */
    fun normalize(text: String): String {
        val folded = fold(text).lowercase()
        val sb = StringBuilder(folded.length)
        var lastSpace = true
        var i = 0
        while (i < folded.length) {
            val cp = folded.codePointAt(i)
            if (isTokenChar(cp)) {
                sb.appendCodePoint(cp); lastSpace = false
            } else if (!lastSpace) {
                sb.append(' '); lastSpace = true
            }
            i += Character.charCount(cp)
        }
        return sb.toString().trim()
    }

    fun tokens(text: String): List<String> {
        val n = normalize(text)
        return if (n.isEmpty()) emptyList() else n.split(' ')
    }

    /** One prepositional prefix removed (የመረጃ → መረጃ) if two or more characters remain. */
    fun stripPrefix(token: String): String? {
        if (token.isEmpty() || !isEthiopic(token[0])) return null
        for (p in prefixes) {
            if (token.startsWith(p) && token.length - p.length >= 2) return token.substring(p.length)
        }
        return null
    }

    /** Tokens for the full-text index: each token followed by its prefix-stripped form. */
    fun indexTokens(text: String): List<String> {
        val out = ArrayList<String>()
        for (t in tokens(text)) {
            out.add(t)
            stripPrefix(t)?.let { out.add(it) }
        }
        return out
    }

    fun indexText(text: String): String = indexTokens(text).joinToString(" ")

    fun detectLang(text: String): String {
        var eth = 0
        var lat = 0
        for (c in text.take(4000)) {
            if (isEthiopic(c)) eth++ else if (c.isLetter()) lat++
        }
        return if (eth > lat) "am" else "en"
    }
}
