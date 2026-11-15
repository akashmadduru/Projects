"""auth_db + user_db rows for a chunk of customers (identity, addresses, membership, referrals)."""
from __future__ import annotations

import numpy as np

from . import world as W
from .gen_util import (B64, HEX, STAFF, rchars, ts_between, ipv4)
from .rand import (mix, b32code, cat, e_bool, e_date, e_int, e_money, e_ts, group_expand, nul, pick, rint, uni, first_of_key, base36)

UA = np.array([b"Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0", b"Mozilla/5.0 (iPhone; CPU iPhone OS 17_5) Safari/605.1.15",
               b"Mozilla/5.0 (Linux; Android 14; Pixel 8) Chrome/126.0 Mobile", b"Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) Safari/605.1.15",
               b"Mozilla/5.0 (X11; Linux x86_64) Firefox/127.0"])
PLANS = {"PLUS_MONTHLY": (30, {"INR": 14900, "USD": 299, "EUR": 279}), "PLUS_YEARLY": (365, {"INR": 99900, "USD": 1999, "EUR": 1899})}


def referral_code(u, seed=0):
    """8-char code, a bijection of the user index (unique) that differs per seed."""
    x = (np.asarray(u, dtype=np.uint64) * np.uint64(0x9E3779B97F4A7C15 | 1)) ^ mix(np.uint64(seed))
    return b32code(x & np.uint64((1 << 40) - 1), 8)


def identity_chunk(ctx, u, usr, emit):
    """Returns per-user facts needed by later stages: membership interval, address counts."""
    w, voc, rng = ctx.w, ctx.voc, ctx.rng
    n = u.size
    uid_u = W.uid_s(w, "user", u)
    signup, cur = usr["signup"], usr["cur"]
    as_of = w.as_of
    r = rng.random(n)
    status = np.where(r < 0.012, b"DISABLED", np.where(r < 0.015, b"LOCKED", b"ACTIVE"))
    has_phone = rng.random(n) < 0.9
    phone_ver = has_phone & (rng.random(n) < 0.7)
    upd = np.minimum(signup + rng.integers(0, 90 * 86400, n), as_of)
    emit("auth_db", "users", dict(id=uid_u, email=usr["email"], email_verified=e_bool(rng.random(n) < 0.85), display_name=usr["name"],
                                  status=status, created_at=e_ts(signup), updated_at=e_ts(upd), version=e_int(np.zeros(n)),
                                  phone=nul(usr["phone"], ~has_phone), phone_verified=e_bool(phone_ver)))
    locked = status == b"LOCKED"
    emit("auth_db", "user_credentials", dict(
        user_id=uid_u, password_hash=cat(b"{argon2id}$argon2id" + b"$v=19$m=16384,t=2,p=1$", rchars(rng, B64, n, 22), b"$", rchars(rng, B64, n, 43)),
        algorithm=np.full(n, b"argon2id"), password_updated_at=e_ts(upd), failed_attempts=e_int(np.where(locked, 5, rng.integers(0, 2, n) * (rng.random(n) < 0.05))),
        locked_until=nul(e_ts(np.full(n, as_of + 3600)), ~locked), must_change_password=e_bool(rng.random(n) < 0.01)))
    fm = rng.random(n) < 0.30
    fi = np.flatnonzero(fm)
    emit("auth_db", "federated_identities", dict(
        id=W.uid_s(w, "fed", u[fi]), user_id=uid_u[fi], provider=np.full(fi.size, b"google"),
        provider_user_id=cat(b"1", np.char.zfill((u[fi] + (w.seed % 1000) * 10_000_000).astype("S"), 20)), provider_email=usr["email"][fi],
        email_verified_at_link=e_bool(np.ones(fi.size, bool)), linked_at=e_ts(signup[fi]),
        last_login_at=nul(e_ts(np.minimum(signup[fi] + 86400 * 5, as_of)), rng.random(fi.size) < 0.2)))
    # roles ------------------------------------------------------------------
    ur_u, ur_r = [uid_u], [np.full(n, 3)]
    ur_t = [signup]
    staff = u < STAFF
    sel = staff & (u == 0); ur_u.append(uid_u[sel]); ur_r.append(np.full(sel.sum(), 1)); ur_t.append(signup[sel])
    sel = staff & (u >= 1) & (u < 4); ur_u.append(uid_u[sel]); ur_r.append(np.full(sel.sum(), 2)); ur_t.append(signup[sel])
    sel = staff & (u >= 4) & (u < 14); ur_u.append(uid_u[sel]); ur_r.append(np.full(sel.sum(), 5)); ur_t.append(signup[sel])
    sel = staff & (u >= 14); ur_u.append(uid_u[sel]); ur_r.append(np.full(sel.sum(), 6)); ur_t.append(signup[sel])
    sel = (u >= STAFF) & (u < STAFF + w.S_ok)
    ur_u.append(uid_u[sel]); ur_r.append(np.full(sel.sum(), 4)); ur_t.append(signup[sel])
    emit("auth_db", "user_roles", dict(user_id=np.concatenate(ur_u), role_id=e_int(np.concatenate(ur_r)), granted_at=e_ts(np.concatenate(ur_t))))
    # refresh tokens -------------------------------------------------------------------
    nf = rng.choice(3, n, p=[0.2, 0.6, 0.2])
    fo, fk = group_expand(nf)
    flen = rng.integers(1, 4, fo.size)
    root_iss = signup[fo] + (rng.random(fo.size) * np.maximum(as_of - signup[fo] - 3600, 1)).astype(np.int64)
    meth = rng.choice([b"password", b"google", b"otp"], fo.size, p=[0.7, 0.2, 0.1])
    to, tk = group_expand(flen)                     # to: family position, tk: index in chain
    own = fo[to]
    iss = np.minimum(root_iss[to] + tk * rng.integers(86400, 6 * 86400, to.size), as_of - 1)
    keybase = u[own] * 16 + fk[to] * 4
    last = tk == (flen[to] - 1)
    fam_abs = root_iss[to] + 30 * 86400
    exp_at = np.minimum(iss + 7 * 86400, fam_abs)
    nxt_iss = np.concatenate((iss[1:], [0]))
    expired = exp_at < as_of
    rev_reason = np.where(~last, b"ROTATED", np.where(rng.random(to.size) < 0.3, b"LOGOUT", np.where(expired, b"EXPIRED", b"")))
    revoked = rev_reason != b""
    rev_at = np.where(~last, nxt_iss, np.where(rev_reason == b"EXPIRED", exp_at, np.minimum(iss + rng.integers(60, 86400, to.size), as_of)))
    emit("auth_db", "refresh_tokens", dict(
        id=W.uid_s(w, "rt", keybase + tk), user_id=uid_u[own], token_hash=rchars(rng, HEX, to.size, 64),
        family_id=W.uid_s(w, "rtfam", u[own] * 4 + fk[to]), parent_id=nul(W.uid_s(w, "rt", keybase + np.maximum(tk - 1, 0)), tk == 0),
        auth_method=meth[to], issued_at=e_ts(iss), expires_at=e_ts(exp_at), family_absolute_expires_at=e_ts(fam_abs),
        rotated_at=nul(e_ts(rev_at), rev_reason != b"ROTATED"), revoked_at=nul(e_ts(rev_at), ~revoked),
        revoked_reason=nul(rev_reason, ~revoked), user_agent=pick(UA, rng.integers(0, 5, to.size)), ip_address=ipv4(rng, to.size)))
    # one-time tokens -------------------------------------------------------------------------
    no = rng.poisson(0.3, n)
    oo, oord = group_expand(no)
    pur = rng.choice([b"PASSWORD_RESET", b"EMAIL_VERIFY", b"PHONE_VERIFY", b"OTP_LOGIN"], oo.size, p=[0.2, 0.4, 0.15, 0.25])
    cr = ts_between(rng, signup[oo], as_of)
    exp = cr + rng.integers(600, 86400, oo.size)
    cons = (rng.random(oo.size) < 0.7) & (exp < as_of)
    emit("auth_db", "one_time_tokens", dict(
        id=W.uid_s(w, "ott", u[oo] * 64 + oord), user_id=uid_u[oo], purpose=pur,
        token_hash=rchars(rng, HEX, oo.size, 64), target=nul(usr["email"][oo], pur == b"PHONE_VERIFY"), attempts=e_int(rng.integers(0, 3, oo.size)),
        created_at=e_ts(cr), expires_at=e_ts(exp), consumed_at=nul(e_ts(np.minimum(cr + 120, as_of)), ~cons)))
    # TOTP / recovery / mfa / devices ----------------------------------------------------------
    tm = rng.random(n) < 0.08
    ti = np.flatnonzero(tm)
    enabled = rng.random(ti.size) < 0.85
    tcr = ts_between(rng, signup[ti], as_of)
    emit("auth_db", "user_totp", dict(id=W.uid_s(w, "totp", u[ti]), user_id=uid_u[ti], secret_enc=rchars(rng, B64, ti.size, 64), enabled=e_bool(enabled),
                                      last_used_step=e_int(np.where(enabled, tcr // 30, 0)), created_at=e_ts(tcr), enabled_at=nul(e_ts(tcr + 60), ~enabled)))
    ei = ti[enabled]
    ro, rk = group_expand(np.full(ei.size, 8))
    emit("auth_db", "recovery_codes", dict(id=W.uid_s(w, "rec", u[ei][ro] * 8 + rk), user_id=uid_u[ei][ro], code_hash=rchars(rng, HEX, ro.size, 64),
                                           used_at=nul(e_ts(np.full(ro.size, as_of - 86400)), rng.random(ro.size) > 0.1), created_at=e_ts(signup[ei][ro] + 3600)))
    mo, mk = group_expand(rng.poisson(0.5, ei.size))
    mcr = ts_between(rng, signup[ei][mo], as_of)
    emit("auth_db", "mfa_challenges", dict(id=W.uid_s(w, "mfa", u[ei][mo] * 16 + mk), token_hash=rchars(rng, HEX, mo.size, 64), user_id=uid_u[ei][mo],
                                           auth_method=np.full(mo.size, b"password"), attempts=e_int(rng.integers(0, 3, mo.size)), created_at=e_ts(mcr),
                                           expires_at=e_ts(mcr + 300), consumed_at=nul(e_ts(mcr + 40), rng.random(mo.size) < 0.1)))
    do, dk = group_expand(rng.integers(1, 4, n))
    fs = ts_between(rng, signup[do], as_of)
    emit("auth_db", "known_devices", dict(id=W.uid_s(w, "dev", u[do] * 4 + dk), user_id=uid_u[do], fingerprint=rchars(rng, HEX, do.size, 64),
                                          first_seen=e_ts(fs), last_seen=e_ts(ts_between(rng, fs, as_of))))
    # ================= user_db ======================================================
    emit("user_db", "user_profiles", dict(user_id=uid_u, email=usr["email"], display_name=usr["name"], phone=nul(usr["phone"], ~has_phone),
                                          created_at=e_ts(signup), updated_at=e_ts(upd), version=e_int(np.zeros(n))))
    n_addr = 1 + rint(u, "u.naddr", 0, 3, w.seed)
    ao, ak = group_expand(n_addr)
    akey = u[ao] * 4 + ak
    af = W.address_fields(w, voc, akey, usr["country_idx"][ao])
    recipient = np.where(ak == 0, usr["name"][ao], pick(voc["first_IN"], akey, "a.rn", w.seed))
    emit("user_db", "addresses", dict(
        id=W.uid_s(w, "addr", akey), user_id=uid_u[ao], label=np.where(ak == 0, b"Home", np.where(ak == 1, b"Work", b"Other")),
        full_name=recipient, phone=nul(usr["phone"][ao], ~has_phone[ao]), line1=af["line1"], line2=nul(af["line2"], ak != 1),
        city=af["city"], state=af["state"], postal_code=af["pin"], country=af["country"], is_default=e_bool(ak == 0),
        created_at=e_ts(signup[ao] + 60), updated_at=e_ts(signup[ao] + 60), version=e_int(np.zeros(ao.size))))
    emit("user_db", "user_preferences", dict(user_id=uid_u, email_orders=e_bool(rng.random(n) < 0.95), email_promotions=e_bool(rng.random(n) < 0.6),
                                             push_orders=e_bool(rng.random(n) < 0.8), push_promotions=e_bool(rng.random(n) < 0.3), sms=e_bool(rng.random(n) < 0.7),
                                             updated_at=e_ts(upd)))
    code = referral_code(u, w.seed)
    emit("user_db", "referral_codes", dict(user_id=uid_u, code=code, created_at=e_ts(signup)))
    # referral claims: ~12% of users were referred by another user
    ref = np.flatnonzero((rng.random(n) < 0.12) & (u > STAFF))
    referrer = rng.integers(STAFF, w.U, ref.size)
    referrer = np.where(referrer == u[ref], (referrer + 1 - STAFF) % (w.U - STAFF) + STAFF, referrer)
    claimed = ts_between(rng, signup[ref], np.minimum(signup[ref] + 3 * 86400, as_of))
    ctx.facts = {"ref_idx": ref, "ref_claimed": claimed, "ref_referrer": referrer, "ref_code": referral_code(referrer, w.seed)}
    # membership -----------------------------------------------------------------------------
    mm = np.flatnonzero(rng.random(n) < 0.14)
    plan = np.where(rng.random(mm.size) < 0.7, b"PLUS_MONTHLY", b"PLUS_YEARLY")
    days = np.where(plan == b"PLUS_MONTHLY", 30, 365)
    start = ts_between(rng, signup[mm] + 86400, np.maximum(as_of - 3600, signup[mm] + 86400))
    end = start + days * 86400
    pending = rng.random(mm.size) < 0.02
    cancelled = (~pending) & (rng.random(mm.size) < 0.12)
    mstat = np.where(pending, b"PENDING_PAYMENT", np.where(end <= as_of, np.where(cancelled, b"CANCELLED", b"EXPIRED"), np.where(cancelled, b"CANCELLED", b"ACTIVE")))
    pay_id = W.uid_s(w, "mpay", u[mm])
    emit("user_db", "memberships", dict(user_id=uid_u[mm], plan=plan, status=mstat, starts_at=nul(e_ts(start), pending), ends_at=nul(e_ts(end), pending),
                                       auto_renew=e_bool((mstat == b"ACTIVE") & (rng.random(mm.size) < 0.6)), payment_id=nul(pay_id, pending),
                                       email=usr["email"][mm], updated_at=e_ts(np.minimum(start + 60, as_of))))
    amt = np.zeros(mm.size, dtype=np.int64)
    for p_, (_, prices) in PLANS.items():
        for c_, v in prices.items():
            amt[(plan == p_.encode()) & (cur[mm] == c_.encode())] = v
    emit("user_db", "membership_payments", dict(id=pay_id, user_id=uid_u[mm], plan=plan, currency=cur[mm], amount=e_money(amt * 100, 4), email=usr["email"][mm],
                                               status=np.where(pending, b"PENDING", b"SUCCEEDED"), payment_id=nul(pay_id, pending), created_at=e_ts(np.where(pending, as_of - 600, start))))
    # membership facts used by orders (free shipping, 2x coins) ------------------------------
    m_start = np.full(n, -1, dtype=np.int64); m_end = np.full(n, -1, dtype=np.int64)
    ok = ~pending
    m_start[mm[ok]] = start[ok]; m_end[mm[ok]] = end[ok]
    # cancelled memberships keep access until ends_at
    ctx.facts.update(m_start=m_start, m_end=m_end, mm=mm, mm_plan=plan, mm_pay=pay_id, mm_amt=amt, mm_pending=pending, mm_start=start,
                     has_phone=has_phone, n_addr=n_addr)
    # privacy flows ------------------------------------------------------------------------------
    xi = np.flatnonzero(rng.random(n) < 0.015)
    xs = rng.choice([b"PENDING", b"RUNNING", b"READY", b"FAILED", b"EXPIRED"], xi.size, p=[0.05, 0.05, 0.6, 0.05, 0.25])
    xc = ts_between(rng, signup[xi], as_of)
    emit("user_db", "export_jobs", dict(id=W.uid_s(w, "exp", u[xi]), user_id=uid_u[xi], email=usr["email"][xi], status=xs,
                                        content=np.full(xi.size, b"\\N"), size_bytes=nul(e_int(rng.integers(2_000, 900_000, xi.size)), xs != b"READY"),
                                        error=nul(np.full(xi.size, b"export failed: timeout"), xs != b"FAILED"), created_at=e_ts(xc),
                                        completed_at=nul(e_ts(xc + 90), np.isin(xs, [b"PENDING", b"RUNNING"])), expires_at=nul(e_ts(xc + 7 * 86400), np.isin(xs, [b"PENDING", b"RUNNING"]))))
    di = np.flatnonzero(rng.random(n) < 0.003)
    dr = ts_between(rng, signup[di] + 86400, as_of)
    ds = rng.choice([b"SCHEDULED", b"CANCELLED", b"COMPLETED"], di.size, p=[0.4, 0.4, 0.2])
    ds = np.where((ds == b"SCHEDULED") & (dr + 30 * 86400 <= as_of), b"COMPLETED", ds)   # the service executes due deletions, so a past-due request is already COMPLETED
    emit("user_db", "deletion_requests", dict(user_id=uid_u[di], email=usr["email"][di], status=ds, requested_at=e_ts(dr), scheduled_for=e_ts(dr + 30 * 86400),
                                              completed_at=nul(e_ts(dr + 30 * 86400), ds != b"COMPLETED")))
    ci = np.flatnonzero(rng.random(n) < 0.002)
    emit("user_db", "delete_codes", dict(user_id=uid_u[ci], code_hash=rchars(rng, HEX, ci.size, 64), attempts=e_int(rng.integers(0, 3, ci.size)),
                                         expires_at=e_ts(np.full(ci.size, as_of + 600))))
    return uid_u, n_addr
