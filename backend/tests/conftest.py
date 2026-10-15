import datetime as dt

import pytest

from lexet.config import Settings
from lexet.models import make_session_factory
from lexet.pipeline.run import Pipeline
from lexet.sources import catalog
from lexet.sources.base import RawObservation


class FakePusher:
    def __init__(self):
        self.sent = []

    def send_tickle(self, version, high_priority, verified_count=0):
        self.sent.append((version, high_priority, verified_count))
        return True


@pytest.fixture
def settings():
    s = Settings()
    s.default_crawl_delay_s = 0
    s.admin_token = "test-token"
    return s


@pytest.fixture
def pipeline(tmp_path, settings):
    sf = make_session_factory(f"sqlite:///{tmp_path}/t.db")
    return Pipeline(sf, settings, sources=catalog.SOURCES, pusher=FakePusher())


def day(y, m, d, h=9):
    return dt.datetime(y, m, d, h)


def feed(pipeline, items):
    """Ingest (source_id, RawObservation) pairs and publish. Returns events."""
    with pipeline.sf() as s:
        for sid, raw in items:
            pipeline.ingest(s, raw, pipeline.sources[sid])
        touched = pipeline.process_pending(s)
        events, merged, featured = pipeline.publish(s, touched)
        s.commit()
    return events


# A synthetic law moving through its life cycle, reported the way Ethiopian
# outlets typically report each stage. Law name and number are fictional.
STAGE_TABLED = [
    ("ena_en", RawObservation(
        url="https://www.ena.et/web/eng/w/eng_t1",
        title="Council of Ministers approves draft Digital Commerce Proclamation",
        text="Addis Ababa, March 2, 2026 (ENA) - The Council of Ministers in its regular session today approved "
             "the draft Digital Commerce Proclamation and forwarded it to the House of Peoples' Representatives "
             "for approval. The draft was prepared by the Ministry of Trade and Regional Integration to regulate "
             "online marketplaces and electronic payments.",
        published_at=day(2026, 3, 2))),
    ("reporter", RawObservation(
        url="https://www.thereporterethiopia.com/t-digital-bill",
        title="Digital commerce bill heads to parliament",
        text="The Council of Ministers has approved a draft proclamation on digital commerce and sent it to "
             "parliament. Legal experts interviewed by The Reporter said the bill would for the first time define "
             "the liability of platform operators, a gap that has long frustrated merchants and consumers alike. "
             "Several lawyers cautioned that the consumer protection chapter remains thin.",
        published_at=day(2026, 3, 4))),
    ("fana_en", RawObservation(  # a copy of ENA's story: must not count
        url="https://www.fanamc.com/english/t-council",
        title="Council approves draft digital commerce proclamation",
        text="Addis Ababa, March 2, 2026 (ENA) - The Council of Ministers in its regular session today approved "
             "the draft Digital Commerce Proclamation and forwarded it to the House of Peoples' Representatives "
             "for approval. The draft was prepared by the Ministry of Trade and Regional Integration to regulate "
             "online marketplaces and electronic payments.",
        published_at=day(2026, 3, 2, 11))),
]
STAGE_COMMITTEE = [
    ("addis_standard", RawObservation(
        url="https://eng.addisstandard.com/t-referral",
        title="Lawmakers refer draft Digital Commerce Proclamation to committee",
        text="The House of Peoples' Representatives today referred the draft Digital Commerce Proclamation to "
             "the Trade and Industry Affairs Standing Committee for detailed scrutiny after a first reading. "
             "Opposition MPs questioned provisions on data localisation.",
        published_at=day(2026, 3, 10))),
]
STAGE_APPROVED = [
    ("ena_en", RawObservation(
        url="https://www.ena.et/web/eng/w/eng_t2",
        title="House approves Digital Commerce Proclamation",
        text="Addis Ababa, June 20, 2026 (ENA) - The House of Peoples' Representatives approved the Digital "
             "Commerce Proclamation with a majority vote at its regular session today.",
        published_at=day(2026, 6, 20))),
]
STAGE_GAZETTE = [
    ("hopr_laws", RawObservation(
        url="https://hopr.gov.et/documents/t-proc-1431.pdf",
        title="Digital Commerce Proclamation No. 1431/2026 የዲጂታል ንግድ አዋጅ ቁጥር 1431/2018",
        text="FEDERAL NEGARIT GAZETTE OF THE FEDERAL DEMOCRATIC REPUBLIC OF ETHIOPIA. Proclamation No. 1431/2026 "
             "Digital Commerce Proclamation. WHEREAS it has become necessary to provide a legal framework for "
             "electronic commerce that protects consumers and promotes trust in digital transactions; NOW, "
             "THEREFORE, in accordance with Article 55(1) of the Constitution it is hereby proclaimed as follows.",
        published_at=day(2026, 7, 1), is_primary_document=True)),
    ("ena_am", RawObservation(
        url="https://www.ena.et/web/amh/w/amh_t3",
        title="የዲጂታል ንግድ አዋጅ ቁጥር 1431/2018 በነጋሪት ጋዜጣ ታትሞ ወጣ",
        text="አዲስ አበባ፣ ሰኔ 24/2018 (ኢዜአ)፦ የሕዝብ ተወካዮች ምክር ቤት ያፀደቀው የዲጂታል ንግድ አዋጅ ቁጥር 1431/2018 "
             "በፌዴራል ነጋሪት ጋዜጣ ታትሞ ወጥቷል።",
        published_at=day(2026, 7, 1, 15))),
]
