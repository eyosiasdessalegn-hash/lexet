"""Pipeline orchestration: crawl → ingest → extract → cluster → verify → publish."""
from __future__ import annotations

import asyncio
import datetime as dt
import hashlib
import json
import logging
import re
from collections import Counter
from dataclasses import dataclass, field
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

from sqlalchemy import select

from ..config import Settings
from ..models import FetchCache, Item, Observation, Signal, Source, current_version, next_version, utcnow
from ..push import Pusher
from ..sources import catalog
from ..sources.adapters import build_adapter
from ..sources.base import AdapterContext, RawObservation, SourceDef
from ..sources.html_extract import looks_like_gazette
from ..sources.http import PoliteFetcher, RobotsDisallowed
from ..text import classify, ethiopic, similarity
from . import cluster, international, verify
from .extract import ObsView, extract_signals
from .summarize import summarize

log = logging.getLogger(__name__)

_TRACKING_PARAMS = re.compile(r"^(utm_|fbclid|gclid|mc_|ref$|amp$)")


def canonical_url(url: str) -> str:
    p = urlsplit(url.strip())
    q = [(k, v) for k, v in parse_qsl(p.query, keep_blank_values=True) if not _TRACKING_PARAMS.match(k)]
    path = p.path.rstrip("/") or "/"
    return urlunsplit((p.scheme.lower(), p.netloc.lower(), path, urlencode(q), ""))


@dataclass
class CycleReport:
    crawled: dict = field(default_factory=dict)
    new_observations: int = 0
    touched_items: int = 0
    merged: int = 0
    featured_intl: int = 0
    events: list = field(default_factory=list)
    version: int = 0
    pushed: bool = False


class DbFetchCache:
    def __init__(self, session_factory):
        self.sf = session_factory

    def get(self, url):
        with self.sf() as s:
            row = s.get(FetchCache, url)
            return (row.etag, row.last_modified) if row else (None, None)

    def put(self, url, etag, last_modified):
        if not etag and not last_modified:
            return
        with self.sf() as s:
            row = s.get(FetchCache, url) or FetchCache(url=url)
            row.etag, row.last_modified, row.fetched_at = etag, last_modified, utcnow()
            s.merge(row)
            s.commit()


class Pipeline:
    def __init__(self, session_factory, settings: Settings | None = None,
                 sources: list[SourceDef] | None = None, fetcher=None, pusher=None):
        self.sf = session_factory
        self.settings = settings or Settings()
        self.sources = {s.id: s for s in (sources if sources is not None else catalog.SOURCES)}
        self.fetcher = fetcher
        self.pusher = pusher or Pusher(self.settings)
        self.aliases: dict[str, list[str]] = {}
        for s in self.sources.values():
            for a in s.aliases:
                self.aliases.setdefault(s.publisher, [])
                if a not in self.aliases[s.publisher]:
                    self.aliases[s.publisher].append(a)
        self.ensure_sources()

    # ------------------------------------------------------------------ sources
    def ensure_sources(self):
        with self.sf() as s:
            for d in self.sources.values():
                row = s.get(Source, d.id) or Source(id=d.id)
                row.name, row.publisher, row.tier = d.name, d.publisher, d.tier
                row.jurisdiction, row.lang, row.kind = d.jurisdiction, d.lang, d.kind
                row.enabled = d.enabled
                row.config_json = json.dumps(d.config, ensure_ascii=False)
                s.merge(row)
            s.commit()

    def due_sources(self, now: dt.datetime | None = None) -> list[SourceDef]:
        now = now or utcnow()
        out = []
        with self.sf() as s:
            for d in self.sources.values():
                if not d.enabled or d.kind == "manual":
                    continue
                row = s.get(Source, d.id)
                if row.last_run_at is None or row.last_run_at + dt.timedelta(minutes=d.interval_min) <= now:
                    out.append(d)
        return out

    # ------------------------------------------------------------------ crawling
    def _fetcher(self):
        if self.fetcher is None:
            self.fetcher = PoliteFetcher(self.settings, cache=DbFetchCache(self.sf))
        return self.fetcher

    async def crawl_source(self, d: SourceDef) -> dict:
        adapter = build_adapter(d.kind, d.config)
        if adapter is None:
            return {"skipped": "manual"}
        fetcher = self._fetcher()
        with self.sf() as s:
            row = s.get(Source, d.id)
            cursor = row.cursor
            known = set(s.scalars(select(Observation.url).where(Observation.source_id == d.id)))
        ctx = AdapterContext(fetcher, d, cursor, lambda u: canonical_url(u) in known)
        error = None
        robots = "allowed"
        emitted = 0
        try:
            async for raw in adapter.discover(ctx):
                with self.sf() as s:
                    if self.ingest(s, raw, d):
                        emitted += 1
                    s.commit()
                known.add(canonical_url(raw.url))
        except RobotsDisallowed as e:
            robots = e.state
            error = f"robots.txt {e.state}: {e.url}"
        except Exception as e:  # noqa: BLE001 — one broken source must not stop the cycle
            log.exception("source %s failed", d.id)
            error = f"{type(e).__name__}: {e}"
        with self.sf() as s:
            row = s.get(Source, d.id)
            row.last_run_at = utcnow()
            row.robots_state = robots
            row.robots_checked_at = utcnow()
            row.last_error = error
            if error is None:
                row.last_success_at = utcnow()
                row.cursor = ctx.new_cursor
            s.commit()
        return {**ctx.stats, "new": emitted, "error": error, "robots": robots}

    # ------------------------------------------------------------------ ingestion
    def ingest(self, session, raw: RawObservation, d: SourceDef) -> Observation | None:
        url = canonical_url(raw.url)
        text = (raw.text or "")[:200_000]
        title = (raw.title or "").strip()[:1000]
        h = hashlib.sha256(f"{title}\n{text}".encode()).hexdigest()
        obs = session.scalar(select(Observation).where(Observation.url == url))
        if obs is not None and obs.content_hash == h:
            return None
        lang = raw.lang if raw.lang in ("en", "am") else ethiopic.detect_lang(f"{title} {text[:2000]}")
        extra = {"doc_type_hint": raw.doc_type_hint, "issuer_hint": raw.issuer_hint, **(raw.extra or {})}
        fields = dict(
            source_id=d.id, publisher=d.publisher, tier=d.tier, title=title, lang=lang,
            published_at=raw.published_at, content_hash=h, text=text,
            shingles_json=json.dumps(similarity.shingles(f"{title} {text}")),
            attribution=classify.detect_attribution(text or title, self.aliases, d.publisher),
            is_primary_document=raw.is_primary_document,
            gazette=raw.gazette or (raw.is_primary_document and looks_like_gazette(text)),
            status_hint=raw.status_hint, extra_json=json.dumps(extra, ensure_ascii=False), processed=False,
        )
        if obs is None:
            obs = Observation(url=url, **fields)
            session.add(obs)
        else:  # the page changed (article updated, list entry corrected): re-process it
            for k, v in fields.items():
                setattr(obs, k, v)
        session.flush()
        return obs

    def process_pending(self, session) -> set[str]:
        touched: set[str] = set()
        pending = session.scalars(select(Observation).where(Observation.processed.is_(False))
                                  .order_by(Observation.published_at.is_(None), Observation.published_at,
                                            Observation.id)).all()
        for obs in pending:
            d = self.sources.get(obs.source_id)
            for old in list(obs.signals):
                if old.item_id:
                    touched.add(old.item_id)
                session.delete(old)
            session.flush()
            if d is not None and d.jurisdiction == "INTL":
                touched.add(self._intl_item(session, obs).id)
            else:
                extra = obs.extra
                view = ObsView(title=obs.title, text=obs.text, lang=obs.lang, when=obs.when, tier=obs.tier,
                               is_primary_document=obs.is_primary_document, gazette=obs.gazette,
                               status_hint=obs.status_hint, doc_type_hint=extra.get("doc_type_hint"),
                               issuer_hint=extra.get("issuer_hint"))
                for sig in extract_signals(view):
                    item = cluster.attach(session, sig, obs.id, obs.when)
                    session.flush()
                    touched.add(item.id)
            obs.processed = True
        session.flush()
        return touched

    def _intl_item(self, session, obs: Observation) -> Item:
        it = Item(jurisdiction="INTL", doc_type="other", title_en=obs.title if obs.lang == "en" else None,
                  title_am=obs.title if obs.lang == "am" else None, verification="reported",
                  relevance=international.relevance(obs.title, obs.text), version=next_version(session))
        session.add(it)
        session.flush()
        session.add(Signal(observation_id=obs.id, item_id=it.id, doc_type="other"))
        session.flush()
        return it

    # ------------------------------------------------------------------ item rebuild
    _SNAPSHOT = ("title_en", "title_am", "doc_type", "law_number", "law_year_gc", "law_year_ec", "issuer",
                 "initiator", "topics_json", "verification", "independent_sources", "has_official_source",
                 "verified_status", "reported_status", "status", "urgency", "summary_en", "summary_am",
                 "event_date", "status_date", "amends_key", "references_json", "verification_json", "deleted")

    def _snap(self, item: Item) -> dict:
        return {k: getattr(item, k) for k in self._SNAPSHOT}

    def rebuild(self, session, item_id: str) -> list[tuple[str, str]]:
        item = session.get(Item, item_id)
        if item is None or (item.deleted and item.merged_into):
            return []
        before = self._snap(item)
        signals = list(item.signals)
        if not signals:
            item.deleted = True
        elif item.jurisdiction == "INTL":
            self._rebuild_intl(item, signals)
        else:
            self._rebuild_et(item, signals)
        events: list[tuple[str, str]] = []
        if self._snap(item) != before:
            item.version = next_version(session)
            item.updated_at = utcnow()
            if item.verification == "verified" and before["verification"] != "verified":
                item.verified_at = utcnow()
                events.append(("verified", item.id))
            elif (item.verification == "verified" and
                  classify.status_rank(item.verified_status) > classify.status_rank(before["verified_status"])):
                events.append(("status", item.id))
        return events

    def _rebuild_intl(self, item: Item, signals: list[Signal]):
        o = signals[0].observation
        item.title_en = o.title if o.lang == "en" else item.title_en
        item.title_am = o.title if o.lang == "am" else item.title_am
        excerpt = re.sub(r"\s+", " ", o.text or "").strip()
        item.summary_en = (excerpt[:self.settings.news_excerpt_chars].rsplit(" ", 1)[0] + "…") \
            if len(excerpt) > self.settings.news_excerpt_chars else excerpt or None
        item.event_date = o.when.date()
        item.status_date = item.event_date
        item.topics_json = json.dumps(classify.detect_topics(f"{o.title} {o.text[:3000]}"))
        item.independent_sources = 1
        item.relevance = international.relevance(o.title, o.text)
        item.verification_json = json.dumps({"evidence": [{
            "observation_id": o.id, "publisher": o.publisher, "tier": o.tier, "url": o.url, "title": o.title,
            "published_at": o.when.replace(microsecond=0).isoformat() + "Z", "status": None, "primary_document": False, "gazette": False,
            "counted": True, "reason": "single international source"}]}, ensure_ascii=False)

    def _rebuild_et(self, item: Item, signals: list[Signal]):
        obs = {s.observation_id: s.observation for s in signals}
        evidence = []
        for oid, o in obs.items():
            st = classify.max_status(*(s.status for s in signals if s.observation_id == oid))
            ev = verify.Evidence(oid, o.publisher, o.tier, o.url, o.title, o.published_at or o.fetched_at,
                                 st, o.is_primary_document, o.gazette)
            evidence.append((ev, o.shingles, o.attribution))
        res = verify.evaluate(evidence, self.settings)

        tier_of = {oid: o.tier for oid, o in obs.items()}
        item.title_en = _pick_title(signals, "title_en", tier_of) or item.title_en
        item.title_am = _pick_title(signals, "title_am", tier_of) or item.title_am
        if item.canonical_key:
            numbered = [s for s in signals if s.canonical_key == item.canonical_key]
            if numbered:
                item.doc_type = numbered[0].doc_type
                gc = [s for s in numbered if s.year_calendar == "gc"]
                pick = sorted(gc or numbered, key=lambda s: -tier_of.get(s.observation_id, 9))[-1]
                item.law_number, item.law_year_gc = pick.law_number, pick.law_year_gc
                ec = [s for s in numbered if s.year_calendar == "ec"]
                item.law_year_ec = (ec[0].law_year_ec if ec else pick.law_year_ec)
        item.issuer = _most_common(s.issuer for s in signals) or item.issuer
        item.initiator = _most_common(s.initiator for s in signals)
        topics = Counter(t for s in signals for t in json.loads(s.topics_json or "[]"))
        item.topics_json = json.dumps([t for t, _ in topics.most_common(4)])
        refs = sorted({r for s in signals for r in json.loads(s.references_json or "[]")} - {item.canonical_key})
        item.references_json = json.dumps(refs)
        amend_words = ("amendment", "ማሻሻያ")
        if any(w in f"{item.title_en or ''} {item.title_am or ''}".lower() for w in amend_words) and refs:
            item.amends_key = next((r for r in refs if r.split(":")[0] == (item.canonical_key or "").split(":")[0]), refs[0])
        item.verification = res.verification
        item.independent_sources = res.independent_sources
        item.has_official_source = res.has_official_source
        item.verified_status = res.verified_status
        item.reported_status = res.reported_status
        item.status = res.display_status
        item.urgency = classify.STATUS_URGENCY.get(item.status or "")
        whens = [o.when for o in obs.values()]
        item.event_date = min(whens).date()
        item.status_date = (res.status_since or max(whens)).date()
        lead = _official_lead(obs.values())
        item.summary_en, item.summary_am = summarize(item, res, lead)
        item.verification_json = json.dumps({
            "rule": {"min_independent_sources": self.settings.min_independent_sources,
                     "require_official_anchor": self.settings.require_official_anchor,
                     "gazette_confirms_in_force": self.settings.gazette_confirms_in_force},
            "status_support": res.status_support,
            "evidence": [e.to_dict() for e in res.evidence],
        }, ensure_ascii=False, sort_keys=True)

    def apply_amendments(self, session, item_ids) -> set[str]:
        """An amendment in force marks the law it amends as amended."""
        changed = set()
        for iid in item_ids:
            it = session.get(Item, iid)
            if not it or it.deleted or not it.amends_key or it.status not in ("in_force",):
                continue
            base = session.scalar(select(Item).where(Item.canonical_key == it.amends_key, Item.deleted.is_(False)))
            if base and base.status == "in_force":
                base.status = "amended"
                base.urgency = classify.STATUS_URGENCY["amended"]
                base.version = next_version(session)
                base.updated_at = utcnow()
                changed.add(base.id)
        return changed

    # ------------------------------------------------------------------ one cycle
    def publish(self, session, touched: set[str]) -> tuple[list, int, int]:
        events = []
        for iid in sorted(touched):
            events += self.rebuild(session, iid)
        merged = cluster.consolidate(session)
        for _, dst in merged:
            events += self.rebuild(session, dst)
        self.apply_amendments(session, touched | {d for _, d in merged})
        featured = international.select_featured(session, self.settings)
        return events, len(merged), len(featured)

    async def run_cycle(self, only: list[str] | None = None, due_only: bool = True) -> CycleReport:
        report = CycleReport()
        targets = [self.sources[i] for i in only] if only else (
            self.due_sources() if due_only else [d for d in self.sources.values() if d.enabled and d.kind != "manual"])
        sem = asyncio.Semaphore(4)

        async def one(d):
            async with sem:
                report.crawled[d.id] = await self.crawl_source(d)

        await asyncio.gather(*(one(d) for d in targets))
        report.new_observations = sum(v.get("new", 0) for v in report.crawled.values())
        with self.sf() as s:
            touched = self.process_pending(s)
            events, merged, featured = self.publish(s, touched)
            s.commit()
            report.touched_items, report.merged, report.featured_intl = len(touched), merged, featured
            report.events = events
            report.version = current_version(s)
        if events:
            report.pushed = self.pusher.send_tickle(report.version, high_priority=True, verified_count=len(events))
        return report

    def submit(self, raw: RawObservation, source_id: str) -> tuple[list[str], list]:
        """Assisted ingestion (admin endpoint / CLI): ingest one observation now."""
        d = self.sources[source_id]
        with self.sf() as s:
            self.ingest(s, raw, d)
            touched = self.process_pending(s)
            events, _, _ = self.publish(s, touched)
            s.commit()
            version = current_version(s)
        if events:
            self.pusher.send_tickle(version, high_priority=True, verified_count=len(events))
        return sorted(touched), events


def _most_common(values) -> str | None:
    c = Counter(v for v in values if v)
    return c.most_common(1)[0][0] if c else None


def _pick_title(signals: list[Signal], field_name: str, tier_of: dict) -> str | None:
    scores: Counter = Counter()
    for s in signals:
        t = getattr(s, field_name)
        if not t or len(t) < 6:
            continue
        tier = tier_of.get(s.observation_id, 9)
        scores[t.strip()] += (10 if tier == 1 else 3 if tier == 2 else 1) + (2 if s.canonical_key else 0)
    return scores.most_common(1)[0][0] if scores else None


_WHEREAS = re.compile(r"\bWHEREAS\b[^;:]{20,600}", re.I)


def _official_lead(observations) -> str | None:
    for o in observations:
        if o.tier == 1 and o.is_primary_document and o.text:
            m = _WHEREAS.search(o.text)
            if m:
                return m.group(0)
    return None
