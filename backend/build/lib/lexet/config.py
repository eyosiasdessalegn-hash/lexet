"""Runtime configuration (environment variables, all optional)."""
from __future__ import annotations

import os
from dataclasses import dataclass, field


def _bool(name: str, default: bool) -> bool:
    v = os.environ.get(name)
    return default if v is None else v.strip().lower() in ("1", "true", "yes", "on")


@dataclass
class Settings:
    database_url: str = "sqlite:///./lexet.db"

    # Crawling
    user_agent: str = "LexETBot/0.1 (+https://example.org/lexet-bot; legal-news aggregator)"
    default_crawl_delay_s: float = 2.0
    request_timeout_s: float = 20.0
    max_response_bytes: int = 15_000_000
    respect_robots: bool = True  # never turn off in production

    # Verification (see docs/VERIFICATION.md)
    min_independent_sources: int = 3
    require_official_anchor: bool = True          # at least one tier-1 or tier-2 source
    gazette_confirms_in_force: bool = True        # official gazette/MoJ register settles "in force"
    official_self_publication_verifies: bool = False  # spec says no: keep False unless you decide otherwise
    near_duplicate_jaccard: float = 0.5           # shingle overlap that marks a copy/syndication

    # International stream
    intl_ratio: int = 10
    intl_window_days: int = 30
    intl_min_relevance: float = 2.0

    # Redistribution limits for source text sent to the app
    news_excerpt_chars: int = 280
    official_excerpt_chars: int = 20_000

    # Sync API
    sync_page_limit: int = 500

    # Admin (assisted ingestion for sources whose robots.txt forbids crawling)
    admin_token: str | None = None

    # Push (Firebase Cloud Messaging, HTTP v1). Optional.
    fcm_project_id: str | None = None
    fcm_credentials_file: str | None = None
    fcm_topic: str = "lexet-et"

    taxonomy_path: str | None = None
    extra: dict = field(default_factory=dict)

    @classmethod
    def from_env(cls) -> "Settings":
        s = cls()
        s.database_url = os.environ.get("LEXET_DATABASE_URL", s.database_url)
        s.user_agent = os.environ.get("LEXET_USER_AGENT", s.user_agent)
        s.default_crawl_delay_s = float(os.environ.get("LEXET_CRAWL_DELAY", s.default_crawl_delay_s))
        s.respect_robots = _bool("LEXET_RESPECT_ROBOTS", True)
        s.min_independent_sources = int(os.environ.get("LEXET_MIN_SOURCES", s.min_independent_sources))
        s.require_official_anchor = _bool("LEXET_REQUIRE_OFFICIAL_ANCHOR", s.require_official_anchor)
        s.gazette_confirms_in_force = _bool("LEXET_GAZETTE_CONFIRMS_IN_FORCE", s.gazette_confirms_in_force)
        s.official_self_publication_verifies = _bool(
            "LEXET_OFFICIAL_SELF_PUBLICATION_VERIFIES", s.official_self_publication_verifies)
        s.intl_ratio = int(os.environ.get("LEXET_INTL_RATIO", s.intl_ratio))
        s.admin_token = os.environ.get("LEXET_ADMIN_TOKEN") or None
        s.fcm_project_id = os.environ.get("LEXET_FCM_PROJECT_ID") or None
        s.fcm_credentials_file = os.environ.get("LEXET_FCM_CREDENTIALS") or None
        s.fcm_topic = os.environ.get("LEXET_FCM_TOPIC", s.fcm_topic)
        s.taxonomy_path = os.environ.get("LEXET_TAXONOMY") or None
        return s
