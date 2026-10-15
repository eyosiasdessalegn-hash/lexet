package app.lexet.domain.report

import app.lexet.domain.model.DocType
import app.lexet.domain.model.Jurisdiction
import app.lexet.domain.model.LegalItem
import app.lexet.domain.model.Taxonomy
import app.lexet.domain.text.EthiopianCalendar
import app.lexet.domain.text.EthiopianDate
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

enum class PeriodKind { WEEK, MONTH, YEAR }
enum class CalendarSystem { GREGORIAN, ETHIOPIAN }

/** A reporting period. Weeks run Monday–Sunday; months and years follow [calendar]. */
data class ReportPeriod(
    val kind: PeriodKind,
    val calendar: CalendarSystem,
    val start: LocalDate,
    val endInclusive: LocalDate,
) {
    val key: String get() = "${kind.name.lowercase()}-${calendar.name.take(2).lowercase()}-$start"

    operator fun contains(d: LocalDate) = !d.isBefore(start) && !d.isAfter(endInclusive)

    fun label(amharic: Boolean): String {
        val e = EthiopianCalendar.fromGregorian(start)
        return when (kind) {
            PeriodKind.WEEK -> if (amharic)
                "ሳምንታዊ፦ ${EthiopianCalendar.format(start, true)} – ${EthiopianCalendar.format(endInclusive, true)}"
            else "Week of ${EthiopianCalendar.formatGregorian(start, false)} – ${EthiopianCalendar.formatGregorian(endInclusive, false)}"
            PeriodKind.MONTH -> when (calendar) {
                CalendarSystem.ETHIOPIAN -> if (amharic) "${EthiopianCalendar.monthsAm[e.month - 1]} ${e.year} ዓ.ም"
                else "${EthiopianCalendar.monthsEn[e.month - 1]} ${e.year} E.C."
                CalendarSystem.GREGORIAN -> if (amharic) "${EthiopianCalendar.gregorianMonthsAm[start.monthValue - 1]} ${start.year}"
                else "${start.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${start.year}"
            }
            PeriodKind.YEAR -> when (calendar) {
                CalendarSystem.ETHIOPIAN -> if (amharic) "${e.year} ዓ.ም" else "${e.year} E.C."
                CalendarSystem.GREGORIAN -> "${start.year}"
            }
        }
    }
}

object ReportPeriods {
    fun containing(date: LocalDate, kind: PeriodKind, calendar: CalendarSystem): ReportPeriod = when (kind) {
        PeriodKind.WEEK -> {
            val s = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            ReportPeriod(kind, calendar, s, s.plusDays(6))
        }
        PeriodKind.MONTH -> when (calendar) {
            CalendarSystem.GREGORIAN -> ReportPeriod(kind, calendar, date.withDayOfMonth(1),
                date.with(TemporalAdjusters.lastDayOfMonth()))
            CalendarSystem.ETHIOPIAN -> {
                val e = EthiopianCalendar.fromGregorian(date)
                val s = EthiopianCalendar.toGregorian(EthiopianDate(e.year, e.month, 1))
                ReportPeriod(kind, calendar, s, s.plusDays(EthiopianCalendar.daysInMonth(e.year, e.month) - 1L))
            }
        }
        PeriodKind.YEAR -> when (calendar) {
            CalendarSystem.GREGORIAN -> ReportPeriod(kind, calendar, date.withDayOfYear(1),
                date.with(TemporalAdjusters.lastDayOfYear()))
            CalendarSystem.ETHIOPIAN -> {
                val y = EthiopianCalendar.fromGregorian(date).year
                ReportPeriod(kind, calendar, EthiopianCalendar.newYear(y), EthiopianCalendar.newYear(y + 1).minusDays(1))
            }
        }
    }

    fun previous(p: ReportPeriod): ReportPeriod = containing(p.start.minusDays(1), p.kind, p.calendar)

    /** The most recent period that has fully ended before [today]. */
    fun lastCompleted(today: LocalDate, kind: PeriodKind, calendar: CalendarSystem): ReportPeriod =
        previous(containing(today, kind, calendar))
}

data class ReportEntry(val number: Int, val item: LegalItem, val citations: List<Int>, val statusChange: Boolean)
data class ReportSection(val code: String, val titleEn: String, val titleAm: String, val entries: List<ReportEntry>)
data class Citation(val number: Int, val publisher: String, val title: String, val url: String, val tier: Int)

data class Report(
    val period: ReportPeriod,
    val generatedAt: Instant,
    val sections: List<ReportSection>,
    val citations: List<Citation>,
    val totals: Map<String, Int>,
) {
    val entryCount get() = sections.sumOf { it.entries.size }
    val isEmpty get() = entryCount == 0
}

/**
 * Builds a compilation of Ethiopian legal developments for a period:
 *  - one section per document type for instruments first reported in the period,
 *  - a "status changes" section for older instruments that changed stage in it
 *    (e.g. a draft from last month that entered into force this week),
 *  - numbered citations (every corroborating source) for the table of contents
 *    and the reference list.
 */
object ReportBuilder {
    private val order = listOf(DocType.PROCLAMATION, DocType.REGULATION, DocType.DIRECTIVE,
        DocType.POLICY, DocType.NOTICE, DocType.OTHER)

    fun itemsFor(period: ReportPeriod, all: List<LegalItem>): Pair<List<LegalItem>, List<LegalItem>> {
        val et = all.filter { it.jurisdiction == Jurisdiction.ET }
        val new = et.filter { it.eventDate != null && it.eventDate in period }
        val newIds = new.map { it.id }.toSet()
        val changed = et.filter { it.id !in newIds && it.statusDate != null && it.statusDate in period }
        return new to changed
    }

    fun build(all: List<LegalItem>, period: ReportPeriod, generatedAt: Instant, taxonomy: Taxonomy?): Report {
        val (new, changed) = itemsFor(period, all)
        val citations = ArrayList<Citation>()
        val citeIndex = HashMap<String, Int>()
        var n = 0
        fun cite(item: LegalItem): List<Int> = item.sources.sortedBy { it.tier }.map { s ->
            citeIndex.getOrPut(s.url) {
                citations.add(Citation(citations.size + 1, s.name, s.title, s.url, s.tier))
                citations.size
            }
        }
        val byDate = compareBy<LegalItem>({ it.eventDate }, { it.lawNumber ?: Int.MAX_VALUE }, { it.titleEn ?: it.titleAm })
        val sections = ArrayList<ReportSection>()
        for (type in order) {
            val items = new.filter { it.docType == type }.sortedWith(byDate)
            if (items.isEmpty()) continue
            val label = taxonomy?.docType(type.code)
            sections.add(ReportSection(type.code, label?.en ?: type.code, label?.am ?: type.code,
                items.map { ReportEntry(++n, it, cite(it), statusChange = false) }))
        }
        if (changed.isNotEmpty()) {
            sections.add(ReportSection("status_changes", "Status changes during the period", "በወቅቱ የሁኔታ ለውጦች",
                changed.sortedWith(compareBy({ it.statusDate }, { it.lawNumber ?: Int.MAX_VALUE }))
                    .map { ReportEntry(++n, it, cite(it), statusChange = true) }))
        }
        val totals = linkedMapOf(
            "new" to new.size,
            "status_changes" to changed.size,
            "verified" to (new + changed).count { it.isVerified },
            "unconfirmed" to (new + changed).count { !it.isVerified },
        )
        return Report(period, generatedAt, sections, citations, totals)
    }
}
