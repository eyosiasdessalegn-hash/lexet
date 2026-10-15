"""Markup-tolerant extraction of title, date and body text from article pages.

Deliberately avoids site-specific CSS selectors where it can: government and
media sites in Ethiopia change templates often, so we rely on metadata that
survives redesigns (Open Graph, JSON-LD, <time>), then fall back to the
densest block of paragraphs.
"""
from __future__ import annotations

import datetime as dt
import io
import json
import re
from dataclasses import dataclass
from urllib.parse import urljoin

import lxml.html

from ..text import calendar, ethiopic


@dataclass
class Extracted:
    title: str
    text: str
    published_at: dt.datetime | None
    lang: str


_ISO_RE = re.compile(r"(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2}))?)?")
_DMY_RE = re.compile(r"\b(\d{1,2})[/.](\d{1,2})[/.](\d{4})\b")
_EN_DATE_RE = re.compile(
    r"\b(January|February|March|April|May|June|July|August|September|October|November|December|"
    r"Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)\.?\s+(\d{1,2}),?\s+(\d{4})\b|"
    r"\b(\d{1,2})\s+(January|February|March|April|May|June|July|August|September|October|November|December)\s+(\d{4})\b",
    re.IGNORECASE,
)
_MONTHS = {m: i for i, m in enumerate(
    ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"], start=1)}


def parse_date(s: str | None) -> dt.datetime | None:
    """ISO, '25 June 2026', 'June 25, 2026', '25/06/2026', or an Amharic E.C. date."""
    if not s:
        return None
    m = _ISO_RE.search(s)
    if m:
        y, mo, d = int(m.group(1)), int(m.group(2)), int(m.group(3))
        hh, mm, ss = int(m.group(4) or 0), int(m.group(5) or 0), int(m.group(6) or 0)
        try:
            out = dt.datetime(y, mo, d, hh, mm, ss)
            # Offset-aware strings: normalise to naive UTC.
            tz = re.search(r"([+-])(\d{2}):?(\d{2})$|Z$", s.strip())
            if tz and tz.group(0) != "Z" and m.group(4):
                sign = 1 if tz.group(1) == "+" else -1
                out -= sign * dt.timedelta(hours=int(tz.group(2)), minutes=int(tz.group(3)))
            return out
        except ValueError:
            pass
    m = _EN_DATE_RE.search(s)
    if m:
        try:
            if m.group(1):
                return dt.datetime(int(m.group(3)), _MONTHS[m.group(1)[:3].lower()], int(m.group(2)))
            return dt.datetime(int(m.group(6)), _MONTHS[m.group(5)[:3].lower()], int(m.group(4)))
        except (ValueError, KeyError):
            pass
    am = calendar.parse_amharic_date(s)
    if am:
        return dt.datetime(am.year, am.month, am.day)
    m = _DMY_RE.search(s)
    if m:
        d, mo, y = int(m.group(1)), int(m.group(2)), int(m.group(3))
        today = dt.date.today()
        # Numeric dates on Ethiopian sites are often in the Ethiopian calendar
        # (18/10/2018 = 25 June 2026). Read the year as E.C. when month 13 is used,
        # or when the Gregorian reading would be 6+ years old but the E.C. reading
        # is recent — this feed is about new developments.
        try:
            g = calendar.ethiopic_to_gregorian(y, mo, d)
        except ValueError:
            g = None
        if g and (mo == 13 or (today.year - y >= 6 and g <= today + dt.timedelta(days=31))):
            return dt.datetime(g.year, g.month, g.day)
        try:
            return dt.datetime(y, mo, d)
        except ValueError:
            return None
    return None


def _meta(doc, *names) -> str | None:
    for n in names:
        for attr in ("property", "name", "itemprop"):
            el = doc.xpath(f"//meta[@{attr}='{n}']/@content")
            if el and el[0].strip():
                return el[0].strip()
    return None


def _jsonld_dates(doc) -> str | None:
    for node in doc.xpath("//script[@type='application/ld+json']/text()"):
        try:
            data = json.loads(node)
        except (ValueError, TypeError):
            continue
        stack = [data]
        while stack:
            cur = stack.pop()
            if isinstance(cur, dict):
                for k in ("datePublished", "dateCreated", "uploadDate"):
                    if isinstance(cur.get(k), str):
                        return cur[k]
                stack.extend(cur.values())
            elif isinstance(cur, list):
                stack.extend(cur)
    return None


def _clean(s: str) -> str:
    return re.sub(r"\s+", " ", s or "").strip()


def _best_text_block(doc) -> str:
    for xp in ("//article", "//*[@itemprop='articleBody']", "//main"):
        els = doc.xpath(xp)
        if els:
            paras = [_clean(p.text_content()) for p in els[0].xpath(".//p|.//li")]
            txt = "\n".join(p for p in paras if len(p) > 25)
            if len(txt) > 200:
                return txt
    # Fallback: the element whose direct <p> children carry the most text.
    best, best_len = None, 0
    for el in doc.xpath("//div|//section|//td"):
        n = sum(len(_clean(p.text_content())) for p in el.xpath("./p"))
        if n > best_len:
            best, best_len = el, n
    if best is not None:
        return "\n".join(_clean(p.text_content()) for p in best.xpath("./p") if _clean(p.text_content()))
    body = doc.xpath("//body")
    return _clean(" ".join(body[0].itertext())) if body else ""


def extract_article(html: str, url: str = "") -> Extracted:
    doc = lxml.html.fromstring(html)
    title = (_meta(doc, "og:title", "twitter:title", "headline")
             or _clean(" ".join(doc.xpath("//h1//text()")))
             or _clean(" ".join(doc.xpath("//title//text()"))))
    date_s = (_meta(doc, "article:published_time", "datePublished", "pubdate", "date", "DC.date.issued")
              or _jsonld_dates(doc)
              or (doc.xpath("//time/@datetime") or [None])[0]
              or _clean(" ".join(doc.xpath("//time//text()"))))
    for bad in doc.xpath("//script|//style|//nav|//footer|//header|//aside|//form"):
        if bad.getparent() is not None:
            bad.getparent().remove(bad)
    text = _best_text_block(doc)
    published = parse_date(date_s) or parse_date(text[:300])
    return Extracted(title=_clean(title), text=text, published_at=published,
                     lang=ethiopic.detect_lang(title + " " + text[:2000]))


@dataclass
class Link:
    url: str
    text: str
    context: str  # text of the enclosing row/list item/card


def links_with_context(html: str, base_url: str) -> list[Link]:
    doc = lxml.html.fromstring(html)
    out = []
    for a in doc.xpath("//a[@href]"):
        href = a.get("href", "").strip()
        if not href or href.startswith(("#", "javascript:", "mailto:", "tel:")):
            continue
        ctx_el = a
        for _ in range(4):  # climb to the row / card that holds date and type
            parent = ctx_el.getparent()
            # Stop before a container that holds several links: that is the list or
            # the page, not this entry's row, and its text would leak other entries.
            if parent is None or parent.tag in ("body", "html") or len(parent.xpath(".//a[@href]")) > 3:
                break
            ctx_el = parent
            if ctx_el.tag in ("tr", "li", "article") or (ctx_el.get("class") or "").find("item") != -1:
                break
        out.append(Link(urljoin(base_url, href), _clean(a.text_content()) or _clean(a.get("title", "")),
                        _clean(" ".join(ctx_el.itertext()))[:600]))
    return out


def pdf_text(data: bytes, max_pages: int = 4) -> str:
    """Text of the first pages of a PDF (gazette header, title, preamble).

    Note: older Amharic gazette PDFs often use legacy non-Unicode fonts
    (e.g. Power Geez) and extract as garbage; OCR is on the roadmap.
    """
    from pypdf import PdfReader
    try:
        reader = PdfReader(io.BytesIO(data))
        return "\n".join((reader.pages[i].extract_text() or "") for i in range(min(max_pages, len(reader.pages))))
    except Exception:  # noqa: BLE001 — malformed PDFs are common; treat as no text
        return ""


GAZETTE_MARKERS = ("negarit gazet", "negarit gazzet", "ነጋሪት ጋዜጣ")


def looks_like_gazette(text: str) -> bool:
    """True if the text starts like a Federal Negarit Gazeta issue (masthead at the top)."""
    low = ethiopic.fold(text[:600]).casefold()
    return any(m in low for m in GAZETTE_MARKERS)
