"""Law identifier and law-name extraction.

Federal proclamations and Council of Ministers regulations are numbered in one
running sequence each ("Proclamation No. 1427/2026" — number 1427, issued in
2026). The number is therefore the stable key; the year is only a check. The
year after the slash is Gregorian in English texts and Ethiopian in Amharic ones
(አዋጅ ቁጥር 1427/2018). Directives are numbered per issuing agency, so their key
also needs the issuer and the year.
"""
from __future__ import annotations

import datetime as dt
import re
from dataclasses import dataclass, field

from . import calendar, ethiopic

DOC_PROCLAMATION = "proclamation"
DOC_REGULATION = "regulation"
DOC_DIRECTIVE = "directive"

_EN_TYPE = (
    r"(?P<type>Council\s+of\s+Ministers\s+Regulations?|Proclamation|Proc\.|"
    r"Regulations?|Reg\.|Directives?|Dir\.)"
)
_EN_LAW = re.compile(
    _EN_TYPE
    + r"\s*(?:No\.?|Number|Nº|#)?\s*:?\s*(?P<num>\d{1,5})\s*[/\-–]\s*(?P<year>\d{4})"
    + r"(?P<ec>\s*(?:E\.\s?C\.?|EC\b|\(E\.?C\.?\)))?",
    re.IGNORECASE,
)
# Run on ethiopic.fold() text, so only one spelling of each word is needed.
_AM_LAW = re.compile(
    r"(?P<type>አዋጅ|ደንብ|መመሪያ)\s*(?:ቁጥር|ቁ\s?\.?)\s*(?P<num>\d{1,5})\s*[/\-]\s*(?P<year>\d{4})"
    r"(?P<ec>\s*(?:አ\s?\.?\s?ም|ዓ\s?\.?\s?ም))?"
)
_AM_TYPE = {"አዋጅ": DOC_PROCLAMATION, "ደንብ": DOC_REGULATION, "መመሪያ": DOC_DIRECTIVE}

# Words that, when they precede a law ID in the same clause, mark it as a
# reference to an existing law rather than the subject of the text.
_REFERENCE_CUES_EN = re.compile(
    r"(amend(?:s|ing|ment\s+of|ment\s+to)?|repeal(?:s|ing)?|replac(?:es|ing)|under|pursuant\s+to|"
    r"in\s+accordance\s+with|as\s+per|established\s+by|issued\s+under|stipulated\s+in|based\s+on)\s+"
    r"(?:the\s+)?(?:[\w'’\-]+\s+){0,8}$",
    re.IGNORECASE,
)
_REFERENCE_CUES_AM = re.compile(r"(መሠረት|መሰረት|ለማሻሻል|የሚሽር|ለመሻር|በተሰጠው)\s*$")

# Known (number → Gregorian year) anchors for the proclamation sequence; used
# to tell whether an unmarked year is E.C. or G.C. Extend as needed.
PROCLAMATION_ANCHORS: list[tuple[int, int]] = [
    (1, 1995),     # Constitution of the FDRE
    (1112, 2019),  # Food and Medicine Administration
    (1156, 2019),  # Labour Proclamation
    (1180, 2020),  # Investment Proclamation
    (1183, 2020),  # Federal Administrative Procedure
    (1401, 2026),
    (1427, 2026),  # Freedom of Information (1427/2018 E.C.)
]


def expected_proclamation_year(number: int) -> float:
    a = PROCLAMATION_ANCHORS
    if number <= a[0][0]:
        return a[0][1]
    for (n0, y0), (n1, y1) in zip(a, a[1:]):
        if n0 <= number <= n1:
            return y0 + (y1 - y0) * (number - n0) / (n1 - n0)
    # Extrapolate past the last anchor with the slope of the last stretch with
    # meaningful spread (about 36 proclamations a year since 2020).
    (n0, y0), (n1, y1) = a[-3], a[-1]
    slope = (y1 - y0) / (n1 - n0)
    return y1 + slope * (number - n1)


@dataclass
class LawRef:
    doc_type: str
    number: int
    year_raw: int
    calendar: str              # "gc" or "ec"
    year_gc: int
    year_ec: int
    raw: str
    start: int
    end: int
    is_reference: bool = False

    @property
    def canonical_key(self) -> str | None:
        if self.doc_type == DOC_PROCLAMATION:
            return f"proc:{self.number}"
        if self.doc_type == DOC_REGULATION:
            return f"reg:{self.number}"
        return None  # directives need the issuer: see canonical_key_for()

    def display(self) -> str:
        label = {DOC_PROCLAMATION: "Proclamation", DOC_REGULATION: "Regulation",
                 DOC_DIRECTIVE: "Directive"}[self.doc_type]
        return f"{label} No. {self.number}/{self.year_gc}"


def canonical_key_for(doc_type: str, number: int, year_gc: int, issuer: str | None) -> str:
    if doc_type == DOC_PROCLAMATION:
        return f"proc:{number}"
    if doc_type == DOC_REGULATION:
        return f"reg:{number}"
    return f"dir:{(issuer or 'UNK').upper()}:{number}:{year_gc}"


def _resolve_year(doc_type: str, number: int, year: int, lang: str, explicit_ec: bool,
                  obs_date: dt.date | None) -> tuple[str, int, int]:
    """Return (calendar, year_gc, year_ec)."""
    if explicit_ec:
        cal = "ec"
    elif doc_type == DOC_PROCLAMATION and number > 1:
        exp = expected_proclamation_year(number)
        cal = "ec" if abs((year + 7.7) - exp) < abs(year - exp) else "gc"
    else:
        cal = "ec" if lang == "am" else "gc"
        # A Gregorian year in the future is impossible; it must be E.C. (and vice versa).
        today = obs_date or dt.date.today()
        if cal == "gc" and year > today.year + 1:
            cal = "ec"
        elif cal == "ec" and year + 7 > today.year + 1:
            cal = "gc"
    if cal == "ec":
        return "ec", calendar.gc_year_for_ec_law_year(year, obs_date), year
    ec = year - 8 if (obs_date is None or obs_date.month < 9) else year - 7
    return "gc", year, ec


def _is_reference(text: str, start: int, lang: str) -> bool:
    window = text[max(0, start - 90):start]
    # Only look inside the current clause.
    window = re.split(r"[.;:()\n።፤]", window)[-1]
    if lang == "am" or ethiopic.detect_lang(window) == "am":
        tail = text[start:start + 70]
        return bool(_REFERENCE_CUES_AM.search(window)) or bool(_REFERENCE_TAIL_AM.match(tail))
    return bool(_REFERENCE_CUES_EN.search(window))


# Amharic puts "pursuant to" / "to amend" after the cited law:
# "በአዋጅ ቁጥር 1183/2012 መሠረት", "አዋጅ ቁጥር 1234/2013ን ለማሻሻል". Text is folded (ሠ→ሰ).
_REFERENCE_TAIL_AM = re.compile(r"[^።]{0,45}?(መሰረት|ለማሻሻል|የሚሽር|ለመሻር|በማሻሻል)")


def find_law_refs(text: str, lang: str | None = None, obs_date: dt.date | None = None) -> list[LawRef]:
    """All law identifiers in the text, de-duplicated by (type, number).

    `is_reference` is True when the text only cites the law (e.g. "amending
    Proclamation No. 1183/2020") rather than reporting on it.
    """
    if not text:
        return []
    folded = ethiopic.fold(text)
    lang = lang or ethiopic.detect_lang(folded)
    found: dict[tuple[str, int], LawRef] = {}

    for m in _EN_LAW.finditer(folded):
        t = m.group("type").lower()
        if t.startswith("council") or t.startswith("reg"):
            doc = DOC_REGULATION
        elif t.startswith("dir"):
            doc = DOC_DIRECTIVE
        else:
            doc = DOC_PROCLAMATION
        _add(found, folded, m, doc, "en", obs_date)

    for m in _AM_LAW.finditer(folded):
        _add(found, folded, m, _AM_TYPE[m.group("type")], "am", obs_date)

    return sorted(found.values(), key=lambda r: r.start)


def _add(found, folded, m, doc, lang, obs_date):
    num, year = int(m.group("num")), int(m.group("year"))
    if not (1900 <= year <= 2100) or num == 0:
        return
    cal, ygc, yec = _resolve_year(doc, num, year, lang, bool(m.group("ec")), obs_date)
    ref = LawRef(doc, num, year, cal, ygc, yec, m.group(0).strip(), m.start(), m.end(),
                 _is_reference(folded, m.start(), lang))
    key = (doc, num)
    prev = found.get(key)
    if prev is None:
        found[key] = ref
    else:
        # Subject wins over reference; an explicit Gregorian year wins for display.
        prev.is_reference = prev.is_reference and ref.is_reference
        # A year written in a calendar beats one converted from the other calendar.
        if prev.calendar == "ec" and ref.calendar == "gc":
            prev.year_gc = ref.year_gc
        elif prev.calendar == "gc" and ref.calendar == "ec":
            prev.year_ec = ref.year_ec


def primary_refs(refs: list[LawRef], obs_date: dt.date | None = None) -> list[LawRef]:
    """The law IDs the text is actually about.

    Drops references, and anything whose year is more than two years before the
    observation (an old law being cited, not a new development).
    """
    subjects = [r for r in refs if not r.is_reference]
    if obs_date:
        subjects = [r for r in subjects if r.year_gc >= obs_date.year - 2]
    return subjects


# --------------------------------------------------------------------------- names

_NAME_EN = re.compile(
    r"(?P<name>(?:(?:[A-Z][\w'’\-]*|of|and|the|for|on|to|in|\(Amendment\)|Amendment)\s+){1,14}?"
    r"(?:\(Amendment\)\s+)?(?:Proclamation|Regulations?|Directive|Bill)(?:\s*\(Amendment\))?)(?![\w])"
)
_NAME_EN_LOWER = re.compile(
    r"\b(?:draft\s+)?(?P<kind>proclamation|regulations?|directive|bill)\s+"
    r"(?:on|to\s+provide\s+for|providing\s+for|for\s+the|for|concerning|to\s+(?:determine|establish|regulate))\s+"
    r"(?:the\s+)?(?P<subject>[a-z][a-z' \-]{3,80}?)(?=[,.;:]|\s+(?:which|that|was|were|is|has|have|with|after|before|to\s+the|by|and\s+(?:sent|forwarded|referred|approved|decided|submitted|passed|tabled|endorsed)|(?:sent|forwarded|referred|approved|decided|submitted|passed|tabled|endorsed|heads?|goes?|awaits?))\b|$)",
    re.IGNORECASE,
)
# Amharic: the type word, optionally preceded by ረቂቅ (draft) and followed by an
# object/possessive suffix (አዋጅን, አዋጁ). The name is the words before it.
_AM_TYPE_WORD = re.compile(r"(?<![\u1200-\u137F])(?:ረቂቅ\s+)?(?P<type>አዋጅ|ደንብ|መመሪያ)(?:ን|ኑ|ዎች|ዎቹ|ና|ም)?(?![ሀ-፿])")
_AM_NAME_MAX_WORDS = 5

_GENERIC_WORDS = {
    "the", "of", "and", "for", "on", "to", "in", "a", "an", "draft", "new", "federal", "ethiopian",
    "ethiopia", "fdre", "proclamation", "regulation", "regulations", "directive", "bill", "no", "number",
    "council", "ministers", "house", "approved", "approves", "approve", "passed", "passes", "today",
    "yesterday", "parliament", "lawmakers", "cabinet", "endorses", "endorsed", "ratifies", "adopts",
    "adopted", "peoples", "people's", "representatives", "hopr", "hpr", "it", "its", "this", "that",
    # Amharic institution words / filler (already folded and prefix-stripped)
    "ህዝብ", "ተወካዮች", "ምክር", "ቤት", "ቤቱ", "ሚኒስትሮች", "ፌዴሬሽን", "ዛሬ", "ትናንት",
    "ባካሄደው", "ስብሰባው", "ስብሰባ", "መደበኛ", "ጉባኤ", "ጉባኤው", "አስቸኳይ", "ልዩ", "ኢትዮጵያ", "ፌዴራል",
}
_LEADING_JUNK = {"the", "a", "an", "today", "yesterday", "on", "and", "in", "to", "of", "for"}


@dataclass
class LawName:
    doc_type: str
    name: str            # as found in the folded text (used for matching)
    lang: str
    start: int
    tokens: frozenset = field(default_factory=frozenset)
    display: str = ""    # same span in the original spelling, when recoverable


def _doc_from_word(w: str) -> str:
    w = w.lower()
    if w.startswith("reg") or w == "ደንብ":
        return DOC_REGULATION
    if w.startswith("dir") or w == "መመሪያ":
        return DOC_DIRECTIVE
    return DOC_PROCLAMATION  # proclamation, bill, አዋጅ


def _trim_leading_generic(name: str) -> str:
    """Drop leading institution/filler words: "ተወካዮች ምክር ቤት የመረጃ ደህንነት አዋጅ" → "የመረጃ ደህንነት አዋጅ"."""
    words = name.split()
    while len(words) > 1:
        w = ethiopic.strip_prefix(words[0]) or words[0]
        if w in _GENERIC_WORDS or w == "ረቂቅ":
            words = words[1:]
        else:
            break
    return " ".join(words)


def name_tokens(name: str) -> frozenset:
    out = set()
    for t in ethiopic.tokens(name):
        if t.isdigit():
            continue
        if ethiopic.is_ethiopic(t[0]):
            t = ethiopic.strip_prefix(t) or t
            if t.endswith("ን") and len(t) > 2:  # accusative suffix
                t = t[:-1]
            if t in {"አዋጅ", "ደንብ", "መመሪያ", "ረቂቅ", "ቁጥር", "አዋጁ", "ደንቡ", "መመሪያው"} or len(t) < 2:
                continue
        if t in _GENERIC_WORDS:
            continue
        out.add(t)
    return frozenset(out)


def find_law_names(text: str, lang: str | None = None) -> list[LawName]:
    """Named instruments, e.g. "Freedom of Information Proclamation".

    Unnumbered drafts are only ever cited by name, so names are what lets us
    follow a bill from Council of Ministers to parliament to the gazette.
    """
    if not text:
        return []
    folded = ethiopic.fold(text)
    lang = lang or ethiopic.detect_lang(folded)
    out: list[LawName] = []
    seen: set[frozenset] = set()
    aligned = len(folded) == len(text)  # folding kept offsets (no numerals/zero-width removed)

    def push(doc, name, start, lng, display=None, single_ok=False):
        name = _trim_leading_generic(name.strip()) if lng == "am" else name.strip()
        toks = name_tokens(name)
        # One distinctive word is enough for a capitalised English title ("Investment Proclamation",
        # "Media Proclamation"); elsewhere two are needed, to avoid matching generic phrases.
        enough = len(toks) >= 2 or (single_ok and len(toks) == 1 and len(next(iter(toks))) >= 4)
        if not enough or toks in seen:
            return
        seen.add(toks)
        if display is None:
            display = name
            if aligned:
                i = folded.find(name, start)
                if i != -1:
                    display = text[i:i + len(name)]
        out.append(LawName(doc, name, lng, start, toks, display))

    for m in _NAME_EN.finditer(folded):
        words = m.group("name").split()
        while words and words[0].lower() in _LEADING_JUNK:
            words = words[1:]
        if len(words) < 2:
            continue
        push(_doc_from_word(words[-1]), " ".join(words), m.start(), "en", single_ok=True)
    for m in _NAME_EN_LOWER.finditer(folded):
        subject = m.group("subject").strip()
        kind = m.group("kind")
        push(_doc_from_word(kind), f"{subject} {kind}", m.start(), "en")
    for m in _AM_TYPE_WORD.finditer(folded):
        clause = re.split(r"[።፣፤፥፦\n.,;:()\"“”«»]", folded[max(0, m.start() - 160):m.start()])[-1]
        words = [w for w in clause.split() if all(ethiopic.is_ethiopic(c) for c in w)]
        words = [w for w in words[-_AM_NAME_MAX_WORDS:] if w != "ረቂቅ"]
        if not words:
            continue
        words = _trim_leading_generic(" ".join(words)).split()
        name = " ".join(words + [m.group("type")])
        display = None
        if aligned:
            # Recover each word's original spelling (ደኅንነት, not the folded ደህንነት).
            parts, end = [], m.start()
            for w in reversed(words):
                i = folded.rfind(w, 0, end)
                if i == -1:
                    parts = None
                    break
                parts.append(text[i:i + len(w)])
                end = i
            if parts is not None:
                display = " ".join(list(reversed(parts)) + [text[m.start("type"):m.end("type")]])
        push(_doc_from_word(m.group("type")), name, m.start(), "am", display)
    return out
