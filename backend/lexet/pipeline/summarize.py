"""Item summaries.

Default summaries are written from the extracted facts, not copied from news
articles: that keeps them accurate (no claims the pipeline did not extract)
and avoids redistributing copyrighted reporting. Where an official text is
available, its opening (the title/preamble of a law, which is not protected
the way news copy is) is appended as context.

An LLM-backed summariser can be plugged in behind the same interface; if you do
so, feed it only tier-1 text and keep the facts line below as the first sentence.
"""
from __future__ import annotations

import re

from ..text import classify

_STATUS_PHRASE_EN = {
    "policy_signal": "is at the policy-initiative stage",
    "draft": "is in draft",
    "tabled": "has been submitted to the House of Peoples' Representatives",
    "approved": "has been approved and is awaiting publication in the Federal Negarit Gazeta",
    "in_force": "is in force",
    "amended": "is in force and has been amended",
    "repealed": "has been repealed",
}
_DOC_EN = {"proclamation": "proclamation", "regulation": "Council of Ministers regulation",
           "directive": "directive", "policy": "policy", "notice": "official notice", "other": "instrument"}
_DOC_AM = {"proclamation": "አዋጅ", "regulation": "የሚኒስትሮች ምክር ቤት ደንብ", "directive": "መመሪያ",
           "policy": "ፖሊሲ", "notice": "ማስታወቂያ", "other": "ሰነድ"}


def _issuer_names(code: str | None) -> tuple[str, str]:
    for i in classify.load_taxonomy()["issuers"]:
        if i["code"] == code:
            return i["en"], i["am"]
    return "", ""


def _status_am(code: str | None) -> str:
    for s in classify.load_taxonomy()["statuses"]:
        if s["code"] == code:
            return s["am"]
    return ""


def summarize(item, verification, official_lead: str | None) -> tuple[str, str]:
    title = item.title_en or item.title_am or "This instrument"
    law_id = ""
    if item.law_number:
        label = {"proclamation": "Proclamation", "regulation": "Regulation", "directive": "Directive"}.get(item.doc_type, "No.")
        law_id = f" ({label} No. {item.law_number}/{item.law_year_gc})" if item.law_year_gc else ""
    issuer_en, issuer_am = _issuer_names(item.issuer)
    status = verification.display_status
    phrase = _STATUS_PHRASE_EN.get(status or "", "has been reported")
    doc = _DOC_EN.get(item.doc_type, "instrument")
    by = f" issued by the {issuer_en}" if issuer_en and item.doc_type == "directive" else ""
    en = f"{title}{law_id}, a {doc}{by}, {phrase}."
    if item.initiator and item.doc_type in ("proclamation", "regulation"):
        ini_en, _ = _issuer_names(item.initiator)
        if ini_en:
            en += f" Initiated by the {ini_en}."
    n = verification.independent_sources
    if verification.verification == "verified":
        en += f" Corroborated by {n} independent sources."
    else:
        en += f" Unconfirmed: {n} independent source{'s' if n != 1 else ''} so far."
    if verification.reported_status and verification.verified_status and \
            classify.status_rank(verification.reported_status) > classify.status_rank(verification.verified_status):
        en += f" One or more sources report a later stage ({verification.reported_status.replace('_', ' ')}), not yet corroborated."
    if official_lead:
        lead = re.sub(r"\s+", " ", official_lead).strip()
        if len(lead) > 400:
            lead = lead[:400].rsplit(" ", 1)[0] + "…"
        en += f" Official text begins: “{lead}”"

    am_title = item.title_am or ""
    am_doc = _DOC_AM.get(item.doc_type, "ሰነድ")
    am_id = f" ቁጥር {item.law_number}/{item.law_year_ec}" if item.law_number and item.law_year_ec else ""
    parts = [p for p in [f"{am_title} ({am_doc}{am_id})" if am_title else f"{am_doc}{am_id}",
                         f"ሁኔታ፦ {_status_am(status)}" if status else "",
                         f"አውጪ፦ {issuer_am}" if issuer_am else ""] if p]
    verified_am = "በ{} ገለልተኛ ምንጮች የተረጋገጠ".format(n) if verification.verification == "verified" \
        else "ያልተረጋገጠ ({} ምንጭ)".format(n)
    am = " · ".join(parts + [verified_am]) + "።"
    return en, am
