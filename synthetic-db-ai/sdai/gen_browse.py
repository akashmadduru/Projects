"""cart_db (carts, wishlist, saved items, alerts, lists) + browsing history (user_db / product_db)."""
from __future__ import annotations

import numpy as np

from . import world as W
from .gen_util import ALNUM, HEX, m4, rchars, serial_ids, ts_between
from .rand import cat, e_bool, e_int, e_ts, first_of_key, group_expand, nul, pick, e_money, base36


def line_info(w, voc, rng, p, cur):
    """Snapshot (name, price in the user's currency, variant, offer) of product p as a buyer sees it."""
    pr = W.products(w, voc, p)
    n = p.size
    hasv = pr["nvar"] > 0
    v = np.where(hasv, rng.integers(0, np.maximum(pr["nvar"], 1)), 0)
    label, _ = W.variant_label(w, voc, p, v, pr["root"])
    j = np.where((pr["noff"] > 1) & (rng.random(n) < 0.25), rng.integers(1, np.maximum(pr["noff"], 2)), 0)
    j = np.minimum(j, pr["noff"] - 1)
    scode = W.offer_seller(w, p, pr["seller"], j)
    price, sale = W.offer_cents(w, pr, j)
    usd = np.where(sale > 0, sale, price)
    return dict(pr=pr, hasv=hasv, v=v, vid=nul(W.uid_s(w, "variant", p * 8 + v), ~hasv), label=nul(label, ~hasv),
                j=j, offer_id=W.uid_s(w, "offer", p * 4 + j), seller_code=scode, seller_s=W.seller_code_to_uid_s(w, scode),
                usd=usd, cents=W.convert_cents(usd, cur), image=W.image_url(w, p), name=pr["name"])


def _avail_pick(w, rng, t, power=1.6):
    na = W.n_avail(w, t)
    return np.minimum((na * rng.random(t.shape[0]) ** power).astype(np.int64), na - 1)


def browse_chunk(ctx, u, usr, uid_u, emit):
    w, voc, rng = ctx.w, ctx.voc, ctx.rng
    n = u.size
    signup, cur, as_of = usr["signup"], usr["cur"], w.as_of
    # ---------------------------------------------------------------- carts
    ci = np.flatnonzero(rng.random(n) < 0.55)
    c_upd = ts_between(rng, np.maximum(signup[ci], as_of - 200 * 86400), np.full(ci.size, as_of))
    cart_id = W.uid_s(w, "cart", u[ci])
    has_email = rng.random(ci.size) < 0.7
    emit("cart_db", "carts", dict(id=cart_id, user_id=uid_u[ci], updated_at=e_ts(c_upd), version=e_int(rng.integers(0, 12, ci.size)),
                                  email=nul(usr["email"][ci], ~has_email),
                                  reminder_sent_at=nul(e_ts(np.minimum(c_upd + 86400, as_of)), ~(has_email & (rng.random(ci.size) < 0.25) & (c_upd < as_of - 2 * 86400)))))
    co, ck = group_expand(rng.integers(1, 6, ci.size))
    p = _avail_pick(w, rng, c_upd[co])
    keep = first_of_key(co * w.P + p)
    co, ck, p = co[keep], ck[keep], p[keep]
    li = line_info(w, voc, rng, p, cur[ci][co])
    added = ts_between(rng, np.maximum(signup[ci][co], c_upd[co] - 20 * 86400), c_upd[co])
    emit("cart_db", "cart_items", dict(
        id=W.uid_s(w, "citem", u[ci][co] * 8 + ck), cart_id=cart_id[co], product_id=W.uid_s(w, "product", p),
        quantity=e_int(rng.choice([1, 2, 3, 4], co.size, p=[0.7, 0.2, 0.07, 0.03])), name=li["name"], unit_price=m4(li["cents"]), currency=cur[ci][co],
        image_url=li["image"], added_at=e_ts(added), variant_id=li["vid"], variant_label=li["label"], offer_id=li["offer_id"], seller_id=li["seller_s"]))
    # ---------------------------------------------------------------- wishlist / saved / lists
    def simple_lines(counts, owner_idx, t_lo, key_prefix):
        o, k = group_expand(counts)
        t = ts_between(rng, np.maximum(signup[owner_idx][o], t_lo[o]), np.full(o.size, as_of))
        pp = _avail_pick(w, rng, t)
        kp = first_of_key(o * w.P + pp)
        return o[kp], pp[kp], t[kp], k[kp]
    wi = np.arange(n)
    wo, wp, wt, wk = simple_lines(rng.poisson(1.0, n), wi, signup, "wl")
    li = line_info(w, voc, rng, wp, cur[wo])
    emit("cart_db", "wishlist_items", dict(
        id=W.uid_s(w, "wish", u[wo] * 64 + wk), user_id=uid_u[wo], product_id=W.uid_s(w, "product", wp), name=li["name"], price=m4(li["cents"]),
        currency=cur[wo], image_url=li["image"], added_at=e_ts(wt), note=nul(pick(voc["lorem"], wp, None), rng.random(wo.size) > 0.1), variant_id=li["vid"], variant_label=li["label"]))
    so, sp, st, sk = simple_lines(rng.poisson(0.4, n), wi, signup, "sv")
    li = line_info(w, voc, rng, sp, cur[so])
    emit("cart_db", "saved_items", dict(
        id=W.uid_s(w, "saved", u[so] * 64 + sk), user_id=uid_u[so], product_id=W.uid_s(w, "product", sp), name=li["name"], unit_price=m4(li["cents"]),
        currency=cur[so], image_url=li["image"], saved_at=e_ts(st), variant_id=li["vid"], variant_label=li["label"], offer_id=li["offer_id"], seller_id=li["seller_s"]))
    # alerts (price drop / back in stock), unique per (user, product, type)
    ao, ap, at, ak = simple_lines(rng.poisson(0.35, n), wi, signup, "al")
    typ = np.where(rng.random(ao.size) < 0.7, b"PRICE_DROP", b"BACK_IN_STOCK")
    kp = first_of_key((ao * w.P + ap) * 2 + (typ == b"PRICE_DROP"))
    ao, ap, at, ak, typ = ao[kp], ap[kp], at[kp], ak[kp], typ[kp]
    li = line_info(w, voc, rng, ap, cur[ao])
    pd = typ == b"PRICE_DROP"
    active = rng.random(ao.size) < 0.7
    emit("cart_db", "alerts", dict(
        id=W.uid_s(w, "alert", u[ao] * 64 + ak), user_id=uid_u[ao], email=usr["email"][ao], product_id=W.uid_s(w, "product", ap),
        variant_id=li["vid"], product_name=li["name"], type=typ, target_price=nul(m4((li["cents"] * rng.uniform(0.7, 0.95, ao.size)).astype(np.int64)), ~pd),
        last_price=nul(m4(li["cents"]), ~pd), currency=cur[ao], active=e_bool(active), triggered_at=nul(e_ts(np.minimum(at + 5 * 86400, as_of)), active),
        created_at=e_ts(at)))
    # lists (+ items)
    lm = np.flatnonzero(rng.random(n) < 0.25)
    lo, lk = group_expand(rng.integers(1, 4, lm.size))
    lkey = u[lm][lo] * 4 + lk
    shared = (lk > 0) & (rng.random(lo.size) < 0.5)
    lname = np.where(lk == 0, b"My Wish List", pick(np.array([b"Gift ideas", b"Home office", b"Birthday", b"Someday", b"Kitchen"]), lkey, "l.n", w.seed))
    lcreated = ts_between(rng, signup[lm][lo], np.full(lo.size, as_of))
    emit("cart_db", "lists", dict(
        id=W.uid_s(w, "list", lkey), user_id=uid_u[lm][lo], name=lname, is_default=e_bool(lk == 0), visibility=np.where(shared, b"SHARED", b"PRIVATE"),
        share_token=nul(cat(rchars(rng, ALNUM, lo.size, 32), base36(lkey, 8)), ~shared), owner_name=usr["first"][lm][lo], created_at=e_ts(lcreated)))
    io_, ik = group_expand(rng.poisson(3.0, lo.size))
    t = ts_between(rng, lcreated[io_], np.full(io_.size, as_of))
    pp = _avail_pick(w, rng, t)
    kp = first_of_key(io_ * w.P + pp)
    io_, ik, pp, t = io_[kp], ik[kp], pp[kp], t[kp]
    li = line_info(w, voc, rng, pp, cur[lm][lo][io_])
    emit("cart_db", "list_items", dict(
        id=W.uid_s(w, "litem", lkey[io_] * 64 + ik), list_id=W.uid_s(w, "list", lkey[io_]), product_id=W.uid_s(w, "product", pp),
        variant_id=li["vid"], variant_label=li["label"], name=li["name"], price=m4(li["cents"]), currency=cur[lm][lo][io_], image_url=li["image"],
        note=nul(pick(voc["lorem"], pp, None), rng.random(io_.size) > 0.1), added_at=e_ts(t)))
    # ---------------------------------------------------------------- browsing history
    ro, rk = group_expand(np.minimum(rng.poisson(5, n), 25))
    t = ts_between(rng, signup[ro], np.full(ro.size, as_of))
    pp = _avail_pick(w, rng, t)
    kp = first_of_key(ro * w.P + pp)
    ro, pp, t = ro[kp], pp[kp], t[kp]
    emit("user_db", "recently_viewed", dict(user_id=uid_u[ro], product_id=W.uid_s(w, "product", pp), viewed_at=e_ts(t)))
    vo, vk = group_expand(rng.poisson(14, n))
    t = ts_between(rng, signup[vo], np.full(vo.size, as_of))
    pp = _avail_pick(w, rng, t)
    emit("product_db", "product_views", dict(
        id=e_int(serial_ids(ctx, "product_views", vo.size)), session_id=cat(b"s-", base36(u[vo] * 4 + rng.integers(0, 4, vo.size), 8)),
        product_id=W.uid_s(w, "product", pp), viewed_at=e_ts(t)))
