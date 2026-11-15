"""The synthetic world: sizes and the pure, index-addressable entity functions.

Every attribute of user #i, product #p or seller #s is a deterministic function of
(seed, index) -> any worker can recompute it, so child rows (orders, reviews, stock...) always
agree with parent rows in other microservice databases without a shared in-memory lookup.
"""
from __future__ import annotations

from dataclasses import dataclass, asdict

import numpy as np

from . import vocab as V
from .rand import (b32code, base36, cat, e_int, h, pick, rint, uid, uni, Uid, _s)

DAY = 86400
ACME_SELLER = "00000000-0000-0000-0000-00000000a11e"   # seeded platform seller (V1__seller_baseline)
ACME_WH = [("eeeeeeee-0000-0000-0000-0000000000b1", "WH-BLR", "560001", "KA"),
           ("eeeeeeee-0000-0000-0000-0000000000d1", "WH-DEL", "110001", "DL"),
           ("eeeeeeee-0000-0000-0000-0000000000e1", "WH-MUM", "400001", "MH")]
FX = {"USD": 1.0, "INR": 83.2, "EUR": 0.92}          # V8__fx_rates seed (base USD)


@dataclass
class World:
    seed: int
    as_of: int
    U: int
    orders_mean: float = 5.5
    S: int = 0
    P: int = 0
    n_coupons: int = 150
    n_deals: int = 0
    n_programs: int = 24

    def __post_init__(self):
        self.S = self.S or max(10, self.U // 100)
        self.P = self.P or max(300, int(self.U * 0.30))
        self.n_deals = self.n_deals or min(3000, self.P // 40 + 8)

    @property
    def T0(self) -> int:
        return self.as_of - 3 * 365 * DAY

    @property
    def S_ok(self) -> int:           # approved sellers (the rest are DRAFT/SUBMITTED/REJECTED/SUSPENDED)
        return max(6, int(self.S * 0.93))

    def dict(self):
        return asdict(self)


def uid_s(w: World, kind: str, idx) -> np.ndarray:
    return uid(kind, idx, w.seed).s()


def uid_of(w: World, kind: str, idx) -> Uid:
    return uid(kind, idx, w.seed)


def _rate(codes: np.ndarray) -> np.ndarray:
    out = np.ones(codes.shape[0])
    for k, r in FX.items():
        out[codes == k.encode()] = r
    return out


def convert_cents(cents_usd: np.ndarray, dst_codes: np.ndarray) -> np.ndarray:
    return np.floor(cents_usd * _rate(dst_codes) + 0.5).astype(np.int64)


# ---------------------------------------------------------------- users
_C_IDX = {c: i for i, c in enumerate(V.COUNTRIES)}
_CITY_BY_C = {c: np.array([i for i, x in enumerate(V.CITIES) if x[3] == c]) for c in V.COUNTRIES}
_PIN_LEN = {"IN": 6, "US": 5, "GB": 0, "DE": 5, "FR": 5, "AE": 5, "SG": 6, "AU": 4}


def _pin(prefix: np.ndarray, country: str, key, salt) -> np.ndarray:
    L = _PIN_LEN[country]
    if L == 0:
        return cat(prefix, e_int(rint(key, salt + "a", 1, 9)), b" ", e_int(rint(key, salt + "b", 1, 9)), b"AA")
    plen = np.char.str_len(prefix)
    pad = L - int(plen[0])
    num = rint(key, salt, 0, 10 ** pad)
    return cat(prefix, np.char.zfill(num.astype("S"), pad))


def address_fields(w: World, voc, key: np.ndarray, country_idx: np.ndarray, salt: str = "addr") -> dict:
    """city/state/pin/country/street for a population, as bytes arrays."""
    n = key.shape[0]
    city = np.empty(n, dtype="S24"); state = np.empty(n, dtype="S24"); pin = np.empty(n, dtype="S10")
    ctry = np.empty(n, dtype="S2")
    for c, ci in _C_IDX.items():
        m = country_idx == ci
        if not m.any():
            continue
        sel = _CITY_BY_C[c]
        ch = sel[(h(key[m], salt + "c", w.seed) % np.uint64(len(sel))).astype(np.int64)]
        cities = np.array(V.CITIES, dtype=object)[ch]
        city[m] = np.array([x[0].encode() for x in cities], dtype="S24")
        state[m] = np.array([x[1].encode() for x in cities], dtype="S24")
        pref = np.array([x[2].encode() for x in cities], dtype="S4")
        pin[m] = _pin(pref, c, key[m], salt + "p")
        ctry[m] = c.encode()
    num = rint(key, salt + "n", 1, 999)
    line1 = cat(e_int(num), b" ", pick(voc["streets"], key, salt + "s", w.seed), np.where(rint(key, salt + "t", 0, 2) == 0, b" Road", b" Street"))
    line2 = cat(b"Apartment ", e_int(rint(key, salt + "u", 1, 40)), b", Block ", pick(np.array([b"A", b"B", b"C", b"D"]), key, salt + "v", w.seed))
    return dict(city=city, state=state, pin=pin, country=ctry, line1=line1, line2=line2)


def users(w: World, voc, u: np.ndarray) -> dict:
    u = u.astype(np.int64)
    cidx = np.searchsorted(np.cumsum(V.COUNTRY_W), uni(u, "u.country", w.seed) * 0.999999)
    span = w.as_of - w.T0 - 2 * DAY
    signup = w.T0 + (span * uni(u, "u.signup", w.seed) ** 0.75).astype(np.int64)
    n = u.shape[0]
    first = np.empty(n, dtype="S24"); last = np.empty(n, dtype="S24"); cur = np.empty(n, dtype="S3")
    phone = np.empty(n, dtype="S16")
    for c, ci in _C_IDX.items():
        m = cidx == ci
        if not m.any():
            continue
        first[m] = pick(voc[f"first_{c}"], u[m], "u.first", w.seed)
        last[m] = pick(voc[f"last_{c}"], u[m], "u.last", w.seed)
        cur[m] = V.CURRENCY[c].encode()
        off = (w.seed % 1024) * 2_000_000            # disjoint index band per seed -> unique across runs too
        d10 = 7_000_000_000 + ((u[m] + off) * 2654435761) % (1 << 31)   # bijective -> unique phone numbers
        phone[m] = cat(b"+91" if c == "IN" else b"+1", d10.astype("S"))
    tag = base36(np.full(1, w.seed % 46656), 3)[0]
    local = cat(np.char.lower(first), b".", np.char.lower(last), b".", base36(u, 7), b".", tag)
    email = cat(local, b"@", pick(voc["domains"], u, "u.dom", w.seed))
    addr = address_fields(w, voc, u * 4, cidx)      # address #0 of the user (see users.addresses)
    return dict(idx=u, signup=signup, country_idx=cidx, first=first, last=last, name=cat(first, b" ", last), cur=cur,
                phone=phone, email=email, **{f"a_{k}": v for k, v in addr.items()})


# ---------------------------------------------------------------- sellers / warehouses
def seller_code_to_uid_s(w: World, sc: np.ndarray) -> np.ndarray:
    """seller code (0..S-1 generated, S = seeded ACME platform seller) -> uuid text."""
    out = uid_s(w, "seller", np.where(sc >= w.S, 0, sc))
    return np.where(sc >= w.S, np.array(ACME_SELLER.encode(), dtype="S36"), out)


def sellers(w: World, voc, s: np.ndarray) -> dict:
    s = s.astype(np.int64)
    comp = pick(voc["companies"], s, "s.name", w.seed)
    status_pool = np.array([b"SUBMITTED", b"REJECTED", b"SUSPENDED", b"DRAFT"])
    status = np.where(s < w.S_ok, b"APPROVED", pick(status_pool, s, "s.status", w.seed))
    comm = (8 + rint(s, "s.comm", 0, 15) * 0.5)
    state_i = rint(s, "s.city", 0, 18)
    return dict(idx=s, name=cat(comp, b" ", pick(np.array([b"Traders", b"Retail", b"Enterprises", b"Stores", b"Mart"]), s, "s.sfx", w.seed)),
                legal=cat(comp, b" Pvt Ltd"), status=status, commission=comm, city=state_i)


def warehouses_of(w: World, voc) -> dict:
    """All generated seller warehouses (the 3 ACME ones are seeded by the migration)."""
    ids = np.arange(w.S * 4, dtype=np.int64)
    ids = ids[(ids % 4) < n_wh(w, ids // 4)]
    return wh_attrs(w, voc, ids)


# ---------------------------------------------------------------- products
_SUB_NOUNS = [np.array([n.encode() for n in x[2]]) for x in V.SUBS]
_SUB_MED = np.array([x[3] for x in V.SUBS], dtype=float)
_SUB_ROOT = np.array([x[0] for x in V.SUBS])


def n_avail(w: World, t) -> np.ndarray:
    """How many products (a prefix of the index space) exist at time t."""
    span0 = w.as_of - w.T0 - 14 * DAY
    f = np.clip((np.asarray(t, dtype=float) - w.T0) / span0, 0, 1)
    return np.maximum((w.P * f ** 1.25).astype(np.int64), max(5, int(w.P * 0.05)))


def product_created(w: World, p: np.ndarray) -> np.ndarray:
    span0 = w.as_of - w.T0 - 14 * DAY
    f = (np.asarray(p, dtype=float) / w.P) ** 0.8
    t = w.T0 + (span0 * f).astype(np.int64)
    early = p < max(5, int(w.P * 0.05))
    return np.where(early, w.T0, t)


def _gauss(key, salt, seed) -> np.ndarray:
    u1 = np.maximum(uni(key, salt + "1", seed), 1e-12)
    u2 = uni(key, salt + "2", seed)
    return np.sqrt(-2 * np.log(u1)) * np.cos(2 * np.pi * u2)


def categories(w: World) -> dict:
    """Static category tree: roots then subs."""
    n = N = V.N_ROOT + V.N_SUB
    idx = np.arange(N)
    parent = np.where(idx < V.N_ROOT, -1, _SUB_ROOT[np.maximum(idx - V.N_ROOT, 0)])
    name = [V.ROOTS[i] if i < V.N_ROOT else V.SUBS[i - V.N_ROOT][1] for i in range(N)]
    return dict(idx=idx, parent=parent, name=name)


def products(w: World, voc, p: np.ndarray) -> dict:
    p = p.astype(np.int64)
    sub = rint(p, "p.sub", 0, V.N_SUB, w.seed)
    root = _SUB_ROOT[sub]
    brand = pick(voc["brands"], p, "p.brand", w.seed)
    noun = np.empty(p.shape[0], dtype="S24")
    for i in np.unique(sub):
        m = sub == i
        noun[m] = pick(_SUB_NOUNS[i], p[m], "p.noun", w.seed)
    name = cat(brand, b" ", noun, b" ", pick(voc["modifiers"], p, "p.mod", w.seed))
    sku = cat(b"SKU-", np.char.upper(np.array([x[:3] for x in brand], dtype="S3")), b"-", np.char.upper(base36(p, 7)), np.char.upper(seed_tag(w)))
    med = _SUB_MED[sub]
    x = med * np.exp(0.55 * _gauss(p, "p.price", w.seed))
    cents = np.where(x < 200, np.floor(x) * 100 + 99, np.floor(x / 5 + 0.5) * 500).astype(np.int64)
    cents = np.maximum(cents, 199)
    disc = np.where(uni(p, "p.hasdisc", w.seed) < 0.4, rint(p, "p.disc", 5, 41, w.seed), 0)
    sale = np.where(disc > 0, (cents * (100 - disc) // 100) // 1, -1)
    sale = np.where(disc > 0, np.maximum(sale // 100 * 100 + 99, 99), -1)
    sale = np.where(sale >= cents, cents - 100, sale)
    sale = np.where(disc > 0, np.maximum(sale, 99), -1)
    s0 = rint(p, "p.seller", 0, w.S_ok, w.seed)
    plat = uni(p, "p.plat", w.seed) < 0.10
    seller = np.where(plat, w.S, s0)                         # code S == seeded ACME platform seller
    nvar = np.where(uni(p, "p.hasvar", w.seed) < 0.45, rint(p, "p.nvar", 2, 7, w.seed), 0)
    noff = 1 + np.where(uni(p, "p.xoff", w.seed) < 0.30, rint(p, "p.noff", 1, 3, w.seed), 0)
    q = np.clip(3.9 + 0.55 * _gauss(p, "p.q", w.seed), 2.2, 4.9)    # latent quality drives review stars
    pop = uni(p, "p.rev", w.seed)
    return dict(idx=p, sub=sub, root=root, brand=brand, noun=noun, name=name, sku=sku, cents=cents, sale=sale, disc=disc,
                seller=seller, nvar=nvar, noff=noff, quality=q, created=product_created(w, p),
                weight=rint(p, "p.wt", 50, 6000, w.seed), color=pick(voc["colors"], p, "p.col", w.seed),
                material=pick(voc["materials"], p, "p.mat", w.seed), review_pop=pop)


def eff_cents(prod: dict) -> np.ndarray:
    """Price a buyer pays (USD cents): sale price if discounted else list."""
    return np.where(prod["sale"] > 0, prod["sale"], prod["cents"])


def offer_seller(w: World, p: np.ndarray, base_seller: np.ndarray, j: np.ndarray) -> np.ndarray:
    a = rint(p, "o.stride", 0, max(2, w.S_ok // 3), w.seed)
    s0 = rint(p, "p.seller", 0, w.S_ok, w.seed)
    ex = (s0 + j * (1 + a)) % w.S_ok
    return np.where(j == 0, base_seller, ex)


def offer_cents(w: World, prod: dict, j: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """(price, sale) cents in USD for offer j of the product (j==0 = the listing seller's)."""
    p = prod["idx"]
    f = 1.0 + np.where(j == 0, 0.0, (rint(p * 4 + j, "o.pm", -5, 9, w.seed)) / 100.0)
    price = np.floor(prod["cents"] * f / 100) * 100 + 99
    sale = np.where(prod["sale"] > 0, np.floor(prod["sale"] * f / 100) * 100 + 99, -1)
    sale = np.where((sale >= price), price - 100, sale)
    return price.astype(np.int64), np.where(prod["sale"] > 0, np.maximum(sale, 99), -1).astype(np.int64)


def variant_label(w: World, voc, p: np.ndarray, v: np.ndarray, root: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """(human label, JSON attributes) for variant v of product p, shaped by the product's root category."""
    key = p * 8 + v
    color = pick(voc["colors"], key, "v.col", w.seed)
    size = pick(np.array([x.encode() for x in V.SIZES]), v, None)
    stor = pick(np.array([x.encode() for x in V.STORAGES]), v, None)
    fashion = root == 3
    tech = (root == 0) | (root == 1)
    label = np.where(fashion, cat(b"Size: ", size, b", Colour: ", color),
                     np.where(tech, cat(b"Storage: ", stor, b", Colour: ", color), cat(b"Colour: ", color)))
    js = np.where(fashion, cat(b'{"size": "', size, b'", "colour": "', color, b'"}'),
                  np.where(tech, cat(b'{"storage": "', stor, b'", "colour": "', color, b'"}'), cat(b'{"colour": "', color, b'"}')))
    return label, js


def image_url(w: World, p: np.ndarray, k: int | np.ndarray = 0) -> np.ndarray:
    return cat(b"https://cdn.shop.example.com/p/", base36(p, 7), b"/", e_int(np.asarray(k) + np.zeros(p.shape[0], dtype=np.int64)), b".jpg")


def wh_attrs(w: World, voc, ids: np.ndarray) -> dict:
    ids = ids.astype(np.int64)
    s = ids // 4
    ci = rint(ids, "wh.city", 0, 18, w.seed)           # Indian cities only (first 18 entries)
    cities = np.array(V.CITIES, dtype=object)[ci]
    city = np.array([x[0].encode() for x in cities], dtype="S24")
    state = np.array([x[1].encode() for x in cities], dtype="S24")
    pref = np.array([x[2].encode() for x in cities], dtype="S4")
    pin = cat(pref, np.char.zfill(rint(ids, "wh.pin", 1, 999, w.seed).astype("S"), 3))
    code = cat(b"WH-", np.char.upper(np.array([x[:3] for x in city], dtype="S3")), b"-", np.char.upper(base36(ids, 5)), np.char.upper(seed_tag(w)))
    return dict(idx=ids, seller=s, city=city, state=state, pin=pin, code=code)


def n_wh(w: World, s: np.ndarray) -> np.ndarray:
    return 1 + rint(s, "wh.n", 0, 3, w.seed)


# ---------------------------------------------------------------- coupons / deals (static, index-addressable)
def coupons(w: World, c: np.ndarray) -> dict:
    c = c.astype(np.int64)
    flat = uni(c, "c.type", w.seed) < 0.4
    cur_i = rint(c, "c.cur", 0, 3, w.seed)
    cur = np.array([b"INR", b"USD", b"EUR"])[cur_i]
    pct = rint(c, "c.pct", 5, 31, w.seed)
    flat_v = np.where(cur_i == 0, rint(c, "c.fv", 5, 51, w.seed) * 10, rint(c, "c.fv", 2, 26, w.seed))   # whole units
    value = np.where(flat, flat_v, pct)
    cap = np.where(~flat & (uni(c, "c.cap", w.seed) < 0.7), np.where(cur_i == 0, 200 * rint(c, "c.capv", 1, 6, w.seed), rint(c, "c.capv", 3, 30, w.seed) * 5), -1)
    minord = np.where(uni(c, "c.min", w.seed) < 0.7, np.where(cur_i == 0, rint(c, "c.minv", 3, 40, w.seed) * 100, rint(c, "c.minv", 2, 20, w.seed) * 10), 0)
    starts = w.T0 + (uni(c, "c.start", w.seed) * (w.as_of - w.T0) * 0.2).astype(np.int64)
    ends = np.where(uni(c, "c.end", w.seed) < 0.3, w.as_of + rint(c, "c.endv", 10, 200, w.seed) * DAY, -1)
    active = uni(c, "c.act", w.seed) > 0.05
    first = uni(c, "c.first", w.seed) < 0.12
    word = pick(np.array([b"SAVE", b"FESTIVE", b"WELCOME", b"MEGA", b"SUPER", b"FLASH", b"BIG", b"EXTRA", b"JOY", b"DEAL"]), c, "c.word", w.seed)
    code = cat(word, e_int(value), b"-", np.char.upper(base36(c, 4)), np.char.upper(seed_tag(w)))
    return dict(idx=c, flat=flat, cur=cur, cur_i=cur_i, value=value, cap=cap, minord=minord, starts=starts, ends=ends,
                active=active, first=first, code=code, total_limit=np.where(uni(c, "c.tl", w.seed) < 0.3, 50_000_000, -1),
                per_user=np.where(uni(c, "c.pu", w.seed) < 0.5, rint(c, "c.puv", 1, 4, w.seed), -1))


def deals(w: World, voc, d: np.ndarray) -> dict:
    """Deals attach to hashed existing products during a window inside the last year."""
    d = d.astype(np.int64)
    prod = rint(d, "d.prod", 0, max(1, w.P // 2), w.seed)
    start = w.as_of - rint(d, "d.start", 2, 330, w.seed) * DAY
    dur = rint(d, "d.dur", 1, 6, w.seed) * DAY
    pr = products(w, voc, prod)
    return dict(idx=d, prod=prod, start=start, end=start + dur, prices=pr)


def deal_price_usd(w: World, dl: dict) -> np.ndarray:
    eff = eff_cents(dl["prices"])
    frac = rint(dl["idx"], "d.frac", 55, 86, w.seed) / 100.0
    v = np.floor(eff * frac / 100) * 100 + 99
    return np.minimum(v, eff - 1).astype(np.int64)


def seed_tag(w: World) -> bytes:
    """2-char tag that keeps natural unique keys (sku, slug, codes) distinct between runs with different seeds."""
    return base36(np.array([w.seed % 1296]), 2)[0]
