"""HTTP API used by the Android app.

  GET  /feed/index.json, /feed/<file>       the feed the app reads (same files the static
                                            export publishes; see lexet/static_feed.py)
  GET  /                                     status page
  GET  /v1/sync?since=<version>&limit=<n>   delta sync (ETag/304, gzip) — earlier protocol
  GET  /v1/items/{id}                        one item
  GET  /v1/sources                           what is monitored, with tier and robots status
  GET  /v1/health
  POST /v1/admin/observations                assisted ingestion (Bearer admin token)

No accounts, no cookies, no device identifiers: the app is anonymous.
"""
from __future__ import annotations

import datetime as dt
import secrets

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request, Response
from fastapi.middleware.gzip import GZipMiddleware
from fastapi.responses import HTMLResponse
from pydantic import BaseModel
from sqlalchemy import or_, select

from .. import static_feed
from ..config import Settings
from ..models import Item, current_version
from ..sources.base import RawObservation
from ..sources.html_extract import parse_date
from .serialize import item_dtos, iso as _iso, law_id, sources_list  # noqa: F401  (law_id re-exported)

class ObservationIn(BaseModel):
    source_id: str
    url: str
    title: str
    text: str = ""
    lang: str | None = None
    published_at: str | None = None
    is_primary_document: bool = False
    gazette: bool = False
    status_hint: str | None = None
    doc_type_hint: str | None = None
    issuer_hint: str | None = None


def create_app(session_factory, settings: Settings | None = None, pipeline=None) -> FastAPI:
    settings = settings or Settings.from_env()
    app = FastAPI(title="LexET API", version="0.1.0", docs_url="/docs", redoc_url=None)
    app.add_middleware(GZipMiddleware, minimum_size=800)

    def db():
        with session_factory() as s:
            yield s

    def dtos(s, items: list[Item]) -> list[dict]:
        return item_dtos(s, items, settings)

    with session_factory() as s:   # create the dataset epoch once, before any phone sees it
        static_feed.epoch(s)
        s.commit()

    cache: dict = {}

    def feed_files(s) -> dict[str, bytes]:
        v = current_version(s)
        if cache.get("version") != v:
            files, _ = static_feed.build(s, settings)
            cache.clear()
            cache.update(version=v, files=files)
        return cache["files"]

    @app.get("/feed/{name}")
    def feed(name: str, s=Depends(db)):
        body = feed_files(s).get(f"feed/{name}")
        if body is None:
            raise HTTPException(404, "not found")
        # Hashed files never change; the index and source list do.
        cc = "public, max-age=31536000, immutable" if name.startswith(("seg-", "snapshot-")) else "no-cache"
        return Response(body, media_type="application/json", headers={"Cache-Control": cc})

    @app.get("/", response_class=HTMLResponse)
    def status(s=Depends(db)):
        return HTMLResponse(feed_files(s)["index.html"].decode("utf-8"))

    @app.get("/v1/health")
    def health(s=Depends(db)):
        return {"ok": True, "version": current_version(s)}

    @app.get("/v1/sync")
    def sync(request: Request, response: Response, since: int = Query(0, ge=0),
             limit: int = Query(settings.sync_page_limit, ge=1, le=2000), s=Depends(db)):
        latest = current_version(s)
        etag = f'W/"v{latest}-s{since}"'
        if since >= latest:
            # Nothing new: an empty 304 is the cheapest possible answer for a phone.
            return Response(status_code=304, headers={"ETag": etag, "Cache-Control": "public, max-age=30"})
        q = (select(Item).where(Item.version > since,
                                or_(Item.jurisdiction == "ET", Item.featured.is_(True), Item.deleted.is_(True)))
             .order_by(Item.version).limit(limit + 1))
        rows = s.scalars(q).all()
        has_more = len(rows) > limit
        rows = rows[:limit]
        next_since = rows[-1].version if rows else latest
        if not has_more:
            next_since = max(next_since, latest)
        response.headers["ETag"] = etag
        response.headers["Cache-Control"] = "public, max-age=30"
        return {
            "server_time": _iso(dt.datetime.utcnow()),
            "next_since": next_since,
            "has_more": has_more,
            "items": dtos(s, rows),
        }

    @app.get("/v1/items/{item_id}")
    def get_item(item_id: str, s=Depends(db)):
        it = s.get(Item, item_id)
        if it is None:
            raise HTTPException(404, "not found")
        return dtos(s, [it])[0]

    @app.get("/v1/sources")
    def sources(s=Depends(db)):
        return sources_list(s)

    @app.post("/v1/admin/observations")
    def submit(body: ObservationIn, authorization: str | None = Header(None)):
        if not settings.admin_token:
            raise HTTPException(403, "admin ingestion disabled (set LEXET_ADMIN_TOKEN)")
        token = (authorization or "").removeprefix("Bearer ").strip()
        if not secrets.compare_digest(token, settings.admin_token):
            raise HTTPException(401, "bad token")
        if pipeline is None or body.source_id not in pipeline.sources:
            raise HTTPException(400, "unknown source_id")
        raw = RawObservation(url=body.url, title=body.title, text=body.text, lang=body.lang,
                             published_at=parse_date(body.published_at) if body.published_at else None,
                             is_primary_document=body.is_primary_document, gazette=body.gazette,
                             status_hint=body.status_hint, doc_type_hint=body.doc_type_hint,
                             issuer_hint=body.issuer_hint)
        touched, events = pipeline.submit(raw, body.source_id)
        return {"items": touched, "events": [{"kind": k, "item_id": i} for k, i in events]}

    return app
