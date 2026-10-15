"""Signal → item matching, and merging of items found to be the same law.

Matching order:
  1. Canonical key (proclamation/regulation number, or issuer+number+year for
     directives). Exact and language-independent: "አዋጅ ቁጥር 1427/2018" and
     "Proclamation No. 1427/2026" both key to proc:1427.
  2. Law-name match against items of the same type seen in the last 18 months
     (a bill can take that long from Council of Ministers to gazette). Used for
     drafts and news that do not cite a number.

Precision beats recall: two different laws merged would corrupt verification,
so names must share at least two content words (similarity.names_match) and an
amendment never matches the law it amends. There is no cross-language name
matching yet; English and Amharic reports of an unnumbered draft join once a
number or a bilingual official title links them.
"""
from __future__ import annotations

import datetime as dt
import json

from sqlalchemy import select

from ..models import Item, Signal, next_version, utcnow
from ..text import similarity
from .extract import SignalDraft

WINDOW_DAYS = 540


def _resolve(session, item: Item | None) -> Item | None:
    hops = 0
    while item is not None and item.deleted and item.merged_into and hops < 10:
        item = session.get(Item, item.merged_into)
        hops += 1
    return item


def name_score(tokens: frozenset, item: Item) -> float:
    best = 0.0
    for s in item.name_sets:
        if similarity.names_match(tokens, s):
            best = max(best, similarity.jaccard(tokens, s) + 0.01)
    return best


def find_item(session, sig: SignalDraft, when: dt.datetime) -> Item | None:
    if sig.canonical_key:
        it = _resolve(session, session.scalar(select(Item).where(Item.canonical_key == sig.canonical_key)))
        if it is not None:
            return it
    names = [t for t in [sig.name_tokens, *sig.alt_name_tokens] if t]
    if not names:
        return None
    floor = min(when, utcnow()) - dt.timedelta(days=WINDOW_DAYS)
    q = select(Item).where(Item.jurisdiction == "ET", Item.deleted.is_(False), Item.doc_type == sig.doc_type,
                           Item.first_seen_at >= floor)
    best, best_score = None, 0.0
    for it in session.scalars(q):
        if sig.canonical_key and it.canonical_key and it.canonical_key != sig.canonical_key:
            continue
        if sig.doc_type == "directive" and sig.issuer and it.issuer and sig.issuer != it.issuer:
            continue
        sc = max(name_score(t, it) for t in names)
        if sc > best_score:
            best, best_score = it, sc
    return best


def create_item(session, sig: SignalDraft) -> Item:
    it = Item(jurisdiction="ET", doc_type=sig.doc_type, canonical_key=sig.canonical_key,
              law_number=sig.law_number, law_year_gc=sig.law_year_gc, law_year_ec=sig.law_year_ec,
              issuer=sig.issuer, initiator=sig.initiator, status=sig.status, verification="unconfirmed",
              version=next_version(session))
    session.add(it)
    session.flush()
    return it


def attach(session, sig: SignalDraft, observation_id: int, when: dt.datetime) -> Item:
    item = find_item(session, sig, when)
    if item is None:
        item = create_item(session, sig)
    elif sig.canonical_key and not item.canonical_key:
        # A numbered report arrives for a law we had only seen by name (a draft
        # that has now been published): the item takes the number.
        item.canonical_key = sig.canonical_key
        item.law_number = sig.law_number
        item.law_year_gc = sig.law_year_gc
        item.law_year_ec = sig.law_year_ec
    item.add_name_set(sig.name_tokens)
    for alt in sig.alt_name_tokens:
        item.add_name_set(alt)
    session.add(Signal(
        observation_id=observation_id, item_id=item.id, doc_type=sig.doc_type, canonical_key=sig.canonical_key,
        law_number=sig.law_number, law_year_gc=sig.law_year_gc, law_year_ec=sig.law_year_ec,
        year_calendar=sig.year_calendar, law_name=sig.law_name, name_tokens_json=json.dumps(sorted(sig.name_tokens), ensure_ascii=False),
        status=sig.status, issuer=sig.issuer, initiator=sig.initiator,
        topics_json=json.dumps(sig.topics), title_en=sig.title_en, title_am=sig.title_am,
        references_json=json.dumps(sig.references + ([sig.amends_key] if sig.amends_key else [])),
    ))
    return item


def merge(session, src: Item, dst: Item) -> None:
    """Fold `src` into `dst`; `src` becomes a tombstone the app uses to move bookmarks."""
    for sig in list(src.signals):
        sig.item_id = dst.id
    for s in src.name_sets:
        dst.add_name_set(s)
    if src.canonical_key and not dst.canonical_key:
        dst.canonical_key, src.canonical_key = src.canonical_key, None
        dst.law_number, dst.law_year_gc, dst.law_year_ec = src.law_number, src.law_year_gc, src.law_year_ec
    src.deleted = True
    src.merged_into = dst.id
    src.canonical_key = None
    src.version = next_version(session)
    src.updated_at = utcnow()
    session.flush()


def consolidate(session) -> list[tuple[str, str]]:
    """Merge unnumbered items into a matching item of the same type. Returns (src, dst) pairs."""
    merged = []
    floor = utcnow() - dt.timedelta(days=WINDOW_DAYS)
    items = session.scalars(select(Item).where(Item.jurisdiction == "ET", Item.deleted.is_(False),
                                               Item.first_seen_at >= floor)
                            .order_by(Item.first_seen_at)).all()
    for a in items:
        if a.deleted or a.canonical_key:
            continue
        for b in items:
            if b is a or b.deleted or b.doc_type != a.doc_type:
                continue
            if a.doc_type == "directive" and a.issuer and b.issuer and a.issuer != b.issuer:
                continue
            if not any(name_score(s, b) for s in a.name_sets):
                continue
            # Keep the numbered one; otherwise the older one.
            if b.canonical_key or b.first_seen_at <= a.first_seen_at:
                merge(session, a, b)
                merged.append((a.id, b.id))
                break
    return merged
