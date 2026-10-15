package app.lexet.data

import app.lexet.data.db.ItemEntity
import app.lexet.data.db.ItemRow
import app.lexet.data.net.ItemDto
import app.lexet.data.net.LexetJson
import app.lexet.data.net.SourceDto
import app.lexet.domain.model.DocType
import app.lexet.domain.model.Jurisdiction
import app.lexet.domain.model.LegalItem
import app.lexet.domain.model.LegalStatus
import app.lexet.domain.model.SourceRef
import app.lexet.domain.model.Verification
import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

private val sourcesSerializer = ListSerializer(SourceDto.serializer())

internal fun parseDay(s: String?): Long? = s?.takeIf { it.length >= 10 }?.let {
    runCatching { LocalDate.parse(it.substring(0, 10)).toEpochDay() }.getOrNull()
}

internal fun parseInstant(s: String?): Long? = s?.let {
    runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
}

fun ItemDto.toEntity(ftsDocId: Long): ItemEntity = ItemEntity(
    id = id,
    ftsDocId = ftsDocId,
    version = version,
    jurisdiction = jurisdiction,
    docType = docType,
    lawId = lawId,
    lawNumber = lawNumber,
    lawYearGc = lawYearGc,
    lawYearEc = lawYearEc,
    titleEn = titleEn,
    titleAm = titleAm,
    summaryEn = summaryEn,
    summaryAm = summaryAm,
    issuer = issuer,
    initiator = initiator,
    status = status,
    verifiedStatus = verifiedStatus,
    reportedStatus = reportedStatus,
    verification = verification,
    sourceCount = sourceCount,
    hasOfficialSource = hasOfficialSource,
    topics = if (topics.isEmpty()) "" else topics.joinToString(",", prefix = ",", postfix = ","),
    amends = amends,
    eventDate = parseDay(eventDate),
    statusDate = parseDay(statusDate),
    firstSeenAt = parseInstant(firstSeenAt),
    updatedAt = parseInstant(updatedAt),
    relevance = relevance,
    sourcesJson = LexetJson.encodeToString(sourcesSerializer, sources),
)

private fun SourceDto.toDomain() = SourceRef(
    publisher = publisher, name = name, tier = tier, url = url, title = title, lang = lang,
    publishedAt = parseInstant(publishedAt)?.let { Instant.ofEpochMilli(it) }
        ?: parseDay(publishedAt)?.let { LocalDate.ofEpochDay(it).atStartOfDay(java.time.ZoneOffset.UTC).toInstant() },
    counted = counted, reason = reason, primaryDocument = primaryDocument, gazette = gazette, excerpt = excerpt,
)

fun ItemEntity.toDomain(withSources: Boolean = true): LegalItem = LegalItem(
    id = id,
    version = version,
    jurisdiction = Jurisdiction.of(jurisdiction),
    docType = DocType.of(docType),
    lawId = lawId,
    lawNumber = lawNumber,
    lawYearGc = lawYearGc,
    lawYearEc = lawYearEc,
    titleEn = titleEn,
    titleAm = titleAm,
    summaryEn = summaryEn,
    summaryAm = summaryAm,
    issuer = issuer,
    initiator = initiator,
    status = LegalStatus.of(status),
    verifiedStatus = LegalStatus.of(verifiedStatus),
    reportedStatus = LegalStatus.of(reportedStatus),
    verification = Verification.of(verification),
    sourceCount = sourceCount,
    hasOfficialSource = hasOfficialSource,
    topics = topics.split(',').filter { it.isNotEmpty() },
    amends = amends,
    eventDate = eventDate?.let { LocalDate.ofEpochDay(it) },
    statusDate = statusDate?.let { LocalDate.ofEpochDay(it) },
    firstSeenAt = firstSeenAt?.let { Instant.ofEpochMilli(it) },
    updatedAt = updatedAt?.let { Instant.ofEpochMilli(it) },
    sources = if (withSources) runCatching { LexetJson.decodeFromString(sourcesSerializer, sourcesJson) }
        .getOrDefault(emptyList()).map { it.toDomain() } else emptyList(),
)

/** List-row view used by cards. */
data class ItemCardModel(
    val id: String,
    val jurisdiction: Jurisdiction,
    val docType: DocType,
    val lawId: String?,
    val lawIdAm: String?,
    val titleEn: String?,
    val titleAm: String?,
    val issuer: String?,
    val status: LegalStatus?,
    val verifiedStatus: LegalStatus?,
    val verification: Verification,
    val sourceCount: Int,
    val hasOfficialSource: Boolean,
    val topics: List<String>,
    val date: LocalDate?,
    val read: Boolean,
    val bookmarked: Boolean,
) {
    fun title(amharicFirst: Boolean): String =
        (if (amharicFirst) titleAm ?: titleEn else titleEn ?: titleAm) ?: lawId ?: "—"

    fun secondaryTitle(amharicFirst: Boolean): String? =
        (if (amharicFirst) titleEn else titleAm)?.takeIf { titleEn != null && titleAm != null }
}

fun ItemRow.toCard(): ItemCardModel {
    val doc = DocType.of(docType)
    val am = lawNumber?.let { n ->
        val word = when (doc) {
            DocType.PROCLAMATION -> "አዋጅ"; DocType.REGULATION -> "ደንብ"; DocType.DIRECTIVE -> "መመሪያ"; else -> null
        }
        word?.let { if (lawYearEc != null) "$it ቁጥር $n/$lawYearEc" else "$it ቁጥር $n" }
    }
    return ItemCardModel(
        id = id, jurisdiction = Jurisdiction.of(jurisdiction), docType = doc, lawId = lawId, lawIdAm = am,
        titleEn = titleEn, titleAm = titleAm, issuer = issuer, status = LegalStatus.of(status),
        verifiedStatus = LegalStatus.of(verifiedStatus), verification = Verification.of(verification),
        sourceCount = sourceCount, hasOfficialSource = hasOfficialSource,
        topics = topics.split(',').filter { it.isNotEmpty() },
        date = (statusDate ?: eventDate)?.let { LocalDate.ofEpochDay(it) }, read = read, bookmarked = bookmarked,
    )
}
