"""order_db rows (orders, shipments, items, history, returns, subscriptions) + the order simulation.

All money is kept as integer minor units (cents) of the order currency; the formulas are the ones in
order-service PricingService (subtotal - discount - coins + shipping; INR GST 18 % inclusive, CGST+SGST
inside Karnataka else IGST; free-shipping thresholds 499 INR / 25 other, flat fee 40 / 4.99) and
promotion-service CoinService (1 coin per 100 INR or per 1 other unit; members earn 2x).
"""
from __future__ import annotations

from types import SimpleNamespace

import numpy as np

from . import world as W
from .gen_browse import line_info
from .gen_util import next_idx, m4, rchars, ts_between
from .rand import (base36, cat, e_bool, e_date, e_int, e_ts, first_of_key, group_expand, nul, pick, rint, uni)

INF = 1 << 60
STATUS = [b"PLACED", b"CONFIRMED", b"PACKED", b"SHIPPED", b"OUT_FOR_DELIVERY", b"DELIVERED"]
HIST_NOTE = [b"Order placed", b"Order confirmed", b"Packed at warehouse", b"Handed over to carrier", b"Out for delivery", b"Delivered"]
HIST_ACTOR = [b"SYSTEM", b"SYSTEM", b"SELLER", b"SELLER", b"SYSTEM", b"SYSTEM"]
RETURN_REASONS = [b"DAMAGED", b"WRONG_ITEM", b"NOT_AS_DESCRIBED", b"NO_LONGER_NEEDED", b"OTHER"]
NUL = b"\\N"


def build_orders(ctx, u, usr, uid_u) -> SimpleNamespace:
    w, voc, rng, F = ctx.w, ctx.voc, ctx.rng, ctx.facts
    n, as_of = u.size, w.as_of
    signup, cur = usr["signup"], usr["cur"]
    o = SimpleNamespace(u=u, usr=usr, uid_u=uid_u, n=n)
    mu = w.orders_mean * (0.4 + 1.6 * uni(u, "u.mu", w.seed) ** 3)
    n_ord = np.where(uni(u, "u.zero", w.seed) < 0.12, 0, np.minimum((-np.log1p(-rng.random(n)) * mu).astype(np.int64), 47))
    oo, oj = group_expand(n_ord)
    N = oo.size
    o.N = N
    if N == 0:
        return o
    placed = signup[oo] + 3600 + (rng.random(N) * np.maximum(as_of - signup[oo] - 7200, 1)).astype(np.int64)
    placed = placed[np.lexsort((placed, oo))]
    ok = u[oo] * 64 + oj
    o.oo, o.oj, o.placed, o.ok = oo, oj, placed, ok
    o.oid = W.uid_s(w, "order", ok)
    o.cur = cur[oo]
    inr = o.cur == b"INR"
    o.inr = inr
    o.country = usr["country_idx"][oo]
    ak = rint(ok, "o.addr", 0, 1 << 30, w.seed) % F["n_addr"][oo]
    akey = u[oo] * 4 + ak
    o.af = W.address_fields(w, voc, akey, o.country)
    o.addr_id = W.uid_s(w, "addr", akey)
    o.ship_name = np.where(ak == 0, usr["name"][oo], pick(voc["first_IN"], akey, "a.rn", w.seed))
    # ----------------------------------------------------------- items
    ni = np.minimum(rng.geometric(0.45, N), 6)
    io, il = group_expand(ni)
    t = placed[io]
    navail = W.n_avail(w, t)
    p = np.minimum((navail * rng.random(io.size) ** 1.8).astype(np.int64), navail - 1)
    keep = first_of_key(io * w.P + p)
    io, il, p, t = io[keep], il[keep], p[keep], t[keep]
    M = io.size
    li = line_info(w, voc, rng, p, o.cur[io])
    qty = rng.choice([1, 2, 3, 4, 5], M, p=[0.7, 0.18, 0.07, 0.03, 0.02])
    unit = li["cents"].copy()
    dl = W.deals(w, voc, np.arange(w.n_deals))
    dprod, di = np.unique(dl["prod"], return_index=True)
    pos = np.minimum(np.searchsorted(dprod, p), dprod.size - 1)
    hit = dprod[pos] == p
    d_i = di[pos]
    in_win = hit & (t >= dl["start"][d_i]) & (t <= dl["end"][d_i]) & (rng.random(M) < 0.35)
    deal_usd = W.deal_price_usd(w, dl)[d_i]
    unit = np.where(in_win, W.convert_cents(deal_usd, o.cur[io]), unit)
    qty = np.where(in_win, np.minimum(qty, 2), qty)
    o.deal_idx = np.where(in_win, d_i, -1)
    o.io, o.il, o.p, o.li, o.qty, o.unit, o.M = io, il, p, li, qty, unit, M
    o.line = unit * qty
    # ----------------------------------------------------------- shipments (one per order x seller)
    sc = li["seller_code"]
    skey = io * (w.S + 2) + sc
    uniq, sinv = np.unique(skey, return_inverse=True)
    K = uniq.size
    o.K, o.sinv = K, sinv
    sh_order = uniq // (w.S + 2)
    sh_seller = uniq % (w.S + 2)
    sh_rank = np.arange(K) - np.searchsorted(sh_order, sh_order)
    o.sh_order, o.sh_seller, o.sh_rank = sh_order, sh_seller, sh_rank
    o.sh_first = np.searchsorted(sh_order, np.arange(N))
    o.ship_id = W.uid_s(w, "ship", ok[sh_order] * 8 + sh_rank)
    o.sh_amount = np.bincount(sinv, weights=o.line, minlength=K).astype(np.int64)
    acme = sh_seller >= w.S
    wi = rint(uniq, "sh.wh", 0, 3, w.seed)
    ssel = np.minimum(sh_seller, w.S - 1)
    wi_real = rint(uniq, "sh.wh2", 0, 1 << 30, w.seed) % W.n_wh(w, ssel)
    wh = W.wh_attrs(w, voc, ssel * 4 + wi_real)
    acme_id = np.array([x[0].encode() for x in W.ACME_WH], dtype="S36")
    acme_code = np.array([x[1].encode() for x in W.ACME_WH], dtype="S8")
    acme_pin = np.array([x[2].encode() for x in W.ACME_WH], dtype="S6")
    o.wh_id = np.where(acme, acme_id[wi], W.uid_s(w, "wh", ssel * 4 + wi_real))
    o.wh_code = np.where(acme, acme_code[wi], wh["code"])
    o.wh_pin = np.where(acme, acme_pin[wi], wh["pin"])
    sellers = W.sellers(w, voc, ssel)
    o.sh_seller_s = W.seller_code_to_uid_s(w, sh_seller)
    o.sh_seller_name = np.where(acme, b"Acme Retail", sellers["name"])
    o.sh_commission = np.where(acme, 0.0, sellers["commission"])
    ship_pin = o.af["pin"][sh_order]
    in_ind = o.country[sh_order] == 0
    days = np.where(~in_ind, 7, np.where(o.wh_pin.astype("S3") == ship_pin.astype("S3"), 2, np.where(o.wh_pin.astype("S1") == ship_pin.astype("S1"), 3, 5)))
    o.days = days
    tp = placed[sh_order]
    conf = tp + rng.integers(120, 1800, K)
    packed = conf + rng.integers(3600, 64800, K)
    shipped = packed + rng.integers(7200, 108000, K)
    late = rng.random(K) < 0.12
    transit = (days * 86400 * rng.uniform(0.45, 0.95, K)).astype(np.int64) + late * rng.integers(1, 4, K) * 86400
    ofd = shipped + transit
    dlv_ts = ofd + rng.integers(7200, 36000, K)
    MS = np.stack([tp, conf, packed, shipped, ofd, dlv_ts], axis=1)
    hold = (placed > as_of - 2 * 86400) & (rng.random(N) < 0.005)      # risk review hold: nothing moves
    o.hold = hold
    MS[hold[sh_order], 1:] = INF
    min_shipped = np.minimum.reduceat(MS[:, 3], o.sh_first)
    cts = placed + rng.integers(180, 20 * 3600, N)
    canc = (rng.random(N) < 0.07) & (cts < min_shipped) & (cts <= as_of) & ~hold
    o.canc, o.cancel_ts = canc, cts
    limit_o = np.where(canc, np.minimum(cts, as_of), as_of)
    s_rank = (MS[:, 1:] <= limit_o[sh_order][:, None]).sum(1)
    Ot = np.stack([np.maximum.reduceat(MS[:, r], o.sh_first) for r in range(6)], axis=1)
    o.o_rank = (Ot[:, 1:] <= limit_o[:, None]).sum(1)
    o.Ot, o.MS, o.s_rank = Ot, MS, s_rank
    o.status = np.where(canc, b"CANCELLED", np.array(STATUS)[o.o_rank])
    o.sh_status = np.where(canc[sh_order], b"CANCELLED", np.array(STATUS)[s_rank])
    o.delivered = (~canc) & (o.o_rank == 5)
    o.delivered_ts = np.where(o.delivered, Ot[:, 5], INF)
    o.sh_delivered = (~canc[sh_order]) & (s_rank == 5)
    o.updated = np.minimum(np.where(canc, cts, np.take_along_axis(Ot, o.o_rank[:, None], 1)[:, 0]), as_of)
    o.member = (F["m_start"][oo] >= 0) & (F["m_start"][oo] <= placed) & (placed < F["m_end"][oo])
    # ----------------------------------------------------------- coupons
    sub = np.bincount(io, weights=o.line, minlength=N).astype(np.int64)
    o.sub = sub
    c = rint(ok, "o.cpn", 0, w.n_coupons, w.seed)
    cp = W.coupons(w, c)
    appl = ((rng.random(N) < 0.22) & cp["active"] & (cp["starts"] <= placed) & ((cp["ends"] < 0) | (cp["ends"] >= placed)) & ((~cp["first"]) | (oj == 0))
            & ((~cp["flat"]) | (cp["cur"] == o.cur)) & (sub >= cp["minord"] * 100) & ~hold)
    ai = np.flatnonzero(appl)
    appl[ai[~first_of_key(oo[ai] * w.n_coupons + c[ai])]] = False   # one redemption per (user, coupon)
    pct_d = sub * cp["value"] // 100
    pct_d = np.where(cp["cap"] > 0, np.minimum(pct_d, cp["cap"] * 100), pct_d)
    disc = np.where(cp["flat"], cp["value"] * 100, pct_d)
    o.cp_applied, o.cp_idx, o.cp = appl, c, cp
    o.disc = np.maximum(np.where(appl, np.minimum(disc, sub - 100), 0), 0)
    after = sub - o.disc
    o.after = after
    # ----------------------------------------------------------- coin- and wallet-aware pricing, sequential per ordinal
    thr = np.where(inr, 49900, 2500)
    fee = np.where(inr, 4000, 499)
    coin_cents = np.where(inr, 100, 1)
    J = int(oj.max()) + 1
    earn = np.zeros((n, J), dtype=np.int64)
    dlv = np.full((n, J), INF, dtype=np.int64)
    red = np.zeros((n, J), dtype=np.int64)
    wdeb = np.zeros((n, J), dtype=np.int64)
    has_w = rng.random(n) < 0.30                                  # wallet users received 1-2 gift cards
    gc_t = np.full((n, 2), INF, dtype=np.int64)
    gc_amt = np.zeros((n, 2), dtype=np.int64)
    for k in range(2):
        m = has_w & ((k == 0) | (rng.random(n) < 0.4))
        gc_t[m, k] = ts_between(rng, signup[m] + 86400, np.full(int(m.sum()), as_of - 86400))
        amts = np.where(cur == b"INR", rng.choice([50000, 100000, 200000, 500000], n), rng.choice([1000, 2500, 5000], n))
        gc_amt[m, k] = amts[m]
    o.gc_t, o.gc_amt = gc_t, gc_amt
    z = lambda: np.zeros(N, dtype=np.int64)
    o.coins, o.coinsv, o.ship, o.total, o.tax, o.cgst, o.sgst, o.igst, o.wallet, o.earn = (z() for _ in range(10))
    o.method = np.full(N, b"CARD", dtype="S6")
    karn = o.af["state"] == b"Karnataka"
    for j in range(J):
        sel = np.flatnonzero(oj == j)
        us = oo[sel]
        bal = ((earn[us, :j] * (dlv[us, :j] <= placed[sel, None])).sum(1) - red[us, :j].sum(1)) if j else np.zeros(sel.size, dtype=np.int64)
        want = (rng.random(sel.size) < 0.15) & (bal >= 50) & ~canc[sel] & ~hold[sel]
        cmax = (after[sel] * 0.25 // coin_cents[sel]).astype(np.int64)
        coins = np.where(want, np.minimum(bal, cmax), 0)
        coinsv = coins * coin_cents[sel]
        ship = np.where(o.member[sel], 0, np.where(after[sel] >= thr[sel], 0, fee[sel]))
        total = after[sel] - coinsv + ship
        tax = np.where(inr[sel], (total * 18 + 59) // 118, 0)
        cg = np.where(inr[sel] & karn[sel], (tax + 1) // 2, 0)
        sg = np.where(inr[sel] & karn[sel], tax - cg, 0)
        ig = np.where(inr[sel] & ~karn[sel], tax, 0)
        avail = (gc_amt[us] * (gc_t[us] < placed[sel, None])).sum(1) - wdeb[us, :j].sum(1)
        cod_ok = (o.country[sel] == 0) & (total <= 5_000_000)
        meth = np.where(cod_ok & (rng.random(sel.size) < 0.42), b"COD", b"CARD").astype("S6")
        wal = (avail >= total) & (rng.random(sel.size) < 0.6) & ~hold[sel]
        meth = np.where(wal, b"WALLET", meth)
        e = np.where(inr[sel], total // 10000, total // 100) * np.where(o.member[sel], 2, 1)
        e = np.where(o.delivered[sel], e, 0)
        earn[us, j], dlv[us, j], red[us, j], wdeb[us, j] = e, o.delivered_ts[sel], coins, np.where(wal, total, 0)
        o.coins[sel], o.coinsv[sel], o.ship[sel], o.total[sel], o.tax[sel] = coins, coinsv, ship, total, tax
        o.cgst[sel], o.sgst[sel], o.igst[sel], o.method[sel], o.wallet[sel], o.earn[sel] = cg, sg, ig, meth, np.where(wal, total, 0), e
    # ----------------------------------------------------------- returns (<=1 per delivered order, from its first shipment)
    rflag = o.delivered & (rng.random(N) < 0.07)
    rcreated = o.delivered_ts + rng.integers(86400, 9 * 86400, N)
    rflag &= rcreated <= as_of
    o.rflag, o.rcreated = rflag, rcreated
    first_item_of_ship = first_of_key(sinv)
    in_ret = rflag[io] & (sinv == o.sh_first[io]) & (first_item_of_ship | (rng.random(M) < 0.6))
    o.in_ret = in_ret
    o.rqty = np.where(in_ret, 1 + (rng.random(M) * qty).astype(np.int64), 0)
    o.ref_amt = np.minimum(np.bincount(io, weights=o.rqty * unit, minlength=N).astype(np.int64), np.maximum(o.total - 1, 0))
    age = as_of - rcreated
    rej = rng.random(N) < 0.10
    o.rstatus = np.where(rej & (age >= 86400), b"REJECTED", np.where(age < 2 * 86400, b"REQUESTED", np.where(age < 4 * 86400, b"APPROVED",
                         np.where(age < 6 * 86400, b"PICKED_UP", b"REFUNDED"))))
    o.rupdated = np.minimum(rcreated + np.select([o.rstatus == b"REQUESTED", o.rstatus == b"APPROVED", o.rstatus == b"PICKED_UP"], [0, 86400, 3 * 86400], 5 * 86400), as_of)
    o.rrefunded = rflag & (o.rstatus == b"REFUNDED")
    o.rto = np.where((o.method != b"CARD") | (rng.random(N) < 0.25), b"WALLET", b"SOURCE")
    o.rid = W.uid_s(w, "return", ok)
    cancel_ref = canc & (o.method != b"COD")                   # cancelled after payment -> full refund
    o.refunded = np.where(cancel_ref, o.total, np.where(o.rrefunded, o.ref_amt, 0))
    o.src_refund = np.where(cancel_ref & (o.method == b"CARD"), o.total, np.where(o.rrefunded & (o.rto == b"SOURCE") & (o.method == b"CARD"), o.ref_amt, 0))
    o.wal_refund = np.where(cancel_ref & (o.method == b"WALLET"), o.total, np.where(o.rrefunded & (o.rto == b"WALLET"), o.ref_amt, 0))
    o.refund_ts = np.where(canc, cts + 3600, rcreated + 5 * 86400)
    o.pay_id = W.uid_s(w, "pay", ok)
    return o


def emit_orders(ctx, o, emit):
    w, voc, rng = ctx.w, ctx.voc, ctx.rng
    N, K, as_of = o.N, o.K, w.as_of
    ok, oo, usr = o.ok, o.oo, o.usr
    has_pay = o.method != b"WALLET"
    paid_state = np.where(o.refunded >= o.total, b"REFUNDED", np.where(o.refunded > 0, b"PARTIALLY_REFUNDED",
                          np.where(o.method == b"COD", np.where(o.delivered, b"COD_COLLECTED", b"COD_PENDING"), b"PAID")))
    shipped_s = o.s_rank >= 3
    trk = cat(b"TRK", rchars(rng, b"0123456789", K, 12))
    carrier = np.where(o.country[o.sh_order] == 0, pick(voc["carriers_in"], rng.integers(0, 100, K)), pick(voc["carriers_intl"], rng.integers(0, 100, K)))
    est_date = e_date(o.placed[o.sh_order] + o.days * 86400)
    f0 = o.sh_first
    score = np.where(o.hold, rng.integers(80, 100, N), rng.integers(0, 35, N))
    reasons = np.where(o.hold, b'["HIGH_VALUE","NEW_ACCOUNT"]', b"[]")
    zero = m4(np.zeros(N, dtype=np.int64))
    emit("order_db", "orders", dict(
        id=o.oid, user_id=o.uid_u[oo], status=o.status, currency=o.cur, total_amount=m4(o.total), placed_at=e_ts(o.placed),
        cancelled_at=nul(e_ts(o.cancel_ts), ~o.canc), idempotency_key=cat(b"idem-", base36(ok, 10)), address_id=o.addr_id,
        ship_full_name=o.ship_name, ship_phone=nul(usr["phone"][oo], ~ctx.facts["has_phone"][oo]), ship_line1=o.af["line1"],
        ship_line2=nul(o.af["line2"], rng.random(N) > 0.3), ship_city=o.af["city"], ship_state=o.af["state"], ship_postal_code=o.af["pin"],
        ship_country=o.af["country"], version=e_int(o.o_rank), payment_method=o.method, payment_status=paid_state,
        payment_id=nul(o.pay_id, ~has_pay), subtotal_amount=m4(o.sub), discount_amount=m4(o.disc), coupon_code=nul(o.cp["code"], ~o.cp_applied),
        shipping_amount=m4(o.ship), tax_amount=m4(o.tax), tax_label=np.where(o.inr, b"GST 18% (incl.)", b"Tax included"),
        cgst_amount=nul(m4(o.cgst), ~(o.inr & (o.cgst > 0))), sgst_amount=nul(m4(o.sgst), ~(o.inr & (o.cgst > 0))),
        igst_amount=nul(m4(o.igst), ~(o.inr & (o.igst > 0))), refunded_amount=m4(o.refunded), email=usr["email"][oo],
        estimated_delivery_date=est_date[f0], tracking_number=nul(trk[f0], ~shipped_s[f0]), carrier=nul(carrier[f0], ~shipped_s[f0]),
        delivered_at=nul(e_ts(o.delivered_ts), ~o.delivered), coins_redeemed=e_int(o.coins), coins_value=m4(o.coinsv), wallet_amount=m4(o.wallet),
        membership_free_shipping=e_bool(o.member & (o.ship == 0)), subscription_id=np.full(N, NUL), risk_score=e_int(score), risk_reasons=reasons,
        risk_hold=e_bool(o.hold), exchange_quote_id=np.full(N, NUL), exchange_amount=zero, exchange_shortfall=zero))
    s_upd = np.where(o.canc[o.sh_order], o.cancel_ts[o.sh_order], np.take_along_axis(o.MS, np.minimum(o.s_rank, 5)[:, None], 1)[:, 0])
    s_upd = np.minimum(s_upd, as_of)
    emit("order_db", "order_shipments", dict(
        id=o.ship_id, order_id=o.oid[o.sh_order], seller_id=o.sh_seller_s, seller_name=o.sh_seller_name, warehouse_id=o.wh_id, warehouse_code=o.wh_code,
        warehouse_pincode=o.wh_pin, status=o.sh_status, tracking_number=nul(trk, ~shipped_s), carrier=nul(carrier, ~shipped_s), estimated_delivery_date=est_date,
        delivered_at=nul(e_ts(o.MS[:, 5]), ~o.sh_delivered), amount=m4(o.sh_amount), created_at=e_ts(o.placed[o.sh_order]), updated_at=e_ts(s_upd),
        version=e_int(o.s_rank)))
    io, il, li = o.io, o.il, o.li
    emit("order_db", "order_items", dict(
        id=W.uid_s(w, "oitem", ok[io] * 8 + il), order_id=o.oid[io], product_id=W.uid_s(w, "product", o.p), sku=li["pr"]["sku"], name=li["name"],
        unit_price=m4(o.unit), quantity=e_int(o.qty), line_total=m4(o.line), image_url=li["image"], variant_id=li["vid"], variant_label=li["label"],
        shipment_id=o.ship_id[o.sinv], offer_id=li["offer_id"], seller_id=li["seller_s"]))
    # status history: order-level rows for every rank reached (+ CANCELLED), shipment rows for multi-shipment orders
    rows = {k: [] for k in ("oid", "status", "note", "actor", "ts", "ship")}
    for r in range(6):
        m = (o.o_rank >= r) & (~o.canc | (o.Ot[:, r] <= o.cancel_ts))
        k = int(m.sum())
        rows["oid"].append(o.oid[m]); rows["status"].append(np.full(k, STATUS[r])); rows["note"].append(np.full(k, HIST_NOTE[r]))
        rows["actor"].append(np.full(k, HIST_ACTOR[r])); rows["ts"].append(o.Ot[m, r]); rows["ship"].append(np.full(k, NUL))
    m = o.canc
    k = int(m.sum())
    rows["oid"].append(o.oid[m]); rows["status"].append(np.full(k, b"CANCELLED")); rows["note"].append(np.full(k, b"Order cancelled"))
    rows["actor"].append(np.where(rng.random(k) < 0.85, b"USER", b"ADMIN")); rows["ts"].append(o.cancel_ts[m]); rows["ship"].append(np.full(k, NUL))
    multi = (np.bincount(o.sh_order, minlength=N) > 1)[o.sh_order]
    for r in (3, 5):
        m = multi & (o.s_rank >= r) & ~o.canc[o.sh_order]
        k = int(m.sum())
        rows["oid"].append(o.oid[o.sh_order][m]); rows["status"].append(np.full(k, STATUS[r])); rows["note"].append(np.full(k, HIST_NOTE[r]))
        rows["actor"].append(np.full(k, b"SELLER")); rows["ts"].append(o.MS[m, r]); rows["ship"].append(o.ship_id[m])
    c = {k: np.concatenate(v) for k, v in rows.items()}
    H = c["oid"].size
    emit("order_db", "order_status_history", dict(id=W.uid_s(w, "hist", next_idx(ctx, H)), order_id=c["oid"], status=c["status"], note=c["note"],
                                                  actor=c["actor"], created_at=e_ts(np.minimum(c["ts"], as_of)), shipment_id=c["ship"]))
    ri = np.flatnonzero(o.rflag)
    if ri.size:
        reason = rng.choice(RETURN_REASONS, ri.size, p=[0.25, 0.1, 0.2, 0.3, 0.15])
        emit("order_db", "order_returns", dict(
            id=o.rid[ri], order_id=o.oid[ri], user_id=o.uid_u[oo][ri], status=o.rstatus[ri], reason=reason,
            comment=nul(pick(voc["lorem"], ok[ri], "r.c", w.seed), reason != b"OTHER"), refund_amount=m4(o.ref_amt[ri]),
            admin_note=nul(pick(voc["agent_msgs"], ok[ri], "r.a", w.seed), ~np.isin(o.rstatus[ri], [b"REJECTED", b"REFUNDED"])),
            created_at=e_ts(o.rcreated[ri]), updated_at=e_ts(o.rupdated[ri]), version=e_int(np.zeros(ri.size)), refund_to=o.rto[ri],
            shipment_id=o.ship_id[o.sh_first[ri]], abuse_flag=e_bool(rng.random(ri.size) < 0.01)))
        im = np.flatnonzero(o.in_ret)
        emit("order_db", "order_return_items", dict(
            id=W.uid_s(w, "ritem", ok[io[im]] * 8 + il[im]), return_id=o.rid[io[im]], product_id=W.uid_s(w, "product", o.p[im]), name=li["name"][im],
            quantity=e_int(o.rqty[im]), unit_price=m4(o.unit[im]), variant_id=li["vid"][im]))
    sm = np.flatnonzero(rng.random(o.n) < 0.015)                 # subscriptions
    if sm.size:
        t = ts_between(rng, o.usr["signup"][sm] + 86400, np.full(sm.size, as_of))
        na = W.n_avail(w, t)
        p = np.minimum((na * rng.random(sm.size)).astype(np.int64), na - 1)
        pr = W.products(w, voc, p)
        stt = rng.choice([b"ACTIVE", b"PAUSED", b"CANCELLED"], sm.size, p=[0.6, 0.2, 0.2])
        emit("order_db", "subscriptions", dict(
            id=W.uid_s(w, "sub", o.u[sm]), user_id=o.uid_u[sm], email=o.usr["email"][sm], product_id=W.uid_s(w, "product", p), variant_id=np.full(sm.size, NUL),
            product_name=pr["name"], quantity=e_int(rng.integers(1, 4, sm.size)), frequency_days=e_int(rng.choice([7, 14, 30, 60], sm.size)),
            next_run_on=e_date(as_of + rng.integers(1, 40, sm.size) * 86400), address_id=W.uid_s(w, "addr", o.u[sm] * 4),
            payment_method=rng.choice([b"COD", b"WALLET"], sm.size), status=stt, discount_percent=e_int(np.full(sm.size, 5)), last_order_id=np.full(sm.size, NUL),
            failures=e_int(rng.integers(0, 2, sm.size) * (stt == b"ACTIVE")), created_at=e_ts(t)))
