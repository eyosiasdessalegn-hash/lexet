"""Text similarity: shingles for syndication detection, token-set overlap for names."""
from __future__ import annotations

import zlib

from . import ethiopic

SHINGLE_K = 5
MAX_SHINGLES = 512


def shingles(text: str, k: int = SHINGLE_K, limit_tokens: int = 1500) -> list[int]:
    """Stable 32-bit hashes of k-token shingles (sorted, capped).

    Stable across processes (crc32, not Python's randomised hash), so they can
    be stored and compared later.
    """
    toks = ethiopic.tokens(text)[:limit_tokens]
    if len(toks) < k:
        return [zlib.crc32(" ".join(toks).encode())] if toks else []
    hs = {zlib.crc32(" ".join(toks[i:i + k]).encode()) for i in range(len(toks) - k + 1)}
    # Keep the smallest hashes: a deterministic sample (bottom-k sketch).
    return sorted(hs)[:MAX_SHINGLES]


def jaccard(a, b) -> float:
    sa, sb = set(a), set(b)
    if not sa or not sb:
        return 0.0
    return len(sa & sb) / len(sa | sb)


def containment(a, b) -> float:
    """Share of the smaller set contained in the larger one."""
    sa, sb = set(a), set(b)
    if not sa or not sb:
        return 0.0
    return len(sa & sb) / min(len(sa), len(sb))


def names_match(a: frozenset, b: frozenset) -> bool:
    """Do two law-name token sets denote the same instrument?

    Needs at least two shared content words, and either a Jaccard overlap of
    0.5 or one name contained in the other. "amendment" must agree: an
    amendment proclamation is a different instrument from the law it amends.
    """
    if not a or not b:
        return False
    amend_words = {"amendment", "ማሻሻያ"}
    if bool(a & amend_words) != bool(b & amend_words):
        return False
    shared = a & b
    if len(shared) < 2:
        return False
    return jaccard(a, b) >= 0.5 or (containment(a, b) >= 0.85 and len(shared) >= 3)
