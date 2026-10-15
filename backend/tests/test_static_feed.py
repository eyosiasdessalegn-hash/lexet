"""The static feed (what the app reads): bootstrap, increments, deletions, epoch, CLI export, API parity."""
import json
import os
import time

from fastapi.testclient import TestClient

from lexet import cli, static_feed
from lexet.api.app import create_app
from lexet.sources.base import RawObservation

from conftest import STAGE_COMMITTEE, STAGE_GAZETTE, STAGE_TABLED, day, feed


class Phone:
    """Mirrors the app's sync (ItemRepository.syncFeed) against a dict of published files."""

    def __init__(self):
        self.epoch, self.since, self.items, self.downloads = None, 0, {}, []

    def sync(self, files: dict[str, bytes]):
        get = lambda rel: json.loads(files[rel])  # noqa: E731
        index = get("feed/index.json")
        if self.epoch is not None and index["epoch"] != self.epoch:
            self.since, self.items = 0, {}
        self.epoch = index["epoch"]
        if index["version"] <= self.since:
            return "not-modified"
        pages = [index["snapshot"]["file"]] if self.since == 0 else \
            [seg["file"] for seg in index["segments"] if seg["to"] > self.since]
        for name in pages:
            self.downloads.append(name)
            page = get(f"feed/{name}")
            for it in page["items"]:
                old = self.items.get(it["id"])
                if it.get("deleted"):
                    self.items.pop(it["id"], None)
                elif old is None or old["version"] < it["version"]:
                    self.items[it["id"]] = it
            self.since = page["next_since"]
        return "updated"


def files_of(pipeline, settings):
    with pipeline.sf() as s:
        files, index = static_feed.build(s, settings)
        s.commit()
    return files, index


def test_new_install_downloads_one_snapshot_then_only_new_segments(pipeline, settings):
    feed(pipeline, STAGE_TABLED + STAGE_COMMITTEE)
    files, index = files_of(pipeline, settings)
    assert index["format"] == "lexet-feed-1" and index["snapshot"]["count"] == 1
    phone = Phone()
    assert phone.sync(files) == "updated"
    assert len(phone.downloads) == 1 and phone.downloads[0].startswith("snapshot-")
    (item,) = phone.items.values()
    assert item["verification"] == "verified" and item["source_count"] == 3
    assert phone.sync(files) == "not-modified"

    feed(pipeline, STAGE_GAZETTE)
    files2, index2 = files_of(pipeline, settings)
    assert index2["epoch"] == index["epoch"] and index2["version"] > index["version"]
    phone.downloads.clear()
    assert phone.sync(files2) == "updated"
    assert phone.downloads and all(n.startswith("seg-") for n in phone.downloads)
    (item,) = phone.items.values()
    assert item["law_id"] == "Proclamation No. 1431/2026" and item["status"] == "in_force"
    assert phone.since == index2["version"]

    fresh = Phone()                       # a new install gets the same state from the snapshot alone
    fresh.sync(files2)
    assert fresh.items == phone.items


def test_merged_items_reach_phones_as_deletions_but_not_new_installs(pipeline, settings):
    feed(pipeline, [
        ("ena_am", RawObservation(url="https://www.ena.et/web/amh/w/t-am1", title="የዲጂታል ንግድ ረቂቅ አዋጅ ለምክር ቤቱ ቀረበ",
                                  text="የሚኒስትሮች ምክር ቤት የዲጂታል ንግድ ረቂቅ አዋጅን ለሕዝብ ተወካዮች ምክር ቤት እንዲመራ ወሰነ።",
                                  published_at=day(2026, 3, 2))),
        ("reporter", RawObservation(url="https://www.thereporterethiopia.com/t-en1",
                                    title="Cabinet okays Digital Commerce Proclamation",
                                    text="The Council of Ministers approved the draft Digital Commerce Proclamation "
                                         "and forwarded it to parliament.", published_at=day(2026, 3, 3))),
    ])
    phone = Phone()
    phone.sync(files_of(pipeline, settings)[0])
    assert len(phone.items) == 2
    feed(pipeline, STAGE_GAZETTE[:1])
    files, _ = files_of(pipeline, settings)
    phone.sync(files)
    assert len(phone.items) == 1
    snapshot = json.loads(files[f"feed/{json.loads(files['feed/index.json'])['snapshot']['file']}"])
    assert len(snapshot["items"]) == 1 and not any(i.get("deleted") for i in snapshot["items"])


def test_file_names_follow_content_and_epoch_resets_phones(pipeline, settings, tmp_path):
    feed(pipeline, STAGE_TABLED)
    a, _ = files_of(pipeline, settings)
    b, _ = files_of(pipeline, settings)
    assert set(a) == set(b)                           # unchanged data → identical names
    phone = Phone()
    phone.sync(a)
    rebuilt = json.loads(a["feed/index.json"])
    rebuilt["epoch"] += 1                             # e.g. the server's database was rebuilt
    c = dict(a, **{"feed/index.json": json.dumps(rebuilt).encode()})
    phone.since = 10**6                               # a cursor the new dataset never reached
    assert phone.sync(c) == "updated" and len(phone.items) == 1


def test_cli_export_writes_site_and_removes_stale_files(tmp_path, monkeypatch):
    db = tmp_path / "x.db"
    monkeypatch.setenv("LEXET_DATABASE_URL", f"sqlite:///{db}")
    fixtures = __import__("pathlib").Path(__file__).parent / "fixtures" / "demo.jsonl"
    cli.main(["replay", str(fixtures)])
    out = tmp_path / "site"
    (out / "feed").mkdir(parents=True)
    old = out / "feed" / "seg-99-old.json"
    recent = out / "feed" / "seg-98-recent.json"
    old.write_text("{}")
    recent.write_text("{}")
    os.utime(old, (time.time() - 7200, time.time() - 7200))
    cli.main(["export", "--out", str(out)])
    index = json.loads((out / "feed" / "index.json").read_text())
    assert (out / "feed" / index["snapshot"]["file"]).exists()
    assert all((out / "feed" / s["file"]).exists() for s in index["segments"])
    assert not old.exists() and recent.exists()      # older files linger for an hour, for CDN caches
    assert (out / ".nojekyll").exists() and "LexET feed" in (out / "index.html").read_text()
    epoch = index["epoch"]
    cli.main(["export", "--out", str(out)])
    assert json.loads((out / "feed" / "index.json").read_text())["epoch"] == epoch


def test_api_serves_the_same_feed(pipeline, settings):
    feed(pipeline, STAGE_TABLED + STAGE_COMMITTEE)
    client = TestClient(create_app(pipeline.sf, settings, pipeline))
    index = client.get("/feed/index.json").json()
    files, built = files_of(pipeline, settings)
    assert {**index, "generated_at": None} == {**built, "generated_at": None}
    r = client.get(f"/feed/{index['snapshot']['file']}")
    assert r.status_code == 200 and "immutable" in r.headers["cache-control"]
    assert client.get("/feed/index.json").headers["cache-control"] == "no-cache"
    assert client.get("/feed/seg-0-nothere.json").status_code == 404
    assert client.get("/feed/sources.json").json()[0]["tier"] == 1
    assert "Digital Commerce Proclamation" in client.get("/").text
