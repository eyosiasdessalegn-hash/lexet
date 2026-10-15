import datetime as dt
import json
from pathlib import Path

import pytest

from lexet.text import calendar, classify, ethiopic, lawid, similarity

SHARED = Path(__file__).resolve().parents[2] / "shared"


# ------------------------------------------------------------------ Ethiopic

def test_shared_vectors_match_python_implementation():
    data = json.loads((SHARED / "ethiopic_vectors.json").read_text(encoding="utf-8"))
    for v in data["vectors"]:
        assert ethiopic.normalize(v["input"]) == v["normalized"], v["input"]
        assert ethiopic.index_tokens(v["input"]) == v["index_tokens"], v["input"]


@pytest.mark.parametrize("a,b", [
    ("ሥልጣን", "ስልጣን"), ("አፀደቀ", "አጸደቀ"), ("ሐምሌ", "ሀምሌ"), ("ኃይል", "ሃይል"),
    ("ዓመት", "አመት"), ("ፍትሕ", "ፍትህ"), ("ቍጥር", "ቁጥር"),
])
def test_homophones_fold_together(a, b):
    assert ethiopic.normalize(a) == ethiopic.normalize(b)


@pytest.mark.parametrize("s,n", [("፩", 1), ("፲", 10), ("፲፰", 18), ("፻", 100), ("፳፻፲፰", 2018),
                                 ("፲፬፻፳፯", 1427), ("፼", 10000), ("፼፳፫፻፵፭", 12345)])
def test_geez_numerals(s, n):
    assert ethiopic.geez_to_int(s) == n


def test_prefix_stripping_only_for_ethiopic_and_long_enough():
    assert ethiopic.strip_prefix("የመረጃ") == "መረጃ"
    assert ethiopic.strip_prefix("በር") is None          # would leave one syllable
    assert ethiopic.strip_prefix("yellow") is None
    assert "መረጃ" in ethiopic.index_tokens("የመረጃ ነፃነት")


def test_detect_lang():
    assert ethiopic.detect_lang("የሕዝብ ተወካዮች ምክር ቤት አዋጁን አፀደቀ") == "am"
    assert ethiopic.detect_lang("Parliament approves proclamation") == "en"


# ------------------------------------------------------------------ calendar

@pytest.mark.parametrize("ec,gc", [
    ((2016, 1, 1), dt.date(2023, 9, 12)),   # new year before a Gregorian leap year
    ((2018, 1, 1), dt.date(2025, 9, 11)),
    ((2018, 10, 18), dt.date(2026, 6, 25)),  # FOI Proclamation approval date
    ((2017, 4, 29), dt.date(2025, 1, 7)),    # Genna
    ((2019, 1, 1), dt.date(2026, 9, 11)),
])
def test_calendar_roundtrip(ec, gc):
    assert calendar.ethiopic_to_gregorian(*ec) == gc
    assert calendar.gregorian_to_ethiopic(gc) == ec


def test_calendar_roundtrip_every_day_for_a_decade():
    d = dt.date(2020, 1, 1)
    while d < dt.date(2030, 1, 1):
        assert calendar.ethiopic_to_gregorian(*calendar.gregorian_to_ethiopic(d)) == d
        d += dt.timedelta(days=1)


def test_parse_amharic_dates_with_variant_spellings():
    assert calendar.parse_amharic_date("ምክር ቤቱ ሰኔ 18 ቀን 2018 ዓ.ም ባካሄደው") == dt.date(2026, 6, 25)
    assert calendar.parse_amharic_date("ሐምሌ 1 ቀን 2018") == calendar.parse_amharic_date("ሀምሌ 1 ቀን 2018")
    assert calendar.parse_amharic_date("ሰኔ ፲፰ ቀን ፳፻፲፰ ዓ.ም") == dt.date(2026, 6, 25)


def test_ec_law_year_to_gregorian():
    assert calendar.gc_year_for_ec_law_year(2018) == 2026
    assert calendar.gc_year_for_ec_law_year(2018, hint=dt.date(2025, 10, 1)) == 2025


# ------------------------------------------------------------------ law IDs

D = dt.date(2026, 7, 1)


def _refs(t):
    return [(r.doc_type, r.number, r.year_gc, r.year_ec, r.calendar, r.is_reference)
            for r in lawid.find_law_refs(t, obs_date=D)]


def test_english_and_amharic_ids_resolve_to_same_law():
    en = lawid.find_law_refs("Freedom of Information Proclamation No. 1427/2026", obs_date=D)[0]
    am = lawid.find_law_refs("የመረጃ ነፃነት አዋጅ ቁጥር 1427/2018", obs_date=D)[0]
    assert en.canonical_key == am.canonical_key == "proc:1427"
    assert (en.year_gc, en.calendar) == (2026, "gc")
    assert (am.year_ec, am.calendar, am.year_gc) == (2018, "ec", 2026)


def test_unmarked_ec_year_in_english_text_uses_number_anchors():
    r = lawid.find_law_refs("Proclamation No. 1427/2018 was approved", lang="en", obs_date=D)[0]
    assert r.calendar == "ec" and r.year_gc == 2026


def test_geez_numeral_ids():
    r = lawid.find_law_refs("አዋጅ ቍጥር ፲፬፻፳፯/፳፻፲፰", obs_date=D)[0]
    assert (r.number, r.year_ec) == (1427, 2018)


def test_references_are_not_subjects():
    t = ("The House approved the Aviation Security (Amendment) Proclamation No. 1412/2026, "
         "amending Proclamation No. 1234/2021, and the directive issued under Proclamation No. 1183/2020.")
    got = {r[1]: r[5] for r in _refs(t)}
    assert got == {1412: False, 1234: True, 1183: True}


def test_amharic_reference_cue_after_the_id():
    t = "መመሪያው በአዋጅ ቁጥር 1183/2012 መሠረት የወጣ ነው።"
    assert _refs(t)[0][5] is True


def test_regulation_and_directive_ids():
    t = "Council of Ministers Regulation No. 560/2025 and EFDA Directive No. 30/2024"
    assert [(r[0], r[1]) for r in _refs(t)] == [("regulation", 560), ("directive", 30)]


def test_directive_key_includes_issuer_and_year():
    assert lawid.canonical_key_for("directive", 30, 2024, "efda") == "dir:EFDA:30:2024"


def test_law_names_english_and_amharic():
    names = lawid.find_law_names("The House approved the Freedom of Information Proclamation today.")
    assert [n.name for n in names] == ["Freedom of Information Proclamation"]
    names = lawid.find_law_names("the council forwarded a draft proclamation on digital commerce to the House.")
    assert names[0].tokens == frozenset({"digital", "commerce"})
    names = lawid.find_law_names("የሕዝብ ተወካዮች ምክር ቤት የመረጃ ነፃነት አዋጅን አፀደቀ።")
    assert names[0].tokens == frozenset({"መረጃ", "ነጻነት"})


def test_amendment_never_matches_the_amended_law():
    base = lawid.name_tokens("Ethiopian Aviation Security Proclamation")
    amend = lawid.name_tokens("Ethiopian Aviation Security (Amendment) Proclamation")
    assert not similarity.names_match(base, amend)
    assert similarity.names_match(amend, lawid.name_tokens("Aviation Security (Amendment) Proclamation"))


# ------------------------------------------------------------------ status

@pytest.mark.parametrize("text,doc,expected", [
    ("The House of Peoples' Representatives approved the proclamation with a majority vote.", "proclamation", "approved"),
    ("The Council of Ministers approved a draft proclamation and forwarded it to the House for approval.", "proclamation", "tabled"),
    ("The draft was referred to the Legal and Justice Affairs Standing Committee for detailed scrutiny.", "proclamation", "tabled"),
    ("The Council of Ministers approved the regulation, which will take effect upon publication.", "regulation", "approved"),
    ("The proclamation entered into force upon publication in the Federal Negarit Gazeta.", "proclamation", "in_force"),
    ("Stakeholders discussed the draft proclamation at a validation workshop.", "proclamation", "draft"),
    ("The ministry is preparing a new policy on data.", "proclamation", "policy_signal"),
    ("ምክር ቤቱ አዋጁን በሙሉ ድምፅ አፀደቀ።", "proclamation", "approved"),
    ("የሚኒስትሮች ምክር ቤት ረቂቅ አዋጁን ለሕዝብ ተወካዮች ምክር ቤት እንዲመራ ወሰነ።", "proclamation", "tabled"),
    ("አዋጁ በፌዴራል ነጋሪት ጋዜጣ ታትሞ ሥራ ላይ ውሏል።", "proclamation", "in_force"),
])
def test_status_detection(text, doc, expected):
    assert classify.detect_status(text, doc) == expected


def test_topics_and_short_amharic_keyword_precision():
    assert "tax" in classify.detect_topics("New excise tax proclamation")
    assert "agriculture" in classify.detect_topics("የግብርና ሚኒስቴር")
    assert "tax" not in classify.detect_topics("የግብርና ሚኒስቴር")   # ግብር (tax) ≠ ግብርና (agriculture)
    assert "mining" in classify.detect_topics("የማዕድን ሥራዎች አዋጅ")


def test_attribution_detection():
    aliases = {"ENA": ["ENA", "Ethiopian News Agency", "ኢዜአ"], "FANA": ["FBC"]}
    assert classify.detect_attribution("Addis Ababa, March 2 (ENA) - The Council...", aliases, "FANA") == "ENA"
    assert classify.detect_attribution("አዲስ አበባ፣ መጋቢት 2/2018 (ኢዜአ)፦ የሚኒስትሮች ምክር ቤት", aliases, "FANA") == "ENA"
    assert classify.detect_attribution("Athena reported strong growth", aliases, "FANA") is None
    assert classify.detect_attribution("Addis Ababa (ENA) - ...", aliases, "ENA") is None


def test_issuer_rules():
    assert classify.issuer_for("proclamation", "Ministry of Finance drafted the bill")[0] == "HPR"
    assert classify.issuer_for("proclamation", "Ministry of Finance drafted the bill")[1] == "MOF"
    assert classify.issuer_for("regulation", "x")[0] == "COM"
    assert classify.issuer_for("directive", "The National Bank of Ethiopia issued a directive")[0] == "NBE"
