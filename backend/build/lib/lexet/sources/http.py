"""Polite HTTP fetching: robots.txt, per-host rate limits, conditional GET, size caps.

Every request goes through here. If a site's robots.txt forbids our user agent
the request is not made; the source is reported as "disallowed" and can only be
fed through assisted (manual) ingestion — see docs/OPERATIONS.md.
"""
from __future__ import annotations

import asyncio
import hashlib
import time
import urllib.robotparser
from dataclasses import dataclass
from urllib.parse import urlsplit

import httpx

from ..config import Settings


class RobotsDisallowed(Exception):
    """Raised instead of fetching. `state` is "disallowed" (robots.txt forbids us)
    or "unknown" (robots.txt unreachable or 5xx: we do not crawl until it answers)."""

    def __init__(self, url: str, state: str):
        super().__init__(f"{url} ({state})")
        self.url, self.state = url, state


@dataclass
class FetchResult:
    url: str
    status: int
    content: bytes
    headers: dict
    not_modified: bool = False

    @property
    def text(self) -> str:
        enc = "utf-8"
        ctype = self.headers.get("content-type", "")
        if "charset=" in ctype:
            enc = ctype.split("charset=")[-1].split(";")[0].strip() or "utf-8"
        try:
            return self.content.decode(enc, errors="replace")
        except LookupError:
            return self.content.decode("utf-8", errors="replace")

    @property
    def content_hash(self) -> str:
        return hashlib.sha256(self.content).hexdigest()


class MemoryCache:
    """Validator cache (ETag / Last-Modified). The pipeline swaps in a DB-backed one."""

    def __init__(self):
        self._d: dict[str, tuple[str | None, str | None]] = {}

    def get(self, url):
        return self._d.get(url, (None, None))

    def put(self, url, etag, last_modified):
        self._d[url] = (etag, last_modified)


class PoliteFetcher:
    ROBOTS_TTL_S = 24 * 3600

    def __init__(self, settings: Settings, client: httpx.AsyncClient | None = None, cache=None):
        self.settings = settings
        self.client = client or httpx.AsyncClient(
            headers={"User-Agent": settings.user_agent, "Accept-Encoding": "gzip, deflate"},
            timeout=settings.request_timeout_s,
            follow_redirects=True,
        )
        self.cache = cache or MemoryCache()
        self._robots: dict[str, tuple[urllib.robotparser.RobotFileParser | None, float, str]] = {}
        self._last_hit: dict[str, float] = {}
        self._locks: dict[str, asyncio.Lock] = {}
        self.ua_token = settings.user_agent.split("/")[0]

    async def aclose(self):
        await self.client.aclose()

    # ------------------------------------------------------------- robots
    async def robots_state(self, url: str) -> str:
        """"allowed", "disallowed" or "unknown" for the URL under our user agent."""
        rp, _, state = await self._robots_for(url)
        if state != "ok":
            return state
        return "allowed" if rp.can_fetch(self.ua_token, url) else "disallowed"

    async def _robots_for(self, url: str):
        parts = urlsplit(url)
        host = f"{parts.scheme}://{parts.netloc}"
        cached = self._robots.get(host)
        if cached and time.time() - cached[1] < self.ROBOTS_TTL_S:
            return cached
        rp = urllib.robotparser.RobotFileParser()
        state = "ok"
        try:
            r = await self.client.get(f"{host}/robots.txt")
            if r.status_code in (401, 403):
                state = "disallowed"
            elif 400 <= r.status_code < 500:
                rp.parse([])            # no robots.txt: everything allowed
            elif r.status_code >= 500:
                state = "unknown"       # server error: assume disallowed for now, retry later
            else:
                rp.parse(r.text.splitlines())
        except httpx.HTTPError:
            state = "unknown"
        entry = (rp, time.time(), state)
        self._robots[host] = entry
        return entry

    async def _delay_for(self, url: str) -> float:
        rp, _, state = await self._robots_for(url)
        delay = None
        if state == "ok":
            try:
                delay = rp.crawl_delay(self.ua_token)
            except AttributeError:
                delay = None
        return float(delay) if delay else self.settings.default_crawl_delay_s

    # ------------------------------------------------------------- fetch
    async def get(self, url: str, conditional: bool = True, accept: str | None = None) -> FetchResult:
        if self.settings.respect_robots:
            state = await self.robots_state(url)
            if state != "allowed":
                raise RobotsDisallowed(url, state)
        host = urlsplit(url).netloc
        lock = self._locks.setdefault(host, asyncio.Lock())
        async with lock:
            wait = await self._delay_for(url) - (time.monotonic() - self._last_hit.get(host, 0))
            if wait > 0:
                await asyncio.sleep(wait)
            headers = {}
            if accept:
                headers["Accept"] = accept
            if conditional:
                etag, lm = self.cache.get(url)
                if etag:
                    headers["If-None-Match"] = etag
                if lm:
                    headers["If-Modified-Since"] = lm
            try:
                async with self.client.stream("GET", url, headers=headers) as r:
                    chunks, size = [], 0
                    async for chunk in r.aiter_bytes():
                        size += len(chunk)
                        if size > self.settings.max_response_bytes:
                            raise httpx.HTTPError(f"response too large: {url}")
                        chunks.append(chunk)
                    result = FetchResult(str(r.url), r.status_code, b"".join(chunks),
                                         {k.lower(): v for k, v in r.headers.items()},
                                         not_modified=r.status_code == 304)
            finally:
                self._last_hit[host] = time.monotonic()
        if result.status == 200 and conditional:
            self.cache.put(url, result.headers.get("etag"), result.headers.get("last-modified"))
        return result
