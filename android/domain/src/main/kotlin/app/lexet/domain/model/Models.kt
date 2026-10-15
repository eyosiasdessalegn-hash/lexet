package app.lexet.domain.model

import java.time.Instant
import java.time.LocalDate

enum class Jurisdiction { ET, INTL;
    companion object { fun of(code: String?) = if (code == "INTL") INTL else ET }
}

enum class DocType(val code: String) {
    PROCLAMATION("proclamation"), REGULATION("regulation"), DIRECTIVE("directive"),
    POLICY("policy"), NOTICE("notice"), OTHER("other");

    companion object {
        fun of(code: String?): DocType = entries.firstOrNull { it.code == code } ?: OTHER
    }
}

enum class Urgency(val code: String) {
    UPCOMING("upcoming"), IMMINENT("imminent"), IN_FORCE("in_force"), ENDED("ended");

    companion object {
        fun of(code: String?): Urgency? = entries.firstOrNull { it.code == code }
    }
}

/** Life-cycle stage. Ordered: a later stage implies the earlier ones happened. */
enum class LegalStatus(val code: String, val rank: Int, val urgency: Urgency) {
    POLICY_SIGNAL("policy_signal", 0, Urgency.UPCOMING),
    DRAFT("draft", 1, Urgency.UPCOMING),
    TABLED("tabled", 2, Urgency.UPCOMING),
    APPROVED("approved", 3, Urgency.IMMINENT),
    IN_FORCE("in_force", 4, Urgency.IN_FORCE),
    AMENDED("amended", 5, Urgency.IN_FORCE),
    REPEALED("repealed", 6, Urgency.ENDED);

    val isPreEnactment get() = rank <= TABLED.rank

    companion object {
        fun of(code: String?): LegalStatus? = entries.firstOrNull { it.code == code }
    }
}

fun LegalStatus?.rankOrMinus(): Int = this?.rank ?: -1

enum class Verification(val code: String) {
    VERIFIED("verified"), UNCONFIRMED("unconfirmed"), REPORTED("reported");

    companion object {
        fun of(code: String?): Verification = entries.firstOrNull { it.code == code } ?: UNCONFIRMED
    }
}

data class SourceRef(
    val publisher: String,
    val name: String,
    val tier: Int,
    val url: String,
    val title: String,
    val lang: String?,
    val publishedAt: Instant?,
    val counted: Boolean,
    val reason: String,
    val primaryDocument: Boolean,
    val gazette: Boolean,
    val excerpt: String?,
)

/** One legal development as the app shows it. */
data class LegalItem(
    val id: String,
    val version: Long,
    val jurisdiction: Jurisdiction,
    val docType: DocType,
    val lawId: String?,
    val lawNumber: Int?,
    val lawYearGc: Int?,
    val lawYearEc: Int?,
    val titleEn: String?,
    val titleAm: String?,
    val summaryEn: String?,
    val summaryAm: String?,
    val issuer: String?,
    val initiator: String?,
    val status: LegalStatus?,
    val verifiedStatus: LegalStatus?,
    val reportedStatus: LegalStatus?,
    val verification: Verification,
    val sourceCount: Int,
    val hasOfficialSource: Boolean,
    val topics: List<String>,
    val amends: String?,
    val eventDate: LocalDate?,
    val statusDate: LocalDate?,
    val firstSeenAt: Instant?,
    val updatedAt: Instant?,
    val sources: List<SourceRef> = emptyList(),
) {
    val urgency: Urgency? get() = status?.urgency
    val isVerified: Boolean get() = verification == Verification.VERIFIED

    /** Amharic law ID uses the Ethiopian year: አዋጅ ቁጥር 1427/2018. */
    val lawIdAm: String?
        get() {
            val n = lawNumber ?: return null
            val word = when (docType) {
                DocType.PROCLAMATION -> "አዋጅ"
                DocType.REGULATION -> "ደንብ"
                DocType.DIRECTIVE -> "መመሪያ"
                else -> return null
            }
            return if (lawYearEc != null) "$word ቁጥር $n/$lawYearEc" else "$word ቁጥር $n"
        }

    fun title(preferAmharic: Boolean): String =
        (if (preferAmharic) titleAm ?: titleEn else titleEn ?: titleAm) ?: lawId ?: "Untitled"

    fun summary(preferAmharic: Boolean): String? =
        if (preferAmharic) summaryAm ?: summaryEn else summaryEn ?: summaryAm
}
