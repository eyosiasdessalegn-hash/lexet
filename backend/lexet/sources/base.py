"""Common types for source adapters."""
from __future__ import annotations

import datetime as dt
import re
from dataclasses import dataclass, field
from typing import AsyncIterator, Callable, Protocol

from ..text import ethiopic


@dataclass
class RawObservation:
    url: str
    title: str
    text: str = ""
    lang: str | None = None
    published_at: dt.datetime | None = None
    is_primary_document: bool = False   # the instrument itself (gazette, official register entry, PDF)
    gazette: bool = False
    status_hint: str | None = None
    doc_type_hint: str | None = None
    issuer_hint: str | None = None
    extra: dict = field(default_factory=dict)
    # Aggregators (GDELT, Google News) carry many outlets: each article names its own
    # publisher and tier, so independence is still judged per outlet.
    publisher: str | None = None
    tier: int | None = None


@dataclass
class SourceDef:
    """Static description of a monitored source (see catalog.py)."""
    id: str
    name: str
    publisher: str
    tier: int                     # 1 official, 2 official news agency, 3 established press, 4 other outlets, 9 international
    jurisdiction: str             # "ET" or "INTL"
    lang: str
    kind: str                     # wordpress | rss | html_listing | html_articles | manual
    interval_min: int = 30
    enabled: bool = True
    config: dict = field(default_factory=dict)
    aliases: list[str] = field(default_factory=list)   # names other outlets use when crediting this one
    notes: str = ""


class AdapterContext:
    def __init__(self, fetcher, source: SourceDef, cursor: str | None,
                 is_known: Callable[[str], bool], now: dt.datetime | None = None):
        self.fetcher = fetcher
        self.source = source
        self.cursor = cursor
        self.new_cursor: str | None = cursor
        self.is_known = is_known
        self.now = now or dt.datetime.utcnow()
        self.stats = {"requests": 0, "not_modified": 0, "seen": 0, "candidates": 0, "emitted": 0,
                      "skipped_known": 0, "blocked_by_robots": 0, "errors": 0}
        self.trace: list[str] = []     # first responses, for `lexet doctor`
        self.samples: list[str] = []   # entries seen but not taken, for `lexet doctor`

    def sample(self, text: str):
        if len(self.samples) < 5 and text:
            self.samples.append(text.strip()[:90])


class Adapter(Protocol):
    def discover(self, ctx: AdapterContext) -> AsyncIterator[RawObservation]: ...


# Words that make a news item a legal update worth taking. Deliberately broad: the
# verification label, not this filter, tells the reader how far to trust an item.
_LEGAL_EN = re.compile(
    r"\b(proclamation|regulations?|directives?|draft\s+law|bill|legislation|parliament|lawmakers|"
    r"house\s+of\s+peoples?|council\s+of\s+ministers|ratif\w*|gazette|gazeta|decree|statute|"
    r"amendment|law\s+no|enact\w*|legal\s+framework|laws?|legal|court|cassation|constitution\w*|"
    r"ruling|guidelines?|polic(y|ies)|licen[cs]\w*|tariffs?|tax\s+(law|reform|regime|code)|"
    r"code\s+of|by-?laws?|regulatory|regulator|tribunal|arbitration|treaty|accord)\b",
    re.IGNORECASE,
)
_LEGAL_AM = ("አዋጅ", "ደንብ", "መመሪያ", "ረቂቅ", "ምክር ቤት", "ህግ", "ማጽደቂያ", "ነጋሪት", "ፖሊሲ", "ፍርድ ቤት",
             "ውሳኔ", "ሰበር", "ህገ መንግስት", "ስምምነት")


def looks_legal(text: str) -> bool:
    if not text:
        return False
    if _LEGAL_EN.search(text):
        return True
    f = ethiopic.fold(text)
    return any(k in f for k in _LEGAL_AM)
