"""Regenerate shared/ethiopic_vectors.json from the Python implementation.

Run after changing lexet/text/ethiopic.py, review the diff by eye, then run the
Kotlin domain tests (they read the same file).
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from lexet.text import ethiopic  # noqa: E402

CASES = [
    "ሥልጣን", "ስልጣን", "አፀደቀ", "አጸደቀ", "ሐምሌ", "ኀይል", "ኃይል", "ሃይል", "ዓመት", "ዐዋጅ",
    "የመረጃ ነፃነት አዋጅ", "የመረጃ፡ነጻነት፡አዋጅ።", "ለኢንቨስትመንት", "እንደሚከተለው", "ከአዲስ አበባ",
    "አዋጅ ቁጥር ፲፬፻፳፯/፳፻፲፰", "Proclamation No. 1427/2026", "FREEDOM of Information",
    "ሰኔ ፲፰ ቀን ፳፻፲፰ ዓ.ም", "ፍትሕ ሚኒስቴር", "ፍትህ ሚኒስቴር", "ሕግ", "ህግ", "ጸሐፊ", "ፀሀፊ",
    "አዋጅ ቍጥር ፩ሺ", "ምክር‍ቤት", "የ", "በር", "Café Société", "ፌዴራል ነጋሪት ጋዜጣ",
]

out = Path(__file__).resolve().parents[2] / "shared" / "ethiopic_vectors.json"
vectors = [{"input": c, "normalized": ethiopic.normalize(c), "index_tokens": ethiopic.index_tokens(c)} for c in CASES]
out.write_text(json.dumps({
    "description": "Ethiopic normalisation vectors shared by the backend (Python) and the Android domain "
                   "module (Kotlin). Regenerate with backend/scripts/gen_vectors.py and review by eye.",
    "vectors": vectors}, ensure_ascii=False, indent=1), encoding="utf-8")
for v in vectors:
    print(v["input"], "→", v["normalized"], "|", " ".join(v["index_tokens"]))
