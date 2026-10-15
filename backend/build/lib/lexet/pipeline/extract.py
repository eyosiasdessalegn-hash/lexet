"""Observation → signals (one per law the observation reports on)."""
from __future__ import annotations

import datetime as dt
import re
from dataclasses import dataclass, field

from ..text import classify, ethiopic, lawid

MAX_SIGNALS_PER_OBSERVATION = 8
_SMALL_WORDS = {"of", "and", "the", "for", "on", "to", "in", "a", "an", "with", "by"}


@dataclass
class SignalDraft:
    doc_type: str
    canonical_key: str | None = None
    law_number: int | None = None
    law_year_gc: int | None = None
    law_year_ec: int | None = None
    year_calendar: str | None = None
    law_name: str | None = None
    name_tokens: frozenset = field(default_factory=frozenset)
    status: str | None = None
    issuer: str | None = None
    initiator: str | None = None
    topics: list[str] = field(default_factory=list)
    title_en: str | None = None
    title_am: str | None = None
    references: list[str] = field(default_factory=list)   # canonical keys of laws cited
    amends_key: str | None = None
    alt_name_tokens: list[frozenset] = field(default_factory=list)  # other names of the same instrument
    position: int = 0


@dataclass
class ObsView:
    """What extraction needs from an observation (DB row or raw)."""
    title: str
    text: str
    lang: str
    when: dt.datetime
    tier: int
    is_primary_document: bool = False
    gazette: bool = False
    status_hint: str | None = None
    doc_type_hint: str | None = None
    issuer_hint: str | None = None


def split_bilingual(title: str) -> tuple[str | None, str | None]:
    """("የመረጃ ነፃነት አዋጅ", "Freedom of Information Proclamation") from a mixed title."""
    am_parts, en_parts, cur, cur_kind = [], [], [], None
    for word in re.split(r"(\s+)", title or ""):
        if not word.strip():
            cur.append(word)
            continue
        kind = "am" if any(ethiopic.is_ethiopic(c) for c in word) else ("en" if re.search(r"[A-Za-z]", word) else cur_kind)
        if kind != cur_kind and cur:
            (am_parts if cur_kind == "am" else en_parts).append("".join(cur).strip(" /|-–—"))
            cur = []
        cur_kind = kind
        cur.append(word)
    if cur:
        (am_parts if cur_kind == "am" else en_parts).append("".join(cur).strip(" /|-–—"))
    am = " ".join(p for p in am_parts if p).strip() or None
    en = " ".join(p for p in en_parts if p).strip() or None
    return am, en


def title_case(name: str) -> str:
    words = re.sub(r"^\s*draft\s+", "", name.strip(), flags=re.I).split()
    out = []
    for i, w in enumerate(words):
        if i > 0 and w.lower() in _SMALL_WORDS:
            out.append(w.lower())
        elif w.isupper() and len(w) > 1:
            out.append(w)
        else:
            out.append(w[:1].upper() + w[1:])
    return " ".join(out)


def _clean_am_name(name) -> str:
    """Amharic display title from a LawName (original spelling, without "draft")."""
    text = getattr(name, "display", "") or getattr(name, "name", name)
    return " ".join(w for w in str(text).split() if w not in ("ረቂቅ",))


def extract_signals(obs: ObsView) -> list[SignalDraft]:
    # All offsets below refer to the folded text (folding can change length when
    # Ge'ez numerals are converted), so work on the folded text throughout.
    original = f"{obs.title}\n{obs.text or ''}"
    full = folded = ethiopic.fold(original)
    lang = obs.lang if obs.lang in ("en", "am") else ethiopic.detect_lang(folded)
    obs_date = obs.when.date()
    title_end = len(ethiopic.fold(obs.title)) + 1

    refs = lawid.find_law_refs(full, lang, obs_date)
    primary = lawid.primary_refs(refs, obs_date)
    # Official listing entries are about the instrument in their title, whatever its age.
    if obs.is_primary_document:
        title_refs = [r for r in refs if r.start < title_end]
        if title_refs:
            primary = title_refs[:1]
    reference_keys = [r.canonical_key for r in refs if r.is_reference and r.canonical_key]
    names = lawid.find_law_names(original, lang)  # offsets refer to the folded text; display keeps original spelling

    signals: list[SignalDraft] = []
    used_names: set[frozenset] = set()

    def status_for(doc_type: str, positions: list[int], numbered: bool) -> str | None:
        """Most advanced stage stated in any sentence that mentions the law."""
        s = None
        for pos in positions:
            s = classify.max_status(s, classify.detect_status(classify.sentence_around(full, pos), doc_type, lang))
        if s is None:
            s = classify.detect_status(obs.title, doc_type, lang)
        if obs.status_hint and (obs.is_primary_document or s is None):
            s = obs.status_hint  # an official list says what stage its entries are at
        if obs.gazette:
            s = classify.max_status(s, "in_force")  # publication in the Gazeta is entry into force
        if s is None:
            s = "in_force" if numbered else ("draft" if doc_type != "directive" else "approved")
        return s

    def mentions(pattern: str, first: int) -> list[int]:
        out = [first]
        try:
            out += [m.start() for m in re.finditer(pattern, folded, re.IGNORECASE)]
        except re.error:
            pass
        return sorted(set(out))[:12]

    for ref in primary:
        if len(signals) >= MAX_SIGNALS_PER_OBSERVATION:
            break
        # The name usually sits right before the number: "... Proclamation No. 1427/2026".
        name = next((n for n in names if 0 <= ref.start - (n.start + len(n.name)) <= 12
                     or (n.start <= ref.start <= n.start + len(n.name) + 12)), None)
        doc_type = ref.doc_type
        issuer, initiator = classify.issuer_for(doc_type, classify.sentence_around(full, ref.start) + " " + obs.title,
                                                obs.issuer_hint)
        key = lawid.canonical_key_for(doc_type, ref.number, ref.year_gc, issuer)
        sig = SignalDraft(doc_type=doc_type, canonical_key=key, law_number=ref.number,
                          law_year_gc=ref.year_gc, law_year_ec=ref.year_ec, year_calendar=ref.calendar,
                          status=status_for(doc_type, mentions(rf"(?<!\d){ref.number}\s*/\s*\d{{4}}", ref.start), True),
                          issuer=issuer, initiator=initiator,
                          references=[k for k in reference_keys if k != key], position=ref.start)
        if name:
            used_names.add(name.tokens)
            sig.law_name, sig.name_tokens = name.name, name.tokens
        _fill_titles(sig, obs, name, ref.start < title_end)
        _amendment(sig, refs)
        signals.append(sig)

    # A second name for a numbered instrument — the Amharic half of a bilingual
    # official title, or a name in the same sentence as the number — is an alias,
    # not another law.
    for n in names:
        if n.tokens in used_names:
            continue
        owner = None
        for sig in signals:
            if sig.doc_type != n.doc_type:
                continue
            same_title = obs.is_primary_document and n.start < title_end and sig.position < title_end
            # Same-language names in one sentence are usually *different* laws
            # ("approved the X Proclamation and the Y Proclamation No. 1401/2026"),
            # so only a name in the other language counts as an alias there.
            same_sentence = (n.lang != lang and classify.sentence_around(full, n.start)
                             == classify.sentence_around(full, sig.position))
            if same_title or same_sentence:
                owner = sig
                break
        if owner is not None:
            owner.alt_name_tokens.append(n.tokens)
            used_names.add(n.tokens)
            if n.lang == "am" and not owner.title_am:
                owner.title_am = _clean_am_name(n)
            if n.lang == "en" and not owner.title_en:
                owner.title_en = title_case(n.display or n.name)

    for n in names:
        if len(signals) >= MAX_SIGNALS_PER_OBSERVATION:
            break
        if n.tokens in used_names or any(n.tokens <= u or u <= n.tokens for u in used_names):
            continue
        if lawid._is_reference(folded, n.start, lang):
            continue
        # Skip names that merely restate a numbered law's subject already captured.
        used_names.add(n.tokens)
        doc_type = n.doc_type
        issuer, initiator = classify.issuer_for(doc_type, classify.sentence_around(full, n.start) + " " + obs.title,
                                                obs.issuer_hint)
        sig = SignalDraft(doc_type=doc_type, law_name=n.name, name_tokens=n.tokens,
                          status=status_for(doc_type, mentions(re.escape(n.name), n.start), False),
                          issuer=issuer, initiator=initiator,
                          references=reference_keys)
        _fill_titles(sig, obs, n, n.start < title_end)
        _amendment(sig, refs)
        signals.append(sig)

    if not signals and obs.is_primary_document and obs.doc_type_hint:
        # An official document list entry without a number or recognisable name.
        toks = lawid.name_tokens(obs.title)
        if len(toks) >= 2:
            issuer, initiator = classify.issuer_for(obs.doc_type_hint, obs.title, obs.issuer_hint)
            sig = SignalDraft(doc_type=obs.doc_type_hint, law_name=obs.title, name_tokens=toks,
                              status=obs.status_hint or "in_force", issuer=issuer, initiator=initiator)
            sig.title_am, sig.title_en = split_bilingual(obs.title)
            signals.append(sig)

    if len(signals) == 1 and signals[0].initiator is None:
        # One law in the report: the drafting ministry may be named in any sentence.
        signals[0].initiator = classify.issuer_for(signals[0].doc_type, full, obs.issuer_hint)[1]
    topic_text = full if len(signals) <= 1 else None
    for sig in signals:
        context = topic_text or " ".join(filter(None, [sig.law_name, sig.title_en, sig.title_am, obs.title]))
        sig.topics = classify.detect_topics(context)
    return signals


def _fill_titles(sig: SignalDraft, obs: ObsView, name, in_title: bool):
    if obs.is_primary_document:
        am, en = split_bilingual(obs.title)
        sig.title_am = _strip_law_id(am) if am else (_clean_am_name(name) if name and name.lang == "am" else None)
        sig.title_en = _strip_law_id(en) if en else (title_case(name.display or name.name) if name and name.lang == "en" else None)
        return
    if name is not None:
        if name.lang == "am":
            sig.title_am = _clean_am_name(name)
        else:
            sig.title_en = title_case(name.display or name.name)


_LAW_ID_TAIL = re.compile(r"\s*(?:No\.?|Number|ቁጥር|ቁ\.)?\s*\d{1,5}\s*/\s*\d{4}.*$", re.I)


def _strip_law_id(title: str) -> str:
    t = _LAW_ID_TAIL.sub("", title).strip(" ,.-–—/")
    return t or title


def _amendment(sig: SignalDraft, refs):
    hay = f"{sig.law_name or ''} {sig.title_en or ''} {sig.title_am or ''}".lower()
    if "amendment" in hay or "ማሻሻያ" in hay:
        cited = [r for r in refs if r.is_reference and r.doc_type == sig.doc_type and r.canonical_key]
        if cited:
            sig.amends_key = cited[0].canonical_key
