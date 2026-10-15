"""Ethiopian (E.C.) ↔ Gregorian (G.C.) calendar conversion.

Why it matters here: the same law is cited with different years depending on
the language of the source. The Freedom of Information Proclamation approved on
25 June 2026 is "No. 1427/2018" in Amharic (Ethiopian year 2018) and
"No. 1427/2026" in English. Amharic dates such as "ሰኔ 18 ቀን 2018 ዓ.ም" also
have to be turned into real dates for ordering and reports.

Algorithm: Julian Day Number arithmetic with the Amete Mihret epoch, as used by
the standard Ethiopic calendar implementations.
"""
from __future__ import annotations

import datetime as dt
import re

from . import ethiopic

_EPOCH = 1723856  # JD epoch offset, Amete Mihret

MONTHS_AM = [
    "መስከረም", "ጥቅምት", "ህዳር", "ታህሳስ", "ጥር", "የካቲት",
    "መጋቢት", "ሚያዝያ", "ግንቦት", "ሰኔ", "ሀምሌ", "ነሀሴ", "ጳጉሜ",
]
MONTHS_EN = [
    "Meskerem", "Tikimt", "Hidar", "Tahsas", "Tir", "Yekatit",
    "Megabit", "Miyazya", "Ginbot", "Sene", "Hamle", "Nehase", "Pagume",
]
# Lookup keyed by the *folded* spelling so ኅዳር/ህዳር, ታኅሣሥ/ታህሳስ, ሐምሌ/ሀምሌ all match.
_MONTH_LOOKUP = {ethiopic.normalize(m): i + 1 for i, m in enumerate(MONTHS_AM)}
_MONTH_LOOKUP.update({
    ethiopic.normalize("ታኅሣሥ"): 4,
    ethiopic.normalize("ሚያዚያ"): 8,
    ethiopic.normalize("ጳጉሜን"): 13,
})


def _jdn_from_gregorian(d: dt.date) -> int:
    return d.toordinal() + 1721425


def _gregorian_from_jdn(jdn: int) -> dt.date:
    return dt.date.fromordinal(jdn - 1721425)


def ethiopic_to_gregorian(year: int, month: int, day: int) -> dt.date:
    if not (1 <= month <= 13 and 1 <= day <= 30) or (month == 13 and day > 6):
        raise ValueError(f"invalid Ethiopian date {year}-{month}-{day}")
    jdn = (_EPOCH + 365) + 365 * (year - 1) + year // 4 + 30 * month + day - 31
    return _gregorian_from_jdn(jdn)


def gregorian_to_ethiopic(d: dt.date) -> tuple[int, int, int]:
    jdn = _jdn_from_gregorian(d)
    r = (jdn - _EPOCH) % 1461
    n = r % 365 + 365 * (r // 1460)
    year = 4 * ((jdn - _EPOCH) // 1461) + r // 365 - r // 1460
    return year, n // 30 + 1, n % 30 + 1


def ec_new_year(ec_year: int) -> dt.date:
    """Gregorian date of Meskerem 1 of the given Ethiopian year."""
    return ethiopic_to_gregorian(ec_year, 1, 1)


def ec_year_to_gc_span(ec_year: int) -> tuple[dt.date, dt.date]:
    start = ec_new_year(ec_year)
    end = ec_new_year(ec_year + 1) - dt.timedelta(days=1)
    return start, end


def gc_year_for_ec_law_year(ec_year: int, hint: dt.date | None = None) -> int:
    """Best Gregorian year for a law numbered with an Ethiopian year.

    E.C. 2018 runs 11 Sep 2025 – 10 Sep 2026. If we know when the law was
    reported and that date falls inside the E.C. year, use its Gregorian year.
    Otherwise use the later Gregorian year, which covers nine of the twelve months.
    """
    start, end = ec_year_to_gc_span(ec_year)
    if hint and start <= hint <= end:
        return hint.year
    return end.year


def format_ec(d: dt.date, lang: str = "am") -> str:
    y, m, day = gregorian_to_ethiopic(d)
    if lang == "am":
        return f"{MONTHS_AM[m - 1]} {day} ቀን {y} ዓ.ም"
    return f"{MONTHS_EN[m - 1]} {day}, {y} E.C."


_AM_DATE = re.compile(r"(?P<month>[ሀ-፿]{2,6})\s*(?P<day>\d{1,2})\s*(?:ቀን)?\s*[,፣]?\s*(?P<year>\d{4})")


def parse_amharic_date(text: str) -> dt.date | None:
    """Find the first Amharic Ethiopian-calendar date ("ሰኔ 18 ቀን 2018 ዓ.ም") in text."""
    folded = ethiopic.fold(text)
    for m in _AM_DATE.finditer(folded):
        month = _MONTH_LOOKUP.get(ethiopic.normalize(m.group("month")))
        if not month:
            continue
        try:
            return ethiopic_to_gregorian(int(m.group("year")), month, int(m.group("day")))
        except ValueError:
            continue
    return None
