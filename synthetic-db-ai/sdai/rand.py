"""Deterministic, vectorised hashing / ID / text-encoding primitives.

Everything an entity "is" (its UUID, signup date, price...) is a pure function of
(seed, kind, index).  That lets any worker recompute a parent row without holding it in
memory, which is what makes generation memory-flat, parallel and resumable.
"""
from __future__ import annotations

import hashlib

import numpy as np

U64 = np.uint64

# numpy 2.x: np.char.zfill raises on zero-size arrays (chunks routinely have empty subsets) - make it total.
_zfill = np.char.zfill
np.char.zfill = lambda a, w: np.asarray(a) if np.asarray(a).size == 0 else _zfill(a, w)
_G = U64(0x9E3779B97F4A7C15)
_M1 = U64(0xBF58476D1CE4E5B9)
_M2 = U64(0x94D049BB133111EB)


def salt_of(name: str) -> np.uint64:
    return U64(int.from_bytes(hashlib.blake2b(name.encode(), digest_size=8).digest(), "little"))


def mix(x) -> np.ndarray:
    """splitmix64 finaliser; a bijection on uint64."""
    x = np.asarray(x).astype(np.uint64, copy=True)
    with np.errstate(over="ignore"):
        x += _G
        x = (x ^ (x >> U64(30))) * _M1
        x = (x ^ (x >> U64(27))) * _M2
        return x ^ (x >> U64(31))


def h(key, salt: str, seed: int = 0) -> np.ndarray:
    return mix(mix(key) ^ salt_of(salt) ^ mix(np.uint64(seed)))


def uni(key, salt: str, seed: int = 0) -> np.ndarray:
    """Uniform float64 in [0,1) as a pure function of key."""
    return (h(key, salt, seed) >> U64(11)).astype(np.float64) * (1.0 / (1 << 53))


def rint(key, salt: str, lo: int, hi: int, seed: int = 0) -> np.ndarray:
    """Integer in [lo, hi) as a pure function of key."""
    return lo + (h(key, salt, seed) % U64(max(hi - lo, 1))).astype(np.int64)


# ------------------------------------------------------------------ UUIDs
_HEX = np.frombuffer(b"0123456789abcdef", dtype=np.uint8)
_HEXPOS = np.array(list(range(0, 8)) + list(range(9, 13)) + list(range(14, 18)) +
                   list(range(19, 23)) + list(range(24, 36)))
_DASH = (8, 13, 18, 23)


class Uid:
    __slots__ = ("hi", "lo")

    def __init__(self, hi, lo):
        self.hi, self.lo = hi, lo

    def s(self) -> np.ndarray:
        """Vectorised canonical text form -> dtype S36."""
        n = self.hi.shape[0]
        raw = np.empty((n, 2), dtype=">u8")
        raw[:, 0], raw[:, 1] = self.hi, self.lo
        b = raw.view(np.uint8).reshape(n, 16)
        out = np.empty((n, 36), dtype=np.uint8)
        out[:, _HEXPOS[0::2]] = _HEX[b >> 4]
        out[:, _HEXPOS[1::2]] = _HEX[b & 15]
        for d in _DASH:
            out[:, d] = 45
        return np.ascontiguousarray(out).view("S36").reshape(n)

    def __len__(self):
        return self.hi.shape[0]


def uid(kind: str, idx, seed: int = 0) -> Uid:
    """UUID for entity `kind` #idx.  lo is a bijection of idx, so ids are unique per kind."""
    idx = np.asarray(idx).astype(np.uint64)
    off = salt_of(f"uid:{kind}") ^ mix(np.uint64(seed))
    with np.errstate(over="ignore"):
        lo = mix(idx + off)
    hi = mix(lo ^ salt_of("hi:" + kind))
    hi = (hi & U64(0xFFFFFFFFFFFF0FFF)) | U64(0x4000)  # version nibble 4
    return Uid(hi, lo)


def uid_str(kind: str, idx: int, seed: int = 0) -> str:
    return uid(kind, np.array([idx]), seed).s()[0].decode()


_B36 = np.frombuffer(b"0123456789abcdefghijklmnopqrstuvwxyz", dtype=np.uint8)


def base36(a, width: int) -> np.ndarray:
    a = np.asarray(a).astype(np.int64)
    out = np.empty((a.shape[0], width), dtype=np.uint8)
    for i in range(width - 1, -1, -1):
        out[:, i] = _B36[a % 36]
        a = a // 36
    return np.ascontiguousarray(out).view(f"S{width}").reshape(-1)


_B32 = np.frombuffer(b"ABCDEFGHJKLMNPQRSTUVWXYZ23456789", dtype=np.uint8)


def b32code(a, width: int) -> np.ndarray:
    a = np.asarray(a).astype(np.uint64)
    out = np.empty((a.shape[0], width), dtype=np.uint8)
    for i in range(width - 1, -1, -1):
        out[:, i] = _B32[(a & U64(31)).astype(np.int64)]
        a = a >> U64(5)
    return np.ascontiguousarray(out).view(f"S{width}").reshape(-1)


# ------------------------------------------------------------------ COPY text encoders
NULL = b"\\N"


def _s(a) -> np.ndarray:
    a = np.asarray(a)
    if a.dtype.kind == "S":
        return a
    if a.dtype.kind == "U":
        return np.char.encode(a, "utf-8")
    if a.dtype.kind == "O":
        return np.array([x if isinstance(x, bytes) else str(x).encode() for x in a], dtype="S")
    return a.astype("S")


def e_int(a) -> np.ndarray:
    return np.asarray(a).astype(np.int64).astype("S")


def e_bool(mask) -> np.ndarray:
    return np.where(np.asarray(mask, dtype=bool), b"t", b"f")


def e_money(minor, scale: int = 2) -> np.ndarray:
    """int64 minor units -> 'q.rr' text (handles negatives)."""
    m = np.asarray(minor).astype(np.int64)
    neg = m < 0
    a = np.abs(m)
    p = 10 ** scale
    q = (a // p).astype("S")
    r = np.char.zfill((a % p).astype("S"), scale)
    out = np.char.add(np.char.add(q, b"."), r)
    return np.where(neg, np.char.add(b"-", out), out)


def e_ts(epoch) -> np.ndarray:
    d = np.asarray(epoch).astype(np.int64).astype("datetime64[s]")
    return np.char.add(np.datetime_as_string(d, unit="s").astype("S"), b"+00")


def e_date(epoch) -> np.ndarray:
    d = np.asarray(epoch).astype(np.int64).astype("datetime64[s]").astype("datetime64[D]")
    return np.datetime_as_string(d).astype("S")


def e_json_dist(c1, c2, c3, c4, c5) -> np.ndarray:
    out = b'{"1": '
    arr = np.char.add(out, np.asarray(c1).astype("S"))
    for k, c in ((2, c2), (3, c3), (4, c4), (5, c5)):
        arr = np.char.add(np.char.add(arr, f', "{k}": '.encode()), np.asarray(c).astype("S"))
    return np.char.add(arr, b"}")


def nul(arr, mask) -> np.ndarray:
    """Replace entries where mask is True with the COPY NULL marker."""
    arr = _s(arr)
    return np.where(np.asarray(mask, dtype=bool), np.array(NULL, dtype="S2"), arr)


def cat(*parts) -> np.ndarray:
    out = _s(parts[0])
    for p in parts[1:]:
        out = np.char.add(out, _s(p))
    return out


def pick(pool: np.ndarray, key_or_idx, salt: str | None = None, seed: int = 0) -> np.ndarray:
    """Pick from a bytes pool by hashed key (salt given) or by explicit integer index."""
    if salt is None:
        return pool[np.asarray(key_or_idx) % len(pool)]
    return pool[(h(key_or_idx, salt, seed) % U64(len(pool))).astype(np.int64)]


def rows_bytes(cols: list[np.ndarray]) -> bytes:
    lists = [_s(c).tolist() for c in cols]
    return b"\n".join(b"\t".join(r) for r in zip(*lists)) + b"\n"


def group_expand(counts: np.ndarray):
    """For per-group counts -> (owner index per child, ordinal within group)."""
    counts = np.asarray(counts).astype(np.int64)
    owner = np.repeat(np.arange(counts.size), counts)
    start = np.cumsum(counts) - counts
    ordinal = np.arange(owner.size) - np.repeat(start, counts)
    return owner, ordinal


def first_of_key(key: np.ndarray) -> np.ndarray:
    """Boolean mask: True for the first occurrence of each key (order preserved)."""
    _, idx = np.unique(key, return_index=True)
    m = np.zeros(key.shape[0], dtype=bool)
    m[idx] = True
    return m
