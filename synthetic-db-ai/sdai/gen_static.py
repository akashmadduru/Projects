"""Stage 0: reference data that other tables reference (categories, coupons, deals, programs, sellers, warehouses...).

These tables are small and are written first (single task) so child rows generated later satisfy
their real foreign keys (e.g. promotion.redemptions -> coupons, seller_feedback -> sellers).
"""
from __future__ import annotations

import numpy as np

from . import vocab as V
from . import world as W
from .gen_util import STAFF, m4, rchars, ts_between
from .rand import (base36, cat, e_bool, e_int, e_ts, nul, pick, rint, uni)


def program_attrs(w, prog):
    prog = np.asarray(prog).astype(np.int64)
    # trade-in programs exist for electronics / computers sub-categories (first 11 subs)
    sub = rint(prog, "xp.sub", 0, 11, w.seed)
    mx = (rint(prog, "xp.max", 3, 25, w.seed) * 1000).astype(np.int64)          # USD cents: 30..250
    return dict(sub=sub, cat_idx=V.N_ROOT + sub, max_cents=mx)


def _faq(w, voc):
    rows = []
    for cat_, titles in V._FAQ.items():
        for t in titles:
            rows.append((cat_, t))
    return rows


def emit_static(ctx, emit):
    w, voc, rng = ctx.w, ctx.voc, ctx.rng
    as_of, T0 = w.as_of, w.T0
    # ---- product_db.categories (roots + one level of subcategories)
    cat_ = W.categories(w)
    N = cat_["idx"].size
    idx = cat_["idx"]
    par = cat_["parent"]
    names = np.array([n.encode() for n in cat_["name"]])
    created = np.full(N, T0 - 30 * 86400)
    emit("product_db", "categories", dict(
        id=W.cat_id(w, idx), name=names, parent_category_id=nul(W.cat_id(w, np.maximum(par, 0)), par < 0), created_at=e_ts(created), updated_at=e_ts(created),
        description=cat(b"Browse our selection of ", np.char.lower(names), b" from trusted brands and verified sellers."), depth_level=e_int((par >= 0).astype(np.int64)),
        is_active=e_bool(np.ones(N, bool)), version=e_int(np.zeros(N))))
    # ---- promotion_db
    K = w.n_coupons
    c = W.coupons(w, np.arange(K))
    emit("promotion_db", "coupons", dict(
        id=W.uid_s(w, "coupon", c["idx"]), code=c["code"], description=cat(np.where(c["flat"], b"Flat discount on your order", b"Percentage discount on your order"), b" (", c["code"], b")"),
        type=np.where(c["flat"], b"FLAT", b"PERCENT"), value=m4(c["value"] * 100), max_discount=nul(m4(np.maximum(c["cap"], 0) * 100), c["cap"] < 0),
        min_order_amount=m4(c["minord"] * 100), currency=nul(c["cur"], ~c["flat"]), starts_at=e_ts(c["starts"]), ends_at=nul(e_ts(np.maximum(c["ends"], 0)), c["ends"] < 0),
        total_limit=nul(e_int(np.maximum(c["total_limit"], 1)), c["total_limit"] < 0), per_user_limit=nul(e_int(np.maximum(c["per_user"], 1)), c["per_user"] < 0),
        first_order_only=e_bool(c["first"]), active=e_bool(c["active"]), created_at=e_ts(c["starts"]), updated_at=e_ts(c["starts"])))
    B = 16
    bi = np.arange(B)
    flat = uni(bi, "b.flat", w.seed) < 0.5
    bank = pick(voc["banks"], bi, None)
    emit("promotion_db", "bank_offers", dict(
        id=W.uid_s(w, "bank", bi), bank=bank, card_type=pick(np.array([b"CREDIT", b"DEBIT", b"ANY"]), bi, "b.ct", w.seed),
        title=cat(bank, np.where(flat, b" flat discount on cards", b" instant discount on cards")), discount_type=np.where(flat, b"FLAT", b"PERCENT"),
        value=m4(np.where(flat, rint(bi, "b.v", 2, 25, w.seed), rint(bi, "b.v", 5, 16, w.seed)) * 100), max_discount=nul(m4(rint(bi, "b.m", 20, 200, w.seed) * 100), flat),
        min_amount=m4(rint(bi, "b.min", 0, 30, w.seed) * 100), currency=nul(np.full(B, b"USD"), ~flat), active=e_bool(np.ones(B, bool)),
        starts_at=e_ts(np.full(B, T0)), ends_at=np.full(B, b"\\N")))
    D = w.n_deals
    d = W.deals(w, voc, np.arange(D))
    dp = W.deal_price_usd(w, d)
    emit("promotion_db", "deals", dict(
        id=W.uid_s(w, "deal", d["idx"]), product_id=W.uid_s(w, "product", d["prod"]), variant_id=np.full(D, b"\\N"), title=cat(d["prices"]["name"], b" deal"),
        deal_price=m4(dp), currency=np.full(D, b"USD"), starts_at=e_ts(d["start"]), ends_at=e_ts(d["end"]), quantity_limit=e_int(np.full(D, max(1000, w.U // 5))),
        claimed=e_int(np.zeros(D)), active=e_bool(np.ones(D, bool)), created_at=e_ts(d["start"] - 86400), members_only_until=nul(e_ts(d["start"] + 3600), uni(d["idx"], "d.mem", w.seed) > 0.2)))
    P = w.n_programs
    pi = np.arange(P)
    pa = program_attrs(w, pi)
    emit("promotion_db", "exchange_programs", dict(
        id=W.uid_s(w, "program", pi), category_id=W.cat_id(w, pa["cat_idx"]), brand=nul(pick(voc["brands"], pi, "xp.b", w.seed), uni(pi, "xp.nb", w.seed) < 0.5),
        title=cat(np.array([x.encode() for x in V.ROOTS])[V.N_ROOT * 0 + np.array([V.SUBS[i][0] for i in pa["sub"]])], b" trade-in - up to ", e_int(pa["max_cents"] // 100), b" off"),
        max_value=m4(pa["max_cents"]), currency=np.full(P, b"USD"), active=e_bool(np.ones(P, bool)), created_at=e_ts(np.full(P, T0))))
    # ---- seller_db.sellers (rating_* start at 0 and are set from the feedback tally in the finalize step)
    S = w.S
    si = np.arange(S)
    sl = W.sellers(w, voc, si)
    st_ts = T0 + (uni(si, "s.created", w.seed) * (as_of - T0 - 30 * 86400)).astype(np.int64)
    appr = sl["status"] == b"APPROVED"
    rej = sl["status"] == b"REJECTED"
    st_state = pick(np.array([x[1].encode() for x in V.CITIES[:18]]), si, "s.pst", w.seed)
    pan = cat(rchars(rng, b"ABCDEFGHJKLMNPQRSTUVWXYZ", S, 5), rchars(rng, b"0123456789", S, 4), rchars(rng, b"ABCDEFGHJKLMNPQRSTUVWXYZ", S, 1))
    emit("seller_db", "sellers", dict(
        id=W.uid_s(w, "seller", si), user_id=W.uid_s(w, "user", STAFF + si), legal_name=sl["legal"], display_name=sl["name"],
        slug=cat(np.char.replace(np.char.lower(sl["name"]), b" ", b"-"), b"-", base36(si, 5), W.seed_tag(w)), email=cat(b"sales@", base36(si, 5), b".example.com"),
        phone=cat(b"+9198", np.char.zfill(rint(si, "s.ph", 0, 10 ** 8, w.seed).astype("S"), 8)), gstin=cat(e_int(rint(si, "s.gst", 10, 38, w.seed)), pan, b"1Z", rchars(rng, b"0123456789", S, 1)),
        pan=pan, bank_account_last4=np.char.zfill(rint(si, "s.b4", 0, 10000, w.seed).astype("S"), 4), bank_account_hash=rchars(rng, b"0123456789abcdef", S, 64),
        ifsc=cat(rchars(rng, b"ABCDEFGHJKLMNPQRSTUVWXYZ", S, 4), b"0", rchars(rng, b"0123456789", S, 6)),
        pickup_address=cat(b'{"line1": "Plot ', e_int(rint(si, "s.pl", 1, 400, w.seed)), b', Industrial Area", "state": "', st_state, b'", "country": "IN"}'),
        status=sl["status"], rejection_reason=nul(np.full(S, b"Documents could not be verified"), ~rej), commission_percent=cat(e_int(sl["commission"].astype(np.int64)), b".", np.where(sl["commission"] % 1 > 0, b"50", b"00")),
        rating_avg=np.full(S, b"0.00"), rating_count=e_int(np.zeros(S)), created_at=e_ts(st_ts), approved_at=nul(e_ts(st_ts + 86400 * 3), ~appr), updated_at=e_ts(st_ts + 86400 * 3)))
    # ---- inventory_db.warehouses (3 ACME warehouses are seeded by migration V7)
    wh = W.warehouses_of(w, voc)
    nw = wh["idx"].size
    emit("inventory_db", "warehouses", dict(
        id=W.uid_s(w, "wh", wh["idx"]), code=wh["code"], name=cat(wh["city"], b" Fulfilment Centre"), seller_id=W.uid_s(w, "seller", wh["seller"]), pincode=wh["pin"],
        state=wh["state"], active=e_bool(np.ones(nw, bool)), created_at=e_ts(np.full(nw, T0))))
    # ---- notification_db.campaigns
    C = 30
    ci = np.arange(C)
    sent = uni(ci, "n.sent", w.seed) < 0.8
    subj = pick(voc["notif_PROMO"], ci, None)
    emit("notification_db", "campaigns", dict(
        id=W.uid_s(w, "camp", ci), name=cat(b"Campaign ", base36(ci, 4)), channel=pick(np.array([b"EMAIL", b"PUSH", b"INBOX", b"ALL"]), ci, "n.ch", w.seed),
        audience=pick(np.array([b"ALL", b"MEMBERS", b"INACTIVE_30D"]), ci, "n.au", w.seed), subject=subj, body=cat(subj, b". ", pick(voc["bio"], ci, None)), link=np.full(C, b"/deals"),
        status=np.where(sent, b"SENT", b"DRAFT"), sent_count=e_int(np.where(sent, rint(ci, "n.sc", 1000, 90000, w.seed), 0)), created_at=e_ts(T0 + ci * 86400 * 20),
        sent_at=nul(e_ts(T0 + ci * 86400 * 20 + 3600), ~sent)))
    # ---- support_db.faq_articles
    faq = _faq(w, voc)
    F = len(faq)
    fi = np.arange(F)
    ft = np.array([t.encode() for _, t in faq])
    emit("support_db", "faq_articles", dict(
        id=W.uid_s(w, "faq", fi), slug=cat(np.char.lower(np.char.replace(np.char.replace(ft, b" ", b"-"), b"?", b"")), b"-", base36(fi + w.seed % 1000, 4)),
        category=np.array([c.encode() for c, _ in faq]), title=ft, body=cat(ft, b" ", pick(voc["bio"], fi, None)), keywords=np.full(F, b"{help,support}"),
        published=e_bool(np.ones(F, bool)), helpful_yes=e_int(rint(fi, "f.y", 0, 400, w.seed)), helpful_no=e_int(rint(fi, "f.n", 0, 40, w.seed)),
        created_at=e_ts(np.full(F, T0)), updated_at=e_ts(np.full(F, T0))))
