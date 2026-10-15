package app.lexet.data

import app.lexet.domain.model.Urgency
import app.lexet.domain.notify.TypeFilter
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchiveQueryTest {
    private val today = LocalDate.of(2026, 10, 15)

    @Test
    fun defaultFilterIsEthiopiaOnlyNewestFirst() {
        val (sql, args) = buildArchiveQuery(ArchiveFilter(), 500, today)
        assertTrue("i.jurisdiction = 'ET'" in sql)
        assertTrue(sql.endsWith("LIMIT 500"))
        assertTrue(args.isEmpty())
        assertFalse("MATCH" in sql)
    }

    @Test
    fun textUsesFtsWithNormalisedPrefixTerms() {
        val (sql, args) = buildArchiveQuery(ArchiveFilter(text = "የመረጃ ነፃነት"), 50, today)
        assertTrue("item_fts MATCH ?" in sql)
        assertEquals(listOf<Any>("የመረጃ* ነጻነት*"), args)
    }

    @Test
    fun draftBillAndUrgencyAndTopicsCombine() {
        val f = ArchiveFilter(types = setOf(TypeFilter.DRAFT_BILL), urgencies = setOf(Urgency.IMMINENT),
            topics = setOf("tax", "mining"), sinceDays = 30, issuer = "MOF")
        val (sql, args) = buildArchiveQuery(f, 100, today)
        assertTrue("i.status IN ('policy_signal','draft','tabled')" in sql)
        assertTrue("COALESCE(i.verifiedStatus, i.status) IN (?)" in sql)
        assertEquals(listOf<Any>("approved", "MOF", "MOF", "%,tax,%", "%,mining,%", today.minusDays(30).toEpochDay()), args)
        assertEquals(sql.count { it == '?' }, args.size)
    }
}
