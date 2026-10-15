package app.lexet.domain.text

import java.time.LocalDate

/** A date in the Ethiopian calendar (month 13 = Pagume, 5 or 6 days). */
data class EthiopianDate(val year: Int, val month: Int, val day: Int)

/**
 * Ethiopian ↔ Gregorian conversion (Julian Day Number arithmetic, Amete Mihret
 * epoch). Mirrors backend/lexet/text/calendar.py.
 */
object EthiopianCalendar {
    private const val EPOCH = 1723856L
    private const val JDN_OFFSET = 2440588L // JDN of 1970-01-01

    val monthsAm = listOf(
        "መስከረም", "ጥቅምት", "ኅዳር", "ታኅሣሥ", "ጥር", "የካቲት",
        "መጋቢት", "ሚያዝያ", "ግንቦት", "ሰኔ", "ሐምሌ", "ነሐሴ", "ጳጉሜ",
    )
    val monthsEn = listOf(
        "Meskerem", "Tikimt", "Hidar", "Tahsas", "Tir", "Yekatit",
        "Megabit", "Miyazya", "Ginbot", "Sene", "Hamle", "Nehase", "Pagume",
    )
    /** Gregorian month names as written in Amharic. */
    val gregorianMonthsAm = listOf(
        "ጃንዩወሪ", "ፌብሩወሪ", "ማርች", "ኤፕሪል", "ሜይ", "ጁን",
        "ጁላይ", "ኦገስት", "ሴፕቴምበር", "ኦክቶበር", "ኖቬምበር", "ዲሴምበር",
    )

    fun isLeapYear(ecYear: Int): Boolean = ecYear % 4 == 3

    fun daysInMonth(ecYear: Int, month: Int): Int =
        if (month == 13) (if (isLeapYear(ecYear)) 6 else 5) else 30

    fun toGregorian(d: EthiopianDate): LocalDate {
        require(d.month in 1..13 && d.day in 1..daysInMonth(d.year, d.month)) { "invalid Ethiopian date $d" }
        val jdn = (EPOCH + 365) + 365L * (d.year - 1) + Math.floorDiv(d.year, 4) + 30L * d.month + d.day - 31
        return LocalDate.ofEpochDay(jdn - JDN_OFFSET)
    }

    fun fromGregorian(date: LocalDate): EthiopianDate {
        val jdn = date.toEpochDay() + JDN_OFFSET
        val r = Math.floorMod(jdn - EPOCH, 1461L)
        val n = r % 365 + 365 * (r / 1460)
        val year = 4 * Math.floorDiv(jdn - EPOCH, 1461L) + r / 365 - r / 1460
        return EthiopianDate(year.toInt(), (n / 30 + 1).toInt(), (n % 30 + 1).toInt())
    }

    fun newYear(ecYear: Int): LocalDate = toGregorian(EthiopianDate(ecYear, 1, 1))

    fun format(date: LocalDate, amharic: Boolean): String {
        val e = fromGregorian(date)
        return if (amharic) "${monthsAm[e.month - 1]} ${e.day} ቀን ${e.year} ዓ.ም"
        else "${monthsEn[e.month - 1]} ${e.day}, ${e.year} E.C."
    }

    fun formatGregorian(date: LocalDate, amharic: Boolean): String {
        val months = if (amharic) gregorianMonthsAm else java.time.Month.values().map {
            it.name.lowercase().replaceFirstChar { c -> c.uppercase() }
        }
        return "${date.dayOfMonth} ${months[date.monthValue - 1]} ${date.year}"
    }
}
