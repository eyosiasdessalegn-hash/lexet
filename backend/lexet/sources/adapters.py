"""Generic source adapters. Concrete sources are configurations of these (catalog.py).

  WordPressAdapter    WP REST API (most Ethiopian newspapers and many agency sites).
                      Fetches only posts newer than the cursor, titles first,
                      full content only for legal candidates. Falls back to RSS.
  RssAdapter          RSS/Atom feeds (international sources, WP fallback).
  HtmlListingAdapter  "Law-link harvester" for official listing pages (MoJ law
                      database, HoPR laws and drafts, EFDA/EIPA document lists).
                      Finds links whose text or row carries a law identifier or a
                      legal document word; no brittle CSS selectors.
  HtmlArticlesAdapter News listing page → article pages (ENA, HoPR news).
"""
from __future__ import annotations

import asyncio
import datetime as dt
import json
import re
from typing import AsyncIterator
from urllib.parse import urlencode, urljoin, urlsplit

import feedparser
import httpx

from ..text import ethiopic, lawid
from .base import AdapterContext, RawObservation, looks_legal
from .html_extract import extract_article, links_with_context, looks_like_gazette, parse_date, pdf_text
from .http import RobotsDisallowed


def _strip_html(s: str) -> str:
    if not s:
        return ""
    import lxml.html
    try:
        return re.sub(r"\s+", " ", lxml.html.fromstring(f"<div>{s}</div>").text_content()).strip()
    except Exception:  # noqa: BLE001
        return re.sub(r"<[^>]+>", " ", s)


def _iso(d: dt.datetime) -> str:
    return d.replace(microsecond=0).isoformat()


async def _safe_get(ctx: AdapterContext, url: str, **kw):
    ctx.stats["requests"] += 1
    try:
        r = await ctx.fetcher.get(url, **kw)
    except RobotsDisallowed:
        ctx.stats["blocked_by_robots"] += 1
        raise
    except httpx.HTTPError as e:
        ctx.stats["errors"] += 1
        if len(ctx.trace) < 4:
            ctx.trace.append(f"ERR {type(e).__name__} {url[:100]}")
        return None
    if r.not_modified:
        ctx.stats["not_modified"] += 1
    if len(ctx.trace) < 4:
        ctype = (r.headers.get("content-type", "") or "").split(";")[0]
        ctx.trace.append(f"{r.status} {ctype} {len(r.content)}B {url[:100]}")
    return r


def _hints(ctx: AdapterContext) -> dict:
    c = ctx.source.config
    return {"status_hint": c.get("status_hint"), "doc_type_hint": c.get("doc_type_hint"),
            "issuer_hint": c.get("issuer_hint")}


# ============================================================== WordPress

class WordPressAdapter:
    def __init__(self, base_url: str, per_page: int = 50, max_pages: int = 4, require_legal: bool = True):
        self.base = base_url.rstrip("/")
        self.per_page = per_page
        self.max_pages = max_pages
        self.require_legal = require_legal

    async def discover(self, ctx: AdapterContext) -> AsyncIterator[RawObservation]:
        after = ctx.cursor or _iso(ctx.now - dt.timedelta(days=14))
        newest = after
        for page in range(1, self.max_pages + 1):
            q = urlencode({"after": after, "per_page": self.per_page, "page": page, "orderby": "date",
                           "order": "desc", "_fields": "id,link,date_gmt,title,excerpt"})
            r = await _safe_get(ctx, f"{self.base}/wp-json/wp/v2/posts?{q}", conditional=page == 1,
                                accept="application/json")
            if r is None or r.status in (401, 403, 404):
                if page == 1:  # REST API disabled: use the feed instead
                    async for o in RssAdapter(f"{self.base}/feed/", fetch_full=True,
                                              require_legal=self.require_legal).discover(ctx):
                        yield o
                return
            if r.not_modified or r.status == 400:  # 400: page beyond the last one
                break
            try:
                posts = json.loads(r.text)
            except ValueError:
                ctx.stats["errors"] += 1
                break
            if not posts:
                break
            for p in posts:
                link = p.get("link")
                title = _strip_html((p.get("title") or {}).get("rendered", ""))
                excerpt = _strip_html((p.get("excerpt") or {}).get("rendered", ""))
                when = parse_date(p.get("date_gmt"))
                if when and _iso(when) > newest:
                    newest = _iso(when)
                ctx.stats["seen"] += 1
                if not link or ctx.is_known(link):
                    ctx.stats["skipped_known"] += 1
                    continue
                if self.require_legal and not looks_legal(f"{title} {excerpt}"):
                    ctx.sample(title)
                    continue
                ctx.stats["candidates"] += 1
                full = await _safe_get(ctx, f"{self.base}/wp-json/wp/v2/posts/{p['id']}?_fields=content",
                                       conditional=False, accept="application/json")
                body = excerpt
                if full is not None and full.status == 200:
                    try:
                        body = _strip_html(json.loads(full.text).get("content", {}).get("rendered", "")) or excerpt
                    except ValueError:
                        pass
                ctx.stats["emitted"] += 1
                yield RawObservation(url=link, title=title, text=body, published_at=when,
                                     lang=ethiopic.detect_lang(title + " " + body[:1000]), **_hints(ctx))
            if len(posts) < self.per_page:
                break
        ctx.new_cursor = newest


# ============================================================== RSS / Atom

class RssAdapter:
    def __init__(self, feed_url: str, fetch_full: bool = False, require_legal: bool = True,
                 keywords: list[str] | None = None, per_item_publisher: bool = False):
        self.feed_url = feed_url
        self.fetch_full = fetch_full
        self.require_legal = require_legal
        self.keywords = [k.lower() for k in (keywords or [])]
        self.per_item_publisher = per_item_publisher   # Google News: <source url="…">Outlet</source>

    async def discover(self, ctx: AdapterContext) -> AsyncIterator[RawObservation]:
        r = await _safe_get(ctx, self.feed_url, accept="application/rss+xml, application/atom+xml, */*")
        if r is None or r.not_modified or r.status != 200:
            return
        feed = feedparser.parse(r.content)
        newest = ctx.cursor or ""
        for e in feed.entries:
            link = e.get("link")
            title = _strip_html(e.get("title", ""))
            summary = _strip_html(e.get("summary", "") or e.get("description", ""))
            when = None
            for key in ("published_parsed", "updated_parsed"):
                if e.get(key):
                    when = dt.datetime(*e[key][:6])
                    break
            if when and _iso(when) > newest:
                newest = _iso(when)
            ctx.stats["seen"] += 1
            if not link or ctx.is_known(link):
                ctx.stats["skipped_known"] += 1
                continue
            hay = f"{title} {summary}"
            if self.keywords and not any(k in hay.lower() for k in self.keywords):
                ctx.sample(title)
                continue
            if self.require_legal and not looks_legal(hay):
                ctx.sample(title)
                continue
            ctx.stats["candidates"] += 1
            text = summary
            if self.fetch_full:
                page = await _safe_get(ctx, link, conditional=False)
                if page is not None and page.status == 200:
                    art = extract_article(page.text, link)
                    text = art.text or summary
                    when = when or art.published_at
            ctx.stats["emitted"] += 1
            publisher, tier = (None, None)
            if self.per_item_publisher:
                src = e.get("source") or {}
                publisher, tier = publisher_for(src.get("href") or "", src.get("title") or "")
            yield RawObservation(url=link, title=title, text=text, published_at=when,
                                 lang=ethiopic.detect_lang(title + " " + text[:1000]), publisher=publisher,
                                 tier=tier, **_hints(ctx))
        ctx.new_cursor = newest


# ============================================================== official listings

_DOC_WORDS = re.compile(r"proclamation|regulation|directive|guideline|gazette|ደንብ|አዋጅ|መመሪያ|ጋዜጣ", re.I)


class HtmlListingAdapter:
    """Harvests official listing pages for links to legal instruments.

    Config keys: listing_urls, page_param ("page"), max_pages, link_regex,
    fetch_documents (download PDFs and read the first pages), status_hint, ...
    """

    def __init__(self, listing_urls: list[str], max_pages: int = 3, page_param: str | None = None,
                 link_regex: str | None = None, fetch_documents: bool = True, pagination: str | None = None):
        self.listing_urls = listing_urls
        self.max_pages = max_pages
        self.page_param = page_param
        self.pagination = pagination  # e.g. "{url}page/{n}/" for WordPress archives
        self.link_re = re.compile(link_regex) if link_regex else None
        self.fetch_documents = fetch_documents

    def _pages(self, url: str):
        yield url
        for n in range(2, self.max_pages + 1):
            if self.pagination:
                yield self.pagination.format(url=url if url.endswith("/") else url + "/", n=n)
            elif self.page_param:
                sep = "&" if "?" in url else "?"
                yield f"{url}{sep}{self.page_param}={n}"

    async def discover(self, ctx: AdapterContext) -> AsyncIterator[RawObservation]:
        seen: set[str] = set()
        for listing in self.listing_urls:
            for page_url in self._pages(listing):
                r = await _safe_get(ctx, page_url)
                if r is None or r.status != 200:
                    break
                if r.not_modified:
                    break  # unchanged listing ⇒ nothing new on later pages either
                new_on_page = 0
                for link in links_with_context(r.text, r.url):
                    if link.url in seen:
                        continue
                    seen.add(link.url)
                    ctx.stats["seen"] += 1
                    if self.link_re and not self.link_re.search(link.url):
                        continue
                    label = f"{link.text} {link.context}"
                    refs = lawid.find_law_refs(label)
                    if not refs and not _DOC_WORDS.search(label):
                        ctx.sample(f"{link.text[:40]} <{urlsplit(link.url).path[:45]}>")
                        continue
                    if urlsplit(link.url).netloc != urlsplit(r.url).netloc and not link.url.lower().endswith(".pdf"):
                        continue
                    if ctx.is_known(link.url):
                        ctx.stats["skipped_known"] += 1
                        continue
                    ctx.stats["candidates"] += 1
                    new_on_page += 1
                    obs = await self._observation(ctx, link, label)
                    if obs:
                        ctx.stats["emitted"] += 1
                        yield obs
                if new_on_page == 0:
                    break  # listings are newest-first: a page with nothing new ends the scan

    async def _observation(self, ctx, link, label) -> RawObservation | None:
        title = link.text if len(link.text) > 12 else link.context[:300]
        text = link.context
        gazette = False
        published = parse_date(link.context)
        if self.fetch_documents:
            doc = await _safe_get(ctx, link.url, conditional=False)
            if doc is not None and doc.status == 200:
                ctype = doc.headers.get("content-type", "")
                if "pdf" in ctype or link.url.lower().endswith(".pdf"):
                    body = pdf_text(doc.content)
                    if body:
                        text = f"{link.context}\n{body}"
                        gazette = looks_like_gazette(body)
                elif "html" in ctype:
                    art = extract_article(doc.text, link.url)
                    text = f"{link.context}\n{art.text}"
                    published = published or art.published_at
                    if len(art.title) > len(title):
                        title = art.title
        return RawObservation(url=link.url, title=title, text=text, published_at=published,
                              lang=ethiopic.detect_lang(title + " " + text[:1000]),
                              is_primary_document=True, gazette=gazette, **_hints(ctx))


# ============================================================== news listings

class HtmlArticlesAdapter:
    def __init__(self, listing_urls: list[str], article_link_regex: str, require_legal: bool = True,
                 max_articles: int = 40):
        self.listing_urls = listing_urls
        self.article_re = re.compile(article_link_regex)
        self.require_legal = require_legal
        self.max_articles = max_articles

    async def discover(self, ctx: AdapterContext) -> AsyncIterator[RawObservation]:
        fetched = 0
        seen = set()
        for listing in self.listing_urls:
            r = await _safe_get(ctx, listing)
            if r is None or r.not_modified or r.status != 200:
                continue
            # A story is often linked twice (picture, then headline): keep the most descriptive link per URL.
            best: dict[str, object] = {}
            for lk in links_with_context(r.text, r.url):
                cur = best.get(lk.url)
                if cur is None or len(lk.text) > len(cur.text):
                    best[lk.url] = lk
            links = list(best.values())
            if not any(self.article_re.search(lk.url) for lk in links):
                # Diagnostics: the article pattern matches nothing; show what links the page has.
                for lk in links[:200:20]:
                    ctx.sample(f"no match: {urlsplit(lk.url).path[:70]}")
            for link in links:
                if link.url in seen or not self.article_re.search(link.url):
                    continue
                seen.add(link.url)
                ctx.stats["seen"] += 1
                if ctx.is_known(link.url):
                    ctx.stats["skipped_known"] += 1
                    continue
                # Only open articles whose headline looks legal (saves requests).
                if self.require_legal and not looks_legal(f"{link.text} {link.context}"):
                    ctx.sample(link.text)
                    continue
                if fetched >= self.max_articles:
                    return
                ctx.stats["candidates"] += 1
                page = await _safe_get(ctx, link.url, conditional=False)
                fetched += 1
                if page is None or page.status != 200:
                    continue
                art = extract_article(page.text, link.url)
                ctx.stats["emitted"] += 1
                yield RawObservation(url=link.url, title=art.title or link.text, text=art.text,
                                     published_at=art.published_at, lang=art.lang, **_hints(ctx))


# ============================================================== aggregators

def _site(host_or_url: str) -> str:
    """'https://eng.addisstandard.com/x' → 'addisstandard.com'; 'www.justice.gov.et' → 'justice.gov.et'."""
    host = urlsplit(host_or_url).netloc if "//" in host_or_url else host_or_url
    labels = [x for x in host.lower().split(":")[0].split(".") if x]
    if len(labels) >= 3 and len(labels[-1]) == 2 and labels[-2] in ("gov", "com", "org", "edu", "co", "net", "ac"):
        return ".".join(labels[-3:])
    return ".".join(labels[-2:])


_KNOWN_SITES: dict[str, tuple[str, int]] | None = None


def publisher_for(host_or_url: str, name: str = "") -> tuple[str | None, int | None]:
    """Publisher and tier for an article an aggregator found. Outlets we also crawl
    directly keep their own publisher (so ENA via Google News is still ENA); any
    other outlet is its own publisher at tier 4 ("other outlet")."""
    global _KNOWN_SITES
    if _KNOWN_SITES is None:
        from . import catalog
        _KNOWN_SITES = {}
        for d in catalog.SOURCES:
            c = d.config
            for u in [c.get("base_url"), c.get("feed_url"), *(c.get("listing_urls") or [])]:
                if u and d.kind not in ("gdelt",) and not c.get("per_item_publisher"):
                    _KNOWN_SITES.setdefault(_site(u), (d.publisher, d.tier))
    site = _site(host_or_url) if host_or_url else ""
    if not site:
        return (name.strip()[:60] or None), 4
    return _KNOWN_SITES.get(site, (site, 4))


class GdeltAdapter:
    """GDELT DOC 2.0 API: worldwide news, searched by keyword, updated every 15 minutes.

    Free and meant for programmatic use. Gives headlines and links (no text), so the
    first `fetch_full` new articles are opened, robots.txt permitting, for their text.
    """
    API = "https://api.gdeltproject.org/api/v2/doc/doc"
    _LANG = {"English": "en", "Amharic": "am"}

    def __init__(self, query: str, max_records: int = 75, timespan: str = "3d", fetch_full: int = 12,
                 require_legal: bool = True):
        self.query, self.max_records, self.timespan = query, max_records, timespan
        self.fetch_full, self.require_legal = fetch_full, require_legal

    async def discover(self, ctx: AdapterContext) -> AsyncIterator[RawObservation]:
        q = urlencode({"query": self.query, "mode": "artlist", "format": "json", "maxrecords": self.max_records,
                       "timespan": self.timespan, "sort": "datedesc"})
        r = await _safe_get(ctx, f"{self.API}?{q}", conditional=False, accept="application/json")
        if r is None:   # GDELT drops connections when busy: one more try after its 5-second rule
            await asyncio.sleep(6)
            r = await _safe_get(ctx, f"{self.API}?{q}", conditional=False, accept="application/json")
        if r is None or r.status != 200:
            return
        try:
            articles = json.loads(r.text).get("articles", [])
        except ValueError:
            ctx.stats["errors"] += 1
            ctx.sample("not JSON: " + r.text[:70])
            return
        opened = 0
        for a in articles:
            ctx.stats["seen"] += 1
            url, title = a.get("url"), _strip_html(a.get("title") or "")
            if not url or not title:
                continue
            if ctx.is_known(url):
                ctx.stats["skipped_known"] += 1
                continue
            if self.require_legal and not looks_legal(title):
                ctx.sample(title)
                continue
            ctx.stats["candidates"] += 1
            when = None
            try:
                when = dt.datetime.strptime(a.get("seendate", ""), "%Y%m%dT%H%M%SZ")
            except ValueError:
                pass
            text = title
            if opened < self.fetch_full:
                opened += 1
                try:
                    page = await _safe_get(ctx, url, conditional=False)
                except RobotsDisallowed:
                    page = None   # that outlet does not allow crawlers: keep the headline only
                if page is not None and page.status == 200 and "html" in page.headers.get("content-type", ""):
                    art = extract_article(page.text, url)
                    text = art.text or title
                    when = when or art.published_at
            publisher, tier = publisher_for(a.get("domain") or url)
            lang = self._LANG.get(a.get("language", "")) or ethiopic.detect_lang(title + " " + text[:1000])
            ctx.stats["emitted"] += 1
            yield RawObservation(url=url, title=title, text=text, published_at=when, lang=lang,
                                 publisher=publisher, tier=tier, **_hints(ctx))


# ============================================================== factory

def build_adapter(kind: str, config: dict):
    if kind == "wordpress":
        return WordPressAdapter(config["base_url"], per_page=config.get("per_page", 50),
                                max_pages=config.get("max_pages", 4),
                                require_legal=config.get("require_legal", True))
    if kind == "rss":
        return RssAdapter(config["feed_url"], fetch_full=config.get("fetch_full", False),
                          require_legal=config.get("require_legal", True), keywords=config.get("keywords"),
                          per_item_publisher=config.get("per_item_publisher", False))
    if kind == "gdelt":
        return GdeltAdapter(config["query"], max_records=config.get("max_records", 75),
                            timespan=config.get("timespan", "3d"), fetch_full=config.get("fetch_full", 12),
                            require_legal=config.get("require_legal", True))
    if kind == "html_listing":
        return HtmlListingAdapter(config["listing_urls"], max_pages=config.get("max_pages", 3),
                                  page_param=config.get("page_param"), link_regex=config.get("link_regex"),
                                  fetch_documents=config.get("fetch_documents", True),
                                  pagination=config.get("pagination"))
    if kind == "html_articles":
        return HtmlArticlesAdapter(config["listing_urls"], config["article_link_regex"],
                                   require_legal=config.get("require_legal", True),
                                   max_articles=config.get("max_articles", 40))
    if kind == "manual":
        return None
    raise ValueError(f"unknown adapter kind {kind}")
