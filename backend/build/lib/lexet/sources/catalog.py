"""Monitored sources.

Tiers (used by the verification rule, docs/VERIFICATION.md):
  1  Official government publication (Negarit Gazeta, MoJ, HoPR, regulators)
  2  Official news agency (ENA)
  3  Reputable independent legal / business press
  9  International / regional (secondary stream, not subject to the 3-source rule)

`publisher` is the unit of independence: two URLs from the same publisher (ENA
English and ENA Amharic) count once. `aliases` are the names other outlets use
when they credit this publisher; they drive syndication detection.

Endpoint status (checked 15 Oct 2026 from the build environment):
  * justice.gov.et and hopr.gov.et refused automated fetching (robots policy).
    The crawler re-checks robots.txt at runtime; while disallowed these
    sources are fed through assisted ingestion (POST /v1/admin/observations).
    Recommended: ask both institutions for crawler permission or a data feed.
  * Listing paths below are best known values. Run `lexet doctor` after
    deployment: it reports robots status and how many legal links each source yields.
"""
from __future__ import annotations

from .base import SourceDef

LEGAL_KEYWORDS_INTL = [
    "africa", "african union", "afcfta", "east africa", "igad", "ethiopia", "horn of africa",
    "court", "tribunal", "arbitration", "treaty", "convention", "sanction", "trade", "investment",
    "data protection", "intellectual property", "mining", "wto", "human rights", "law", "legal",
]

SOURCES: list[SourceDef] = [
    # ------------------------------------------------------------------ tier 1: official
    SourceDef(
        id="moj_laws", name="Ministry of Justice — laws & directives", publisher="MOJ", tier=1,
        jurisdiction="ET", lang="mixed", kind="html_listing", interval_min=60,
        config={"listing_urls": ["https://justice.gov.et/"], "max_pages": 1, "fetch_documents": True,
                "status_hint": "in_force"},
        notes="Publishes proclamations, regulations, amendments and registered directives with "
              "law IDs, dates and types. Set the law-database listing URL once confirmed.",
    ),
    SourceDef(
        id="hopr_laws", name="House of Peoples' Representatives — laws", publisher="HPR", tier=1,
        jurisdiction="ET", lang="mixed", kind="html_listing", interval_min=60,
        config={"listing_urls": ["https://hopr.gov.et/"], "max_pages": 1, "fetch_documents": True},
        aliases=["House of Peoples' Representatives", "HoPR"],
        notes="Published laws and draft legislation. Split into separate laws / drafts listings "
              "(status_hint in_force / tabled) once the paths are confirmed.",
    ),
    SourceDef(
        id="efda_docs", name="Ethiopian Food and Drug Authority — legislation", publisher="EFDA", tier=1,
        jurisdiction="ET", lang="mixed", kind="html_listing", interval_min=360,
        config={"listing_urls": ["https://www.efda.gov.et/doc-category/policies-legislation-and-regulation/"],
                "pagination": "{url}page/{n}/", "max_pages": 3, "fetch_documents": True,
                "doc_type_hint": "directive", "issuer_hint": "EFDA", "status_hint": "in_force"},
    ),
    SourceDef(
        id="eipa", name="Ethiopian Intellectual Property Authority", publisher="EIPA", tier=1,
        jurisdiction="ET", lang="mixed", kind="wordpress", interval_min=360,
        config={"base_url": "https://eipa.gov.et", "issuer_hint": "EIPA"},
        notes="Directives and IP gazette notices.",
    ),
    # ------------------------------------------------------------------ tier 2: news agency
    SourceDef(
        id="ena_en", name="Ethiopian News Agency (English)", publisher="ENA", tier=2,
        jurisdiction="ET", lang="en", kind="html_articles", interval_min=15,
        config={"listing_urls": ["https://www.ena.et/web/eng"], "article_link_regex": r"/web/eng/w/"},
        aliases=["ENA", "Ethiopian News Agency", "ኢዜአ", "የኢትዮጵያ ዜና አገልግሎት"],
    ),
    SourceDef(
        id="ena_am", name="ኢዜአ — የኢትዮጵያ ዜና አገልግሎት (Amharic)", publisher="ENA", tier=2,
        jurisdiction="ET", lang="am", kind="html_articles", interval_min=15,
        config={"listing_urls": ["https://www.ena.et/web/amh"], "article_link_regex": r"/web/amh/w/"},
        aliases=["ENA", "ኢዜአ"],
    ),
    # ------------------------------------------------------------------ tier 3: independent press
    SourceDef(
        id="reporter", name="The Reporter Ethiopia", publisher="REPORTER", tier=3,
        jurisdiction="ET", lang="en", kind="wordpress", interval_min=30,
        config={"base_url": "https://www.thereporterethiopia.com"}, aliases=["The Reporter"],
    ),
    SourceDef(
        id="addis_standard", name="Addis Standard", publisher="ADDIS_STANDARD", tier=3,
        jurisdiction="ET", lang="en", kind="wordpress", interval_min=30,
        config={"base_url": "https://eng.addisstandard.com"}, aliases=["Addis Standard"],
    ),
    SourceDef(
        id="capital", name="Capital Ethiopia", publisher="CAPITAL", tier=3,
        jurisdiction="ET", lang="en", kind="wordpress", interval_min=60,
        config={"base_url": "https://capitalethiopia.com"}, aliases=["Capital Ethiopia"],
    ),
    SourceDef(
        id="fortune", name="Addis Fortune", publisher="FORTUNE", tier=3,
        jurisdiction="ET", lang="en", kind="wordpress", interval_min=60,
        config={"base_url": "https://addisfortune.news"}, aliases=["Addis Fortune"],
    ),
    SourceDef(
        id="fana_en", name="Fana Media Corporation (English)", publisher="FANA", tier=3,
        jurisdiction="ET", lang="en", kind="wordpress", interval_min=30,
        config={"base_url": "https://www.fanamc.com/english"}, aliases=["FBC", "FMC", "Fana Broadcasting"],
        notes="State-affiliated; frequently republishes ENA copy, which syndication detection discounts.",
    ),
    SourceDef(
        id="chilot", name="Chilot — Ethiopian legal information", publisher="CHILOT", tier=3,
        jurisdiction="ET", lang="mixed", kind="wordpress", interval_min=180,
        config={"base_url": "https://chilot.me"},
    ),
    # ------------------------------------------------------------------ assisted ingestion
    SourceDef(
        id="negarit_manual", name="Federal Negarit Gazeta (assisted entry)", publisher="NEGARIT", tier=1,
        jurisdiction="ET", lang="mixed", kind="manual", interval_min=0,
        notes="The Gazeta has no public machine-readable feed. Curators submit issue PDFs/links here; "
              "gazette PDFs found on MoJ/HoPR are also detected automatically.",
    ),
    # ------------------------------------------------------------------ international (10:1 stream)
    SourceDef(
        id="un_news_law", name="UN News — Law and Crime Prevention", publisher="UN_NEWS", tier=9,
        jurisdiction="INTL", lang="en", kind="rss", interval_min=360,
        config={"feed_url": "https://news.un.org/feed/subscribe/en/news/topic/law-and-crime-prevention/feed/rss.xml",
                "require_legal": False},
    ),
    SourceDef(
        id="jurist", name="JURIST — legal news", publisher="JURIST", tier=9,
        jurisdiction="INTL", lang="en", kind="rss", interval_min=360,
        config={"feed_url": "https://www.jurist.org/news/feed/", "require_legal": False},
    ),
    SourceDef(
        id="au_press", name="African Union — press releases", publisher="AU", tier=9,
        jurisdiction="INTL", lang="en", kind="html_articles", interval_min=360,
        config={"listing_urls": ["https://au.int/en/press-releases"], "article_link_regex": r"/en/pressreleases/",
                "require_legal": False, "max_articles": 10},
    ),
    SourceDef(
        id="igad", name="IGAD — news", publisher="IGAD", tier=9,
        jurisdiction="INTL", lang="en", kind="wordpress", interval_min=720,
        config={"base_url": "https://igad.int", "require_legal": False},
    ),
]


def by_id() -> dict[str, SourceDef]:
    return {s.id: s for s in SOURCES}


def aliases_by_publisher() -> dict[str, list[str]]:
    out: dict[str, list[str]] = {}
    for s in SOURCES:
        if s.aliases:
            out.setdefault(s.publisher, [])
            for a in s.aliases:
                if a not in out[s.publisher]:
                    out[s.publisher].append(a)
    return out
