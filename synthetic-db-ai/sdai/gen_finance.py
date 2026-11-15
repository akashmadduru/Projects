"""payment_db / promotion_db / seller_db / inventory_db rows derived from a chunk's orders.

Business rules mirrored here:
  * payment-service: one payments row per CARD/COD order (UNIQUE order_id), WALLET orders have none;
    membership and gift-card purchases are payments too (purpose MEMBERSHIP / GIFT_CARD); source refunds
    only for CARD; wallet ledger is a running balance that never goes negative.
  * promotion-service CoinService: EARN_ORDER on delivery (members 2x), REDEEM_ORDER at order time,
    referral reward 100 coins when the referee's first order is delivered.
  * seller-service LedgerService: SALE gross, COMMISSION = -gross*pct, FEE (10 INR / 0.25) only when pct > 0,
    return refunds post REFUND_REVERSAL / COMMISSION_REVERSAL keyed by return_id.
"""
from __future__ import annotations

import numpy as np

from . import world as W
from .gen_orders import INF, NUL
from .gen_util import ALNUM, STAFF, group_cumsum, m2, m4, next_idx, rchars, ts_between
from .rand import (b32code, base36, cat, e_bool, e_int, e_ts, group_expand, nul, pick)

B32 = b"ABCDEFGHJKLMNPQRSTUVWXYZ23456789"


def _idx_runs(eu):
    """first/last row index of each user run in a user-sorted array."""
    brk = np.flatnonzero(eu[1:] != eu[:-1])
    return np.r_[0, brk + 1], np.r_[brk, eu.size - 1]


def emit_payments(ctx, o, F, emit):
    w, voc, rng = ctx.w, ctx.voc, ctx.rng
    as_of, u, usr, uid_u, N, n = w.as_of, o.u, o.usr, o.uid_u, o.N, o.n
    pm = np.flatnonzero(o.method != b"WALLET")
    mode = np.where(o.method == b"COD", b"SIMULATED",
                    np.where(o.inr, np.where(rng.random(N) < 0.7, b"RAZORPAY", b"STRIPE"), np.where(rng.random(N) < 0.92, b"STRIPE", b"SIMULATED")))
    pstat = np.where(o.method == b"COD", np.where(o.delivered, b"SUCCEEDED", b"COD_PENDING"), b"SUCCEEDED")
    pstat = np.where(o.src_refund >= o.total, b"REFUNDED", np.where(o.src_refund > 0, b"PARTIALLY_REFUNDED", pstat))
    p_upd = np.minimum(np.where(o.src_refund > 0, o.refund_ts, np.where(o.delivered & (o.method == b"COD"), o.delivered_ts, o.placed + 30)), as_of)
    reported = (p_upd < as_of - 86400) & (pstat != b"COD_PENDING")
    nul_stripe = (mode != b"STRIPE") | (o.method == b"COD")
    nul_rzp = (mode != b"RAZORPAY") | (o.method == b"COD")
    pay = dict(id=o.pay_id, order_id=o.oid, user_id=uid_u[o.oo], method=o.method.astype("S4"), mode=mode, amount=m4(o.total), currency=o.cur, status=pstat,
               stripe_payment_intent_id=nul(cat(b"pi_", rchars(rng, ALNUM, N, 24)), nul_stripe), refunded_amount=m4(o.src_refund),
               reported=e_bool(reported), report_attempts=e_int(reported.astype(np.int64)), created_at=e_ts(o.placed), updated_at=e_ts(p_upd),
               purpose=np.full(N, b"ORDER"), reference_id=o.oid, provider_order_id=nul(cat(b"order_", rchars(rng, ALNUM, N, 14)), nul_rzp),
               provider_payment_id=nul(cat(b"pay_", rchars(rng, ALNUM, N, 14)), nul_rzp | (pstat == b"COD_PENDING")))
    pay = {k: v[pm] for k, v in pay.items()}
    # membership purchases -------------------------------------------------------------------
    ok_m = ~F["mm_pending"]
    mi, mid = F["mm"][ok_m], F["mm_pay"][ok_m]
    mc = usr["cur"][mi]
    mmode = np.where(mc == b"INR", b"RAZORPAY", b"STRIPE")
    nm = mi.size
    mem = dict(id=W.uid_s(w, "mpay2", u[mi]), order_id=mid, user_id=uid_u[mi], method=np.full(nm, b"CARD"), mode=mmode, amount=m4(F["mm_amt"][ok_m]), currency=mc,
               status=np.full(nm, b"SUCCEEDED"), stripe_payment_intent_id=nul(cat(b"pi_", rchars(rng, ALNUM, nm, 24)), mmode != b"STRIPE"),
               refunded_amount=m4(np.zeros(nm, dtype=np.int64)), reported=e_bool(np.ones(nm, bool)), report_attempts=e_int(np.ones(nm, dtype=np.int64)),
               created_at=e_ts(F["mm_start"][ok_m]), updated_at=e_ts(F["mm_start"][ok_m] + 20), purpose=np.full(nm, b"MEMBERSHIP"), reference_id=mid,
               provider_order_id=nul(cat(b"order_", rchars(rng, ALNUM, nm, 14)), mmode != b"RAZORPAY"),
               provider_payment_id=nul(cat(b"pay_", rchars(rng, ALNUM, nm, 14)), mmode != b"RAZORPAY"))
    # gift cards redeemed by this chunk's wallet users (bought by another user -> that user's payment) ------
    gu, gk = np.nonzero(o.gc_t < (1 << 59))
    G = gu.size
    gkey = u[gu] * 4 + gk
    gamt = o.gc_amt[gu, gk]
    purch = rng.integers(STAFF, w.U, G)
    gcreated = o.gc_t[gu, gk] - rng.integers(86400, 10 * 86400, G)
    gid = W.uid_s(w, "gc", gkey)
    gcode = cat(rchars(rng, B32, G, 4), b"-", b32code(gkey >> 20, 4), b"-", rchars(rng, B32, G, 4), b"-", b32code(gkey, 4))
    pu = W.users(w, voc, purch)
    gcur = usr["cur"][gu]
    g_red = dict(id=gid, code=gcode, amount=m4(gamt), currency=gcur, status=np.full(G, b"REDEEMED"), purchaser_user_id=W.uid_s(w, "user", purch),
                 purchaser_email=pu["email"], recipient_email=usr["email"][gu], message=nul(pick(voc["lorem"], gkey, "g.m", w.seed), rng.random(G) < 0.4),
                 redeemed_by=uid_u[gu], redeemed_at=e_ts(o.gc_t[gu, gk]), created_at=e_ts(gcreated), expires_at=e_ts(gcreated + 365 * 86400))
    gmode = np.where(gcur == b"INR", b"RAZORPAY", b"STRIPE")
    gpay = dict(id=W.uid_s(w, "gcpay", gkey), order_id=gid, user_id=W.uid_s(w, "user", purch), method=np.full(G, b"CARD"), mode=gmode, amount=m4(gamt), currency=gcur,
                status=np.full(G, b"SUCCEEDED"), stripe_payment_intent_id=nul(cat(b"pi_", rchars(rng, ALNUM, G, 24)), gmode != b"STRIPE"),
                refunded_amount=m4(np.zeros(G, dtype=np.int64)), reported=e_bool(np.ones(G, bool)), report_attempts=e_int(np.ones(G, dtype=np.int64)),
                created_at=e_ts(gcreated), updated_at=e_ts(gcreated + 20), purpose=np.full(G, b"GIFT_CARD"), reference_id=gid,
                provider_order_id=np.full(G, NUL), provider_payment_id=np.full(G, NUL))
    emit("payment_db", "payments", {k: np.concatenate([pay[k], mem[k], gpay[k]]) for k in pay})
    ri = np.flatnonzero(o.src_refund > 0)
    emit("payment_db", "refunds", dict(
        id=W.uid_s(w, "refund", o.ok[ri]), payment_id=o.pay_id[ri], amount=m4(o.src_refund[ri]), reason=np.where(o.canc[ri], b"Order cancelled", b"Return refund"),
        stripe_refund_id=nul(cat(b"re_", rchars(rng, ALNUM, ri.size, 24)), mode[ri] != b"STRIPE"), idempotency_key=cat(b"refund-", o.oid[ri]),
        created_at=e_ts(np.minimum(o.refund_ts[ri], as_of)), provider_refund_id=nul(cat(b"rfnd_", rchars(rng, ALNUM, ri.size, 14)), mode[ri] != b"RAZORPAY")))
    # wallet: gift-card credits, wallet-paid orders (debits), wallet refunds (credits) -> running balance
    wd = o.wallet > 0
    wr = o.wal_refund > 0
    eu = np.concatenate([gu, o.oo[wd], o.oo[wr]])
    et = np.concatenate([o.gc_t[gu, gk], o.placed[wd], np.minimum(o.refund_ts[wr], as_of)])
    ea = np.concatenate([gamt, -o.wallet[wd], o.wal_refund[wr]])
    er = np.concatenate([np.full(G, b"GIFT_CARD"), np.full(int(wd.sum()), b"ORDER"), np.full(int(wr.sum()), b"REFUND")])
    ef = np.concatenate([gid, o.oid[wd], o.oid[wr]])
    if eu.size:
        order = np.lexsort((-ea, et, eu))                      # per user by time; credits before debits on ties
        eu, et, ea, er, ef = eu[order], et[order], ea[order], er[order], ef[order]
        bal = group_cumsum(ea, eu)
        first, last = _idx_runs(eu)
        emit("payment_db", "wallets", dict(user_id=uid_u[eu[last]], balance=m4(bal[last]), currency=usr["cur"][eu[last]], created_at=e_ts(et[first]), updated_at=e_ts(et[last])))
        emit("payment_db", "wallet_txns", dict(
            id=W.uid_s(w, "wtx", next_idx(ctx, eu.size)), user_id=uid_u[eu], type=np.where(ea > 0, b"CREDIT", b"DEBIT"), amount=m4(np.abs(ea)), currency=usr["cur"][eu],
            reason=er, reference_id=ef, idempotency_key=cat(np.char.lower(er), b":", ef), balance_after=m4(bal), created_at=e_ts(et)))
    # unredeemed gift cards bought by this chunk's users (+ their payments are skipped: status ACTIVE needs one, so add it)
    um = np.flatnonzero(rng.random(n) < 0.03)
    ucre = as_of - rng.integers(1, 300, um.size) * 86400
    ukey = u[um] * 4 + 3
    umt = rng.choice([b"ACTIVE", b"PENDING_PAYMENT", b"CANCELLED"], um.size, p=[0.8, 0.1, 0.1])
    ucur = usr["cur"][um]
    uamt = np.where(ucur == b"INR", rng.choice([50000, 100000, 200000], um.size), rng.choice([1000, 2500, 5000], um.size))
    emit("payment_db", "gift_cards", {k: np.concatenate([g_red[k], v]) for k, v in dict(
        id=W.uid_s(w, "gc", ukey), code=cat(rchars(rng, B32, um.size, 4), b"-", b32code(ukey >> 20, 4), b"-", rchars(rng, B32, um.size, 4), b"-", b32code(ukey, 4)),
        amount=m4(uamt), currency=ucur, status=umt, purchaser_user_id=uid_u[um], purchaser_email=usr["email"][um],
        recipient_email=cat(b"friend.", base36(ukey, 8), b"@example.com"), message=pick(voc["lorem"], ukey, "g.m", w.seed),
        redeemed_by=np.full(um.size, NUL), redeemed_at=np.full(um.size, NUL), created_at=e_ts(ucre), expires_at=e_ts(ucre + 365 * 86400)).items()})
    dm = o.delivered
    ev = np.concatenate([o.oid, o.oid[dm]])
    evn = np.concatenate([np.full(N, b"order.placed:"), np.full(int(dm.sum()), b"order.delivered:")])
    evt = np.concatenate([o.placed + 2, o.delivered_ts[dm] + 2])
    emit("payment_db", "processed_events", dict(event_id=cat(evn, ev), received_at=e_ts(np.minimum(evt, as_of))))


def emit_promotion(ctx, o, F, emit):
    w, voc, rng = ctx.w, ctx.voc, ctx.rng
    as_of, uid_u = w.as_of, o.uid_u
    ci = np.flatnonzero(o.cp_applied)
    emit("promotion_db", "redemptions", dict(
        id=W.uid_s(w, "redeem", o.ok[ci]), coupon_id=W.uid_s(w, "coupon", o.cp_idx[ci]), user_id=uid_u[o.oo[ci]], order_id=o.oid[ci], discount_amount=m4(o.disc[ci]),
        status=np.where(o.canc[ci], b"RELEASED", b"APPLIED"), created_at=e_ts(o.placed[ci])))
    ctx.tally_add("coupon_used", (w.n_coupons,), (o.cp_idx[ci][~o.canc[ci]],))
    di = np.flatnonzero(o.deal_idx >= 0)
    emit("promotion_db", "deal_claims", dict(
        id=W.uid_s(w, "dclaim", o.ok[o.io[di]] * 8 + o.il[di]), order_id=o.oid[o.io[di]], deal_id=W.uid_s(w, "deal", o.deal_idx[di]), quantity=e_int(o.qty[di]),
        released=e_bool(o.canc[o.io[di]]), created_at=e_ts(o.placed[o.io[di]])))
    live = di[~o.canc[o.io[di]]]
    ctx.tally_add("deal_claimed", (w.n_deals,), (o.deal_idx[live],), o.qty[live])
    first_deliv = np.full(o.n, INF, dtype=np.int64)
    np.minimum.at(first_deliv, o.oo, o.delivered_ts)
    ri = F["ref_idx"]
    rew = first_deliv[ri] < INF
    F["ref_rewarded"] = np.where(rew, first_deliv[ri], -1)
    m_e, m_r = o.earn > 0, o.coins > 0
    cu = np.concatenate([uid_u[o.oo[m_e]], uid_u[o.oo[m_r]], W.uid_s(w, "user", F["ref_referrer"][rew])])
    cd = np.concatenate([o.earn[m_e], -o.coins[m_r], np.full(int(rew.sum()), 100)])
    cr = np.concatenate([np.full(int(m_e.sum()), b"EARN_ORDER"), np.full(int(m_r.sum()), b"REDEEM_ORDER"), np.full(int(rew.sum()), b"REFERRAL")])
    cf = np.concatenate([o.oid[m_e], o.oid[m_r], cat(b"referral-", uid_u[ri][rew])])
    ct = np.concatenate([o.delivered_ts[m_e], o.placed[m_r], first_deliv[ri][rew]])
    ce = np.concatenate([o.delivered_ts[m_e] + 365 * 86400, np.full(int(m_r.sum()), -1), first_deliv[ri][rew] + 365 * 86400])
    emit("promotion_db", "coin_ledger", dict(id=W.uid_s(w, "coin", next_idx(ctx, cu.size)), user_id=cu, delta=e_int(cd), reason=cr, reference_id=cf,
                                             created_at=e_ts(np.minimum(ct, as_of)), expires_at=nul(e_ts(np.where(ce < 0, 0, ce)), ce < 0)))
    qm = np.flatnonzero(rng.random(o.n) < 0.012)               # device trade-in quotes
    if qm.size:
        from .gen_static import program_attrs
        prog = rng.integers(0, w.n_programs, qm.size)
        cond = rng.choice([b"WORKING", b"SCREEN_DAMAGED", b"NOT_WORKING"], qm.size, p=[0.6, 0.25, 0.15])
        age = rng.integers(0, 7, qm.size)
        mx = program_attrs(w, prog)["max_cents"]
        val = np.maximum((mx * np.select([cond == b"WORKING", cond == b"SCREEN_DAMAGED"], [0.8, 0.45], 0.15) * np.maximum(0.2, 1 - age * 0.12)).astype(np.int64), 100)
        t = ts_between(rng, o.usr["signup"][qm] + 86400, np.full(qm.size, as_of))
        rej = rng.random(qm.size) < 0.15
        brand = pick(voc["brands"], o.u[qm], "x.b", w.seed)
        emit("promotion_db", "exchange_quotes", dict(
            id=W.uid_s(w, "xq", o.u[qm]), user_id=uid_u[qm], program_id=W.uid_s(w, "program", prog), device_brand=brand,
            device_model=cat(brand, b" ", pick(voc["modifiers"], o.u[qm], "x.m", w.seed)), device_condition=cond, age_years=e_int(age), quoted_value=m4(val),
            currency=np.full(qm.size, b"USD"), expires_at=e_ts(t + 7 * 86400), status=np.where(rej, b"REJECTED", b"QUOTED"), order_id=np.full(qm.size, NUL),
            final_value=np.full(qm.size, NUL), shortfall=m4(np.zeros(qm.size, dtype=np.int64)), created_at=e_ts(t), verified_at=nul(e_ts(t + 86400), ~rej)))


def emit_seller_inventory(ctx, o, F, emit):
    w, voc, rng = ctx.w, ctx.voc, ctx.rng
    as_of, N = w.as_of, o.N
    sd = np.flatnonzero(o.sh_delivered)
    gross, pct = o.sh_amount[sd], o.sh_commission[sd]
    comm = np.floor(gross * pct / 100.0 + 0.5).astype(np.int64)
    cur_s = o.cur[o.sh_order[sd]]
    fee = np.where(pct > 0, np.where(cur_s == b"INR", 1000, 25), 0)
    dts = np.minimum(o.MS[sd, 5], as_of)
    nors = np.full(sd.size, NUL)
    fm = fee > 0
    parts = [(o.sh_seller[sd], sd, b"SALE", gross, dts, o.sh_order[sd], nors, cur_s),
             (o.sh_seller[sd], sd, b"COMMISSION", -comm, dts, o.sh_order[sd], nors, cur_s),
             (o.sh_seller[sd][fm], sd[fm], b"FEE", -fee[fm], dts[fm], o.sh_order[sd][fm], nors[fm], cur_s[fm])]
    rr = np.flatnonzero(o.rrefunded & o.sh_delivered[o.sh_first])          # partial refunds of the returned shipment
    if rr.size:
        rsh = o.sh_first[rr]
        rc = np.floor(o.ref_amt[rr] * o.sh_commission[rsh] / 100.0 + 0.5).astype(np.int64)
        rts = np.minimum(o.rcreated[rr] + 5 * 86400, as_of)
        parts.append((o.sh_seller[rsh], rsh, b"REFUND_REVERSAL", -o.ref_amt[rr], rts, rr, o.rid[rr], o.cur[rr]))
        m = rc > 0
        parts.append((o.sh_seller[rsh][m], rsh[m], b"COMMISSION_REVERSAL", rc[m], rts[m], rr[m], o.rid[rr][m], o.cur[rr][m]))
    ls = np.concatenate([p[0] for p in parts]); lsh = np.concatenate([p[1] for p in parts])
    lt = np.concatenate([np.full(p[0].size, p[2]) for p in parts]); la = np.concatenate([p[3] for p in parts])
    lc = np.concatenate([p[4] for p in parts]); lo = np.concatenate([p[5] for p in parts])
    lr = np.concatenate([p[6] for p in parts]); lcur = np.concatenate([p[7] for p in parts])
    S2 = w.S + 2
    # one payout per (seller, chunk), in the seller's majority currency, for entries settled > 14 days ago
    inr_cnt = np.bincount(ls, weights=(lcur == b"INR"), minlength=S2)
    all_cnt = np.bincount(ls, minlength=S2)
    pcur = np.where(inr_cnt * 2 > all_cnt, b"INR", b"USD").astype("S3")
    settled = (lc < as_of - 14 * 86400) & np.where(pcur[ls] == b"INR", lcur == b"INR", lcur != b"INR")
    sums = np.bincount(ls[settled], weights=la[settled], minlength=S2).astype(np.int64)
    lastc = np.zeros(S2, dtype=np.int64)
    np.maximum.at(lastc, ls[settled], lc[settled])
    paying = np.flatnonzero(sums > 0)
    settled &= np.isin(ls, paying)
    pidx = ctx.chunk * S2 + np.arange(S2)
    emit("seller_db", "payouts", dict(
        id=W.uid_s(w, "payout", pidx[paying]), seller_id=W.seller_code_to_uid_s(w, paying), amount=m2(sums[paying]), currency=pcur[paying],
        status=np.full(paying.size, b"PAID"), reference=cat(b"PO-", np.char.upper(base36(pidx[paying], 9))), created_at=e_ts(np.minimum(lastc[paying] + 3 * 86400, as_of))))
    emit("seller_db", "ledger_entries", dict(
        id=W.uid_s(w, "ledger", next_idx(ctx, ls.size)), seller_id=W.seller_code_to_uid_s(w, ls), type=lt, amount=m2(la), currency=lcur, order_id=o.oid[lo],
        shipment_id=o.ship_id[lsh], created_at=e_ts(lc), payout_id=nul(W.uid_s(w, "payout", pidx[ls]), ~settled), return_id=lr))
    im = np.flatnonzero(o.sh_delivered[o.sinv])
    emit("seller_db", "sale_lines", dict(
        id=W.uid_s(w, "sline", o.ok[o.io[im]] * 8 + o.il[im]), seller_id=o.sh_seller_s[o.sinv[im]], order_id=o.oid[o.io[im]], shipment_id=o.ship_id[o.sinv[im]],
        product_id=W.uid_s(w, "product", o.p[im]), name=o.li["name"][im], quantity=e_int(o.qty[im]), amount=m2(o.line[im]),
        created_at=e_ts(np.minimum(o.MS[o.sinv[im], 5], as_of))))
    fb = sd[rng.random(sd.size) < 0.12]                                     # seller feedback; also tallied for sellers.rating_*
    stars = np.clip(np.floor(4.15 + 0.9 * rng.standard_normal(fb.size) + 0.5), 1, 5).astype(np.int64)
    emit("seller_db", "seller_feedback", dict(
        id=W.uid_s(w, "sfb", o.ok[o.sh_order[fb]] * 8 + o.sh_rank[fb]), seller_id=o.sh_seller_s[fb], order_id=o.oid[o.sh_order[fb]], shipment_id=o.ship_id[fb],
        user_id=o.uid_u[o.oo[o.sh_order[fb]]], rating=e_int(stars), comment=nul(pick(voc["rev_pos"], fb, None), (stars < 3) | (rng.random(fb.size) < 0.5)),
        created_at=e_ts(np.minimum(o.MS[fb, 5] + rng.integers(86400, 14 * 86400, fb.size), as_of))))
    ctx.tally_add("seller_fb", (S2, 2), (o.sh_seller[fb], 0), stars)
    ctx.tally_add("seller_fb", (S2, 2), (o.sh_seller[fb], 1), 1)
    # inventory reservations / releases ---------------------------------------------------------------
    io = o.io
    restored = np.where(o.canc[io], o.qty, np.where(o.in_ret & o.rrefunded[io], o.rqty, 0))
    emit("inventory_db", "reservations", dict(
        id=W.uid_s(w, "resv", o.ok[io] * 8 + o.il), order_id=o.oid[io], product_id=W.uid_s(w, "product", o.p), quantity=e_int(o.qty), restored_quantity=e_int(restored),
        created_at=e_ts(o.placed[io] + 1), variant_id=o.li["vid"], warehouse_id=o.wh_id[o.sinv], offer_id=o.li["offer_id"], seller_id=o.li["seller_s"]))
    rel = np.flatnonzero(restored > 0)
    emit("inventory_db", "reservation_releases", dict(
        id=W.uid_s(w, "rel", o.ok[io[rel]] * 8 + o.il[rel]), order_id=o.oid[io[rel]], release_key=np.where(o.canc[io[rel]], b"cancel", cat(b"return-", o.rid[io[rel]])),
        product_id=W.uid_s(w, "product", o.p[rel]), quantity=e_int(restored[rel]), created_at=e_ts(np.minimum(o.refund_ts[io[rel]], as_of)), variant_id=o.li["vid"][rel]))
    act = (~o.canc[io]) & (o.o_rank[io] <= 2)
    ctx.tally_add("reserved", (w.P,), (o.p[act],), o.qty[act])
