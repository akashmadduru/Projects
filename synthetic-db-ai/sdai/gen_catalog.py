"""Stage B: catalog + inventory for a range of product indexes.

Runs AFTER all customer chunks: the denormalised columns the real services keep in sync by events
(products.average_rating / rating_count / review_count / rating_distribution / seller_rating,
inventory reserved_quantity / status) are computed from the exact tallies of the rows already generated, so
they match rating_db, order_db and inventory_db without any cross-database query.
"""
from __future__ import annotations

import numpy as np

from . import world as W
from .gen_util import ALNUM, STAFF, m2, m4, rchars, ts_between
from .rand import (base36, cat, e_bool, e_int, e_json_dist, e_money, e_ts, group_expand, nul, pick, rint, uni)

NUL = b"\\N"


def catalog_chunk(ctx, lo, hi, emit):
    w, voc, rng, agg = ctx.w, ctx.voc, ctx.rng, ctx.agg
    as_of = w.as_of
    p = np.arange(lo, hi, dtype=np.int64)
    n = p.size
    pr = W.products(w, voc, p)
    sc = pr["seller"]
    acme = sc >= w.S
    sl = W.sellers(w, voc, np.minimum(sc, w.S - 1))
    created = pr["created"]
    upd = np.minimum(created + rng.integers(0, 120 * 86400, n), as_of)
    hist = agg["rating_hist"][lo:hi] if "rating_hist" in agg else np.zeros((n, 5), dtype=np.int64)
    cnt = hist.sum(1)
    avg = np.where(cnt > 0, (hist * np.arange(1, 6)).sum(1) / np.maximum(cnt, 1), 0.0)
    rev = agg["review_text"][lo:hi] if "review_text" in agg else np.zeros(n, dtype=np.int64)
    sfb = agg.get("seller_fb")
    if sfb is not None:
        sr_cnt = sfb[sc, 1]
        s_rating = np.where(sr_cnt > 0, sfb[sc, 0] / np.maximum(sr_cnt, 1), -1.0)
    else:
        s_rating = np.full(n, -1.0)
    s_rating = np.where(acme, 4.5, s_rating)
    cents, sale = pr["cents"], pr["sale"]
    reserved = agg["reserved"][lo:hi] if "reserved" in agg else np.zeros(n, dtype=np.int64)
    avail = np.where(uni(p, "i.oos", w.seed) < 0.06, 0, np.minimum((np.exp(rng.normal(4.6, 1.1, n))).astype(np.int64), 5000))
    qty = reserved + avail
    thr = rint(p, "i.thr", 5, 31, w.seed)
    status = np.where(avail == 0, b"OUT_OF_STOCK", np.where(avail <= thr, b"LOW_STOCK", b"IN_STOCK"))
    # primary warehouse of the listing seller
    wi0 = np.minimum(sc, w.S - 1) * 4
    whp = W.wh_attrs(w, voc, wi0)
    acme_idx = rint(p, "wh.p", 0, 3, w.seed)
    acme_code = np.array([x[1].encode() for x in W.ACME_WH], dtype="S8")
    wh_code = np.where(acme, acme_code[acme_idx], whp["code"])
    thumb = W.image_url(w, p, 0)
    disc_pct = np.where(sale > 0, np.round((cents - sale) * 100.0 / cents, 2), 0.0)
    dims = lambda s: e_money((rint(p, s, 50, 6000, w.seed)).astype(np.int64), 2)
    desc_len = rng.integers(2, 6, n)
    body_pool = voc["s_body"]
    ds = [pick(body_pool, p * 7 + i, "p.ds", w.seed) for i in range(5)]
    desc = cat(pick(voc["s_intro"], p, "p.di", w.seed), b" ", ds[0], b" ", ds[1])
    for i in (2, 3, 4):
        desc = np.where(desc_len > i, cat(desc, b" ", ds[i]), desc)
    desc = np.where(rng.random(n) < 0.5, cat(desc, b" ", pick(voc["s_close"], p, "p.dc", w.seed)), desc)
    llm = voc.get("llm_descriptions")
    if llm is not None:
        desc = np.where(rng.random(n) < 0.7, cat(pick(llm, p, "p.dl", w.seed), b" ", ds[0]), desc)
    name = pr["name"]
    specs = cat(b'{"weight_g": ', e_int(pr["weight"]), b', "material": "', pr["material"], b'", "warranty_months": ', e_int(rint(p, "p.war", 6, 37, w.seed)), b"}")
    emit("product_db", "products", dict(
        id=W.uid_s(w, "product", p), sku=pr["sku"], name=name, price_amount=m2(cents), price_currency=np.full(n, b"USD"), active=e_bool(uni(p, "p.act", w.seed) < 0.95),
        created_at=e_ts(created), updated_at=e_ts(upd), version=e_int(np.zeros(n)), category_id=W.uid_s(w, "cat", pr["sub"] + 10), seller_name=np.where(acme, b"Acme Retail", sl["name"]),
        specifications=specs, last_known_quantity=e_int(qty), description=desc, list_price=m2(cents), sale_price=nul(m2(np.maximum(sale, 0)), sale < 0),
        discount_percent=nul(e_money((disc_pct * 100).astype(np.int64), 2), sale < 0), thumbnail_url=thumb,
        video_urls=nul(cat(b'["https://cdn.shop.example.com/v/', base36(p, 7), b'.mp4"]'), uni(p, "p.vid", w.seed) > 0.4),
        view_360_url=nul(cat(b"https://cdn.shop.example.com/360/", base36(p, 7)), uni(p, "p.360", w.seed) > 0.05), weight_grams=e_int(pr["weight"]),
        dimensions_length=dims("p.dl"), dimensions_width=dims("p.dw"), dimensions_height=dims("p.dh"), color=pr["color"], material=pr["material"],
        average_rating=nul(e_money(np.round(avg * 100).astype(np.int64), 2), cnt == 0), rating_count=e_int(cnt), review_count=e_int(rev),
        rating_distribution=e_json_dist(*(hist[:, i] for i in range(5))), is_featured=e_bool(uni(p, "p.feat", w.seed) < 0.03),
        seller_rating=nul(e_money(np.round(np.maximum(s_rating, 0) * 100).astype(np.int64), 2), s_rating < 0),
        is_seller_verified=e_bool(acme | ((sl["status"] == b"APPROVED") & (uni(p, "p.ver", w.seed) < 0.7))), brand=pr["brand"],
        attributes=cat(b'{"brand": "', pr["brand"], b'", "color": "', pr["color"], b'"}'),
        slug=cat(np.char.replace(np.char.lower(name), b" ", b"-"), b"-", base36(p, 7), W.seed_tag(w)),
        seller_id=W.seller_code_to_uid_s(w, sc), listed_by=np.where(acme, b"PLATFORM", b"SELLER")))
    # images -------------------------------------------------------------------------------
    io, ik = group_expand(rng.integers(1, 6, n))
    emit("product_db", "product_images", dict(id=W.uid_s(w, "pimg", p[io] * 8 + ik), product_id=W.uid_s(w, "product", p[io]), url=W.image_url(w, p[io], ik),
                                              display_order=e_int(ik), created_at=e_ts(created[io] + 60)))
    # variants ----------------------------------------------------------------------------------
    vo, vk = group_expand(pr["nvar"])
    vp = p[vo]
    _, vjs = W.variant_label(w, voc, vp, vk, pr["root"][vo])
    vid = W.uid_s(w, "variant", vp * 8 + vk)
    emit("product_db", "product_variants", dict(
        id=vid, product_id=W.uid_s(w, "product", vp), sku=cat(pr["sku"][vo], b"-V", e_int(vk)), attributes=vjs, price_amount=np.full(vo.size, NUL), sale_price=np.full(vo.size, NUL),
        image_url=W.image_url(w, vp, vk), active=e_bool(np.ones(vo.size, bool)), sort_order=e_int(vk), created_at=e_ts(created[vo] + 120), updated_at=e_ts(created[vo] + 120)))
    # offers (base listing + competing sellers) ------------------------------------------------------
    oo, oj = group_expand(pr["noff"])
    op = p[oo]
    sub_pr = {k: (v[oo] if isinstance(v, np.ndarray) and v.shape[:1] == (n,) else v) for k, v in pr.items()}
    price, osale = W.offer_cents(w, sub_pr, oj)
    oseller = W.offer_seller(w, op, pr["seller"][oo], oj)
    cond = np.where(oj == 0, b"NEW", np.where(rng.random(oo.size) < 0.85, b"NEW", np.where(rng.random(oo.size) < 0.5, b"REFURBISHED", b"USED")))
    emit("product_db", "offers", dict(
        id=W.uid_s(w, "offer", op * 4 + oj), product_id=W.uid_s(w, "product", op), variant_id=np.full(oo.size, NUL), seller_id=W.seller_code_to_uid_s(w, oseller),
        price=m2(price), sale_price=nul(m2(np.maximum(osale, 0)), osale < 0), currency=np.full(oo.size, b"USD"), condition=cond,
        dispatch_days=e_int(rng.integers(0, 6, oo.size)), active=e_bool(rng.random(oo.size) < 0.97), created_at=e_ts(created[oo] + 30), updated_at=e_ts(upd[oo])))
    # inventory ----------------------------------------------------------------------------------------
    emit("inventory_db", "stock_levels", dict(
        id=W.uid_s(w, "stock", p), product_id=W.uid_s(w, "product", p), quantity=e_int(qty), version=e_int(rng.integers(0, 40, n)), created_at=e_ts(created + 30),
        updated_at=e_ts(upd), warehouse_code=wh_code, product_name=name, product_sku=pr["sku"], low_stock_threshold=e_int(thr), reserved_quantity=e_int(reserved),
        status=status, last_updated_at=e_ts(upd)))
    vq = rng.integers(0, 400, vo.size)
    emit("inventory_db", "variant_stock", dict(variant_id=vid, product_id=W.uid_s(w, "product", vp), quantity=e_int(vq), updated_at=e_ts(upd[vo])))
    # warehouse_stock: the base offer's stock is split over the seller's warehouses (sum == stock_levels.quantity)
    nwh = np.where(acme, 3, W.n_wh(w, np.minimum(sc, w.S - 1)))
    wo, wk = group_expand(nwh)
    share = np.where(wk == nwh[wo] - 1, 0, (qty[wo] // nwh[wo]))
    given = np.bincount(wo, weights=share, minlength=n).astype(np.int64)
    wq = np.where(wk == nwh[wo] - 1, qty[wo] - given[wo], share)
    wacme = acme[wo]
    acme_wid = np.array([x[0].encode() for x in W.ACME_WH], dtype="S36")
    wid = np.where(wacme, acme_wid[np.minimum(wk, 2)], W.uid_s(w, "wh", np.minimum(sc[wo], w.S - 1) * 4 + np.minimum(wk, 3)))
    emit("inventory_db", "warehouse_stock", dict(
        id=W.uid_s(w, "wstock", p[wo] * 8 + wk), product_id=W.uid_s(w, "product", p[wo]), variant_id=np.full(wo.size, NUL), seller_id=W.seller_code_to_uid_s(w, sc[wo]),
        offer_id=W.uid_s(w, "offer", p[wo] * 4), warehouse_id=wid, quantity=e_int(wq), updated_at=e_ts(upd[wo])))
    # ad campaigns for ~3 % of listings --------------------------------------------------------------------
    am = np.flatnonzero(uni(p, "ad.on", w.seed) < 0.03)
    if am.size:
        acr = ts_between(rng, created[am] + 86400, np.full(am.size, as_of))
        budget = rng.integers(5, 200, am.size)
        spent = np.minimum(rng.integers(0, 200, am.size), budget)
        kw = cat(b"{", np.char.lower(pr["noun"][am]), b",", np.char.lower(pr["brand"][am]), b"}")
        emit("seller_db", "ad_campaigns", dict(
            id=W.uid_s(w, "ad", p[am]), seller_id=W.seller_code_to_uid_s(w, sc[am]), product_id=W.uid_s(w, "product", p[am]), keywords=kw,
            category_id=W.uid_s(w, "cat", pr["sub"][am] + 10), bid_cpc=m2(rng.integers(5, 150, am.size)), daily_budget=m2(budget * 100), currency=np.full(am.size, b"USD"),
            status=np.where(spent >= budget, b"EXHAUSTED", rng.choice([b"ACTIVE", b"PAUSED"], am.size, p=[0.8, 0.2])), spent_today=m2(spent * 100),
            clicks=e_int(rng.integers(0, 20000, am.size)), impressions=e_int(rng.integers(1000, 900000, am.size)), created_at=e_ts(acr)))
