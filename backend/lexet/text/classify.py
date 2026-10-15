"""Classification of legal signals: status, issuing body, topics, attribution."""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

from . import ethiopic

STATUS_RANK = {
    "policy_signal": 0, "draft": 1, "tabled": 2, "approved": 3,
    "in_force": 4, "amended": 5, "repealed": 6,
}
STATUS_URGENCY = {
    "policy_signal": "upcoming", "draft": "upcoming", "tabled": "upcoming",
    "approved": "imminent", "in_force": "in_force", "amended": "in_force", "repealed": "ended",
}


def status_rank(s: str | None) -> int:
    return STATUS_RANK.get(s or "", -1)


def max_status(*statuses: str | None) -> str | None:
    best = None
    for s in statuses:
        if s and status_rank(s) > status_rank(best):
            best = s
    return best


# ---------------------------------------------------------------- taxonomy

_DEFAULT_TAXONOMY = Path(__file__).resolve().parents[3] / "shared" / "taxonomy.json"


@lru_cache(maxsize=4)
def load_taxonomy(path: str | None = None) -> dict:
    p = Path(path) if path else _DEFAULT_TAXONOMY
    with open(p, encoding="utf-8") as f:
        return json.load(f)


# ---------------------------------------------------------------- sentences

_SENT_SPLIT = re.compile(r"(?<=[.!?።])\s+|\n{2,}")


def sentences(text: str) -> list[str]:
    return [s.strip() for s in _SENT_SPLIT.split(text or "") if s.strip()]


def sentence_around(text: str, pos: int) -> str:
    """The sentence containing character offset `pos`."""
    start = max(text.rfind(".", 0, pos), text.rfind("።", 0, pos), text.rfind("\n", 0, pos))
    ends = [i for i in (text.find(". ", pos), text.find("።", pos), text.find("\n", pos)) if i != -1]
    end = min(ends) if ends else len(text)
    return text[start + 1:end + 1].strip()


# ---------------------------------------------------------------- status

_EN_IN_FORCE = re.compile(
    r"published\s+(?:in|on)\s+the\s+(?:federal\s+)?negarit\s+gaze(?:t|tt)[ae]|"
    r"enter(?:ed|s)\s+into\s+force|c(?:a|o)me(?:s)?\s+into\s+(?:force|effect)|took\s+effect|takes\s+effect|"
    r"effective\s+(?:as\s+of|from)|now\s+in\s+force|registered\s+(?:by|with)\s+the\s+ministry\s+of\s+justice"
)
_EN_APPROVED = re.compile(r"\b(?:approv(?:ed|es|al)|pass(?:ed|es)|ratif(?:ied|ies|ication)|endors(?:ed|es)|adopt(?:ed|s)|enact(?:ed|s)|issued)\b")
_EN_TABLED = re.compile(
    r"forward(?:ed|s)?\b[^.]{0,80}?\bto\s+(?:the\s+)?(?:house|parliament|hopr|lawmakers)|"
    r"\brefer(?:s|red|ring)?\b[^.]{0,80}?\bcommittee|\btabled\b|submitted\s+to\s+(?:the\s+)?(?:house|parliament)|"
    r"first\s+reading|public\s+hearing|sent\s+to\s+(?:the\s+)?(?:house|parliament)|for\s+(?:further\s+)?scrutiny"
)
_EN_DRAFT = re.compile(r"\bdraft(?:ed|ing|s)?\b|\bbill\b|validation\s+workshop|\bconsultation\b")
_EN_POLICY = re.compile(
    r"\bpolicy\b|\bstrategy\b|\broadmap\b|plans?\s+to\s+(?:introduce|enact|draft|prepare|amend)|"
    r"\bis\s+preparing\b|being\s+prepared|\binitiat(?:ed|es|ive)\b|\bstudy\b"
)
_EN_HPR = re.compile(r"house\s+of\s+peoples?['’]?\s*representatives|\bparliament\b|\blawmakers\b|\bthe\s+house\b|\bhopr\b|\bmps\b")
_EN_COM = re.compile(r"council\s+of\s+ministers|\bcabinet\b")
_EN_DRAFTISH = re.compile(r"\bdraft\b|\bbill\b")
# Future or conditional statements ("will enter into force upon publication",
# "once approved by parliament") describe a later stage that has not happened.
_EN_FUTURE = re.compile(
    r"\b(?:will|would|shall|should|could|may|expected\s+to|is\s+set\s+to|set\s+to|to\s+be|once|upon|pending|"
    r"awaiting|until|before|after\s+(?:it\s+is|being))\s+(?:be\s+|have\s+been\s+|get\s+|its\s+|their\s+|the\s+)?"
    r"(?:\w+\s+){0,2}?(?:approv\w*|pass\w*|ratif\w*|endors\w*|adopt\w*|enact\w*|enter\w*\s+into\s+force|"
    r"come\s+into\s+(?:force|effect)|take\s+effect|effective|publish\w*|publication|implement\w*)"
)
_AM_FUTURE = re.compile(r"ሲጸድቅ|ከጸደቀ\s*በኋላ|እንዲጸድቅ|ለማጽደቅ|ይጠበቃል|ተግባራዊ\s*ይሆናል|ስራ\s*ላይ\s*ይውላል")
_EN_HPR_APPROVED = re.compile(
    r"\b(?:house|parliament|lawmakers|hopr|mps)\b[^.]{0,100}?"
    r"\b(?:approv(?:ed|es)|pass(?:ed|es)|ratif(?:ied|ies)|endors(?:ed|es)|adopt(?:ed|s)|enact(?:ed|s))\b|"
    r"\b(?:approv(?:ed|al)|pass(?:ed|age)|ratif(?:ied|ication)|endorse(?:d|ment)|adopt(?:ed|ion))\b"
    r"[^.]{0,60}?\bby\s+(?:the\s+)?(?:house|parliament|lawmakers|hopr)"
)

# Amharic, matched against ethiopic.fold() text (ሠ→ሰ, ፀ→ጸ, ዐ→አ, ሐ/ኀ→ሀ).
_AM_IN_FORCE = re.compile(r"ነጋሪት\s*ጋዜጣ[^።]{0,40}(ታትሞ|ታተመ|ወጥቷል|ወጣ)|ስራ\s*ላይ\s*(ውሏል|ዋለ)|ተግባራዊ\s*መሆን\s*(ጀምሯል|ጀመረ)|ጸንቶ\s*የሚቆይ")
_AM_APPROVED = re.compile(r"አጸደቀ|አጽድቋል|አጸድቋል|ጸድቋል|ጸደቀ|በሙሉ\s*ድምጽ|በአብላጫ\s*ድምጽ|አጽድቆታል")
_AM_TABLED = re.compile(
    r"ለዝርዝር\s*እይታ|ቋሚ\s*ኮሚቴ[^።]{0,30}(መራ|መርቷል|ተመርቷል)|ተወካዮች\s*ምክር\s*ቤት[^።]{0,40}(እንዲመራ|መራ|ተመርቷል|ልኳል|አስተላልፏል)|"
    r"ህዝባዊ\s*ውይይት|የህዝብ\s*አስተያየት\s*መስጫ"
)
_AM_DRAFT = re.compile(r"ረቂቅ")
_AM_POLICY = re.compile(r"ፖሊሲ|ስትራቴጂ|ለማዘጋጀት|እየተዘጋጀ|ጥናት")
_AM_COM = re.compile(r"የሚኒስትሮች\s*ምክር\s*ቤት|ሚኒስትሮች")


def detect_status(text: str, doc_type: str, lang: str | None = None) -> str | None:
    """Status implied by a sentence (or headline) for an instrument of `doc_type`.

    The subject matters: when the Council of Ministers "approves" a *draft
    proclamation* it is sending it to parliament (tabled); when parliament
    approves it, it is enacted and waits for the gazette (approved). When the
    Council of Ministers approves a *regulation*, that regulation is enacted.
    """
    if not text:
        return None
    lang = lang or ethiopic.detect_lang(text)
    if lang == "am":
        return _detect_status_am(ethiopic.fold(text), doc_type)
    s = _EN_FUTURE.sub(" ", text.casefold())
    if _EN_IN_FORCE.search(s):
        return "in_force"
    approved = bool(_EN_APPROVED.search(s))
    tabled = bool(_EN_TABLED.search(s))
    hpr = bool(_EN_HPR.search(s))
    com = bool(_EN_COM.search(s))
    draftish = bool(_EN_DRAFTISH.search(s))
    if doc_type == "proclamation":
        if _EN_HPR_APPROVED.search(s) or (approved and hpr and not com and not tabled):
            return "approved"
        if tabled or (approved and com):
            return "tabled"
        if approved:
            return "approved"  # passive voice, body not named
    elif doc_type == "regulation":
        if approved:
            return "approved"
    elif doc_type == "directive":
        if approved:
            return "approved"
    if draftish or _EN_DRAFT.search(s):
        return "draft"
    if _EN_POLICY.search(s):
        return "policy_signal"
    return None


def _detect_status_am(f: str, doc_type: str) -> str | None:
    f = _AM_FUTURE.sub(" ", f)
    if _AM_IN_FORCE.search(f):
        return "in_force"
    approved = bool(_AM_APPROVED.search(f))
    tabled = bool(_AM_TABLED.search(f))
    com = bool(_AM_COM.search(f))
    draftish = bool(_AM_DRAFT.search(f))
    if doc_type == "proclamation":
        if com and (draftish or tabled):
            return "tabled"
        if tabled:
            return "tabled"
        if approved:
            return "approved"
    elif approved:
        return "approved"
    if draftish:
        return "draft"
    if _AM_POLICY.search(f):
        return "policy_signal"
    return None


# ---------------------------------------------------------------- issuers

@dataclass(frozen=True)
class IssuerMatch:
    code: str
    pos: int


def find_issuers(text: str, taxonomy: dict | None = None) -> list[IssuerMatch]:
    """Issuing bodies mentioned in the text, in order of first mention."""
    taxonomy = taxonomy or load_taxonomy()
    low = text.casefold()
    folded = ethiopic.fold(text)
    hits: dict[str, int] = {}
    for issuer in taxonomy["issuers"]:
        best = None
        for p in issuer.get("patterns_en", []):
            i = _find_phrase(low, p)
            if i is not None and (best is None or i < best):
                best = i
        for p in issuer.get("patterns_am", []):
            i = folded.find(ethiopic.fold(p))
            if i != -1 and (best is None or i < best):
                best = i
        if best is not None:
            hits[issuer["code"]] = best
    return [IssuerMatch(c, p) for c, p in sorted(hits.items(), key=lambda kv: kv[1])]


def _find_phrase(haystack: str, phrase: str) -> int | None:
    m = re.search(r"(?<![\w])" + re.escape(phrase) + r"(?![\w])", haystack)
    return m.start() if m else None


def issuer_for(doc_type: str, text: str, hint: str | None = None) -> tuple[str, str | None]:
    """(issuing body, initiating body). Proclamations are enacted by the HPR and
    regulations by the Council of Ministers whoever drafted them; directives are
    issued by the agency named in the text (or the publishing agency)."""
    mentions = [m.code for m in find_issuers(text)]
    others = [c for c in mentions if c not in ("HPR", "COM", "HOF", "PMO", "MOJ")]
    initiator = others[0] if others else None
    if doc_type == "proclamation":
        return "HPR", initiator
    if doc_type == "regulation":
        return "COM", initiator
    if doc_type == "directive":
        return (hint or initiator or "OTHER"), None
    return (hint or (mentions[0] if mentions else "OTHER")), initiator


# ---------------------------------------------------------------- topics

def detect_topics(text: str, taxonomy: dict | None = None) -> list[str]:
    taxonomy = taxonomy or load_taxonomy()
    norm = ethiopic.normalize(text)
    toks = norm.split()
    stripped = [ethiopic.strip_prefix(t) or t for t in toks]
    padded = f" {norm} "
    padded_stripped = f" {' '.join(stripped)} "
    tokset = set(toks) | set(stripped)
    found = []
    for topic in taxonomy["topics"]:
        hit = False
        for kw in topic.get("kw_en", []) + topic.get("kw_am", []):
            k = ethiopic.normalize(kw)
            if not k:
                continue
            if " " in k:
                hit = f" {k} " in padded or f" {k} " in padded_stripped
            elif ethiopic.is_ethiopic(k[0]):
                hit = k in tokset or (len(k) >= 4 and any(t.startswith(k) for t in tokset))
            else:
                hit = k in tokset or (k + "s") in tokset
            if hit:
                break
        if hit:
            found.append(topic["code"])
    return found


# ---------------------------------------------------------------- attribution

def detect_attribution(text: str, aliases: dict[str, list[str]], own_publisher: str) -> str | None:
    """Publisher another outlet credits as its source ("(ENA)", "ኢዜአ", "according to ENA").

    `aliases` maps publisher code → names/datelines. Looks at the dateline area
    and the sign-off, where credits appear. Returns a publisher other than
    `own_publisher`, or None.
    """
    head, tail = text[:600], text[-300:]
    for pub, names in aliases.items():
        if pub == own_publisher:
            continue
        for name in names:
            if not name:
                continue
            if ethiopic.is_ethiopic(name[0]):
                n = ethiopic.fold(name)
                h, t = ethiopic.fold(head), ethiopic.fold(tail)
                if f"({n})" in h or f"({n})" in t or n in h[:200]:
                    return pub
                continue
            n = re.escape(name.casefold())
            pat = re.compile(
                rf"\({n}\)|\bsource\s*:\s*{n}\b|\baccording\s+to\s+(?:the\s+)?{n}\b|"
                rf"\b{n}\s+reported\b|\bvia\s+{n}\b|\bcredit\s*:\s*{n}\b|^\s*{n}\s*[-–—:]"
            )
            if pat.search(head.casefold()) or pat.search(tail.casefold()):
                return pub
    return None
