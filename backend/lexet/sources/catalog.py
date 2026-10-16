"""Monitored sources.

Tiers (used by the verification rule, docs/VERIFICATION.md):
  1  Official government publication (Negarit Gazeta, MoJ, HoPR, regulators)
  2  Official news agency (ENA)
  3  Established Ethiopian press (independent, state-affiliated and broadcasters)
  4  Other outlets: blogs, smaller sites, and outlets found through aggregators
  9  International / regional (secondary stream, not subject to the 3-source rule)

Every tier is shown in the app; tiers 3–4 count as independent publishers but only
tiers 1–2 can anchor verification. Aggregators (GDELT, Google News) name each
article's own outlet, so independence is still counted per outlet.

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
        enabled=False,  # checked 16 Oct 2026: API and feed return 404 (site moved?)
    ),
    SourceDef(
        id="reporter_am", name="ሪፖርተር — The Reporter (Amharic)", publisher="REPORTER", tier=3,
        jurisdiction="ET", lang="am", kind="wordpress", interval_min=30,
        config={"base_url": "https://www.ethiopianreporter.com"}, aliases=["ሪፖርተር"],
        enabled=False,  # checked 16 Oct 2026: 403 / bot challenge to automated requests
    ),
    SourceDef(
        id="fana_am", name="ፋና — Fana Media Corporation (Amharic)", publisher="FANA", tier=3,
        jurisdiction="ET", lang="am", kind="wordpress", interval_min=30,
        config={"base_url": "https://www.fanamc.com"}, aliases=["ፋና"],
    ),
    SourceDef(
        id="walta", name="Walta Media and Communication", publisher="WALTA", tier=3,
        jurisdiction="ET", lang="mixed", kind="wordpress", interval_min=30,
        config={"base_url": "https://waltainfo.com"}, aliases=["Walta", "ዋልታ"],
        enabled=False,  # checked 16 Oct 2026: bot challenge (HTTP 202) to automated requests
    ),
    SourceDef(
        id="press_et", name="Ethiopian Press Agency (The Ethiopian Herald, Addis Zemen)", publisher="EPA", tier=3,
        jurisdiction="ET", lang="mixed", kind="wordpress", interval_min=60,
        config={"base_url": "https://press.et"}, aliases=["The Ethiopian Herald", "Addis Zemen", "አዲስ ዘመን"],
        enabled=False,  # checked 16 Oct 2026: bot challenge instead of the WordPress API
    ),
    SourceDef(
        id="ebr", name="Ethiopian Business Review", publisher="EBR", tier=3,
        jurisdiction="ET", lang="en", kind="wordpress", interval_min=180,
        config={"base_url": "https://ethiopianbusinessreview.net"},
        enabled=False,  # checked 16 Oct 2026: feed carries spam posts (casino ads); re-enable once clean
    ),
    SourceDef(
        id="bbc_amharic", name="BBC News አማርኛ", publisher="BBC", tier=3,
        jurisdiction="ET", lang="am", kind="rss", interval_min=60,
        config={"feed_url": "https://feeds.bbci.co.uk/amharic/rss.xml"},
    ),
    SourceDef(
        id="dw_amharic", name="DW አማርኛ", publisher="DW", tier=3,
        jurisdiction="ET", lang="am", kind="rss", interval_min=60,
        config={"feed_url": "https://rss.dw.com/xml/rss-amh-all"},
        enabled=False,  # checked 16 Oct 2026: feed URL returns an empty document; find the current one
    ),
    SourceDef(
        id="borkena", name="Borkena", publisher="BORKENA", tier=4,
        jurisdiction="ET", lang="en", kind="wordpress", interval_min=60,
        config={"base_url": "https://borkena.com"},
    ),
    SourceDef(
        id="ethiopia_insight", name="Ethiopia Insight", publisher="ETHIOPIA_INSIGHT", tier=4,
        jurisdiction="ET", lang="en", kind="wordpress", interval_min=180,
        config={"base_url": "https://www.ethiopia-insight.com"},
    ),
    SourceDef(
        id="ethiopian_monitor", name="Ethiopian Monitor", publisher="ETHIOPIAN_MONITOR", tier=4,
        jurisdiction="ET", lang="en", kind="wordpress", interval_min=60,
        config={"base_url": "https://ethiopianmonitor.com"},
    ),
    SourceDef(
        id="nbe", name="National Bank of Ethiopia", publisher="NBE", tier=1,
        jurisdiction="ET", lang="mixed", kind="wordpress", interval_min=180,
        config={"base_url": "https://nbe.gov.et", "issuer_hint": "NBE", "require_legal": False},
        notes="Directives and circulars; news posts announce new directives.",
    ),
    SourceDef(
        id="ecma", name="Ethiopian Capital Market Authority", publisher="ECMA", tier=1,
        jurisdiction="ET", lang="mixed", kind="wordpress", interval_min=360,
        config={"base_url": "https://ecma.gov.et", "require_legal": False},
    ),
    # ------------------------------------------------------------------ aggregators (many outlets)
    SourceDef(
        id="gdelt", name="GDELT — worldwide news about Ethiopian law", publisher="GDELT", tier=4,
        jurisdiction="ET", lang="mixed", kind="gdelt", interval_min=30,
        config={"query": 'Ethiopia (proclamation OR directive OR regulation OR legislation OR "draft law" '
                         'OR parliament OR "Council of Ministers" OR court OR "new law")'},
        notes="Each article keeps its own outlet as publisher.",
    ),
    SourceDef(
        id="gnews_en", name="Google News — Ethiopian legal news (English)", publisher="GOOGLE_NEWS", tier=4,
        jurisdiction="ET", lang="en", kind="rss", interval_min=30,
        config={"feed_url": "https://news.google.com/rss/search?q=Ethiopia+(proclamation+OR+directive+OR+"
                            "regulation+OR+%22draft+law%22+OR+parliament+OR+%22Council+of+Ministers%22)+when:3d"
                            "&hl=en-US&gl=US&ceid=US:en",
                "per_item_publisher": True},
        enabled=False,  # checked 16 Oct 2026: news.google.com robots.txt disallows crawlers
    ),
    SourceDef(
        id="gnews_am", name="Google News — የሕግ ዜና (Amharic)", publisher="GOOGLE_NEWS", tier=4,
        jurisdiction="ET", lang="am", kind="rss", interval_min=60,
        config={"feed_url": "https://news.google.com/rss/search?q=%E1%8A%A0%E1%8B%8B%E1%8C%85+OR+"
                            "%E1%8B%B0%E1%8A%95%E1%89%A5+OR+%E1%88%98%E1%88%98%E1%88%AA%E1%8B%AB+when:3d"
                            "&hl=am&gl=ET&ceid=ET:am",
                "per_item_publisher": True},
        enabled=False,  # checked 16 Oct 2026: news.google.com robots.txt disallows crawlers
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
