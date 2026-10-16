"""The sync data as plain files that any web host can serve (GitHub Pages, a CDN, S3…).

This is what the app reads. The HTTP API serves the same paths, generated on
request, so the app speaks one protocol whether the feed comes from a running
server or from files published by a scheduled job.

Layout, relative to the address the app is pointed at:

  feed/index.json            small; changes on every publish. Format, dataset epoch,
                             latest version, and the files below.
  feed/snapshot-<hash>.json  every current item: what a new install downloads, in one request.
  feed/seg-<k>-<hash>.json   the changes (including deletions) whose version is in
                             (k*S, (k+1)*S]; a phone that already has version N fetches
                             only the segments that end after N.
  feed/sources.json          monitored sources, with last success / last error.
  index.html                 a human-readable status page.

Snapshot and segment names carry a hash of their content, so a CDN can never
pair a fresh index with a stale segment: a phone always gets a consistent set.
The epoch identifies the dataset; if the server's database is ever rebuilt it
changes, and phones discard their copy and download the snapshot again.
"""
from __future__ import annotations

import datetime as dt
import hashlib
import html
import json
import secrets
import time
from collections import Counter
from pathlib import Path

from sqlalchemy import or_, select

from .api.serialize import item_dtos, iso, law_id, sources_list
from .config import Settings
from .models import Item, Meta, current_version

FORMAT = "lexet-feed-1"
SEGMENT = 100


def epoch(session) -> int:
    """Random identifier of this dataset, created once with the database."""
    row = session.get(Meta, "epoch")
    if row is None:
        row = Meta(key="epoch", value=secrets.randbelow(2**31 - 2) + 1)
        session.add(row)
        session.flush()
    return row.value


def _dump(obj) -> bytes:
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def _named(prefix: str, body: bytes) -> str:
    return f"{prefix}-{hashlib.sha256(body).hexdigest()[:12]}.json"


def build(session, settings: Settings, now: dt.datetime | None = None) -> tuple[dict[str, bytes], dict]:
    """All feed files as {relative path: bytes}, plus the index as a dict."""
    now = now or dt.datetime.now(dt.timezone.utc).replace(tzinfo=None)
    version = current_version(session)
    ep = epoch(session)
    visible = or_(Item.jurisdiction == "ET", Item.featured.is_(True))
    files: dict[str, bytes] = {}

    current = session.scalars(select(Item).where(visible, Item.deleted.is_(False)).order_by(Item.version)).all()
    snap = _dump({"next_since": version, "has_more": False, "items": item_dtos(session, current, settings)})
    snap_name = _named("snapshot", snap)
    files[f"feed/{snap_name}"] = snap

    changes = session.scalars(select(Item).where(or_(visible, Item.deleted.is_(True)), Item.version > 0)
                              .order_by(Item.version)).all()
    by_segment: dict[int, list[Item]] = {}
    for it in changes:
        by_segment.setdefault((it.version - 1) // SEGMENT, []).append(it)
    segments = []
    for k in sorted(by_segment):
        rows = by_segment[k]
        to = min((k + 1) * SEGMENT, version)
        body = _dump({"next_since": to, "has_more": False, "items": item_dtos(session, rows, settings)})
        name = _named(f"seg-{k}", body)
        files[f"feed/{name}"] = body
        segments.append({"k": k, "from": k * SEGMENT, "to": to, "file": name, "count": len(rows)})

    srcs = sources_list(session)
    files["feed/sources.json"] = _dump(srcs)

    index = {
        "format": FORMAT, "epoch": ep, "version": version, "generated_at": iso(now),
        "segment_size": SEGMENT, "snapshot": {"file": snap_name, "count": len(current)},
        "segments": segments, "sources": "sources.json",
    }
    files["feed/index.json"] = _dump(index)
    files["index.html"] = status_page(index, current, srcs).encode("utf-8")
    files[".nojekyll"] = b""   # GitHub Pages: serve files as they are
    return files, index


KEEP_OLD_FILES_S = 3600


def write(session, settings: Settings, out: Path, now: float | None = None) -> dict:
    """Write the feed into `out`, over an earlier feed there. Returns the index.

    Files an earlier index referred to are kept for an hour: a CDN may still hand
    a phone that older index for a few minutes, and its files must still exist.
    """
    files, index = build(session, settings)
    feed_dir = out / "feed"
    now = time.time() if now is None else now
    if feed_dir.exists():
        for old in feed_dir.glob("*.json"):
            if f"feed/{old.name}" not in files and now - old.stat().st_mtime > KEEP_OLD_FILES_S:
                old.unlink()
    for rel, body in files.items():
        p = out / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(body)
    return index


# ---------------------------------------------------------------- status page

_STATUS_LABEL = {
    "policy_signal": "Policy signal", "draft": "Draft", "tabled": "Before parliament", "approved": "Approved",
    "in_force": "In force", "amended": "Amended", "repealed": "Repealed",
}


def _when(iso_z: str | None) -> str:
    try:
        return dt.datetime.fromisoformat((iso_z or "").rstrip("Z")).strftime("%d %b %Y, %H:%M")
    except ValueError:
        return iso_z or ""


def status_page(index: dict, items: list[Item], sources: list[dict]) -> str:
    e = html.escape
    et = [i for i in items if i.jurisdiction == "ET"]
    counts = Counter(i.verification for i in et)
    latest = sorted(items, key=lambda i: (i.event_date or dt.date.min), reverse=True)[:25]
    rows = []
    for it in latest:
        title = it.title_en or it.title_am or "—"
        sub = it.title_am if it.title_en and it.title_am else ""
        rows.append(
            f"<li><div class='meta'>{' · '.join(e(x) for x in (str(it.event_date or ''), _STATUS_LABEL.get(it.status or '', it.status or '')) if x)}"
            f" · <b class='{e(it.verification or '')}'>{e((it.verification or '').capitalize())}</b> ({it.independent_sources})"
            f"{' · International' if it.jurisdiction != 'ET' else ''}</div>"
            f"{f'<div class=id>{e(law_id(it))}</div>' if law_id(it) else ''}"
            f"<div class='t'>{e(title)}</div>{f'<div class=am>{e(sub)}</div>' if sub else ''}</li>")
    src_rows = "".join(
        f"<tr><td>{e(s['name'])}</td><td>{s['tier']}</td><td>{e(s['robots_state'] or '')}</td>"
        f"<td>{e((s['last_success_at'] or '—')[:16].replace('T', ' '))}</td>"
        f"<td class='err'>{e((s['last_error'] or '')[:90])}</td></tr>" for s in sources if s["kind"] != "manual")
    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Aristophanes feed</title><meta name="robots" content="noindex">
<style>
:root{{--bg:#f6f0e3;--fg:#1a2238;--muted:#5d5a52;--brand:#1b2a4a;--line:#e0d5bd;--ok:#2e6b45;--warn:#94600a}}
@media (prefers-color-scheme:dark){{:root{{--bg:#0e1626;--fg:#eee6d3;--muted:#b3ac9c;--brand:#e8d9b5;--line:#26324e;--ok:#9ed3ae;--warn:#f0c070}}}}
body{{margin:0;background:var(--bg);color:var(--fg);font:15px/1.45 system-ui,'Noto Sans Ethiopic',sans-serif}}
main{{max-width:760px;margin:0 auto;padding:20px 16px 48px}}h1{{color:var(--brand);margin:0 0 4px;font-family:Georgia,'Times New Roman',serif;letter-spacing:.5px}}
.lead{{color:var(--muted);margin:0 0 18px}}.stats{{display:flex;gap:10px;flex-wrap:wrap;margin:0 0 20px}}
.stats div{{border:1px solid var(--line);border-radius:10px;padding:8px 12px}}.stats b{{display:block;font-size:20px}}
ul{{list-style:none;padding:0;margin:0}}li{{border-top:1px solid var(--line);padding:10px 0}}
.meta{{color:var(--muted);font-size:13px}}.id{{color:var(--brand);font-size:13px;font-weight:600}}.t{{font-weight:600}}
.am{{color:var(--muted)}}.verified{{color:var(--ok)}}.unconfirmed{{color:var(--warn)}}
h2{{font-size:16px;margin:28px 0 8px;color:var(--brand)}}.wrap{{overflow-x:auto}}
table{{border-collapse:collapse;width:100%;font-size:13px}}td,th{{text-align:left;padding:6px 8px;border-top:1px solid var(--line);vertical-align:top}}
.err{{color:var(--muted)}}code{{background:var(--line);padding:1px 5px;border-radius:4px}}
</style></head><body><main>
<h1>Aristophanes</h1>
<p class="lead">Ethiopian legal developments from official, press and other sources, each labelled by how many independent outlets confirm it. Updated {e(_when(index['generated_at']))} UTC
· data version {index['version']}. To use it, enter this page's address in the Aristophanes app under <b>Settings › Server</b>.</p>
<div class="stats"><div><b>{len(et)}</b>Ethiopian items</div><div><b>{counts.get('verified', 0)}</b>verified</div>
<div><b>{counts.get('unconfirmed', 0)}</b>unconfirmed</div><div><b>{len(items) - len(et)}</b>international</div></div>
<h2>Latest</h2><ul>{''.join(rows) or '<li>No items yet. The first crawl may take a few runs.</li>'}</ul>
<h2>Sources</h2><div class="wrap"><table><tr><th>Source</th><th>Tier</th><th>robots.txt</th><th>Last success (UTC)</th><th>Last error</th></tr>{src_rows}</table></div>
<p class="lead" style="margin-top:20px">Machine-readable feed: <code>feed/index.json</code>. Not legal advice; check the official text in the Federal Negarit Gazeta.</p>
</main></body></html>
"""
