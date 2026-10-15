"""How items and sources are presented to the app — shared by the HTTP API and the static feed."""
from __future__ import annotations

import datetime as dt
import json

from sqlalchemy import select

from ..config import Settings
from ..models import Item, Observation, Source

LAW_LABEL = {"proclamation": "Proclamation", "regulation": "Regulation", "directive": "Directive"}


def law_id(item: Item) -> str | None:
    if not item.law_number or item.doc_type not in LAW_LABEL:
        return None
    year = item.law_year_gc or item.law_year_ec
    return f"{LAW_LABEL[item.doc_type]} No. {item.law_number}/{year}" if year else f"{LAW_LABEL[item.doc_type]} No. {item.law_number}"


def iso(v):
    if v is None:
        return None
    if isinstance(v, dt.datetime):
        return v.replace(microsecond=0).isoformat() + "Z"
    return v.isoformat()


def item_dto(item: Item, obs_text: dict[int, tuple[str, str, int]], source_names: dict[str, str],
             settings: Settings) -> dict:
    if item.deleted:
        return {"id": item.id, "deleted": True, "merged_into": item.merged_into, "version": item.version}
    detail = json.loads(item.verification_json or "{}")
    sources = []
    for ev in detail.get("evidence", []):
        text, lang, tier = obs_text.get(ev["observation_id"], ("", "", ev.get("tier", 9)))
        limit = settings.official_excerpt_chars if tier == 1 else settings.news_excerpt_chars
        excerpt = " ".join(text.split())
        if len(excerpt) > limit:
            excerpt = excerpt[:limit].rsplit(" ", 1)[0] + "…"
        sources.append({
            "publisher": ev["publisher"], "name": source_names.get(ev["publisher"], ev["publisher"]),
            "tier": ev["tier"], "url": ev["url"], "title": ev["title"], "lang": lang or None,
            "published_at": ev.get("published_at"), "counted": ev.get("counted", False),
            "reason": ev.get("reason", ""), "primary_document": ev.get("primary_document", False),
            "gazette": ev.get("gazette", False), "excerpt": excerpt or None,
        })
    return {
        "id": item.id, "version": item.version, "jurisdiction": item.jurisdiction, "doc_type": item.doc_type,
        "law_id": law_id(item), "law_number": item.law_number, "law_year_gc": item.law_year_gc,
        "law_year_ec": item.law_year_ec, "title_en": item.title_en, "title_am": item.title_am,
        "summary_en": item.summary_en, "summary_am": item.summary_am, "issuer": item.issuer,
        "initiator": item.initiator, "status": item.status, "verified_status": item.verified_status,
        "reported_status": item.reported_status, "urgency": item.urgency, "verification": item.verification,
        "source_count": item.independent_sources, "has_official_source": item.has_official_source,
        "topics": item.topics, "amends": item.amends_key, "references": json.loads(item.references_json or "[]"),
        "event_date": iso(item.event_date), "status_date": iso(item.status_date),
        "first_seen_at": iso(item.first_seen_at), "updated_at": iso(item.updated_at),
        "verified_at": iso(item.verified_at), "relevance": round(item.relevance or 0, 2),
        "sources": sources,
    }


def source_names(session) -> dict[str, str]:
    out: dict[str, str] = {}
    for src in session.scalars(select(Source)):
        out.setdefault(src.publisher, src.name.split(" — ")[0].split(" (")[0])
    return out


def item_dtos(session, items: list[Item], settings: Settings) -> list[dict]:
    ids = set()
    for it in items:
        for ev in json.loads(it.verification_json or "{}").get("evidence", []):
            ids.add(ev["observation_id"])
    obs = {}
    if ids:
        for o in session.scalars(select(Observation).where(Observation.id.in_(ids))):
            obs[o.id] = (o.text or "", o.lang, o.tier)
    names = source_names(session)
    return [item_dto(it, obs, names, settings) for it in items]


def sources_list(session) -> list[dict]:
    return [{
        "id": r.id, "name": r.name, "publisher": r.publisher, "tier": r.tier, "jurisdiction": r.jurisdiction,
        "lang": r.lang, "kind": r.kind, "enabled": r.enabled, "robots_state": r.robots_state,
        "last_success_at": iso(r.last_success_at), "last_error": r.last_error,
    } for r in session.scalars(select(Source).order_by(Source.tier, Source.id))]
