"""Secondary stream: international and regional developments at about 1 per 10
Ethiopian items.

Every international observation becomes an item, but only the most relevant
ones are *featured* (synced to the app), and only as many as the ratio allows
over a rolling window. The app then interleaves them into the feed.
"""
from __future__ import annotations

import datetime as dt

from sqlalchemy import func, select

from ..models import Item, next_version, utcnow
from ..text import ethiopic

# Weight of each term for an Ethiopian practitioner. Matched on normalised text.
RELEVANCE_TERMS: dict[str, float] = {
    "ethiopia": 4, "ethiopian": 4, "horn of africa": 3, "east africa": 2.5, "eastern africa": 2.5,
    "african union": 2.5, "afcfta": 3, "igad": 3, "comesa": 2.5, "eac": 2, "africa": 1.5, "african": 1.5,
    "kenya": 1.5, "djibouti": 2, "somalia": 1.5, "somaliland": 2, "sudan": 1.5, "south sudan": 1.5, "eritrea": 1.5,
    "nile": 2, "gerd": 3, "wto accession": 3, "wto": 1.5, "arbitration": 1.5, "investment treaty": 2,
    "bilateral investment": 2, "mining": 1.5, "critical minerals": 2, "data protection": 1.5,
    "intellectual property": 1.2, "sanctions": 1, "human rights": 1, "court": 0.5, "treaty": 1,
    "convention": 0.8, "tax": 0.8, "debt": 1, "imf": 1, "world bank": 1,
}


def relevance(title: str, text: str) -> float:
    norm = f" {ethiopic.normalize(title + ' ' + title + ' ' + (text or '')[:3000])} "
    score = 0.0
    for term, w in RELEVANCE_TERMS.items():
        if f" {term} " in norm:
            score += w
    return score


def select_featured(session, settings, now: dt.datetime | None = None) -> list[str]:
    """Feature the most relevant international items the ratio allows. Returns ids featured."""
    now = now or utcnow()
    since = now - dt.timedelta(days=settings.intl_window_days)
    et_count = session.scalar(select(func.count()).select_from(Item).where(
        Item.jurisdiction == "ET", Item.deleted.is_(False), Item.first_seen_at >= since)) or 0
    featured = session.scalar(select(func.count()).select_from(Item).where(
        Item.jurisdiction == "INTL", Item.featured.is_(True), Item.first_seen_at >= since)) or 0
    allowed = int(et_count / settings.intl_ratio + 0.5) - featured
    if allowed <= 0:
        return []
    candidates = session.scalars(
        select(Item).where(Item.jurisdiction == "INTL", Item.featured.is_(False), Item.deleted.is_(False),
                           Item.first_seen_at >= since, Item.relevance >= settings.intl_min_relevance)
        .order_by(Item.relevance.desc(), Item.first_seen_at.desc()).limit(allowed)
    ).all()
    out = []
    for it in candidates:
        it.featured = True
        it.version = next_version(session)
        it.updated_at = now
        out.append(it.id)
    return out
