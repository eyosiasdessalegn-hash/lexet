"""Command line: `lexet serve | run | crawl | publish | export | tickle | doctor | submit | replay | sources`."""
from __future__ import annotations

import argparse
import asyncio
import json
import logging
import sys
import time
from pathlib import Path

from . import static_feed
from .config import Settings
from .models import current_version, make_session_factory
from .pipeline.run import Pipeline
from .sources import catalog
from .sources.adapters import build_adapter
from .sources.base import AdapterContext, RawObservation
from .sources.html_extract import parse_date
from .sources.http import PoliteFetcher, RobotsDisallowed


def _pipeline(settings: Settings) -> Pipeline:
    return Pipeline(make_session_factory(settings.database_url), settings)


def cmd_serve(args, settings):
    import uvicorn
    from .api.app import create_app
    p = _pipeline(settings)
    uvicorn.run(create_app(p.sf, settings, p), host=args.host, port=args.port, log_level="info")


def cmd_crawl(args, settings):
    p = _pipeline(settings)
    report = asyncio.run(p.run_cycle(only=args.source or None, due_only=False))
    print(json.dumps(report.__dict__, indent=2, default=str))


class _NoPush:
    def send_tickle(self, *a, **k):
        return False


def _export(p: Pipeline, settings: Settings, out: str) -> dict:
    with p.sf() as s:
        index = static_feed.write(s, settings, Path(out))
        s.commit()   # keeps the dataset epoch if this export created it
    return index


def cmd_export(args, settings):
    """Write the feed as static files (for GitHub Pages or any web host)."""
    index = _export(_pipeline(settings), settings, args.out)
    print(json.dumps({"version": index["version"], "items": index["snapshot"]["count"],
                      "segments": len(index["segments"])}))


def cmd_publish(args, settings):
    """One scheduled run: crawl the sources that are due, verify, then export the feed.

    Push is not sent here (the files are not live yet); `lexet tickle` sends it
    after the host has published them, using the report written by this command.
    """
    p = _pipeline(settings)
    p.pusher = _NoPush()                # never push from inside this command
    # Compare with what phones last saw: the previously published index, if this
    # directory holds one (a manual addition made just before this run then counts too).
    published = Path(args.out) / "feed" / "index.json"
    if published.exists():
        before = json.loads(published.read_text(encoding="utf-8")).get("version", 0)
    else:
        with p.sf() as s:
            before = current_version(s)
    report = asyncio.run(p.run_cycle(due_only=not args.all, only=args.source or None))
    index = _export(p, settings, args.out)
    summary = {
        "previous_version": before, "version": index["version"], "events": len(report.events),
        "new_observations": report.new_observations, "touched_items": report.touched_items,
        "items": index["snapshot"]["count"], "crawled": report.crawled,
    }
    if args.report:
        Path(args.report).write_text(json.dumps(summary, indent=2, default=str), encoding="utf-8")
    print(json.dumps({k: v for k, v in summary.items() if k != "crawled"}))
    for sid, r in sorted(report.crawled.items()):
        print(f"  {sid:<16} {json.dumps(r, default=str)}")


def cmd_tickle(args, settings):
    """Send the push "tickle" for a publish run, once its files are live."""
    from .push import Pusher
    r = json.loads(Path(args.report).read_text(encoding="utf-8"))
    if r["version"] <= r["previous_version"]:
        print("no changes; nothing to send")
        return
    sent = Pusher(settings).send_tickle(r["version"], high_priority=r["events"] > 0, verified_count=r["events"])
    print(f"tickle v={r['version']} events={r['events']} sent={sent}")


def cmd_run(args, settings):
    """Scheduler loop: every tick, crawl the sources that are due, then publish."""
    p = _pipeline(settings)
    log = logging.getLogger("lexet.run")
    while True:
        started = time.monotonic()
        try:
            r = asyncio.run(p.run_cycle(due_only=True))
            if r.crawled or r.events:
                log.info("cycle: sources=%s new=%s touched=%s events=%s v=%s pushed=%s", len(r.crawled),
                         r.new_observations, r.touched_items, len(r.events), r.version, r.pushed)
        except Exception:  # noqa: BLE001
            log.exception("cycle failed")
        time.sleep(max(5, args.tick - (time.monotonic() - started)))


def cmd_doctor(args, settings):
    """Check every source: robots.txt permission and how many legal candidates it yields."""
    async def check():
        fetcher = PoliteFetcher(settings)
        rows = []
        for d in catalog.SOURCES:
            if d.kind == "manual" or (args.source and d.id not in args.source):
                continue
            adapter = build_adapter(d.kind, d.config)
            ctx = AdapterContext(fetcher, d, None, lambda u: False)
            status, n, err = "ok", 0, ""
            try:
                async for _ in adapter.discover(ctx):
                    n += 1
                    if n >= args.limit:
                        break
            except RobotsDisallowed as e:
                status = "ROBOTS-DISALLOWED" if e.state == "disallowed" else "UNREACHABLE"
                err = str(e)
            except Exception as e:  # noqa: BLE001
                status, err = "ERROR", f"{type(e).__name__}: {e}"
            if status == "ok" and ctx.stats["requests"] and ctx.stats["errors"] == ctx.stats["requests"]:
                status = "UNREACHABLE"
            rows.append((d.id, d.tier, status, n, ctx.stats["requests"], err))
        await fetcher.aclose()
        return rows

    rows = asyncio.run(check())
    print(f"{'source':<16} {'tier':>4}  {'status':<18} {'legal items':>11} {'requests':>8}  note")
    for sid, tier, status, n, req, err in rows:
        print(f"{sid:<16} {tier:>4}  {status:<18} {n:>11} {req:>8}  {err[:80]}")


def cmd_submit(args, settings):
    p = _pipeline(settings)
    text = open(args.text_file, encoding="utf-8").read() if args.text_file else (args.text or "")
    raw = RawObservation(url=args.url, title=args.title, text=text, published_at=parse_date(args.date),
                         is_primary_document=args.primary, gazette=args.gazette, status_hint=args.status,
                         doc_type_hint=args.doc_type, issuer_hint=args.issuer)
    touched, events = p.submit(raw, args.source)
    print(json.dumps({"items": touched, "events": events}, indent=2))


def cmd_replay(args, settings):
    """Feed observations from a JSONL file (one RawObservation + source_id per line)."""
    p = _pipeline(settings)
    with p.sf() as s:
        n = 0
        for line in open(args.file, encoding="utf-8"):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            d = json.loads(line)
            src = p.sources[d.pop("source_id")]
            d["published_at"] = parse_date(d.get("published_at"))
            p.ingest(s, RawObservation(**d), src)
            n += 1
        touched = p.process_pending(s)
        events, merged, featured = p.publish(s, touched)
        s.commit()
    print(json.dumps({"observations": n, "items_touched": len(touched), "events": len(events),
                      "merged": merged, "featured_intl": featured}, indent=2))


def cmd_sources(args, settings):
    for d in catalog.SOURCES:
        print(f"{d.id:<16} tier {d.tier}  {d.kind:<14} {d.publisher:<15} {d.name}")


def main(argv=None):
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    settings = Settings.from_env()
    ap = argparse.ArgumentParser(prog="lexet")
    sub = ap.add_subparsers(dest="cmd", required=True)

    a = sub.add_parser("serve", help="run the HTTP API")
    a.add_argument("--host", default="0.0.0.0")
    a.add_argument("--port", type=int, default=8080)
    a.set_defaults(fn=cmd_serve)

    a = sub.add_parser("run", help="run the crawl/publish scheduler")
    a.add_argument("--tick", type=int, default=60)
    a.set_defaults(fn=cmd_run)

    a = sub.add_parser("crawl", help="crawl now (all sources or --source ID ...)")
    a.add_argument("--source", nargs="*")
    a.set_defaults(fn=cmd_crawl)

    a = sub.add_parser("publish", help="crawl once, verify, and write the static feed")
    a.add_argument("--out", default="site")
    a.add_argument("--report", help="write a JSON summary here (used by `tickle`)")
    a.add_argument("--all", action="store_true", help="crawl every source, not only those due")
    a.add_argument("--source", nargs="*")
    a.set_defaults(fn=cmd_publish)

    a = sub.add_parser("export", help="write the static feed from the database, without crawling")
    a.add_argument("--out", default="site")
    a.set_defaults(fn=cmd_export)

    a = sub.add_parser("tickle", help="send the push notification for a `publish --report` run")
    a.add_argument("--report", required=True)
    a.set_defaults(fn=cmd_tickle)

    a = sub.add_parser("doctor", help="check robots permission and yield of every source")
    a.add_argument("--source", nargs="*")
    a.add_argument("--limit", type=int, default=5)
    a.set_defaults(fn=cmd_doctor)

    a = sub.add_parser("submit", help="assisted ingestion of one observation")
    a.add_argument("--source", required=True)
    a.add_argument("--url", required=True)
    a.add_argument("--title", required=True)
    a.add_argument("--text")
    a.add_argument("--text-file")
    a.add_argument("--date")
    a.add_argument("--primary", action="store_true")
    a.add_argument("--gazette", action="store_true")
    a.add_argument("--status")
    a.add_argument("--doc-type")
    a.add_argument("--issuer")
    a.set_defaults(fn=cmd_submit)

    a = sub.add_parser("replay", help="ingest observations from a JSONL file")
    a.add_argument("file")
    a.set_defaults(fn=cmd_replay)

    a = sub.add_parser("sources", help="list monitored sources")
    a.set_defaults(fn=cmd_sources)

    args = ap.parse_args(argv)
    args.fn(args, settings)


if __name__ == "__main__":
    sys.exit(main())
