package app.lexet.domain.clip

import app.lexet.domain.model.DocType
import java.net.URLEncoder

/**
 * A post or page the user shared into the app (LinkedIn, Telegram, a browser…).
 * Saved locally as an archive item: searchable, taggable, bookmarked. Nothing is
 * fetched from the platform; the app keeps what the user shared.
 */
data class Clip(
    val url: String?,
    val platform: String,
    val title: String,
    val text: String,
    val docType: DocType,
    val lawNumber: Int?,
    /** Year as written; Ethiopian (E.C.) when [yearIsEthiopian]. */
    val lawYear: Int?,
    val yearIsEthiopian: Boolean,
    val amharic: Boolean,
) {
    /** "Proclamation No. 1430/2026" style, in the calendar the post used. */
    val lawIdText: String?
        get() {
            val n = lawNumber ?: return null
            val word = when (docType) {
                DocType.PROCLAMATION -> "Proclamation"
                DocType.REGULATION -> "Regulation"
                DocType.DIRECTIVE -> "Directive"
                else -> return null
            }
            return if (lawYear != null) "$word No. $n/$lawYear" else "$word No. $n"
        }
}

object ClipParser {
    const val ID_PREFIX = "clip-"

    private val URL = Regex("""https?://[^\s<>"']+""")
    private val LAW_EN = Regex("""\b(Proclamation|Regulations?|Directive)\s+No\.?\s*(\d{1,4})(?:\s*/\s*(\d{4}))?""", RegexOption.IGNORE_CASE)
    private val LAW_AM = Regex("""(አዋጅ|ደንብ|መመሪያ)\s*ቁ(?:ጥር|\.)\s*(\d{1,4})(?:\s*/\s*(\d{4}))?""")

    fun isClip(id: String) = id.startsWith(ID_PREFIX)

    fun platformOf(url: String?): String {
        val host = url?.let { Regex("""https?://([^/?#]+)""").find(it)?.groupValues?.get(1)?.lowercase() } ?: return "Shared text"
        return when {
            host.endsWith("linkedin.com") || host == "lnkd.in" -> "LinkedIn"
            host == "t.me" || host.endsWith("telegram.org") || host.endsWith("telegram.me") -> "Telegram"
            host.endsWith("facebook.com") || host == "fb.watch" -> "Facebook"
            host == "x.com" || host.endsWith("twitter.com") -> "X"
            else -> host.removePrefix("www.").removePrefix("m.")
        }
    }

    fun parse(text: String?, subject: String?): Clip {
        val raw = (text ?: "").trim()
        val url = URL.find(raw)?.value?.trimEnd('.', ',', ')', ']')
        val body = raw.replace(URL, " ").replace(Regex("""[ \t]+"""), " ").trim()
        val platform = platformOf(url)
        val all = listOfNotNull(subject, body).joinToString("\n")

        var docType = DocType.OTHER
        var number: Int? = null
        var year: Int? = null
        var ethiopian = false
        LAW_EN.find(all)?.let { m ->
            docType = docTypeOf(m.groupValues[1])
            number = m.groupValues[2].toInt()
            year = m.groupValues[3].toIntOrNull()
            ethiopian = (year ?: 9999) < 2020   // 2016/2017/2018 E.C. are 2023–2026 G.C.
        } ?: LAW_AM.find(all)?.let { m ->
            docType = docTypeOf(m.groupValues[1])
            number = m.groupValues[2].toInt()
            year = m.groupValues[3].toIntOrNull()
            ethiopian = true
        }

        val firstLine = body.lineSequence().map { it.trim() }.firstOrNull { it.length >= 8 }
        val title = subject?.trim()?.takeIf { it.isNotEmpty() && !URL.matches(it) }
            ?: firstLine?.let { shorten(it, 140) }
            ?: "$platform post"
        return Clip(url, platform, title, body, docType, number, year, ethiopian, isAmharic(title + " " + body))
    }

    private fun docTypeOf(word: String): DocType = when (word.lowercase()) {
        "proclamation", "አዋጅ" -> DocType.PROCLAMATION
        "regulation", "regulations", "ደንብ" -> DocType.REGULATION
        else -> DocType.DIRECTIVE
    }

    private fun shorten(s: String, max: Int): String {
        // "Proclamation No. 1431/2026" must not end the sentence at "No."
        val guarded = s.replace(Regex("""\b(No|Art|Dr|Mr|Ms|St|vs|Proc)\.\s"""), "$1.\u0001")
        val sentence = guarded.split(Regex("""(?<=[.!?።])\s""")).first().replace('\u0001', ' ')
        val t = if (sentence.length in 8..max) sentence else s
        return if (t.length <= max) t else t.take(max).substringBeforeLast(' ') + "…"
    }

    private fun isAmharic(s: String): Boolean {
        val letters = s.count { it.isLetter() }
        if (letters == 0) return false
        return s.count { it in 'ሀ'..'᎟' || it in 'ⶀ'..'⷟' || it in '꬀'..'꬯' } * 2 > letters
    }
}

/** "Search the web" from the Search screen: a Google query, opened in the user's browser. */
enum class WebScope { LAWS_PDF, ETHIOPIAN_LEGAL_SITES, LINKEDIN, WEB }

object WebSearch {
    val ETHIOPIAN_LEGAL_SITES = listOf(
        "chilot.me", "lawethiopia.com", "abyssinialaw.com", "ethiopian-law.com", "hopr.gov.et",
        "justice.gov.et", "fao.org/faolex", "natlex.ilo.org", "wipo.int/wipolex",
    )

    fun query(text: String, scope: WebScope): String {
        val q = text.trim()
        return when (scope) {
            WebScope.LAWS_PDF -> "$q filetype:pdf (proclamation OR regulation OR directive OR law OR አዋጅ OR ደንብ OR መመሪያ)"
            WebScope.ETHIOPIAN_LEGAL_SITES -> "$q (" + ETHIOPIAN_LEGAL_SITES.joinToString(" OR ") { "site:$it" } + ")"
            WebScope.LINKEDIN -> "$q (site:linkedin.com/posts OR site:linkedin.com/pulse)"
            WebScope.WEB -> q
        }
    }

    fun url(text: String, scope: WebScope): String =
        "https://www.google.com/search?q=" + URLEncoder.encode(query(text, scope), "UTF-8")
}
