import asyncio
import datetime as dt
import json

import httpx
import pytest
import respx

from lexet.models import Observation, Source
from lexet.sources.adapters import HtmlArticlesAdapter, HtmlListingAdapter, RssAdapter, WordPressAdapter
from lexet.sources.base import AdapterContext, SourceDef
from lexet.sources.html_extract import extract_article, looks_like_gazette, parse_date, pdf_text
from lexet.sources.http import PoliteFetcher, RobotsDisallowed

ALLOW_ALL = "User-agent: *\nAllow: /\n"


def make_pdf(text: str) -> bytes:
    """A minimal one-page PDF with the given ASCII text (for fixtures)."""
    stream = f"BT /F1 11 Tf 40 780 Td ({text}) Tj ET".encode()
    objs = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
        b"<< /Length %d >>\nstream\n" % len(stream) + stream + b"\nendstream",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    out, offsets = bytearray(b"%PDF-1.4\n"), []
    for i, o in enumerate(objs, 1):
        offsets.append(len(out))
        out += b"%d 0 obj\n" % i + o + b"\nendobj\n"
    xref = len(out)
    out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objs) + 1)
    for off in offsets:
        out += b"%010d 00000 n \n" % off
    out += b"trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objs) + 1, xref)
    return bytes(out)


def ctx_for(settings, source: SourceDef, cursor=None, known=()):
    fetcher = PoliteFetcher(settings)
    return AdapterContext(fetcher, source, cursor, lambda u: u in known,
                          now=dt.datetime(2026, 10, 15, 12, 0))


async def collect(adapter, ctx):
    return [o async for o in adapter.discover(ctx)]


SRC_WP = SourceDef(id="t_wp", name="T", publisher="T", tier=3, jurisdiction="ET", lang="en", kind="wordpress")


@respx.mock
def test_robots_disallow_blocks_fetching(settings):
    respx.get("https://gov.example/robots.txt").mock(return_value=httpx.Response(200, text="User-agent: *\nDisallow: /\n"))
    page = respx.get("https://gov.example/laws").mock(return_value=httpx.Response(200, text="<html></html>"))
    fetcher = PoliteFetcher(settings)
    with pytest.raises(RobotsDisallowed):
        asyncio.run(fetcher.get("https://gov.example/laws"))
    assert not page.called
    assert asyncio.run(fetcher.robots_state("https://gov.example/laws")) == "disallowed"


@respx.mock
def test_robots_specific_to_our_agent(settings):
    respx.get("https://gov.example/robots.txt").mock(return_value=httpx.Response(
        200, text="User-agent: LexETBot\nDisallow: /private\n\nUser-agent: *\nDisallow: /\n"))
    fetcher = PoliteFetcher(settings)
    assert asyncio.run(fetcher.robots_state("https://gov.example/laws")) == "allowed"
    assert asyncio.run(fetcher.robots_state("https://gov.example/private/x")) == "disallowed"


@respx.mock
def test_conditional_get_uses_validators(settings):
    respx.get("https://news.example/robots.txt").mock(return_value=httpx.Response(404))
    route = respx.get("https://news.example/feed/")
    route.side_effect = [httpx.Response(200, text="x", headers={"ETag": '"abc"'}), httpx.Response(304)]
    fetcher = PoliteFetcher(settings)
    asyncio.run(fetcher.get("https://news.example/feed/"))
    r2 = asyncio.run(fetcher.get("https://news.example/feed/"))
    assert r2.not_modified
    assert route.calls[1].request.headers["If-None-Match"] == '"abc"'


@respx.mock
def test_wordpress_fetches_only_legal_candidates_and_advances_cursor(settings):
    respx.get("https://paper.example/robots.txt").mock(return_value=httpx.Response(200, text=ALLOW_ALL))
    posts = [
        {"id": 1, "link": "https://paper.example/a", "date_gmt": "2026-10-14T08:00:00",
         "title": {"rendered": "House approves Investment (Amendment) Proclamation"}, "excerpt": {"rendered": "<p>Lawmakers…</p>"}},
        {"id": 2, "link": "https://paper.example/b", "date_gmt": "2026-10-14T09:00:00",
         "title": {"rendered": "Coffee prices rise"}, "excerpt": {"rendered": "<p>Markets</p>"}},
        {"id": 3, "link": "https://paper.example/c", "date_gmt": "2026-10-13T09:00:00",
         "title": {"rendered": "ምክር ቤቱ የማዕድን አዋጅን አፀደቀ"}, "excerpt": {"rendered": ""}},
    ]
    listing = respx.get(url__startswith="https://paper.example/wp-json/wp/v2/posts?").mock(
        return_value=httpx.Response(200, json=posts))
    respx.get("https://paper.example/wp-json/wp/v2/posts/1?_fields=content").mock(
        return_value=httpx.Response(200, json={"content": {"rendered": "<p>Full story one.</p>"}}))
    respx.get("https://paper.example/wp-json/wp/v2/posts/3?_fields=content").mock(
        return_value=httpx.Response(200, json={"content": {"rendered": "<p>ሙሉ ዘገባ።</p>"}}))
    ctx = ctx_for(settings, SRC_WP)
    out = asyncio.run(collect(WordPressAdapter("https://paper.example"), ctx))
    assert [o.url for o in out] == ["https://paper.example/a", "https://paper.example/c"]
    assert out[0].text == "Full story one." and out[1].lang == "am"
    assert ctx.new_cursor == "2026-10-14T09:00:00"
    assert "after=2026-10-01" in str(listing.calls[0].request.url)


@respx.mock
def test_wordpress_falls_back_to_rss(settings):
    respx.get("https://old.example/robots.txt").mock(return_value=httpx.Response(404))
    respx.get(url__startswith="https://old.example/wp-json/").mock(return_value=httpx.Response(404))
    rss = """<?xml version="1.0"?><rss version="2.0"><channel><title>x</title>
      <item><title>Council of Ministers approves regulation</title><link>https://old.example/r1</link>
      <pubDate>Tue, 14 Oct 2026 08:00:00 GMT</pubDate><description>Regulation on fuel.</description></item>
    </channel></rss>"""
    respx.get("https://old.example/feed/").mock(return_value=httpx.Response(200, text=rss))
    respx.get("https://old.example/r1").mock(return_value=httpx.Response(
        200, text="<html><head><meta property='og:title' content='Council approves regulation'></head>"
                  "<body><article><p>The Council of Ministers approved the Fuel Pricing Regulation today in Addis Ababa, officials said.</p>"
                  "<p>It will take effect upon publication in the Federal Negarit Gazeta, according to the statement.</p></article></body></html>"))
    out = asyncio.run(collect(WordPressAdapter("https://old.example"), ctx_for(settings, SRC_WP)))
    assert len(out) == 1 and "Fuel Pricing Regulation" in out[0].text
    assert out[0].published_at == dt.datetime(2026, 10, 14, 8, 0)


@respx.mock
def test_listing_harvester_finds_law_links_and_reads_gazette_pdfs(settings):
    respx.get("https://moj.example/robots.txt").mock(return_value=httpx.Response(200, text=ALLOW_ALL))
    listing = """<html><body><nav><a href="/about">About us</a><a href="/news">News</a></nav>
      <table>
       <tr><td>Proclamation</td><td><a href="/docs/1431.pdf">Digital Commerce Proclamation No. 1431/2026</a></td><td>01/07/2026</td></tr>
       <tr><td>መመሪያ</td><td><a href="/docs/dir-12.pdf">የመድኃኒት ማስመጣት መመሪያ ቁጥር 12/2018</a></td><td>ሰኔ 30 ቀን 2018</td></tr>
       <tr><td>Notice</td><td><a href="/vacancy">Vacancy announcement</a></td><td></td></tr>
      </table></body></html>"""
    respx.get("https://moj.example/laws").mock(return_value=httpx.Response(200, text=listing))
    respx.get("https://moj.example/laws?page=2").mock(return_value=httpx.Response(200, text=listing))
    respx.get("https://moj.example/docs/1431.pdf").mock(return_value=httpx.Response(
        200, content=make_pdf("FEDERAL NEGARIT GAZETTE Proclamation No. 1431/2026"), headers={"content-type": "application/pdf"}))
    respx.get("https://moj.example/docs/dir-12.pdf").mock(return_value=httpx.Response(
        200, content=make_pdf("Directive"), headers={"content-type": "application/pdf"}))
    src = SourceDef(id="t_moj", name="T", publisher="MOJ", tier=1, jurisdiction="ET", lang="mixed", kind="html_listing",
                    config={"status_hint": "in_force"})
    adapter = HtmlListingAdapter(["https://moj.example/laws"], max_pages=2, page_param="page")
    out = asyncio.run(collect(adapter, ctx_for(settings, src)))
    assert [o.url for o in out] == ["https://moj.example/docs/1431.pdf", "https://moj.example/docs/dir-12.pdf"]
    assert out[0].gazette and out[0].is_primary_document and out[0].status_hint == "in_force"
    assert out[0].published_at == dt.datetime(2026, 7, 1)
    assert out[1].published_at == dt.datetime(2026, 7, 7)   # ሰኔ 30, 2018 E.C.


@respx.mock
def test_article_adapter_reads_metadata(settings):
    respx.get("https://agency.example/robots.txt").mock(return_value=httpx.Response(200, text=ALLOW_ALL))
    respx.get("https://agency.example/eng").mock(return_value=httpx.Response(200, text="""
      <html><body><a href="/eng/w/eng_1">House approves Freedom of Information Proclamation</a>
      <a href="/eng/w/eng_2">Football results</a><a href="/eng/about">About</a></body></html>"""))
    respx.get("https://agency.example/eng/w/eng_1").mock(return_value=httpx.Response(200, text="""
      <html><head><meta property="og:title" content="House approves Freedom of Information Proclamation">
      <meta property="article:published_time" content="2026-06-25T14:30:00+03:00"></head>
      <body><header><h1>Site</h1></header><div class="content">
      <p>Addis Ababa, June 25, 2026 (ENA) - The House of Peoples' Representatives approved the Freedom of Information Proclamation today.</p>
      <p>The new law replaces the earlier mass media and access to information law.</p></div></body></html>"""))
    adapter = HtmlArticlesAdapter(["https://agency.example/eng"], r"/eng/w/")
    out = asyncio.run(collect(adapter, ctx_for(settings, SRC_WP)))
    assert len(out) == 1
    assert out[0].title == "House approves Freedom of Information Proclamation"
    assert out[0].published_at == dt.datetime(2026, 6, 25, 11, 30)  # normalised to UTC
    assert "replaces the earlier" in out[0].text


def test_date_parsing_variants():
    assert parse_date("25 June 2026") == dt.datetime(2026, 6, 25)
    assert parse_date("June 25, 2026") == dt.datetime(2026, 6, 25)
    assert parse_date("18/10/2018") == dt.datetime(2026, 6, 25)   # day/month/E.C. year
    assert parse_date("ሰኔ 18 ቀን 2018 ዓ.ም") == dt.datetime(2026, 6, 25)


def test_pdf_and_gazette_detection():
    assert "1431/2026" in pdf_text(make_pdf("FEDERAL NEGARIT GAZETTE Proclamation No. 1431/2026"))
    assert looks_like_gazette("ፌዴራል ነጋሪት ጋዜጣ ቁጥር 45")
    assert not looks_like_gazette("Weekly news digest")


@respx.mock
def test_pipeline_crawl_records_robots_and_cursor(pipeline):
    respx.get("https://www.thereporterethiopia.com/robots.txt").mock(return_value=httpx.Response(200, text=ALLOW_ALL))
    respx.get(url__startswith="https://www.thereporterethiopia.com/wp-json/wp/v2/posts?").mock(return_value=httpx.Response(200, json=[
        {"id": 9, "link": "https://www.thereporterethiopia.com/x", "date_gmt": "2026-10-14T08:00:00",
         "title": {"rendered": "Parliament approves Proclamation No. 1433/2026"}, "excerpt": {"rendered": ""}}]))
    respx.get("https://www.thereporterethiopia.com/wp-json/wp/v2/posts/9?_fields=content").mock(
        return_value=httpx.Response(200, json={"content": {"rendered": "<p>The House approved Proclamation No. 1433/2026.</p>"}}))
    respx.get("https://hopr.gov.et/robots.txt").mock(return_value=httpx.Response(200, text="User-agent: *\nDisallow: /\n"))
    report = asyncio.run(pipeline.run_cycle(only=["reporter", "hopr_laws"]))
    assert report.crawled["reporter"]["new"] == 1
    assert report.crawled["hopr_laws"]["robots"] == "disallowed"
    with pipeline.sf() as s:
        assert s.get(Source, "reporter").cursor == "2026-10-14T08:00:00"
        assert s.get(Source, "hopr_laws").robots_state == "disallowed"
        assert s.query(Observation).count() == 1
    assert report.touched_items == 1
