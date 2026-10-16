package app.lexet.domain.notify

import app.lexet.domain.model.DocType
import app.lexet.domain.model.Jurisdiction
import app.lexet.domain.model.LegalItem
import app.lexet.domain.model.LegalStatus
import app.lexet.domain.model.Urgency
import app.lexet.domain.model.Verification
import app.lexet.domain.model.rankOrMinus
import app.lexet.domain.text.EthiopicText

/** Document-type filter values. DRAFT_BILL is a proclamation or regulation not yet enacted. */
enum class TypeFilter { PROCLAMATION, REGULATION, DIRECTIVE, DRAFT_BILL, POLICY, NOTICE, OTHER }

fun LegalItem.typeFilters(): Set<TypeFilter> {
    val base = when (docType) {
        DocType.PROCLAMATION -> TypeFilter.PROCLAMATION
        DocType.REGULATION -> TypeFilter.REGULATION
        DocType.DIRECTIVE -> TypeFilter.DIRECTIVE
        DocType.POLICY -> TypeFilter.POLICY
        DocType.NOTICE -> TypeFilter.NOTICE
        DocType.OTHER -> TypeFilter.OTHER
    }
    val draft = status?.isPreEnactment == true &&
        (docType == DocType.PROCLAMATION || docType == DocType.REGULATION)
    return if (draft) setOf(base, TypeFilter.DRAFT_BILL) else setOf(base)
}

enum class DigestMode { INSTANT, HOURLY, DAILY }

/** User notification settings. Empty sets mean "any". Stored only on the device. */
data class NotificationFilters(
    val enabled: Boolean = true,
    val types: Set<TypeFilter> = emptySet(),
    val issuers: Set<String> = emptySet(),
    val topics: Set<String> = emptySet(),
    val keywords: List<String> = emptyList(),
    val urgencies: Set<Urgency> = emptySet(),
    val digest: DigestMode = DigestMode.INSTANT,
    val showOnLockScreen: Boolean = true,
    /** Also notify new items that fewer than three independent sources report (labelled Unconfirmed). */
    val includeUnconfirmed: Boolean = true,
)

enum class ChangeKind { NEW_VERIFIED, STATUS_ADVANCED, NEW_UNCONFIRMED }

object NotificationRules {

    /**
     * What, if anything, about this update deserves a notification.
     *
     * Only verified Ethiopian developments notify: when an item first becomes
     * verified, or when its *verified* stage moves forward (e.g. approved → in
     * force). Unconfirmed items, international items and edits that do not change
     * the stage stay silent. During the very first sync (bootstrap) nothing
     * notifies — the backlog is archive, not news.
     */
    fun detectChange(previous: LegalItem?, current: LegalItem, bootstrap: Boolean,
                     includeUnconfirmed: Boolean = false): ChangeKind? {
        if (bootstrap) return null
        if (current.jurisdiction != Jurisdiction.ET) return null
        if (current.verification != Verification.VERIFIED) {
            // Optional: a brand-new item that is not (yet) corroborated by three sources.
            return if (includeUnconfirmed && previous == null) ChangeKind.NEW_UNCONFIRMED else null
        }
        if (previous == null || previous.verification != Verification.VERIFIED) return ChangeKind.NEW_VERIFIED
        if (current.verifiedStatus.rankOrMinus() > previous.verifiedStatus.rankOrMinus()) return ChangeKind.STATUS_ADVANCED
        return null
    }

    fun matches(filters: NotificationFilters, item: LegalItem): Boolean {
        if (!filters.enabled) return false
        if (filters.types.isNotEmpty() && filters.types.intersect(item.typeFilters()).isEmpty()) return false
        if (filters.issuers.isNotEmpty() &&
            item.issuer !in filters.issuers && item.initiator !in filters.issuers) return false
        if (filters.topics.isNotEmpty() && filters.topics.intersect(item.topics.toSet()).isEmpty()) return false
        val urgency = (item.verifiedStatus ?: item.status)?.urgency
        if (filters.urgencies.isNotEmpty() && urgency !in filters.urgencies) return false
        if (filters.keywords.isNotEmpty() && filters.keywords.none { keywordMatches(it, item) }) return false
        return true
    }

    /**
     * Keyword match on whole words, Amharic-aware: "መረጃ" matches "የመረጃ", "ፍትሕ"
     * matches "ፍትህ", "tax" matches "taxation". Multi-word keywords match as a phrase.
     */
    fun keywordMatches(keyword: String, item: LegalItem): Boolean {
        val kw = EthiopicText.tokens(keyword)
        if (kw.isEmpty()) return false
        val hay = EthiopicText.indexTokens(searchableText(item))
        if (kw.size == 1) return hay.any { it.startsWith(kw[0]) }
        outer@ for (i in 0..hay.size - kw.size) {
            for (j in kw.indices) {
                val ok = if (j == kw.lastIndex) hay[i + j].startsWith(kw[j]) else hay[i + j] == kw[j]
                if (!ok) continue@outer
            }
            return true
        }
        // Prefix-stripped variants interleave in `hay`; also try on plain tokens.
        val plain = EthiopicText.tokens(searchableText(item))
        return plain.windowed(kw.size).any { w ->
            w.indices.all { j -> if (j == kw.lastIndex) w[j].startsWith(kw[j]) else w[j] == kw[j] }
        }
    }

    private fun searchableText(item: LegalItem) = listOfNotNull(
        item.titleEn, item.titleAm, item.summaryEn, item.summaryAm, item.lawId, item.lawIdAm,
    ).joinToString(" \n ")

    /** Notification channel the item belongs to (for per-urgency system settings). */
    fun channelFor(item: LegalItem): String = when ((item.verifiedStatus ?: item.status)?.urgency) {
        Urgency.IN_FORCE, Urgency.ENDED -> "in_force"
        Urgency.IMMINENT -> "imminent"
        else -> "upcoming"
    }

    fun statusPhrase(kind: ChangeKind, status: LegalStatus?, amharic: Boolean): String = when {
        kind == ChangeKind.NEW_VERIFIED && amharic -> "አዲስ የተረጋገጠ የሕግ ለውጥ"
        kind == ChangeKind.NEW_VERIFIED -> "New verified development"
        kind == ChangeKind.NEW_UNCONFIRMED && amharic -> "አዲስ የሕግ መረጃ (ያልተረጋገጠ)"
        kind == ChangeKind.NEW_UNCONFIRMED -> "New legal update (unconfirmed)"
        amharic -> when (status) {
            LegalStatus.APPROVED -> "ጸድቋል — በጋዜጣ የሚጠበቅ"
            LegalStatus.IN_FORCE -> "በሥራ ላይ ውሏል"
            LegalStatus.AMENDED -> "ተሻሽሏል"
            LegalStatus.REPEALED -> "ተሽሯል"
            LegalStatus.TABLED -> "ለምክር ቤቱ ቀርቧል"
            else -> "ሁኔታው ተቀይሯል"
        }
        else -> when (status) {
            LegalStatus.APPROVED -> "Approved — about to be published"
            LegalStatus.IN_FORCE -> "Now in force"
            LegalStatus.AMENDED -> "Amended"
            LegalStatus.REPEALED -> "Repealed"
            LegalStatus.TABLED -> "Now before parliament"
            else -> "Status changed"
        }
    }
}
