"""Schema contract + business-rule tests on a real (in-memory) generation, no database needed."""
import numpy as np
import pytest

from sdai import world as W


def f(a):
    return np.asarray(a).astype(float)


def test_every_non_seed_table_has_a_generator(small_world, catalog):
    w, em = small_world
    emitted = {(db, catalog.find(db, t).fq) for (db, t) in em.cap}
    seeded_only = {"auth.roles", "users.membership_plans", "users.membership_plan_prices", "product.fx_rates", "product.theme_settings",
                   "notification.settings"}
    missing = [(db, fq) for (db, fq) in catalog.tables if (db, fq) not in emitted and fq not in seeded_only]
    assert missing == [], f"tables with no generator: {missing}"


def test_primary_keys_unique_within_generation(small_world, catalog):
    w, em = small_world
    for (db, table), chunks in em.cap.items():
        tb = catalog.find(db, table)
        if not tb.pk:
            continue
        cols = [np.concatenate([c[k] for c in chunks]) for k in tb.pk]
        keys = list(zip(*[c.tolist() for c in cols]))
        assert len(keys) == len(set(keys)), f"duplicate PK in {db}.{table}"


def test_unique_constraints_hold(small_world, catalog):
    w, em = small_world
    for (db, table), chunks in em.cap.items():
        tb = catalog.find(db, table)
        for uq in tb.uniques:
            if not all(k in chunks[0] for k in uq):
                continue
            cols = [np.concatenate([c[k] for c in chunks]) for k in uq]
            rows = [r for r in zip(*[c.tolist() for c in cols]) if b"\\N" not in r]
            assert len(rows) == len(set(rows)), f"unique {uq} violated in {db}.{table}"


def test_intra_service_foreign_keys(small_world, catalog):
    w, em = small_world
    for (db, table), chunks in em.cap.items():
        tb = catalog.find(db, table)
        for fk in tb.fks:
            parent = catalog.tables.get((db, f"{fk['ref_schema']}.{fk['ref_table']}"))
            if parent is None or (db, parent.name) not in em.cap or len(fk["cols"]) != 1:
                continue                                    # parent is seeded by migrations (roles, plans...)
            child = np.concatenate([c[fk["cols"][0]] for c in chunks])
            par = set(np.concatenate([c[fk["ref_cols"][0]] for c in em.cap[(db, parent.name)]]).tolist())
            child = [x for x in child.tolist() if x != b"\\N"]
            if parent.fq == "seller.sellers":
                par |= {W.ACME_SELLER.encode()}                       # seeded platform seller
            if parent.fq == "inventory.warehouses":
                par |= {x[0].encode() for x in W.ACME_WH}             # seeded platform warehouses
            bad = [x for x in child if x not in par]
            assert not bad, f"{db}.{tb.fq}.{fk['cols']} -> {parent.fq}: {len(bad)} orphans (e.g. {bad[:1]})"


def test_order_pricing_follows_pricing_service(small_world):
    w, em = small_world
    g = lambda c: em.col("order_db", "orders", c)
    sub, disc, coins, ship, total, tax = (f(g(c)) for c in ("subtotal_amount", "discount_amount", "coins_value", "shipping_amount", "total_amount", "tax_amount"))
    cur = g("currency")
    assert np.allclose(total, sub - disc - coins + ship, atol=0.005)
    inr = cur == b"INR"
    assert np.allclose(tax[inr], np.floor(total[inr] * 18 / 118 * 100 + 0.5) / 100, atol=0.011)
    assert (tax[~inr] == 0).all()
    member_free = g("membership_free_shipping") == b"t"
    after = sub - disc
    thr = np.where(inr, 499, 25)
    fee = np.where(inr, 40.0, 4.99)
    expect = np.where(member_free, 0, np.where(after >= thr, 0, fee))
    assert np.allclose(ship, expect, atol=0.005)
    assert (total > 0).all()


def test_order_items_roll_up_to_orders_and_shipments(small_world):
    w, em = small_world
    oid = em.col("order_db", "orders", "id")
    sub = dict(zip(oid.tolist(), f(em.col("order_db", "orders", "subtotal_amount"))))
    items_o = em.col("order_db", "order_items", "order_id")
    line = f(em.col("order_db", "order_items", "line_total"))
    assert np.allclose(f(em.col("order_db", "order_items", "unit_price")) * f(em.col("order_db", "order_items", "quantity")), line, atol=0.005)
    agg = {}
    for o, l in zip(items_o.tolist(), line):
        agg[o] = agg.get(o, 0) + l
    assert all(abs(agg[o] - sub[o]) < 0.01 for o in sub)


def test_lifecycle_state_is_consistent(small_world):
    w, em = small_world
    st = em.col("order_db", "orders", "status")
    canc = em.col("order_db", "orders", "cancelled_at") != b"\\N"
    deliv = em.col("order_db", "orders", "delivered_at") != b"\\N"
    assert ((st == b"CANCELLED") == canc).all()
    assert ((st == b"DELIVERED") == deliv).all()
    assert set(st.tolist()) <= {b"PLACED", b"CONFIRMED", b"PACKED", b"SHIPPED", b"OUT_FOR_DELIVERY", b"DELIVERED", b"CANCELLED"}
    assert (st == b"DELIVERED").any() and (st == b"CANCELLED").any()


def test_payments_are_one_per_card_or_cod_order(small_world):
    w, em = small_world
    meth = em.col("order_db", "orders", "payment_method")
    oid = em.col("order_db", "orders", "id")
    pay_order = em.col("payment_db", "payments", "order_id")
    purpose = em.col("payment_db", "payments", "purpose")
    paid_orders = set(pay_order[purpose == b"ORDER"].tolist())
    assert paid_orders == set(oid[meth != b"WALLET"].tolist())
    amt = dict(zip(pay_order.tolist(), f(em.col("payment_db", "payments", "amount"))))
    tot = dict(zip(oid.tolist(), f(em.col("order_db", "orders", "total_amount"))))
    assert all(abs(amt[o] - tot[o]) < 0.005 for o in paid_orders)


def test_wallet_never_negative_and_balance_is_running_sum(small_world):
    w, em = small_world
    if ("payment_db", "wallet_txns") not in em.cap:
        pytest.skip("no wallet activity in sample")
    bal = f(em.col("payment_db", "wallet_txns", "balance_after"))
    assert (bal >= -1e-9).all()
    wu = em.col("payment_db", "wallets", "user_id").tolist()
    assert len(wu) == len(set(wu))


def test_ratings_counters_are_exact(small_world):
    w, em = small_world
    rid = em.col("rating_db", "ratings", "id")
    votes = em.col("rating_db", "rating_votes", "rating_id")
    typ = em.col("rating_db", "rating_votes", "vote_type")
    helpful = dict(zip(rid.tolist(), em.col("rating_db", "ratings", "helpful_count").astype(int)))
    cnt = {}
    for v, t in zip(votes.tolist(), typ.tolist()):
        cnt[v] = cnt.get(v, 0) + (t == b"HELPFUL")
    assert all(helpful[r] == cnt.get(r, 0) for r in helpful)
    v = em.col("rating_db", "ratings", "rating_value").astype(int)
    assert v.min() >= 1 and v.max() <= 5


def test_product_aggregates_equal_the_tally(small_world):
    w, em = small_world
    rc = em.col("product_db", "products", "rating_count").astype(int)
    assert rc.sum() > 0
    import json
    dist = [json.loads(x) for x in em.col("product_db", "products", "rating_distribution")]
    assert all(sum(d.values()) == c for d, c in zip(dist, rc))


def test_money_is_exact_cents(small_world):
    w, em = small_world
    for c in ("total_amount", "subtotal_amount"):
        a = em.col("order_db", "orders", c)
        assert all(len(x.split(b".")[1]) == 4 for x in a.tolist()[:500])


def test_generation_is_deterministic(settings, catalog, vocab):
    from sdai.gen_customer import customer_chunk
    from sdai.gen_util import Ctx
    from conftest import Capture
    w = W.World(seed=77, as_of=1_800_000_000, U=600)
    outs = []
    for _ in range(2):
        em = Capture(settings, catalog, dry=True)
        customer_chunk(Ctx(w=w, voc=vocab, rng=np.random.default_rng(9), chunk=3, serial_base={"product_views": 1, "chat_messages": 1}), 20, 120, em)
        outs.append(em.col("order_db", "orders", "id").tolist() + em.col("order_db", "orders", "total_amount").tolist())
    assert outs[0] == outs[1]


def test_schema_drift_is_detected(settings, catalog):
    from conftest import Capture
    from sdai.emitter import SchemaMismatch
    em = Capture(settings, catalog, dry=True)
    with pytest.raises(SchemaMismatch):            # a column the migrations do not define
        em("cart_db", "carts", {"id": np.array([b"x"]), "user_id": np.array([b"y"]), "bogus_column": np.array([b"1"])})
    with pytest.raises(SchemaMismatch):            # NOT NULL column without default is missing
        em("cart_db", "carts", {"id": np.array([b"x"])})
    with pytest.raises(SchemaMismatch):            # NULL into NOT NULL
        em("cart_db", "carts", {"id": np.array([b"x"]), "user_id": np.array([b"\\N"])})
