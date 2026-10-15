package app.lexet.data

import app.lexet.domain.model.LegalStatus
import app.lexet.domain.model.Urgency
import app.lexet.domain.notify.TypeFilter
import app.lexet.domain.search.SearchIndex
import java.time.LocalDate

enum class VerificationFilter { ALL, VERIFIED, UNCONFIRMED }
enum class JurisdictionFilter { ALL, ETHIOPIA, INTERNATIONAL }

/** Archive / search criteria. Empty sets mean "any". */
data class ArchiveFilter(
    val text: String = "",
    val types: Set<TypeFilter> = emptySet(),
    val urgencies: Set<Urgency> = emptySet(),
    val verification: VerificationFilter = VerificationFilter.ALL,
    val jurisdiction: JurisdictionFilter = JurisdictionFilter.ETHIOPIA,
    val issuer: String? = null,
    val topics: Set<String> = emptySet(),
    val sinceDays: Int? = null,
    val bookmarkedOnly: Boolean = false,
    val unreadOnly: Boolean = false,
    val tagId: Long? = null,
) {
    val isDefault get() = this == ArchiveFilter(text = text)
}

/** SQL for the archive screen. Kept separate so it can be unit-tested. */
fun buildArchiveQuery(f: ArchiveFilter, limit: Int, today: LocalDate): Pair<String, List<Any>> {
    val where = ArrayList<String>()
    val args = ArrayList<Any>()

    when (f.jurisdiction) {
        JurisdictionFilter.ETHIOPIA -> where += "i.jurisdiction = 'ET'"
        JurisdictionFilter.INTERNATIONAL -> where += "i.jurisdiction = 'INTL'"
        JurisdictionFilter.ALL -> {}
    }
    SearchIndex.matchExpression(f.text)?.let {
        where += "i.ftsDocId IN (SELECT rowid FROM item_fts WHERE item_fts MATCH ?)"
        args.add(it)
    }
    if (f.types.isNotEmpty()) {
        val parts = f.types.map { t ->
            when (t) {
                TypeFilter.PROCLAMATION -> "i.docType = 'proclamation'"
                TypeFilter.REGULATION -> "i.docType = 'regulation'"
                TypeFilter.DIRECTIVE -> "i.docType = 'directive'"
                TypeFilter.POLICY -> "i.docType = 'policy'"
                TypeFilter.NOTICE -> "i.docType = 'notice'"
                TypeFilter.OTHER -> "i.docType = 'other'"
                TypeFilter.DRAFT_BILL ->
                    "(i.docType IN ('proclamation','regulation') AND i.status IN ('policy_signal','draft','tabled'))"
            }
        }
        where += parts.joinToString(" OR ", "(", ")")
    }
    if (f.urgencies.isNotEmpty()) {
        val codes = LegalStatus.entries.filter { it.urgency in f.urgencies }.map { it.code }
        where += codes.joinToString(",", "COALESCE(i.verifiedStatus, i.status) IN (", ")") { "?" }
        args.addAll(codes)
    }
    when (f.verification) {
        VerificationFilter.VERIFIED -> where += "i.verification = 'verified'"
        VerificationFilter.UNCONFIRMED -> where += "i.verification = 'unconfirmed'"
        VerificationFilter.ALL -> {}
    }
    f.issuer?.let { where += "(i.issuer = ? OR i.initiator = ?)"; args.add(it); args.add(it) }
    if (f.topics.isNotEmpty()) {
        where += f.topics.joinToString(" OR ", "(", ")") { "i.topics LIKE ?" }
        args.addAll(f.topics.map { "%,$it,%" })
    }
    f.sinceDays?.let {
        where += "MAX(COALESCE(i.statusDate, 0), COALESCE(i.eventDate, 0)) >= ?"
        args.add(today.minusDays(it.toLong()).toEpochDay())
    }
    if (f.bookmarkedOnly) where += "COALESCE(u.bookmarked, 0) = 1"
    if (f.unreadOnly) where += "COALESCE(u.read, 0) = 0"
    f.tagId?.let { where += "i.id IN (SELECT itemId FROM item_tags WHERE tagId = ?)"; args.add(it) }

    val sql = buildString {
        append("SELECT i.id, i.jurisdiction, i.docType, i.lawId, i.lawNumber, i.lawYearEc, i.titleEn, i.titleAm, ")
        append("i.issuer, i.status, i.verifiedStatus, i.verification, i.sourceCount, i.hasOfficialSource, ")
        append("i.topics, i.eventDate, i.statusDate, COALESCE(u.read, 0) AS read, COALESCE(u.bookmarked, 0) AS bookmarked ")
        append("FROM items i LEFT JOIN user_state u ON u.itemId = i.id")
        if (where.isNotEmpty()) append(" WHERE ").append(where.joinToString(" AND "))
        append(" ORDER BY MAX(COALESCE(i.statusDate, 0), COALESCE(i.eventDate, 0)) DESC, i.updatedAt DESC LIMIT ").append(limit)
    }
    return sql to args
}
