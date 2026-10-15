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


@dataclass
class SourceDef:
    """Static description of a monitored source (see catalog.py)."""
    id: str
    name: str
    publisher: str
    tier: int                     # 1 official, 2 official news agency, 3 independent legal press, 9 international
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
        self.stats = {"requests": 0, "not_modified": 0, "candidates": 0, "emitted": 0, "skipped_known": 0,
                      "blocked_by_robots": 0, "errors": 0}


class Adapter(Protocol):
    def discover(self, ctx: AdapterContext) -> AsyncIterator[RawObservation]: ...


# Words that make a news item worth fetching in full. Matched on normalised text.
_LEGAL_EN = re.compile(
    r"\b(proclamation|regulations?|directives?|draft\s+law|bill|legislation|parliament|lawmakers|"
    r"house\s+of\s+peoples?|council\s+of\s+ministers|ratif\w*|gazette|gazeta|decree|statute|"
    r"amendment|law\s+no|enact\w*|legal\s+framework)\b",
    re.IGNORECASE,
)
_LEGAL_AM = ("አዋጅ", "ደንብ", "መመሪያ", "ረቂቅ", "ምክር ቤት", "ህግ", "ማጽደቂያ", "ነጋሪት")


def looks_legal(text: str) -> bool:
    if not text:
        return False
    if _LEGAL_EN.search(text):
        return True
    f = ethiopic.fold(text)
    return any(k in f for k in _LEGAL_AM)
