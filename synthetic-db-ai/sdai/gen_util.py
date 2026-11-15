from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np

from .rand import e_money, e_int, nul

B64 = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
HEX = b"0123456789abcdef"
ALNUM = b"abcdefghijklmnopqrstuvwxyz0123456789"
STAFF = 20          # users [0, STAFF) are staff accounts (admin / catalog / support / finance)


@dataclass
class Ctx:
    w: object                       # World
    voc: object                     # Vocab
    rng: np.random.Generator
    chunk: int = 0
    serial_base: dict = field(default_factory=dict)
    tally: dict = field(default_factory=dict)
    agg: dict = field(default_factory=dict)         # exact aggregates from the customer stage (catalog stage input)
    facts: dict = field(default_factory=dict)

    def tally_add(self, name: str, shape: tuple, idx: tuple, val=1, dtype=np.int64):
        if name not in self.tally:
            self.tally[name] = np.zeros(shape, dtype=dtype)
        np.add.at(self.tally[name], idx, val)


def rchars(rng, alphabet: bytes, n: int, L: int) -> np.ndarray:
    a = np.frombuffer(alphabet, dtype=np.uint8)
    out = np.ascontiguousarray(a[rng.integers(0, len(a), (n, L))])
    return out.view(f"S{L}").reshape(n)


def ts_between(rng, lo, hi) -> np.ndarray:
    lo = np.asarray(lo, dtype=np.int64)
    hi = np.maximum(np.asarray(hi, dtype=np.int64), lo)
    return lo + ((hi - lo) * rng.random(lo.shape)).astype(np.int64)


def m4(cents) -> np.ndarray:        # numeric(19,4) from integer cents
    return e_money(np.asarray(cents, dtype=np.int64) * 100, 4)


def m2(cents) -> np.ndarray:        # numeric(x,2)
    return e_money(cents, 2)


def zeros(n, dtype=np.int64):
    return np.zeros(n, dtype=dtype)


def ipv4(rng, n):
    a = rng.integers(1, 255, (n, 4))
    return np.array([b".".join(str(int(x)).encode() for x in r) for r in a], dtype="S15")


def group_cumsum(values: np.ndarray, group_id: np.ndarray) -> np.ndarray:
    """Running sum restarting per group (rows must be grouped contiguously by group_id)."""
    if values.size == 0:
        return values
    cs = np.cumsum(values)
    first = np.r_[True, group_id[1:] != group_id[:-1]]
    start = np.flatnonzero(first)
    base = np.repeat(np.r_[0, cs[start[1:] - 1]], np.diff(np.r_[start, values.size]))
    return cs - base


def next_idx(ctx, n: int) -> np.ndarray:
    """Chunk-unique index range for entities without a natural key (history rows, audit...)."""
    base = ctx.chunk * (1 << 26) + getattr(ctx, "_ctr", 0)
    ctx._ctr = getattr(ctx, "_ctr", 0) + n
    return np.arange(base, base + n, dtype=np.int64)


def serial_ids(ctx, table: str, n: int) -> np.ndarray:
    """Explicit values for bigserial PKs: collision-free per chunk (2^24 ids reserved per chunk)."""
    base = ctx.serial_base.get(table, 1) + ctx.chunk * (1 << 24) + getattr(ctx, "_ser_" + table, 0)
    setattr(ctx, "_ser_" + table, getattr(ctx, "_ser_" + table, 0) + n)
    return np.arange(base, base + n, dtype=np.int64)
