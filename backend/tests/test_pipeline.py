import json

from fastapi.testclient import TestClient
from sqlalchemy import select

from lexet.api.app import create_app
from lexet.models import Item
from lexet.sources.base import RawObservation

from conftest import STAGE_APPROVED, STAGE_COMMITTEE, STAGE_GAZETTE, STAGE_TABLED, day, feed


def et_items(pipeline):
    with pipeline.sf() as s:
        return s.scalars(select(Item).where(Item.jurisdiction == "ET", Item.deleted.is_(False))).all()


def evidence(item):
    return {e["publisher"]: e for e in json.loads(item.verification_json)["evidence"]}


def test_life_cycle_from_cabinet_to_gazette(pipeline):
    # Stage 1: cabinet sends the draft to parliament. ENA + Reporter report it,
    # Fana republishes ENA's copy (which must not count).
    events = feed(pipeline, STAGE_TABLED)
    items = et_items(pipeline)
    assert len(items) == 1
    it = items[0]
    assert it.title_en == "Digital Commerce Proclamation"
    assert it.doc_type == "proclamation" and it.issuer == "HPR" and it.initiator == "MOTRI"
    assert it.verification == "unconfirmed" and it.independent_sources == 2
    assert it.status == "tabled" and it.urgency == "upcoming"
    ev = evidence(it)
    assert ev["FANA"]["counted"] is False and "ENA" in ev["FANA"]["reason"]
    assert events == []

    # Stage 2: a third independent publisher → verified (one notification event).
    events = feed(pipeline, STAGE_COMMITTEE)
    it = et_items(pipeline)[0]
    assert it.verification == "verified" and it.independent_sources == 3
    assert it.verified_status == "tabled"
    assert events == [("verified", it.id)]

    # Stage 3: only ENA reports the vote. ENA is already counted, so "approved"
    # is reported but not yet corroborated; no notification.
    events = feed(pipeline, STAGE_APPROVED)
    it = et_items(pipeline)[0]
    assert it.reported_status == "approved" and it.verified_status == "tabled"
    assert it.status == "tabled"
    assert "later stage (approved)" in it.summary_en
    assert events == []

    # Stage 4: HoPR posts the gazette PDF (number assigned) and ENA Amharic reports
    # publication. The numbered official document joins the same item by name, the
    # Amharic report joins by number, and the Gazeta settles "in force".
    events = feed(pipeline, STAGE_GAZETTE)
    items = et_items(pipeline)
    assert len(items) == 1, [i.title_en or i.title_am for i in items]
    it = items[0]
    assert it.canonical_key == "proc:1431"
    assert (it.law_number, it.law_year_gc, it.law_year_ec) == (1431, 2026, 2018)
    assert it.title_am == "የዲጂታል ንግድ አዋጅ"
    assert it.has_official_source is True and it.independent_sources == 4
    assert it.verified_status == "in_force" and it.status == "in_force" and it.urgency == "in_force"
    assert events == [("status", it.id)]
    assert "Official text begins" in it.summary_en
    assert it.event_date.isoformat() == "2026-03-02"


def test_single_source_items_are_unconfirmed_not_hidden(pipeline):
    feed(pipeline, [("efda_docs", RawObservation(
        url="https://www.efda.gov.et/t/directive-1050.pdf",
        title="Cosmetics Import and Registration Directive No. 1050/2025",
        text="Directive No. 1050/2025 issued by the Ethiopian Food and Drug Authority.",
        published_at=day(2025, 11, 3), is_primary_document=True, status_hint="in_force",
        doc_type_hint="directive", issuer_hint="EFDA"))])
    it = et_items(pipeline)[0]
    assert it.canonical_key == "dir:EFDA:1050:2025"
    assert it.verification == "unconfirmed" and it.has_official_source and it.independent_sources == 1
    assert it.status == "in_force" and it.verified_status is None
    assert "health" in it.topics


def test_two_laws_in_one_report_become_two_items(pipeline):
    text = ("The House of Peoples' Representatives today approved the Payment for Ecosystem Services "
            "Proclamation and the Extradition Treaty Ratification Proclamation with majority votes.")
    feed(pipeline, [("reporter", RawObservation(url="https://www.thereporterethiopia.com/t-two",
                                                title="House passes two proclamations", text=text,
                                                published_at=day(2026, 2, 3)))])
    titles = sorted(i.title_en for i in et_items(pipeline))
    assert titles == ["Extradition Treaty Ratification Proclamation", "Payment for Ecosystem Services Proclamation"]
    assert all(i.status == "approved" for i in et_items(pipeline))


def test_amharic_only_draft_merges_once_a_bilingual_official_title_links_it(pipeline, settings):
    feed(pipeline, [
        ("ena_am", RawObservation(url="https://www.ena.et/web/amh/w/t-am1",
                                  title="የዲጂታል ንግድ ረቂቅ አዋጅ ለምክር ቤቱ ቀረበ",
                                  text="የሚኒስትሮች ምክር ቤት የዲጂታል ንግድ ረቂቅ አዋጅን ለሕዝብ ተወካዮች ምክር ቤት እንዲመራ ወሰነ።",
                                  published_at=day(2026, 3, 2))),
        ("reporter", RawObservation(url="https://www.thereporterethiopia.com/t-en1",
                                    title="Cabinet okays Digital Commerce Proclamation",
                                    text="The Council of Ministers approved the draft Digital Commerce Proclamation "
                                         "and forwarded it to parliament.", published_at=day(2026, 3, 3))),
    ])
    assert len(et_items(pipeline)) == 2  # no cross-language matching without a number
    feed(pipeline, STAGE_GAZETTE[:1])     # bilingual, numbered official title
    items = et_items(pipeline)
    assert len(items) == 1 and items[0].canonical_key == "proc:1431"
    with pipeline.sf() as s:
        tomb = s.scalars(select(Item).where(Item.deleted.is_(True))).all()
    assert len(tomb) == 1 and tomb[0].merged_into == items[0].id


def test_amendment_in_force_marks_base_law_amended(pipeline):
    base = [("hopr_laws", RawObservation(url="https://hopr.gov.et/t/base.pdf",
                                         title="Ethiopian Aviation Security Proclamation No. 1236/2021",
                                         text="FEDERAL NEGARIT GAZETTE Proclamation No. 1236/2021",
                                         published_at=day(2021, 3, 1), is_primary_document=True))]
    feed(pipeline, base)
    amend = [("hopr_laws", RawObservation(
        url="https://hopr.gov.et/t/amend.pdf",
        title="Ethiopian Aviation Security (Amendment) Proclamation No. 1412/2026",
        text="FEDERAL NEGARIT GAZETTE. This Proclamation amends the Ethiopian Aviation Security Proclamation "
             "No. 1236/2021.", published_at=day(2026, 2, 1), is_primary_document=True))]
    feed(pipeline, amend)
    by_key = {i.canonical_key: i for i in et_items(pipeline)}
    assert by_key["proc:1412"].amends_key == "proc:1236"
    assert by_key["proc:1236"].status == "amended"


def test_international_stream_respects_ratio(pipeline, settings):
    feed(pipeline, STAGE_TABLED + STAGE_COMMITTEE)  # one ET item
    intl = [("jurist", RawObservation(url=f"https://www.jurist.org/news/t{i}",
                                      title=t, text=body, published_at=day(2026, 3, 5)))
            for i, (t, body) in enumerate([
                ("AfCFTA dispute settlement rules adopted", "African Union trade ministers adopted rules for the AfCFTA."),
                ("Court rules on zoning in Ohio", "A state court in Ohio ruled on a zoning dispute."),
            ])]
    feed(pipeline, intl)
    with pipeline.sf() as s:
        featured = s.scalars(select(Item).where(Item.jurisdiction == "INTL", Item.featured.is_(True))).all()
    assert featured == []  # 1 ET item → 0.1 international items allowed

    settings.intl_ratio = 1
    feed(pipeline, [])
    with pipeline.sf() as s:
        featured = s.scalars(select(Item).where(Item.jurisdiction == "INTL", Item.featured.is_(True))).all()
    assert [f.title_en for f in featured] == ["AfCFTA dispute settlement rules adopted"]


# ------------------------------------------------------------------ API

def test_sync_api_delta_etag_and_tombstones(pipeline, settings):
    client = TestClient(create_app(pipeline.sf, settings, pipeline))
    feed(pipeline, STAGE_TABLED + STAGE_COMMITTEE)
    r = client.get("/v1/sync?since=0")
    assert r.status_code == 200
    body = r.json()
    assert not body["has_more"] and len(body["items"]) == 1
    item = body["items"][0]
    assert item["verification"] == "verified" and item["source_count"] == 3
    assert item["law_id"] is None and item["title_en"] == "Digital Commerce Proclamation"
    pubs = {s["publisher"]: s for s in item["sources"]}
    assert set(pubs) == {"ENA", "REPORTER", "FANA", "ADDIS_STANDARD"}
    assert pubs["FANA"]["counted"] is False
    assert all(len(s["excerpt"] or "") <= settings.news_excerpt_chars + 1 for s in item["sources"] if s["tier"] != 1)
    since = body["next_since"]

    assert client.get(f"/v1/sync?since={since}").status_code == 304

    feed(pipeline, STAGE_GAZETTE)
    r = client.get(f"/v1/sync?since={since}")
    d = r.json()
    assert [i["law_id"] for i in d["items"]] == ["Proclamation No. 1431/2026"]
    assert d["items"][0]["status"] == "in_force"
    official = [s for s in d["items"][0]["sources"] if s["tier"] == 1][0]
    assert official["gazette"] is True and "WHEREAS" in official["excerpt"]


def test_sync_paging(pipeline, settings):
    client = TestClient(create_app(pipeline.sf, settings, pipeline))
    feed(pipeline, [("reporter", RawObservation(
        url=f"https://www.thereporterethiopia.com/t-page-{i}", title=f"House approves Proclamation No. {1300 + i}/2026",
        text=f"The House approved Proclamation No. {1300 + i}/2026.", published_at=day(2026, 1, 1 + i)))
        for i in range(5)])
    seen, since = [], 0
    while True:
        r = client.get(f"/v1/sync?since={since}&limit=2")
        if r.status_code == 304:
            break
        d = r.json()
        seen += [i["law_number"] for i in d["items"]]
        since = d["next_since"]
        if not d["has_more"]:
            break
    assert sorted(seen) == [1300, 1301, 1302, 1303, 1304]


def test_admin_assisted_ingestion(pipeline, settings):
    client = TestClient(create_app(pipeline.sf, settings, pipeline))
    payload = {"source_id": "moj_laws", "url": "https://justice.gov.et/t/proc-1440",
               "title": "Commercial Registration Proclamation No. 1440/2026",
               "text": "Proclamation No. 1440/2026", "published_at": "2026-09-01",
               "is_primary_document": True, "status_hint": "in_force"}
    assert client.post("/v1/admin/observations", json=payload).status_code == 401
    r = client.post("/v1/admin/observations", json=payload, headers={"Authorization": "Bearer test-token"})
    assert r.status_code == 200 and len(r.json()["items"]) == 1
    assert client.get("/v1/sources").json()[0]["tier"] == 1


def test_news_mentioning_the_gazette_is_not_a_gazette(pipeline):
    feed(pipeline, [("ena_en", RawObservation(
        url="https://www.ena.et/web/eng/w/t-fuel", title="Council of Ministers approves Fuel Price Regulation",
        text="The Council of Ministers approved the Fuel Price Regulation, which will take effect upon "
             "publication in the Federal Negarit Gazeta.", published_at=day(2026, 9, 29)))])
    it = et_items(pipeline)[0]
    assert it.status == "approved" and it.doc_type == "regulation" and it.issuer == "COM"
    assert "energy" in it.topics


def test_amharic_titles_keep_original_spelling_without_institution_words(pipeline):
    feed(pipeline, [("ena_am", RawObservation(
        url="https://www.ena.et/web/amh/w/t-sec", title="ምክር ቤቱ የመረጃ ደኅንነት ረቂቅ አዋጅን ለዝርዝር እይታ መራ",
        text="የሕዝብ ተወካዮች ምክር ቤት የመረጃ ደኅንነት ረቂቅ አዋጅን ለዝርዝር እይታ ለቋሚ ኮሚቴው መራ።",
        published_at=day(2026, 10, 10)))])
    it = et_items(pipeline)[0]
    assert it.title_am == "የመረጃ ደኅንነት አዋጅ"
    assert it.status == "tabled" and it.topics == []


def test_explicit_ethiopian_year_beats_converted_one(pipeline):
    feed(pipeline, [("moj_laws", RawObservation(
        url="https://justice.gov.et/t/1430.pdf", is_primary_document=True, published_at=day(2026, 9, 25),
        title="Rural Roads Loan Ratification Proclamation No. 1430/2026 የገጠር መንገዶች ብድር ማጽደቂያ አዋጅ ቁጥር 1430/2018",
        text="FEDERAL NEGARIT GAZETTE Proclamation No. 1430/2026"))])
    it = et_items(pipeline)[0]
    assert (it.law_year_gc, it.law_year_ec) == (2026, 2018)
    assert it.title_am == "የገጠር መንገዶች ብድር ማጽደቂያ አዋጅ"


def test_legal_news_without_a_named_law_is_kept_as_unconfirmed_item(pipeline):
    feed(pipeline, [
        ("borkena", RawObservation(url="https://borkena.com/t-court", title="Federal court rules on land lease policy dispute",
                                   text="The Federal Supreme Court ruled on Monday in a dispute over the land lease policy in Addis Ababa.",
                                   published_at=day(2026, 10, 14))),
        ("borkena", RawObservation(url="https://borkena.com/t-sport", title="Athletes return from Tokyo",
                                   text="Ethiopian athletes returned home on Monday.", published_at=day(2026, 10, 14))),
    ])
    (item,) = et_items(pipeline)
    assert item.doc_type == "other" and item.verification == "unconfirmed" and item.independent_sources == 1
    assert item.title_en == "Federal court rules on land lease policy dispute"
    assert "Supreme Court" in item.summary_en
    ev = json.loads(item.verification_json)["evidence"]
    assert ev[0]["publisher"] == "BORKENA" and "names no specific law" in ev[0]["reason"]
