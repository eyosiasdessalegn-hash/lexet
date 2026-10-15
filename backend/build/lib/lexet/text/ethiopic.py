"""Amharic / Ethiopic text normalisation.

Amharic is written in the Ethiopic (Ge'ez) script. Three properties matter for
search and for matching the same law across sources:

1. Homophone letters. Several consonant series are pronounced identically in
   modern Amharic and writers use them interchangeably:
   ሀ / ሐ / ኀ, ሰ / ሠ, አ / ዐ, ጸ / ፀ. "ሥልጣን" and "ስልጣን" are the same word, and
   "አጸደቀ" / "አፀደቀ" (approved) appear in different outlets for the same event.
   We fold every series onto one canonical series, keeping the vowel order.
2. Order equivalence. In the h- and glottal series the 1st and 4th orders sound
   the same (ሀ = ሃ, አ = ኣ), so we fold the 4th order onto the 1st.
3. Ge'ez numerals (፩ ፪ … ፻ ፼) still appear in gazette headers and older texts.
   We convert runs of them to Arabic digits so "፲፬፻፳፯" matches "1427".

Ethiopic punctuation (፡ ። ፣ ፤ …) becomes whitespace. Latin text is case-folded.

The Android client implements the same rules (domain module,
`EthiopicText.kt`); `shared/ethiopic_vectors.json` holds test vectors that both
implementations must agree on.
"""
from __future__ import annotations

import re
import unicodedata

# Canonical target row for each homophone row (row = 8 consecutive code points).
_HOMOPHONE_ROWS = {
    0x1210: 0x1200,  # ሐ → ሀ
    0x1280: 0x1200,  # ኀ → ሀ
    0x1220: 0x1230,  # ሠ → ሰ
    0x12D0: 0x12A0,  # ዐ → አ
    0x1340: 0x1338,  # ፀ → ጸ
}

_ETHIOPIC_PUNCT = range(0x1360, 0x1369)  # ፠ ፡ ። ፣ ፤ ፥ ፦ ፧ ፨

_GEEZ_DIGITS = {0x1369 + i: i + 1 for i in range(9)}        # ፩..፱ → 1..9
_GEEZ_TENS = {0x1372 + i: (i + 1) * 10 for i in range(9)}   # ፲..፺ → 10..90
_GEEZ_HUNDRED = 0x137B                                       # ፻
_GEEZ_MYRIAD = 0x137C                                        # ፼


def _build_fold_table() -> dict[int, int | str | None]:
    table: dict[int, int | str | None] = {}
    for src, dst in _HOMOPHONE_ROWS.items():
        for i in range(8):
            table[src + i] = dst + i
    # 4th-order → 1st-order equivalence in the (already folded) h and glottal rows.
    for cp in (0x1203, 0x1213, 0x1283):  # ሃ ሓ ኃ → ሀ
        table[cp] = 0x1200
    for cp in (0x12A3, 0x12D3):          # ኣ ዓ → አ
        table[cp] = 0x12A0
    # Old labialised 6th-order spellings of the -u vowel, common in older gazettes:
    # ቍጥር = ቁጥር ("number"), ኵ = ኩ, ጕ = ጉ, ኍ = ሁ.
    table[0x124D] = 0x1241  # ቍ → ቁ
    table[0x12B5] = 0x12A9  # ኵ → ኩ
    table[0x1315] = 0x1309  # ጕ → ጉ
    table[0x128D] = 0x1201  # ኍ → ሁ
    # Zero-width characters that sneak in from copy/paste, and the optional
    # Ethiopic gemination/vowel-length combining marks (U+135D–U+135F).
    for cp in (0x200B, 0x200C, 0x200D, 0xFEFF, 0x135D, 0x135E, 0x135F):
        table[cp] = None
    return table


_FOLD = _build_fold_table()
_GEEZ_NUMERAL_RUN = re.compile("[፩-፼]+")
_SPACES = re.compile(r"\s+")

# One-syllable prepositional prefixes that attach directly to the noun.
# "የመረጃ" (of information) should also be findable as "መረጃ".
_PREFIXES = ("እንደ", "ስለ", "ወደ", "የ", "በ", "ለ", "ከ")


def geez_to_int(s: str) -> int:
    """Convert a run of Ge'ez numerals to an int. ፲፬፻፳፯ → 1427, ፳፻፲፰ → 2018."""
    total = 0      # completed myriads
    current = 0    # value below 10 000
    group = 0      # value below 100
    seen = False
    for ch in s:
        cp = ord(ch)
        if cp in _GEEZ_DIGITS:
            group += _GEEZ_DIGITS[cp]
            seen = True
        elif cp in _GEEZ_TENS:
            group += _GEEZ_TENS[cp]
            seen = True
        elif cp == _GEEZ_HUNDRED:
            current += (group or 1) * 100
            group = 0
            seen = True
        elif cp == _GEEZ_MYRIAD:
            total = (total + current + group or 1) * 10000
            current = group = 0
            seen = True
        else:
            raise ValueError(f"not a Ge'ez numeral: {ch!r}")
    if not seen:
        raise ValueError("empty numeral")
    return total + current + group


def fold(text: str) -> str:
    """Homophone/order folding + Ge'ez numerals → digits. Keeps punctuation and case.

    Use this before running regexes (law IDs, dates) so patterns only need one
    spelling. Use :func:`normalize` for search keys.
    """
    if not text:
        return ""
    text = unicodedata.normalize("NFC", text).translate(_FOLD)
    return _GEEZ_NUMERAL_RUN.sub(lambda m: str(geez_to_int(m.group(0))), text)


def _is_token_char(ch: str) -> bool:
    cat = unicodedata.category(ch)
    return cat[0] in ("L", "N") or cat == "Mn"


def normalize(text: str) -> str:
    """Search key: fold, case-fold Latin, turn every non-letter/digit into a space."""
    folded = fold(text).casefold()
    out = [ch if _is_token_char(ch) else " " for ch in folded]
    return _SPACES.sub(" ", "".join(out)).strip()


def tokens(text: str) -> list[str]:
    n = normalize(text)
    return n.split(" ") if n else []


def is_ethiopic(ch: str) -> bool:
    return 0x1200 <= ord(ch) <= 0x139F or 0x2D80 <= ord(ch) <= 0x2DDF or 0xAB00 <= ord(ch) <= 0xAB2F


def strip_prefix(token: str) -> str | None:
    """Return the token without one prepositional prefix, or None.

    Only applied to Ethiopic tokens, and only when at least two syllables remain
    so that short words are not destroyed.
    """
    if not token or not is_ethiopic(token[0]):
        return None
    for p in _PREFIXES:
        if token.startswith(p) and len(token) - len(p) >= 2:
            return token[len(p):]
    return None


def index_tokens(text: str) -> list[str]:
    """Tokens for the full-text index: every token plus its prefix-stripped form."""
    out: list[str] = []
    for t in tokens(text):
        out.append(t)
        s = strip_prefix(t)
        if s:
            out.append(s)
    return out


def index_text(text: str) -> str:
    return " ".join(index_tokens(text))


def detect_lang(text: str) -> str:
    """'am' if the text is mostly Ethiopic letters, otherwise 'en'."""
    eth = lat = 0
    for ch in text[:4000]:
        if is_ethiopic(ch):
            eth += 1
        elif ch.isalpha():
            lat += 1
    return "am" if eth > lat else "en"
