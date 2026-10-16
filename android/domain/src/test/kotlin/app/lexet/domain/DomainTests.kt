package app.lexet.domain

import app.lexet.domain.feed.FeedMixer
import app.lexet.domain.model.DocType
import app.lexet.domain.model.Jurisdiction
import app.lexet.domain.model.LegalItem
import app.lexet.domain.model.LegalStatus
import app.lexet.domain.model.MiniJson
import app.lexet.domain.model.SourceRef
import app.lexet.domain.model.Taxonomy
import app.lexet.domain.model.Urgency
import app.lexet.domain.model.Verification
import app.lexet.domain.notify.ChangeKind
import app.lexet.domain.notify.DigestMode
import app.lexet.domain.notify.DigestPlanner
import app.lexet.domain.notify.NotificationFilters
import app.lexet.domain.notify.NotificationRules
import app.lexet.domain.notify.PendingNotice
import app.lexet.domain.notify.TypeFilter
import app.lexet.domain.report.CalendarSystem
import app.lexet.domain.report.PeriodKind
import app.lexet.domain.report.ReportBuilder
import app.lexet.domain.report.ReportPeriods
import app.lexet.domain.search.SearchIndex
import app.lexet.domain.text.EthiopianCalendar
import app.lexet.domain.text.EthiopianDate
import app.lexet.domain.text.EthiopicText
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun sharedFile(name: String): File {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
        val f = File(dir, "shared/$name")
        if (f.exists()) return f
        dir = dir.parentFile
    }
    error("shared/$name not found")
}

fun item(
    id: String = "i1",
    jurisdiction: Jurisdiction = Jurisdiction.ET,
    docType: DocType = DocType.PROCLAMATION,
    status: LegalStatus? = LegalStatus.IN_FORCE,
    verifiedStatus: LegalStatus? = status,
    verification: Verification = Verification.VERIFIED,
    titleEn: String? = "Freedom of Information Proclamation",
    titleAm: String? = "የመረጃ ነፃነት አዋጅ",
    lawNumber: Int? = 1427,
    issuer: String? = "HPR",
    topics: List<String> = listOf("rights"),
    eventDate: LocalDate? = LocalDate.of(2026, 6, 25),
    statusDate: LocalDate? = eventDate,
    sources: List<SourceRef> = emptyList(),
) = LegalItem(
    id = id, version = 1, jurisdiction = jurisdiction, docType = docType,
    lawId = lawNumber?.let { "Proclamation No. $it/2026" }, lawNumber = lawNumber,
    lawYearGc = lawNumber?.let { 2026 }, lawYearEc = lawNumber?.let { 2018 },
    titleEn = titleEn, titleAm = titleAm, summaryEn = null, summaryAm = null,
    issuer = issuer, initiator = null, status = status, verifiedStatus = verifiedStatus,
    reportedStatus = status, verification = verification, sourceCount = 3, hasOfficialSource = true,
    topics = topics, amends = null, eventDate = eventDate, statusDate = statusDate,
    firstSeenAt = null, updatedAt = null, sources = sources,
)

class EthiopicTextTest {
    @Test
    fun sharedVectorsMatchBackend() {
        @Suppress("UNCHECKED_CAST")
        val data = MiniJson.parse(sharedFile("ethiopic_vectors.json").readText()) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val vectors = data["vectors"] as List<Map<String, Any?>>
        assertTrue(vectors.size > 20)
        for (v in vectors) {
            val input = v["input"] as String
            assertEquals(v["normalized"], EthiopicText.normalize(input), "normalize($input)")
            assertEquals(v["index_tokens"], EthiopicText.indexTokens(input), "indexTokens($input)")
        }
    }

    @Test
    fun geezNumerals() {
        assertEquals(1427, EthiopicText.geezToInt("፲፬፻፳፯"))
        assertEquals(2018, EthiopicText.geezToInt("፳፻፲፰"))
        assertEquals(12345, EthiopicText.geezToInt("፼፳፫፻፵፭"))
    }

    @Test
    fun languageDetection() {
        assertEquals("am", EthiopicText.detectLang("የሕዝብ ተወካዮች ምክር ቤት"))
        assertEquals("en", EthiopicText.detectLang("House of Peoples' Representatives"))
    }
}

class CalendarTest {
    @Test
    fun knownDates() {
        assertEquals(LocalDate.of(2023, 9, 12), EthiopianCalendar.toGregorian(EthiopianDate(2016, 1, 1)))
        assertEquals(LocalDate.of(2026, 6, 25), EthiopianCalendar.toGregorian(EthiopianDate(2018, 10, 18)))
        assertEquals(EthiopianDate(2019, 2, 5), EthiopianCalendar.fromGregorian(LocalDate.of(2026, 10, 15)))
    }

    @Test
    fun roundTripTenYears() {
        var d = LocalDate.of(2020, 1, 1)
        while (d.isBefore(LocalDate.of(2030, 1, 1))) {
            assertEquals(d, EthiopianCalendar.toGregorian(EthiopianCalendar.fromGregorian(d)))
            d = d.plusDays(1)
        }
    }

    @Test
    fun formatting() {
        assertEquals("ሰኔ 18 ቀን 2018 ዓ.ም", EthiopianCalendar.format(LocalDate.of(2026, 6, 25), true))
        assertEquals("Tikimt 5, 2019 E.C.", EthiopianCalendar.format(LocalDate.of(2026, 10, 15), false))
    }
}

class TaxonomyTest {
    @Test
    fun parsesSharedTaxonomy() {
        val t = Taxonomy.parse(sharedFile("taxonomy.json").readText())
        assertEquals("አዋጅ", t.docType("proclamation")?.am)
        assertEquals("Ethiopian Food and Drug Authority", t.issuer("EFDA")?.en)
        assertEquals("About to be released", t.urgency("imminent")?.en)
        // Every status code the app knows exists in the shared file.
        for (s in LegalStatus.entries) assertNotNull(t.status(s.code), s.code)
        for (d in DocType.entries) assertNotNull(t.docType(d.code), d.code)
        for (u in Urgency.entries) assertNotNull(t.urgency(u.code), u.code)
    }
}

class NotificationRulesTest {
    private val unverified = item(verification = Verification.UNCONFIRMED, verifiedStatus = null, status = LegalStatus.TABLED)

    @Test
    fun onlyVerifiedEthiopianChangesNotify() {
        val v = item(status = LegalStatus.TABLED)
        assertEquals(ChangeKind.NEW_VERIFIED, NotificationRules.detectChange(null, v, bootstrap = false))
        assertEquals(ChangeKind.NEW_VERIFIED, NotificationRules.detectChange(unverified, v, false))
        assertNull(NotificationRules.detectChange(null, unverified, false))
        assertEquals(ChangeKind.NEW_UNCONFIRMED, NotificationRules.detectChange(null, unverified, false, includeUnconfirmed = true))
        assertNull(NotificationRules.detectChange(unverified, unverified.copy(titleEn = "Edited"), false, includeUnconfirmed = true))
        assertEquals(ChangeKind.NEW_VERIFIED, NotificationRules.detectChange(unverified, v, false, includeUnconfirmed = true))
        assertNull(NotificationRules.detectChange(null, v.copy(jurisdiction = Jurisdiction.INTL), false))
        assertNull(NotificationRules.detectChange(null, v, bootstrap = true))
        assertNull(NotificationRules.detectChange(v, v.copy(titleEn = "Edited"), false))
        val inForce = v.copy(status = LegalStatus.IN_FORCE, verifiedStatus = LegalStatus.IN_FORCE)
        assertEquals(ChangeKind.STATUS_ADVANCED, NotificationRules.detectChange(v, inForce, false))
        // A reported-but-uncorroborated stage does not notify.
        assertNull(NotificationRules.detectChange(v, v.copy(status = LegalStatus.APPROVED), false))
    }

    @Test
    fun filtersByTypeIssuerTopicUrgencyKeyword() {
        val draft = item(status = LegalStatus.TABLED, verifiedStatus = LegalStatus.TABLED, topics = listOf("digital"))
        assertTrue(NotificationRules.matches(NotificationFilters(), draft))
        assertTrue(NotificationRules.matches(NotificationFilters(types = setOf(TypeFilter.DRAFT_BILL)), draft))
        assertFalse(NotificationRules.matches(NotificationFilters(types = setOf(TypeFilter.DIRECTIVE)), draft))
        assertTrue(NotificationRules.matches(NotificationFilters(issuers = setOf("HPR")), draft))
        assertFalse(NotificationRules.matches(NotificationFilters(issuers = setOf("NBE")), draft))
        assertFalse(NotificationRules.matches(NotificationFilters(topics = setOf("tax")), draft))
        assertTrue(NotificationRules.matches(NotificationFilters(urgencies = setOf(Urgency.UPCOMING)), draft))
        assertFalse(NotificationRules.matches(NotificationFilters(urgencies = setOf(Urgency.IN_FORCE)), draft))
        assertFalse(NotificationRules.matches(NotificationFilters(enabled = false), draft))
    }

    @Test
    fun keywordsAreAmharicAware() {
        val it = item()
        assertTrue(NotificationRules.keywordMatches("መረጃ", it))          // matches የመረጃ
        assertTrue(NotificationRules.keywordMatches("ነጻነት", it))         // ፃ/ጻ homophones
        assertTrue(NotificationRules.keywordMatches("information", it))
        assertTrue(NotificationRules.keywordMatches("freedom of info", it))
        assertTrue(NotificationRules.keywordMatches("1427", it))
        assertFalse(NotificationRules.keywordMatches("mining", it))
        assertFalse(NotificationRules.keywordMatches("of freedom", it))
    }
}

class FeedMixerTest {
    @Test
    fun tenToOne() {
        val et = (1..25).map { "e$it" }
        val intl = listOf("i1", "i2", "i3", "i4")
        val mixed = FeedMixer.interleave(et, intl, ratio = 10)
        assertEquals(27, mixed.size)
        assertEquals("i1", mixed[10].value)
        assertEquals("i2", mixed[21].value)
        assertTrue(mixed[10] is FeedMixer.Entry.Secondary)
        val end = FeedMixer.interleave(et, intl, ratio = 10, primaryExhausted = true)
        assertEquals(29, end.size)
    }
}

class DigestTest {
    @Test
    fun buildsOneLinePerItem() {
        val t0 = Instant.parse("2026-10-15T06:00:00Z")
        val p = listOf(
            PendingNotice("a", "Digital Commerce Proclamation", null, ChangeKind.NEW_VERIFIED, "New verified development", t0),
            PendingNotice("a", "Digital Commerce Proclamation", "Proclamation No. 1431/2026", ChangeKind.STATUS_ADVANCED, "Now in force", t0.plusSeconds(60)),
            PendingNotice("b", "Cosmetics Directive", null, ChangeKind.NEW_VERIFIED, "New verified development", t0.plusSeconds(30)),
        )
        val d = DigestPlanner.build(p, amharic = false)!!
        assertEquals("2 verified legal developments", d.title)
        assertEquals("Proclamation No. 1431/2026 · Digital Commerce Proclamation — Now in force", d.lines[0])
        assertNull(DigestPlanner.build(emptyList(), false))
    }

    @Test
    fun schedule() {
        val now = ZonedDateTime.of(2026, 10, 15, 15, 44, 0, 0, ZoneId.of("Africa/Addis_Ababa"))
        assertEquals(16, DigestPlanner.delayUntilNext(DigestMode.HOURLY, now).toMinutes())
        assertEquals(16 * 60 + 16, DigestPlanner.delayUntilNext(DigestMode.DAILY, now, 8).toMinutes())
    }
}

class SearchTest {
    @Test
    fun matchExpression() {
        assertEquals("መረጃ* ነጻነት*", SearchIndex.matchExpression("መረጃ ነፃነት"))
        assertEquals("1427* 2018*", SearchIndex.matchExpression("1427/2018"))
        assertEquals("proclamation* a", SearchIndex.matchExpression("Proclamation \"a\" -- *"))
        assertNull(SearchIndex.matchExpression("  ።፡ "))
    }

    @Test
    fun documentHoldsBothYearsAndPrefixVariants() {
        val t = Taxonomy.parse(sharedFile("taxonomy.json").readText())
        val doc = SearchIndex.document(item(), t)
        assertTrue(" 2026 " in " ${doc.lawId} " && " 2018 " in " ${doc.lawId} ")
        assertTrue(" መረጃ " in " ${doc.titles} ")          // stripped from የመረጃ
        assertTrue("ህዝብ" in doc.meta)                       // issuer, folded
    }
}

class ReportTest {
    @Test
    fun periodsInBothCalendars() {
        val d = LocalDate.of(2026, 10, 15)
        val w = ReportPeriods.containing(d, PeriodKind.WEEK, CalendarSystem.GREGORIAN)
        assertEquals(LocalDate.of(2026, 10, 12), w.start)
        assertEquals(LocalDate.of(2026, 10, 18), w.endInclusive)
        val ecMonth = ReportPeriods.containing(d, PeriodKind.MONTH, CalendarSystem.ETHIOPIAN)
        assertEquals(LocalDate.of(2026, 10, 11), ecMonth.start)      // Tikimt 1, 2019
        assertEquals(LocalDate.of(2026, 11, 9), ecMonth.endInclusive)
        assertEquals("ጥቅምት 2019 ዓ.ም", ecMonth.label(true))
        val ecYear = ReportPeriods.containing(d, PeriodKind.YEAR, CalendarSystem.ETHIOPIAN)
        assertEquals(LocalDate.of(2026, 9, 11), ecYear.start)
        assertEquals(LocalDate.of(2027, 9, 11), ecYear.endInclusive)  // 2019 E.C. is a leap year
        fun pagumeDays(d: LocalDate) = ReportPeriods.containing(d, PeriodKind.MONTH, CalendarSystem.ETHIOPIAN)
            .let { (it.endInclusive.toEpochDay() - it.start.toEpochDay() + 1).toInt() }
        assertEquals(5, pagumeDays(LocalDate.of(2026, 9, 8)))   // Pagume 2018
        assertEquals(6, pagumeDays(LocalDate.of(2027, 9, 8)))   // Pagume 2019
        assertEquals(LocalDate.of(2026, 9, 1),
            ReportPeriods.lastCompleted(d, PeriodKind.MONTH, CalendarSystem.GREGORIAN).start)
    }

    @Test
    fun buildsSectionsCitationsAndStatusChanges() {
        val src = { u: String, tier: Int -> SourceRef("P$tier", "Pub$tier", tier, u, "t", "en", null, true, "independent", false, false, null) }
        val period = ReportPeriods.containing(LocalDate.of(2026, 6, 25), PeriodKind.MONTH, CalendarSystem.GREGORIAN)
        val items = listOf(
            item(id = "p", sources = listOf(src("https://a", 3), src("https://b", 1))),
            item(id = "d", docType = DocType.DIRECTIVE, lawNumber = 30, eventDate = LocalDate.of(2026, 6, 2),
                sources = listOf(src("https://b", 1))),
            item(id = "old", eventDate = LocalDate.of(2026, 3, 2), statusDate = LocalDate.of(2026, 6, 20), lawNumber = 1431),
            item(id = "x", jurisdiction = Jurisdiction.INTL),
            item(id = "late", eventDate = LocalDate.of(2026, 7, 2)),
        )
        val r = ReportBuilder.build(items, period, Instant.EPOCH, null)
        assertEquals(listOf("proclamation", "directive", "status_changes"), r.sections.map { it.code })
        assertEquals(listOf(1, 2, 3), r.sections.flatMap { s -> s.entries.map { it.number } })
        assertEquals(listOf(1, 2), r.sections[0].entries[0].citations)   // official source cited first
        assertEquals(listOf(1), r.sections[1].entries[0].citations)       // shared citation reused
        assertEquals(2, r.citations.size)
        assertEquals(mapOf("new" to 2, "status_changes" to 1, "verified" to 3, "unconfirmed" to 0), r.totals)
    }
}

class ClipTests {
    @org.junit.Test fun linkedInShareWithLawNumber() {
        val c = app.lexet.domain.clip.ClipParser.parse(
            "Parliament approved the Digital Commerce Proclamation No. 1431/2026 today. A big step for e-commerce.\nhttps://www.linkedin.com/posts/abebe_digital-activity-123?utm_source=share",
            null)
        kotlin.test.assertEquals("LinkedIn", c.platform)
        kotlin.test.assertEquals("https://www.linkedin.com/posts/abebe_digital-activity-123?utm_source=share", c.url)
        kotlin.test.assertEquals("Proclamation No. 1431/2026", c.lawIdText)
        kotlin.test.assertEquals(false, c.yearIsEthiopian)
        kotlin.test.assertEquals("Parliament approved the Digital Commerce Proclamation No. 1431/2026 today.", c.title)
    }

    @org.junit.Test fun amharicPostAndBareLink() {
        val am = app.lexet.domain.clip.ClipParser.parse("የመረጃ ደኅንነት አዋጅ ቁጥር 1450/2018 ጸደቀ https://t.me/ethiolaw/77", null)
        kotlin.test.assertEquals("Telegram", am.platform)
        kotlin.test.assertEquals(app.lexet.domain.model.DocType.PROCLAMATION, am.docType)
        kotlin.test.assertEquals(1450, am.lawNumber)
        kotlin.test.assertEquals(true, am.yearIsEthiopian)
        kotlin.test.assertEquals(true, am.amharic)
        val bare = app.lexet.domain.clip.ClipParser.parse("https://lnkd.in/abc", "")
        kotlin.test.assertEquals("LinkedIn post", bare.title)
        kotlin.test.assertEquals(null, bare.lawNumber)
    }

    @org.junit.Test fun webSearchScopes() {
        val u = app.lexet.domain.clip.WebSearch.url("investment proclamation", app.lexet.domain.clip.WebScope.LINKEDIN)
        kotlin.test.assertTrue(u.startsWith("https://www.google.com/search?q=investment+proclamation+%28site%3Alinkedin.com%2Fposts"))
        kotlin.test.assertTrue("site:chilot.me" in app.lexet.domain.clip.WebSearch.query("x", app.lexet.domain.clip.WebScope.ETHIOPIAN_LEGAL_SITES))
    }
}
