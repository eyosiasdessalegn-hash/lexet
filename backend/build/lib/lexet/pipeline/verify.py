"""The verification rule.

An Ethiopian item is VERIFIED when at least `min_independent_sources` (3)
independent publishers report it and, if `require_official_anchor`, at least
one of them is tier 1 (official) or tier 2 (ENA). Otherwise it is UNCONFIRMED
(shown, never hidden, never notified).

Independence is decided per publisher, not per URL, and copies do not count:
  * an observation that credits another publisher ("(ENA)", "ኢዜአ",
    "according to ENA") is syndicated from that publisher;
  * an observation whose text overlaps an earlier one from another publisher
    above `near_duplicate_jaccard` is a near-duplicate of it.

Statuses are verified the same way: the verified status is the most advanced
stage that at least N independent publishers report (a source reporting a
later stage supports the earlier ones). One deliberate exception, on by
default (`gazette_confirms_in_force`): for an item that is already verified,
publication in the Federal Negarit Gazeta or the official MoJ register settles
"in force", because that publication is what puts the law into force.
"""
from __future__ import annotations

import datetime as dt
from dataclasses import asdict, dataclass, field

from ..config import Settings
from ..text import classify, similarity


@dataclass
class Evidence:
    observation_id: int
    publisher: str
    tier: int
    url: str
    title: str
    published_at: dt.datetime | None
    status: str | None
    primary_document: bool
    gazette: bool
    counted: bool = False
    reason: str = ""

    def to_dict(self):
        d = asdict(self)
        d["published_at"] = (self.published_at.replace(microsecond=0).isoformat() + "Z") if self.published_at else None
        return d


@dataclass
class VerificationResult:
    verification: str                      # verified | unconfirmed
    independent_sources: int
    has_official_source: bool
    verified_status: str | None
    reported_status: str | None
    status_support: dict[str, int]
    evidence: list[Evidence] = field(default_factory=list)
    status_since: dt.datetime | None = None

    @property
    def display_status(self) -> str | None:
        return self.verified_status or self.reported_status


def evaluate(evidence_in: list[tuple[Evidence, list[int], str | None]], settings: Settings) -> VerificationResult:
    """`evidence_in`: (evidence, shingles, attribution) per observation linked to the item."""
    rows = sorted(evidence_in, key=lambda t: (t[0].published_at or dt.datetime.max, t[0].observation_id))
    counted_by_pub: dict[str, Evidence] = {}

    for i, (ev, sh, attribution) in enumerate(rows):
        if attribution and attribution != ev.publisher:
            ev.reason = f"syndicated from {attribution}"
            continue
        dup_of = None
        if ev.tier != 1:  # official pages are primary even when news copied them
            for prev, prev_sh, _ in rows[:i]:
                if prev.publisher != ev.publisher and similarity.jaccard(sh, prev_sh) >= settings.near_duplicate_jaccard:
                    dup_of = prev.publisher
                    break
        if dup_of:
            ev.reason = f"near-duplicate of {dup_of}"
            continue
        if ev.publisher in counted_by_pub:
            ev.reason = "same publisher already counted"
            continue
        ev.counted = True
        ev.reason = "independent"
        counted_by_pub[ev.publisher] = ev

    counted = list(counted_by_pub.values())
    n = len(counted)
    anchor = any(e.tier in (1, 2) for e in counted)
    official = any(e.tier == 1 for e in counted)
    verified = n >= settings.min_independent_sources and (anchor or not settings.require_official_anchor)
    if settings.official_self_publication_verifies and any(e.tier == 1 and e.primary_document for e in counted):
        verified = True

    # A publisher's claim is the most advanced stage it has reported in any of its
    # own (non-copied) observations: ENA reporting "tabled" in March and
    # "approved" in June supports "approved".
    pub_status: dict[str, str | None] = {}
    pub_first: dict[tuple[str, str], dt.datetime] = {}
    for e, _, _ in rows:
        if e.publisher in counted_by_pub and (e.counted or e.reason == "same publisher already counted"):
            pub_status[e.publisher] = classify.max_status(pub_status.get(e.publisher), e.status)
            if e.status and e.published_at:
                k = (e.publisher, e.status)
                pub_first[k] = min(pub_first.get(k, e.published_at), e.published_at)
    claims = [(counted_by_pub[p], st) for p, st in pub_status.items()]

    # Support per status: publishers reporting that stage or a later one.
    support: dict[str, int] = {}
    for s, rank in classify.STATUS_RANK.items():
        support[s] = sum(1 for _, st in claims if classify.status_rank(st) >= rank)
    reported = classify.max_status(*(st for _, st in claims)) or classify.max_status(*(e.status for e, _, _ in rows))

    verified_status = None
    if verified:
        for s in sorted(classify.STATUS_RANK, key=classify.STATUS_RANK.get):
            supporters = [e for e, st in claims if classify.status_rank(st) >= classify.STATUS_RANK[s]]
            ok = len(supporters) >= settings.min_independent_sources and (
                any(e.tier in (1, 2) for e in supporters) or not settings.require_official_anchor)
            if ok:
                verified_status = s
        if settings.gazette_confirms_in_force:
            official_claims = [st for e, st in claims if e.tier == 1 and classify.status_rank(st) >= classify.STATUS_RANK["in_force"]
                               and any(x.publisher == e.publisher and (x.gazette or x.primary_document) for x, _, _ in rows)]
            if official_claims:
                verified_status = classify.max_status(verified_status, *official_claims)
        if verified_status is None:
            # Existence is verified but no stage has enough support (statuses missing):
            # use the least advanced stage anyone reports.
            verified_status = min((st for _, st in claims if st), key=classify.status_rank, default=None)

    display = verified_status or reported
    since = None
    if display:
        dates = [d for (p, st), d in pub_first.items() if st == display]
        since = min(dates) if dates else None

    return VerificationResult(
        verification="verified" if verified else "unconfirmed",
        independent_sources=n,
        has_official_source=official,
        verified_status=verified_status,
        reported_status=reported,
        status_support=support,
        evidence=[e for e, _, _ in rows],
        status_since=since,
    )
