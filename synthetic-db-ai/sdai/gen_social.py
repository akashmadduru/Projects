"""rating_db (reviews, votes, Q&A), support_db (tickets, chat, audit log), notification_db and referral claims.

Reviews are attached to the reviewer's real delivered purchases (is_verified_purchase = true) plus a small
share of unverified ones; helpful/unhelpful counters equal the generated vote rows and questions' answer_count
equals the generated answers, so every denormalised counter is exact.
"""
from __future__ import annotations

import numpy as np

from . import world as W
from .gen_orders import NUL, STATUS
from .gen_util import ALNUM, B64, HEX, STAFF, ipv4, next_idx, rchars, serial_ids, ts_between
from .rand import (base36, cat, e_bool, e_int, e_ts, first_of_key, group_expand, nul, pick, e_money)
from .vocab import _NOTIF

SLA_FIRST = {b"URGENT": 3600, b"HIGH": 4 * 3600, b"NORMAL": 8 * 3600, b"LOW": 24 * 3600}
SLA_RESOLVE = {b"URGENT": 4 * 3600, b"HIGH": 24 * 3600, b"NORMAL": 72 * 3600, b"LOW": 120 * 3600}


def _sentiment_text(voc, key, stars, seed, llm):
    """title/text for each rating from sentiment pools (+ optional LLM-written pools)."""
    pos, neu, neg = stars >= 4, stars == 3, stars <= 2
    def pool(name, mask):
        p = voc.get("llm_" + name)
        base = voc[name]
        return base if p is None else np.concatenate([base, p, p])
    text = np.empty(stars.size, dtype=object)
    title = np.empty(stars.size, dtype=object)
    out_t = np.empty(stars.size, dtype="S400"); out_h = np.empty(stars.size, dtype="S60")
    for nm, m in (("pos", pos), ("neu", neu), ("neg", neg)):
        if not m.any():
            continue
        pr = pool(f"rev_{nm}", m)
        k = key[m]
        s1 = pick(pr, k, "rv.1", seed); s2 = pick(pr, k, "rv.2", seed); s3 = pick(voc["lorem"], k, "rv.3", seed)
        two = (k % 3) != 0
        out_t[m] = np.where(two, cat(s1, b" ", s2), s1)
        out_t[m] = np.where((k % 5) == 0, cat(out_t[m], b" ", s3), out_t[m])
        out_h[m] = pick(voc[f"title_{nm}"], k, "rv.t", seed)
    return out_h, out_t


def emit_reviews_and_qa(ctx, o, emit):
    w, voc, rng = ctx.w, ctx.voc, ctx.rng
    as_of, usr, u, uid_u, n = w.as_of, o.usr, o.u, o.uid_u, o.n
    # verified reviews from delivered purchases + a few unverified ones --------------------------------
    cand = np.flatnonzero(o.sh_delivered[o.sinv] & (rng.random(o.M) < 0.16))
    c_user = o.oo[o.io[cand]]
    c_prod = o.p[cand]
    c_ts = o.MS[o.sinv[cand], 5] + rng.integers(86400, 21 * 86400, cand.size)
    c_ret = o.in_ret[cand]
    ok = c_ts <= as_of
    cand, c_user, c_prod, c_ts, c_ret = cand[ok], c_user[ok], c_prod[ok], c_ts[ok], c_ret[ok]
    ver_n = cand.size
    um = np.flatnonzero(rng.random(n) < 0.05)
    uo, _ = group_expand(rng.integers(1, 4, um.size))
    t_un = ts_between(rng, usr["signup"][um][uo] + 86400, np.full(uo.size, as_of))
    na = W.n_avail(w, t_un)
    p_un = np.minimum((na * rng.random(uo.size) ** 1.8).astype(np.int64), na - 1)
    r_user = np.concatenate([c_user, um[uo]])
    r_prod = np.concatenate([c_prod, p_un])
    r_ts = np.concatenate([c_ts, t_un])
    r_ver = np.concatenate([np.ones(ver_n, bool), np.zeros(uo.size, bool)])
    r_ret = np.concatenate([c_ret, np.zeros(uo.size, bool)])
    keep = first_of_key(r_user * w.P + r_prod)                 # UNIQUE (product_id, user_id)
    r_user, r_prod, r_ts, r_ver, r_ret = r_user[keep], r_prod[keep], r_ts[keep], r_ver[keep], r_ret[keep]
    R = r_user.size
    if R == 0:
        return
    pr = W.products(w, voc, r_prod)
    stars = np.clip(np.floor(pr["quality"] + 0.9 * rng.standard_normal(R) - 0.7 * r_ret + 0.5), 1, 5).astype(np.int64)
    rkey = u[r_user] * w.P + r_prod
    rid = W.uid_s(w, "rating", rkey)
    title, text = _sentiment_text(voc, rkey, stars, w.seed, True)
    has_text = rng.random(R) < 0.75
    # votes
    nv = np.minimum(rng.poisson(0.9, R), 6)
    vo, vk = group_expand(nv)
    voter = rng.integers(0, w.U, vo.size)
    keepv = first_of_key(vo * w.U + voter) & (voter != r_user[vo])
    vo, vk, voter = vo[keepv], vk[keepv], voter[keepv]
    helpful = rng.random(vo.size) < 0.8
    h_cnt = np.bincount(vo, weights=helpful, minlength=R).astype(np.int64)
    u_cnt = np.bincount(vo, weights=~helpful, minlength=R).astype(np.int64)
    flagged = rng.random(R) < 0.008
    emit("rating_db", "ratings", dict(
        id=rid, product_id=W.uid_s(w, "product", r_prod), user_id=uid_u[r_user], user_display_name=usr["name"][r_user], rating_value=e_int(stars),
        review_title=nul(title, ~has_text), review_text=nul(text, ~has_text), is_verified_purchase=e_bool(r_ver), helpful_count=e_int(h_cnt), unhelpful_count=e_int(u_cnt),
        version=e_int(np.zeros(R)), created_at=e_ts(r_ts), updated_at=e_ts(r_ts), flagged=e_bool(flagged),
        flag_reasons=nul(np.full(R, b"duplicate_text"), ~flagged), moderated=e_bool(flagged & (rng.random(R) < 0.5))))
    vts = np.minimum(r_ts[vo] + rng.integers(3600, 60 * 86400, vo.size), as_of)
    emit("rating_db", "rating_votes", dict(id=W.uid_s(w, "rvote", next_idx(ctx, vo.size)), rating_id=rid[vo], user_id=W.uid_s(w, "user", voter),
                                           vote_type=np.where(helpful, b"HELPFUL", b"UNHELPFUL"), created_at=e_ts(vts)))
    mi = np.flatnonzero(has_text & (rng.random(R) < 0.06))
    emit("rating_db", "review_media", dict(
        id=W.uid_s(w, "rmedia", rkey[mi]), rating_id=rid[mi], file_name=cat(b"review-", base36(rkey[mi], 9), b".jpg"), content_type=np.full(mi.size, b"image/jpeg"),
        size_bytes=e_int(rng.integers(40_000, 4_000_000, mi.size)), created_at=e_ts(r_ts[mi])))
    # product / seller tallies for the catalog stage
    for s in range(1, 6):
        m = stars == s
        ctx.tally_add("rating_hist", (w.P, 5), (r_prod[m], s - 1), 1)
    ctx.tally_add("review_text", (w.P,), (r_prod[has_text],), 1)
    # questions & answers ---------------------------------------------------------------------------------
    qm = np.flatnonzero(rng.random(n) < 0.10)
    if qm.size:
        qt = ts_between(rng, usr["signup"][qm] + 86400, np.full(qm.size, as_of))
        na = W.n_avail(w, qt)
        qp = np.minimum((na * rng.random(qm.size) ** 1.8).astype(np.int64), na - 1)
        qpr = W.products(w, voc, qp)
        qkey = u[qm] * 4 + rng.integers(0, 4, qm.size)
        qkey = qkey + 0 * qp
        keepq = first_of_key(qkey)
        qm, qt, qp, qpr, qkey = qm[keepq], qt[keepq], qp[keepq], {k: v[keepq] for k, v in qpr.items() if isinstance(v, np.ndarray)}, qkey[keepq]
        pre = np.array([b"Is the ", b"Does the ", b"Can the ", b"How long does the "])
        suf = np.array([b" compatible with other brands?", b" come with a warranty?", b" work for daily heavy use?", b" ship with all accessories?", b" last long?"])
        qtext = cat(pick(pre, qkey, "q.p", w.seed), qpr["noun"], pick(suf, qkey, "q.s", w.seed))
        na_ = np.minimum(rng.poisson(1.2, qm.size), 4)
        ao, ak = group_expand(na_)
        a_user = rng.integers(0, w.U, ao.size)
        au = W.users(w, voc, a_user)
        a_ts = np.minimum(qt[ao] + rng.integers(3600, 20 * 86400, ao.size), as_of)
        a_id = W.uid_s(w, "answer", qkey[ao] * 8 + ak)
        q_id = W.uid_s(w, "question", qkey)
        # votes on questions / answers
        qv_n = np.minimum(rng.poisson(0.6, qm.size), 5); av_n = np.minimum(rng.poisson(0.5, ao.size), 5)
        qvo, _ = group_expand(qv_n); avo, _ = group_expand(av_n)
        qv_u = rng.integers(0, w.U, qvo.size); av_u = rng.integers(0, w.U, avo.size)
        kq = first_of_key(qvo * w.U + qv_u); ka = first_of_key(avo * w.U + av_u)
        qvo, qv_u, avo, av_u = qvo[kq], qv_u[kq], avo[ka], av_u[ka]
        emit("rating_db", "questions", dict(
            id=q_id, product_id=W.uid_s(w, "product", qp), user_id=uid_u[qm], author_name=usr["name"][qm], text=qtext,
            answer_count=e_int(np.bincount(ao, minlength=qm.size)), upvotes=e_int(np.bincount(qvo, minlength=qm.size)), created_at=e_ts(qt)))
        emit("rating_db", "answers", dict(
            id=a_id, question_id=q_id[ao], user_id=W.uid_s(w, "user", a_user), author_name=au["name"], text=pick(voc["agent_msgs"], a_user + ao),
            verified_buyer=e_bool(np.zeros(ao.size, bool)), upvotes=e_int(np.bincount(avo, minlength=ao.size)), created_at=e_ts(a_ts)))
        emit("rating_db", "qa_votes", dict(
            item_type=np.concatenate([np.full(qvo.size, b"QUESTION"), np.full(avo.size, b"ANSWER")]), item_id=np.concatenate([q_id[qvo], a_id[avo]]),
            user_id=W.uid_s(w, "user", np.concatenate([qv_u, av_u])), created_at=e_ts(np.concatenate([qt[qvo] + 600, a_ts[avo] + 600]).clip(max=as_of))))


def emit_support_audit_notify(ctx, o, F, emit):
    w, voc, rng = ctx.w, ctx.voc, ctx.rng
    as_of, usr, u, uid_u, n, N = w.as_of, o.usr, o.u, o.uid_u, o.n, o.N
    # ------------------------------------------------------------- tickets + messages
    tm = np.flatnonzero(rng.random(n) < 0.22)
    cats = np.array([b"ORDERS", b"PAYMENTS", b"RETURNS", b"ACCOUNT", b"SELLERS", b"OTHER"])
    cat_i = rng.choice(6, tm.size, p=[0.35, 0.2, 0.15, 0.12, 0.08, 0.10])
    cat_b = cats[cat_i]
    tkey = u[tm] * 8 + rng.integers(0, 8, tm.size)
    keep = first_of_key(tkey)
    tm, cat_i, cat_b, tkey = tm[keep], cat_i[keep], cat_b[keep], tkey[keep]
    T = tm.size
    # link a ticket to one of the user's own orders (first order of that user in this chunk)
    first_order = np.full(n, -1, dtype=np.int64)
    if N:
        fo = np.full(n, N, dtype=np.int64)
        np.minimum.at(fo, o.oo, np.arange(N))
        first_order = np.where(fo == N, -1, fo)
    lo_ = first_order[tm]
    linked = (lo_ >= 0) & (cat_i <= 4) & (rng.random(T) < 0.8)
    lo_safe = np.maximum(lo_, 0)
    base_t = np.where(linked, o.placed[lo_safe], usr["signup"][tm] + 3600) if N else usr["signup"][tm] + 3600
    created = ts_between(rng, base_t, np.full(T, as_of))
    prio = rng.choice([b"LOW", b"NORMAL", b"HIGH", b"URGENT"], T, p=[0.2, 0.55, 0.19, 0.06])
    age = as_of - created
    st = np.where(age < 3600, b"OPEN", np.where(age < 2 * 86400, rng.choice([b"OPEN", b"PENDING_AGENT", b"PENDING_CUSTOMER"], T), np.where(age < 14 * 86400,
                  rng.choice([b"PENDING_CUSTOMER", b"RESOLVED", b"CLOSED"], T, p=[0.2, 0.5, 0.3]), rng.choice([b"RESOLVED", b"CLOSED"], T, p=[0.3, 0.7]))))
    closed = np.isin(st, [b"RESOLVED", b"CLOSED"])
    # messages per ticket
    nm_ = rng.integers(1, 7, T)
    mo, mk = group_expand(nm_)
    mts = np.minimum(created[mo] + mk * rng.integers(600, 36 * 3600, mo.size), as_of)
    mts = np.maximum(mts, created[mo])
    auth = np.where(mk == 0, b"CUSTOMER", np.where(mk % 2 == 1, rng.choice([b"AGENT", b"BOT", b"AGENT"], mo.size), np.where(rng.random(mo.size) < 0.9, b"CUSTOMER", b"SYSTEM")))
    mid = W.uid_s(w, "tmsg", tkey[mo] * 8 + mk)
    cust_msg = np.empty(T, dtype="S400")
    subj = np.empty(T, dtype="S200"); body = np.empty(T, dtype="S400")
    for i, cname in enumerate(cats):
        m = cat_i == i
        if m.any():
            pk = f"tsub_{cname.decode()}"
            subj[m] = pick(voc[pk], tkey[m], "t.s", w.seed)
            body[m] = pick(voc[f"tbody_{cname.decode()}"], tkey[m], "t.b", w.seed)
    am = auth == b"AGENT"
    fa_ts = np.full(T, 1 << 60, dtype=np.int64)
    np.minimum.at(fa_ts, mo[am], mts[am])
    has_fa = fa_ts < (1 << 60)
    last_ts = np.zeros(T, dtype=np.int64); np.maximum.at(last_ts, mo, mts)
    csat = nul(e_int(rng.integers(1, 6, T)), ~(closed & (rng.random(T) < 0.6)))
    assignee = W.uid_s(w, "user", rng.integers(4, 14, T))
    emit("support_db", "tickets", dict(
        id=W.uid_s(w, "ticket", tkey), user_id=uid_u[tm], email=usr["email"][tm], order_id=nul(o.oid[lo_safe] if N else np.full(T, NUL), ~linked),
        shipment_id=nul(o.ship_id[o.sh_first[lo_safe]] if N else np.full(T, NUL), ~(linked & (cat_i == 2))), category=cat_b,
        subject=np.where(linked, cat(subj, b" (order ", (o.oid[lo_safe].astype("S8") if N else subj), b")"), subj), status=st, priority=prio,
        assignee_user_id=nul(assignee, ~has_fa), created_at=e_ts(created), updated_at=e_ts(last_ts), first_response_at=nul(e_ts(fa_ts.clip(max=as_of)), ~has_fa),
        resolved_at=nul(e_ts(last_ts), ~closed), first_response_due_at=e_ts(created + np.array([SLA_FIRST[p] for p in prio])),
        resolution_due_at=e_ts(created + np.array([SLA_RESOLVE[p] for p in prio])), csat=csat))
    agent_pool = voc["agent_msgs"]; cust_pool = voc["cust_msgs"]
    mbody = np.where(mk == 0, body[mo], np.where(auth == b"CUSTOMER", pick(cust_pool, tkey[mo] + mk), pick(agent_pool, mo + mk)))
    emit("support_db", "ticket_messages", dict(
        id=mid, ticket_id=W.uid_s(w, "ticket", tkey)[mo], author_type=auth, author_id=nul(np.where(auth == b"AGENT", assignee[mo], uid_u[tm][mo]), (auth == b"BOT") | (auth == b"SYSTEM")),
        body=mbody, internal=e_bool((auth == b"AGENT") & (rng.random(mo.size) < 0.1)), created_at=e_ts(mts)))
    # ------------------------------------------------------------- chat sessions / messages
    cm = np.flatnonzero(rng.random(n) < 0.06)
    cu_ = u[cm] * 4 + rng.integers(0, 4, cm.size)
    keepc = first_of_key(cu_)
    cm, cu_ = cm[keepc], cu_[keepc]
    sess = cat(b"sess-", base36(cu_, 9), W.seed_tag(w))
    cts = ts_between(rng, usr["signup"][cm], np.full(cm.size, as_of))
    cn = rng.integers(2, 9, cm.size)
    co, ck = group_expand(cn)
    emit("support_db", "chat_sessions", dict(session_id=sess, user_id=nul(uid_u[cm], rng.random(cm.size) < 0.1), low_conf=e_int(rng.integers(0, 3, cm.size)),
                                             created_at=e_ts(cts), updated_at=e_ts(np.minimum(cts + cn * 60, as_of))))
    emit("support_db", "chat_messages", dict(
        id=e_int(serial_ids(ctx, "chat_messages", co.size)), session_id=sess[co], role=np.where(ck % 2 == 0, b"user", b"bot"),
        text=np.where(ck % 2 == 0, pick(voc["chat_q"], cu_[co] + ck), pick(voc["chat_a"], cu_[co] + ck)), created_at=e_ts(np.minimum(cts[co] + ck * 30, as_of))))
    # ------------------------------------------------------------- audit log: staff actions on orders/returns/etc.
    if N:
        am_ = np.flatnonzero((rng.random(N) < 0.25) & (o.o_rank >= 1))
        a_new = np.array(STATUS)[np.minimum(o.o_rank[am_], 5)]
        a_old = np.array(STATUS)[np.maximum(o.o_rank[am_] - 1, 0)]
        ev_n = am_.size
    else:
        am_ = np.array([], dtype=np.int64); a_new = a_old = np.array([], dtype="S1"); ev_n = 0
    gm = np.flatnonzero(rng.random(n) < 0.3)
    gt = ts_between(rng, usr["signup"][gm], np.full(gm.size, as_of))
    act_g = pick(voc["audit_actions"], u[gm], "au.a", w.seed)
    actor_idx = np.concatenate([rng.integers(4, 14, ev_n), rng.integers(0, STAFF, gm.size)])
    at = np.concatenate([(o.Ot[am_, np.minimum(o.o_rank[am_], 5)] if N else np.array([], dtype=np.int64)), gt]).clip(max=as_of)
    action = np.concatenate([np.full(ev_n, b"ORDER_STATUS_CHANGED"), act_g])
    rtype = np.concatenate([np.full(ev_n, b"ORDER"), np.full(gm.size, b"USER")])
    rid_ = np.concatenate([(o.oid[am_] if N else np.array([], dtype="S36")), uid_u[gm]])
    before = np.concatenate([cat(b'{"status":"', a_old, b'"}'), np.full(gm.size, b'{"active":true}')])
    after = np.concatenate([cat(b'{"status":"', a_new, b'"}'), np.full(gm.size, b'{"active":false}')])
    roles = np.where(actor_idx < 4, b"ROLE_ADMIN", np.where(actor_idx < 14, b"ROLE_SUPPORT", b"ROLE_FINANCE"))
    A = at.size
    emit("support_db", "audit_log", dict(
        id=W.uid_s(w, "audit", next_idx(ctx, A)), actor_user_id=W.uid_s(w, "user", actor_idx), actor_roles=roles, action=action, resource_type=rtype, resource_id=rid_,
        before_json=before, after_json=after, ip=ipv4(rng, A), at=e_ts(at), received_at=e_ts(at + 1)))
    # ------------------------------------------------------------- notifications (+ push subscriptions)
    nt, nu_, nti, nbody, nlink, ndk = [], [], [], [], [], []
    if N:
        for r, label in ((0, b"PLACED"), (3, b"SHIPPED"), (5, b"DELIVERED")):
            m = (o.o_rank >= r) & (~o.canc | (o.Ot[:, r] <= o.cancel_ts))
            k = int(m.sum())
            nt.append(o.Ot[m, r] + 5); nu_.append(uid_u[o.oo[m]]); nlink.append(cat(b"/orders/", o.oid[m]))
            idx = {0: 0, 3: 2, 5: 4}[r]
            nti.append(np.full(k, _NOTIF["ORDER"][idx].encode()))
            nbody.append(cat(b"Order #", np.char.upper(o.oid[m].astype("S8")), b" - ", np.full(k, _NOTIF["ORDER"][idx].encode()), b"."))
            ndk.append(cat(b"order:", o.oid[m], b":", np.full(k, label)))
        m = o.canc
        k = int(m.sum())
        nt.append(o.cancel_ts[m] + 5); nu_.append(uid_u[o.oo[m]]); nlink.append(cat(b"/orders/", o.oid[m])); nti.append(np.full(k, b"Your order was cancelled"))
        nbody.append(cat(b"Order #", np.char.upper(o.oid[m].astype("S8")), b" was cancelled and any payment will be refunded.")); ndk.append(cat(b"order:", o.oid[m], b":CANCELLED"))
    types_order = N and 1
    promo_n = rng.poisson(4, n)
    po, pk = group_expand(promo_n)
    pt = ts_between(rng, usr["signup"][po], np.full(po.size, as_of))
    pkey = u[po] * 16 + pk
    nt.append(pt); nu_.append(uid_u[po]); nlink.append(np.full(po.size, b"/deals")); nti.append(pick(voc["notif_PROMO"], pkey, "n.p", w.seed))
    nbody.append(cat(pick(voc["notif_PROMO"], pkey, "n.p", w.seed), b". ", pick(voc["lorem"], pkey, "n.b", w.seed))); ndk.append(cat(b"promo:", base36(pkey, 9), W.seed_tag(w)))
    t_all = np.concatenate(nt); u_all = np.concatenate(nu_); ti_all = np.concatenate(nti)
    typ_all = np.concatenate([np.full(a.size, b"ORDER") for a in nt[:-1]] + [np.full(nt[-1].size, b"PROMO")]) if nt else np.array([], dtype="S5")
    L = t_all.size
    t_all = np.minimum(t_all, as_of)
    read = (rng.random(L) < 0.75) & (t_all < as_of - 3 * 86400)
    emit("notification_db", "notifications", dict(
        id=W.uid_s(w, "notif", next_idx(ctx, L)), user_id=u_all, type=typ_all, title=ti_all, body=np.concatenate(nbody), link=np.concatenate(nlink),
        dedupe_key=np.concatenate(ndk), read_at=nul(e_ts(t_all + rng.integers(60, 86400, L)), ~read), created_at=e_ts(t_all)))
    pm = np.flatnonzero(rng.random(n) < 0.20)
    emit("notification_db", "push_subscriptions", dict(
        id=W.uid_s(w, "push", u[pm]), user_id=uid_u[pm], endpoint=cat(b"https://fcm.googleapis.com/fcm/send/", rchars(rng, ALNUM, pm.size, 100), base36(u[pm], 8)),
        p256dh=rchars(rng, B64, pm.size, 87), auth=rchars(rng, B64, pm.size, 22), created_at=e_ts(usr["signup"][pm] + 600),
        last_success_at=nul(e_ts(np.full(pm.size, as_of - 3600)), rng.random(pm.size) < 0.2)))
    # ------------------------------------------------------------- referral claims (needs delivered-order facts)
    ri = F["ref_idx"]
    rew = F.get("ref_rewarded", np.full(ri.size, -1))
    emit("user_db", "referral_claims", dict(
        referee_user_id=uid_u[ri], referrer_user_id=W.uid_s(w, "user", F["ref_referrer"]), code=F["ref_code"], claimed_at=e_ts(F["ref_claimed"]),
        rewarded_at=nul(e_ts(np.where(rew < 0, 0, rew)), rew < 0)))
