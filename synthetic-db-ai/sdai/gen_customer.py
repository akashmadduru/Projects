"""One customer chunk = everything that hangs off a contiguous range of users, across all service databases.

Generating by customer (rather than table by table) is what keeps cross-service data coherent: the same worker
that decides an order exists also writes its payment, shipments, ledger rows, reservations, review and notifications.
"""
from __future__ import annotations

import numpy as np

from . import world as W
from .gen_browse import browse_chunk
from .gen_finance import emit_payments, emit_promotion, emit_seller_inventory
from .gen_identity import identity_chunk
from .gen_orders import build_orders, emit_orders
from .gen_social import emit_reviews_and_qa, emit_support_audit_notify


def customer_chunk(ctx, lo: int, hi: int, emit) -> None:
    w, voc = ctx.w, ctx.voc
    u = np.arange(lo, hi, dtype=np.int64)
    usr = W.users(w, voc, u)
    uid_u, _ = identity_chunk(ctx, u, usr, emit)        # auth_db + user_db; sets ctx.facts
    browse_chunk(ctx, u, usr, uid_u, emit)              # cart_db + browsing history
    o = build_orders(ctx, u, usr, uid_u)                # pure simulation of the chunk's orders (no I/O)
    if o.N == 0:
        return
    F = ctx.facts
    emit_orders(ctx, o, emit)                           # order_db
    emit_payments(ctx, o, F, emit)                      # payment_db
    emit_promotion(ctx, o, F, emit)                     # promotion_db (also sets referral reward facts)
    emit_seller_inventory(ctx, o, F, emit)              # seller_db + inventory_db reservations
    emit_reviews_and_qa(ctx, o, emit)                   # rating_db
    emit_support_audit_notify(ctx, o, F, emit)          # support_db, notification_db, user_db.referral_claims
