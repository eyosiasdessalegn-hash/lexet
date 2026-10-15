"""Database schema (SQLAlchemy 2.0). Portable between SQLite (dev) and PostgreSQL (prod).

Three layers:
  Observation — one fetched document (an article, a listing entry, a gazette PDF),
                exactly as a source published it. Evidence; never edited.
  Signal      — one law mentioned in an observation, with what the observation
                says about it (number, name, status, issuer). An article about a
                parliamentary session that passed four laws yields four signals.
  Item        — one legal development (a law moving through its life cycle),
                built from all signals that refer to it. This is what the app shows.
"""
from __future__ import annotations

import datetime as dt
import json
import uuid

from sqlalchemy import Boolean, Date, DateTime, Float, ForeignKey, Index, Integer, String, Text, create_engine, event
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship, sessionmaker


def utcnow() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc).replace(tzinfo=None)


class Base(DeclarativeBase):
    pass


class Source(Base):
    __tablename__ = "sources"
    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    name: Mapped[str] = mapped_column(String(200))
    publisher: Mapped[str] = mapped_column(String(64), index=True)  # independence is counted per publisher
    tier: Mapped[int] = mapped_column(Integer)                      # 1 official, 2 news agency, 3 press, 9 international
    jurisdiction: Mapped[str] = mapped_column(String(8))            # ET | INTL
    lang: Mapped[str] = mapped_column(String(8))
    kind: Mapped[str] = mapped_column(String(32))
    enabled: Mapped[bool] = mapped_column(Boolean, default=True)
    robots_state: Mapped[str] = mapped_column(String(16), default="unknown")  # allowed | disallowed | unknown
    robots_checked_at: Mapped[dt.datetime | None] = mapped_column(DateTime)
    last_run_at: Mapped[dt.datetime | None] = mapped_column(DateTime)
    last_success_at: Mapped[dt.datetime | None] = mapped_column(DateTime)
    last_error: Mapped[str | None] = mapped_column(Text)
    cursor: Mapped[str | None] = mapped_column(String(200))
    config_json: Mapped[str] = mapped_column(Text, default="{}")

    @property
    def config(self) -> dict:
        return json.loads(self.config_json or "{}")


class FetchCache(Base):
    __tablename__ = "fetch_cache"
    url: Mapped[str] = mapped_column(String(1000), primary_key=True)
    etag: Mapped[str | None] = mapped_column(String(300))
    last_modified: Mapped[str | None] = mapped_column(String(100))
    content_hash: Mapped[str | None] = mapped_column(String(64))
    fetched_at: Mapped[dt.datetime] = mapped_column(DateTime, default=utcnow)


class Observation(Base):
    __tablename__ = "observations"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    source_id: Mapped[str] = mapped_column(ForeignKey("sources.id"), index=True)
    publisher: Mapped[str] = mapped_column(String(64), index=True)
    tier: Mapped[int] = mapped_column(Integer)
    url: Mapped[str] = mapped_column(String(1000), unique=True)
    title: Mapped[str] = mapped_column(Text)
    lang: Mapped[str] = mapped_column(String(8))
    published_at: Mapped[dt.datetime | None] = mapped_column(DateTime, index=True)
    fetched_at: Mapped[dt.datetime] = mapped_column(DateTime, default=utcnow)
    content_hash: Mapped[str] = mapped_column(String(64))
    text: Mapped[str] = mapped_column(Text, default="")   # kept server-side only (see excerpt rules)
    shingles_json: Mapped[str] = mapped_column(Text, default="[]")
    attribution: Mapped[str | None] = mapped_column(String(64))  # publisher this copy credits
    is_primary_document: Mapped[bool] = mapped_column(Boolean, default=False)  # the instrument itself
    gazette: Mapped[bool] = mapped_column(Boolean, default=False)  # a Negarit Gazeta publication
    status_hint: Mapped[str | None] = mapped_column(String(32))
    extra_json: Mapped[str] = mapped_column(Text, default="{}")
    processed: Mapped[bool] = mapped_column(Boolean, default=False, index=True)

    signals: Mapped[list["Signal"]] = relationship(back_populates="observation", cascade="all, delete-orphan")

    @property
    def shingles(self) -> list[int]:
        return json.loads(self.shingles_json or "[]")

    @property
    def extra(self) -> dict:
        return json.loads(self.extra_json or "{}")

    @property
    def when(self) -> dt.datetime:
        return self.published_at or self.fetched_at


class Signal(Base):
    __tablename__ = "signals"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    observation_id: Mapped[int] = mapped_column(ForeignKey("observations.id"), index=True)
    item_id: Mapped[str | None] = mapped_column(ForeignKey("items.id"), index=True)
    doc_type: Mapped[str] = mapped_column(String(32))
    canonical_key: Mapped[str | None] = mapped_column(String(120), index=True)
    law_number: Mapped[int | None] = mapped_column(Integer)
    law_year_gc: Mapped[int | None] = mapped_column(Integer)
    law_year_ec: Mapped[int | None] = mapped_column(Integer)
    year_calendar: Mapped[str | None] = mapped_column(String(4))  # calendar the source wrote the year in
    law_name: Mapped[str | None] = mapped_column(Text)
    name_tokens_json: Mapped[str] = mapped_column(Text, default="[]")
    status: Mapped[str | None] = mapped_column(String(32))
    issuer: Mapped[str | None] = mapped_column(String(32))
    initiator: Mapped[str | None] = mapped_column(String(32))
    topics_json: Mapped[str] = mapped_column(Text, default="[]")
    title_en: Mapped[str | None] = mapped_column(Text)
    title_am: Mapped[str | None] = mapped_column(Text)
    references_json: Mapped[str] = mapped_column(Text, default="[]")

    observation: Mapped[Observation] = relationship(back_populates="signals")
    item: Mapped["Item | None"] = relationship(back_populates="signals")

    @property
    def name_tokens(self) -> frozenset:
        return frozenset(json.loads(self.name_tokens_json or "[]"))


class Item(Base):
    __tablename__ = "items"
    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=lambda: uuid.uuid4().hex)
    jurisdiction: Mapped[str] = mapped_column(String(8), index=True)
    doc_type: Mapped[str] = mapped_column(String(32))
    canonical_key: Mapped[str | None] = mapped_column(String(120), unique=True)
    law_number: Mapped[int | None] = mapped_column(Integer)
    law_year_gc: Mapped[int | None] = mapped_column(Integer)
    law_year_ec: Mapped[int | None] = mapped_column(Integer)
    title_en: Mapped[str | None] = mapped_column(Text)
    title_am: Mapped[str | None] = mapped_column(Text)
    name_sets_json: Mapped[str] = mapped_column(Text, default="[]")  # every distinct name token-set seen
    summary_en: Mapped[str | None] = mapped_column(Text)
    summary_am: Mapped[str | None] = mapped_column(Text)
    issuer: Mapped[str | None] = mapped_column(String(32))
    initiator: Mapped[str | None] = mapped_column(String(32))
    status: Mapped[str | None] = mapped_column(String(32))            # what the app shows
    verified_status: Mapped[str | None] = mapped_column(String(32))   # status that passed the 3-source rule
    reported_status: Mapped[str | None] = mapped_column(String(32))   # most advanced status any counted source claims
    urgency: Mapped[str | None] = mapped_column(String(16))
    verification: Mapped[str] = mapped_column(String(16), default="unconfirmed")  # verified | unconfirmed | reported
    independent_sources: Mapped[int] = mapped_column(Integer, default=0)
    has_official_source: Mapped[bool] = mapped_column(Boolean, default=False)
    topics_json: Mapped[str] = mapped_column(Text, default="[]")
    references_json: Mapped[str] = mapped_column(Text, default="[]")
    amends_key: Mapped[str | None] = mapped_column(String(120))
    event_date: Mapped[dt.date | None] = mapped_column(Date, index=True)
    status_date: Mapped[dt.date | None] = mapped_column(Date)
    verified_at: Mapped[dt.datetime | None] = mapped_column(DateTime)
    first_seen_at: Mapped[dt.datetime] = mapped_column(DateTime, default=utcnow)
    updated_at: Mapped[dt.datetime] = mapped_column(DateTime, default=utcnow)
    version: Mapped[int] = mapped_column(Integer, default=0, index=True)
    deleted: Mapped[bool] = mapped_column(Boolean, default=False)
    merged_into: Mapped[str | None] = mapped_column(String(32))
    featured: Mapped[bool] = mapped_column(Boolean, default=False)   # international items chosen for the 10:1 stream
    relevance: Mapped[float] = mapped_column(Float, default=0.0)
    verification_json: Mapped[str] = mapped_column(Text, default="{}")

    signals: Mapped[list[Signal]] = relationship(back_populates="item")

    __table_args__ = (Index("ix_items_sync", "version", "jurisdiction", "featured"),)

    @property
    def name_sets(self) -> list[frozenset]:
        return [frozenset(x) for x in json.loads(self.name_sets_json or "[]")]

    def add_name_set(self, tokens: frozenset) -> bool:
        if not tokens:
            return False
        sets = self.name_sets
        if tokens in sets:
            return False
        sets.append(tokens)
        self.name_sets_json = json.dumps([sorted(x) for x in sets], ensure_ascii=False)
        return True

    @property
    def topics(self) -> list[str]:
        return json.loads(self.topics_json or "[]")


class Meta(Base):
    __tablename__ = "meta"
    key: Mapped[str] = mapped_column(String(64), primary_key=True)
    value: Mapped[int] = mapped_column(Integer, default=0)


# ----------------------------------------------------------------------- engine

def make_engine(url: str):
    kwargs = {}
    if url.startswith("sqlite"):
        kwargs["connect_args"] = {"check_same_thread": False}
    engine = create_engine(url, future=True, **kwargs)
    if url.startswith("sqlite"):
        @event.listens_for(engine, "connect")
        def _pragmas(dbapi_conn, _):
            cur = dbapi_conn.cursor()
            cur.execute("PRAGMA journal_mode=WAL")
            cur.execute("PRAGMA foreign_keys=ON")
            cur.execute("PRAGMA synchronous=NORMAL")
            cur.close()
    return engine


def make_session_factory(url: str):
    engine = make_engine(url)
    Base.metadata.create_all(engine)
    return sessionmaker(bind=engine, expire_on_commit=False, future=True)


def next_version(session) -> int:
    """Monotonic change counter. Every item write takes the next value, so the
    app can ask for "everything after version N" (delta sync).

    SQLite serialises writers; on PostgreSQL replace with a SEQUENCE.
    """
    row = session.get(Meta, "seq")
    if row is None:
        row = Meta(key="seq", value=0)
        session.add(row)
    row.value += 1
    session.flush()
    return row.value


def current_version(session) -> int:
    row = session.get(Meta, "seq")
    return row.value if row else 0
