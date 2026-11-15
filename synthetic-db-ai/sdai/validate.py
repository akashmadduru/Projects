"""`validate`: read-only verification of what was generated.

  1. physical foreign keys           (anti-join per FK from the catalog)
  2. cross-service references        (sampled: child ids looked up in the parent service's database)
  3. business rules                  (pricing, GST, coins, wallet, ledger, ratings, inventory... as SQL)
Every check returns a violation count and must be 0.  The connection is read-only.
"""
from __future__ import annotations

import time

from .catalog import Catalog
from .config import Settings
from .db import Target
from .planner import load_catalog

# (name, db, SQL returning the number of violating rows)
RULES: list[tuple[str, str, str]] = [
    # ---------------------------------------------------------------- order_db
    ("order total = subtotal - discount - coins + shipping", "order_db",
     "select count(*) from orders.orders where total_amount <> subtotal_amount - discount_amount - coins_value + shipping_amount"),
    ("order subtotal = sum(item line totals)", "order_db",
     "select count(*) from orders.orders o join (select order_id, sum(line_total) s from orders.order_items group by 1) i on i.order_id=o.id where o.subtotal_amount <> i.s"),
    ("item line_total = unit_price * quantity", "order_db", "select count(*) from orders.order_items where line_total <> unit_price * quantity"),
    ("shipment amount = sum(its items)", "order_db",
     "select count(*) from orders.order_shipments s join (select shipment_id, sum(line_total) a from orders.order_items group by 1) i on i.shipment_id=s.id where s.amount <> i.a"),
    ("INR orders: GST 18% inclusive = total*18/118", "order_db",
     "select count(*) from orders.orders where currency='INR' and tax_amount <> round(total_amount*18/118, 2)"),
    ("INR GST split: CGST+SGST (Karnataka) or IGST, summing to tax", "order_db",
     "select count(*) from orders.orders where currency='INR' and coalesce(cgst_amount,0)+coalesce(sgst_amount,0)+coalesce(igst_amount,0) <> tax_amount"),
    ("non-INR orders carry no tax", "order_db", "select count(*) from orders.orders where currency <> 'INR' and (tax_amount <> 0 or cgst_amount is not null or igst_amount is not null)"),
    ("shipping charged iff below free threshold and not a member (INR 499/40, other 25/4.99)", "order_db",
     "select count(*) from orders.orders where not membership_free_shipping and shipping_amount <> case when subtotal_amount - discount_amount >= (case when currency='INR' then 499 else 25 end) then 0 else (case when currency='INR' then 40 else 4.99 end) end"),
    ("members ship free", "order_db", "select count(*) from orders.orders where membership_free_shipping and shipping_amount <> 0"),
    ("CANCELLED <=> cancelled_at, DELIVERED <=> delivered_at", "order_db",
     "select count(*) from orders.orders where (status='CANCELLED') <> (cancelled_at is not null) or (status='DELIVERED') <> (delivered_at is not null)"),
    ("delivery/cancel/estimate dates after placement", "order_db",
     "select count(*) from orders.orders where delivered_at < placed_at or cancelled_at < placed_at or estimated_delivery_date < placed_at::date"),
    ("refunded_amount <= total_amount", "order_db", "select count(*) from orders.orders where refunded_amount > total_amount"),
    ("risk-held orders have not progressed", "order_db", "select count(*) from orders.orders where risk_hold and status <> 'PLACED'"),
    ("every order has >=1 item and >=1 shipment", "order_db",
     "select count(*) from orders.orders o where not exists (select 1 from orders.order_items i where i.order_id=o.id) or not exists (select 1 from orders.order_shipments s where s.order_id=o.id)"),
    ("latest history status = order status", "order_db",
     "select count(*) from orders.orders o join lateral (select status from orders.order_status_history h where h.order_id=o.id and h.shipment_id is null order by created_at desc, (case status when 'PLACED' then 0 when 'CONFIRMED' then 1 when 'PACKED' then 2 when 'SHIPPED' then 3 when 'OUT_FOR_DELIVERY' then 4 when 'DELIVERED' then 5 else 6 end) desc limit 1) h on true where h.status <> o.status"),
    ("returns only on delivered orders, refund <= order total", "order_db",
     "select count(*) from orders.order_returns r join orders.orders o on o.id=r.order_id where o.status <> 'DELIVERED' or r.refund_amount > o.total_amount"),
    ("returned quantity <= purchased quantity", "order_db",
     "select count(*) from orders.order_return_items ri join orders.order_returns r on r.id=ri.return_id join orders.order_items i on i.order_id=r.order_id and i.product_id=ri.product_id where ri.quantity > i.quantity"),
    ("COD orders refund to wallet, never to source", "order_db", "select count(*) from orders.order_returns r join orders.orders o on o.id=r.order_id where o.payment_method='COD' and r.refund_to='SOURCE'"),
    ("tracking only after shipping", "order_db", "select count(*) from orders.order_shipments where (status in ('PLACED','CONFIRMED','PACKED','CANCELLED')) and tracking_number is not null"),
    # ---------------------------------------------------------------- payment_db
    ("payment refunded_amount <= amount", "payment_db", "select count(*) from payment.payments where refunded_amount > amount"),
    ("REFUNDED / PARTIALLY_REFUNDED statuses match refunded_amount", "payment_db",
     "select count(*) from payment.payments where (status='REFUNDED' and refunded_amount <> amount) or (status='PARTIALLY_REFUNDED' and (refunded_amount <= 0 or refunded_amount >= amount)) or (status in ('SUCCEEDED','COD_PENDING') and refunded_amount <> 0)"),
    ("refund rows sum to payments.refunded_amount", "payment_db",
     "select count(*) from payment.payments p left join (select payment_id, sum(amount) s from payment.refunds group by 1) r on r.payment_id=p.id where coalesce(r.s,0) <> p.refunded_amount"),
    ("wallet balance = sum of its ledger and never negative", "payment_db",
     "select count(*) from payment.wallets w left join (select user_id, sum(case type when 'CREDIT' then amount else -amount end) b, min(balance_after) m from payment.wallet_txns group by 1) t on t.user_id=w.user_id where w.balance <> coalesce(t.b,0) or coalesce(t.m,0) < 0"),
    ("wallet_txns.balance_after is a true running balance", "payment_db",
     "select count(*) from (select balance_after, sum(case type when 'CREDIT' then amount else -amount end) over (partition by user_id order by created_at, (type='DEBIT'), id) run from payment.wallet_txns) x where balance_after <> run"),
    ("redeemed gift cards carry redeemer + time; expiry after creation", "payment_db",
     "select count(*) from payment.gift_cards where (status='REDEEMED') <> (redeemed_by is not null and redeemed_at is not null) or expires_at <= created_at"),
    ("MEMBERSHIP/GIFT_CARD payments reference themselves", "payment_db", "select count(*) from payment.payments where purpose <> 'ORDER' and order_id <> reference_id"),
    # ---------------------------------------------------------------- promotion_db
    ("coupons: redemptions per (coupon,user) within per_user_limit", "promotion_db",
     "select count(*) from (select r.coupon_id, r.user_id, count(*) c, max(c.per_user_limit) l from promotion.redemptions r join promotion.coupons c on c.id=r.coupon_id where r.status='APPLIED' group by 1,2) x where l is not null and c > l"),
    ("coupons: total redemptions within total_limit", "promotion_db",
     "select count(*) from promotion.coupons c join (select coupon_id, count(*) n from promotion.redemptions where status='APPLIED' group by 1) r on r.coupon_id=c.id where c.total_limit is not null and r.n > c.total_limit"),
    ("redemptions only of active, started, unexpired coupons", "promotion_db",
     "select count(*) from promotion.redemptions r join promotion.coupons c on c.id=r.coupon_id where not c.active or r.created_at < c.starts_at or (c.ends_at is not null and r.created_at > c.ends_at)"),
    ("deals.claimed = sum(unreleased claims) and <= quantity_limit", "promotion_db",
     "select count(*) from promotion.deals d left join (select deal_id, sum(quantity) q from promotion.deal_claims where not released group by 1) c on c.deal_id=d.id where d.claimed <> coalesce(c.q,0) or d.claimed > d.quantity_limit"),
    ("coin ledger: no user ends with a negative balance (generated users)", "promotion_db",
     "select count(*) from (select user_id, sum(delta) b from promotion.coin_ledger group by 1) x where b < 0"),
    ("coin ledger: EARN positive, REDEEM negative", "promotion_db",
     "select count(*) from promotion.coin_ledger where (reason in ('EARN_ORDER','REFERRAL') and delta <= 0) or (reason in ('REDEEM_ORDER','REVERSE_ORDER') and delta >= 0)"),
    # ---------------------------------------------------------------- seller_db
    ("ledger: COMMISSION = -round(SALE * seller commission %)", "seller_db",
     "select count(*) from seller.ledger_entries s join seller.ledger_entries c on c.shipment_id=s.shipment_id and c.type='COMMISSION' and c.return_id is null join seller.sellers k on k.id=s.seller_id where s.type='SALE' and s.return_id is null and k.id <> '00000000-0000-0000-0000-00000000a11e' and c.amount <> -round(s.amount * k.commission_percent / 100, 2)"),
    ("ledger: platform seller (0%) pays no commission or fee", "seller_db",
     "select count(*) from seller.ledger_entries where seller_id='00000000-0000-0000-0000-00000000a11e' and ((type='COMMISSION' and amount <> 0) or type='FEE')"),
    ("ledger: SALE positive, COMMISSION/FEE negative, one SALE per shipment", "seller_db",
     "select count(*) from seller.ledger_entries where (type='SALE' and amount <= 0) or (type in ('COMMISSION','FEE') and amount > 0)"),
    ("payout amount = sum of its ledger entries", "seller_db",
     "select count(*) from seller.payouts p left join (select payout_id, sum(amount) s from seller.ledger_entries group by 1) l on l.payout_id=p.id where p.amount <> coalesce(l.s,0)"),
    ("sellers.rating_* = seller_feedback aggregate (generated sellers)", "seller_db",
     "select count(*) from seller.sellers s left join (select seller_id, round(avg(rating),2) a, count(*) n from seller.seller_feedback group by 1) f on f.seller_id=s.id where s.id <> '00000000-0000-0000-0000-00000000a11e' and (s.rating_count <> coalesce(f.n,0) or (f.n > 0 and s.rating_avg <> f.a))"),
    ("sale_lines sum = SALE per shipment", "seller_db",
     "select count(*) from seller.ledger_entries l join (select shipment_id, sum(amount) a from seller.sale_lines group by 1) s on s.shipment_id=l.shipment_id where l.type='SALE' and l.return_id is null and l.amount <> s.a"),
    # ---------------------------------------------------------------- rating_db
    ("ratings: helpful/unhelpful counters = vote rows", "rating_db",
     "select count(*) from rating.ratings r left join (select rating_id, count(*) filter (where vote_type='HELPFUL') h, count(*) filter (where vote_type='UNHELPFUL') u from rating.rating_votes group by 1) v on v.rating_id=r.id where r.user_id::text !~ '^([0-9a-f])\\1{7}-' and (r.helpful_count <> coalesce(v.h,0) or r.unhelpful_count <> coalesce(v.u,0))"),
    ("questions.answer_count = answers", "rating_db",
     "select count(*) from rating.questions q left join (select question_id, count(*) n from rating.answers group by 1) a on a.question_id=q.id where q.answer_count <> coalesce(a.n,0)"),
    ("Q&A upvotes = qa_votes", "rating_db",
     "select (select count(*) from rating.questions q left join (select item_id, count(*) n from rating.qa_votes where item_type='QUESTION' group by 1) v on v.item_id=q.id where q.upvotes <> coalesce(v.n,0)) + (select count(*) from rating.answers a left join (select item_id, count(*) n from rating.qa_votes where item_type='ANSWER' group by 1) v on v.item_id=a.id where a.upvotes <> coalesce(v.n,0))"),
    ("reviews are dated after the product existed (>= 3y window) and not in the future", "rating_db", "select count(*) from rating.ratings where created_at > @ASOF@ + interval '1 day'"),
    # ---------------------------------------------------------------- inventory_db
    ("stock: reserved <= quantity", "inventory_db", "select count(*) from inventory.stock_levels where reserved_quantity > quantity"),
    ("stock status matches availability vs low_stock_threshold", "inventory_db",
     "select count(*) from inventory.stock_levels where status <> case when quantity - reserved_quantity = 0 then 'OUT_OF_STOCK' when quantity - reserved_quantity <= low_stock_threshold then 'LOW_STOCK' else 'IN_STOCK' end"),
    ("warehouse_stock (listing seller) sums to stock_levels.quantity", "inventory_db",
     "select count(*) from inventory.stock_levels s join (select product_id, sum(quantity) q from inventory.warehouse_stock where offer_id is not null group by 1) w on w.product_id=s.product_id where s.quantity <> w.q"),
    ("reservation restored_quantity <= quantity; releases = restored", "inventory_db",
     "select count(*) from inventory.reservations r left join (select order_id, product_id, sum(quantity) q from inventory.reservation_releases group by 1,2) l on l.order_id=r.order_id and l.product_id=r.product_id where r.restored_quantity > r.quantity or r.restored_quantity <> coalesce(l.q,0)"),
    # ---------------------------------------------------------------- product_db
    ("products: discount_percent consistent with list/sale price", "product_db",
     "select count(*) from product.products where (sale_price is null) <> (discount_percent is null) or sale_price >= list_price or (sale_price is not null and abs(discount_percent - round((list_price - sale_price) * 100 / list_price, 2)) > 0.01)"),
    ("products: rating_distribution sums to rating_count; average within 1-5", "product_db",
     "select count(*) from product.products where (rating_distribution->>'1')::int + (rating_distribution->>'2')::int + (rating_distribution->>'3')::int + (rating_distribution->>'4')::int + (rating_distribution->>'5')::int <> rating_count or review_count > rating_count or (average_rating is not null and average_rating not between 1 and 5)"),
    ("products: last_known_quantity present, slug/sku unique shape", "product_db", "select count(*) from product.products where sku ~ '^SKU-[A-Z]{3}-[0-9A-Z]{9}$' and (slug is null or last_known_quantity is null)"),
    ("categories: one level, depth_level matches parent", "product_db",
     "select count(*) from product.categories c left join product.categories p on p.id=c.parent_category_id where (c.parent_category_id is null and c.depth_level <> 0) or (c.parent_category_id is not null and (c.depth_level <> 1 or p.parent_category_id is not null))"),
    ("offers: one base offer per product, sale < price", "product_db",
     "select count(*) from product.products p where p.sku ~ '^SKU-[A-Z]{3}-[0-9A-Z]{9}$' and not exists (select 1 from product.offers o where o.product_id=p.id) or exists (select 1 from product.offers o where o.product_id=p.id and o.sale_price >= o.price)"),
    # ---------------------------------------------------------------- user/auth/cart/support
    ("each user has <=1 default address and >=1 address", "user_db",
     "select count(*) from users.user_profiles p left join (select user_id, count(*) n, count(*) filter (where is_default) d from users.addresses group by 1) a on a.user_id=p.user_id where coalesce(a.n,0) < 1 or a.d <> 1"),
    ("memberships: ends after starts; ACTIVE not expired", "user_db",
     "select count(*) from users.memberships where ends_at <= starts_at or (status='ACTIVE' and ends_at <= @ASOF@) or (status='EXPIRED' and ends_at > @ASOF@)"),
    ("referral claim codes belong to the claimed referrer", "user_db",
     "select count(*) from users.referral_claims c join users.referral_codes r on r.user_id=c.referrer_user_id where r.code <> c.code"),
    ("refresh token families: absolute expiry >= expiry, revoked_reason iff revoked_at", "auth_db",
     "select count(*) from auth.refresh_tokens where expires_at > family_absolute_expires_at or (revoked_at is null) <> (revoked_reason is null)"),
    ("one referral/role: every user has ROLE_VIEWER", "auth_db",
     "select count(*) from auth.users u where not exists (select 1 from auth.user_roles r where r.user_id=u.id and r.role_id=3)"),
    ("cart: line prices non-negative, quantity within 1..99, product unique per cart", "cart_db",
     "select count(*) from cart.cart_items where unit_price < 0 or quantity not between 1 and 99"),
    ("tickets: SLA due dates after creation; resolved states carry resolved_at; csat only when resolved", "support_db",
     "select count(*) from support.tickets where first_response_due_at <= created_at or resolution_due_at <= created_at or (status in ('RESOLVED','CLOSED')) <> (resolved_at is not null) or (csat is not null and status not in ('RESOLVED','CLOSED'))"),
]

# Seed rows shipped inside the migrations use hand-written ids (aaaaaaaa-..., 00000000-...); they reference seed data that
# only exists in dev profiles.  They are not ours, so reference checks ignore ids of that shape.
SEED_ID = r"^([0-9a-f])\1{7}-"
WHERE = {("payment_db", "payment.payments", "order_id"): "purpose = 'ORDER'"}   # MEMBERSHIP/GIFT_CARD rows reuse order_id for their own id


def _fk_checks(cat: Catalog):
    for (db, fq), tb in cat.tables.items():
        for fk in tb.fks:
            cond = " and ".join(f'c."{a}" = p."{b}"' for a, b in zip(fk["cols"], fk["ref_cols"]))
            nn = " and ".join(f'c."{a}" is not null' for a in fk["cols"])
            parent = f'{fk["ref_schema"]}.{fk["ref_table"]}'
            yield (f"FK {fq}({','.join(fk['cols'])}) -> {parent}", db, f"select count(*) from {fq} c where {nn} and not exists (select 1 from {parent} p where {cond})")


def run_validate(settings: Settings, sample: int = 20000) -> int:
    cat = load_catalog(settings)
    conns: dict[str, Target] = {}

    def conn(db):
        if db not in conns:
            t = Target(settings, db, autocommit=True)
            t.conn.execute("SET default_transaction_read_only = on")
            t.conn.execute("SET statement_timeout = '1800s'")
            conns[db] = t
        return conns[db]

    fails = warns = total = 0
    asof = "now()"
    pj = settings.state_dir / "plan.json"
    if pj.exists():
        import json
        asof = f"to_timestamp({json.loads(pj.read_text())['world']['as_of']})"      # the synthetic 'now' of the latest run
    print("== 1. physical foreign keys")
    for name, db, sql in _fk_checks(cat):
        total += 1
        try:
            n = conn(db).q(sql)[0][0]
        except Exception as e:
            n = f"ERROR {e}"
        ok = n == 0
        if not ok:
            fails += 1
        print(f"  {'PASS' if ok else 'FAIL'}  {db}: {name}" + ("" if ok else f"  -> {n}"))
    print("\n== 2. cross-service references (sampled)")
    for r in cat.logical:
        total += 1
        child, parent = r["child_table"], r["parent_table"]
        try:
            reltuples = max(conn(r["child_db"]).q(f"select reltuples::bigint from pg_class where oid = '{child}'::regclass")[0][0], 1)
            pct = min(100.0, max(0.001, sample * 100.0 / reltuples))
            extra = ("and " + WHERE[(r["child_db"], child, r["child_col"])]) if (r["child_db"], child, r["child_col"]) in WHERE else ""
            vals = [x[0] for x in conn(r["child_db"]).q(
                f'select distinct "{r["child_col"]}"::text from {child} tablesample bernoulli ({pct:.4f}) where "{r["child_col"]}" is not null and "{r["child_col"]}"::text !~ %s {extra} limit {sample}', (SEED_ID,))]
            if not vals:
                print(f"  SKIP  {r['child_db']}.{child}.{r['child_col']} (no rows)")
                continue
            found = 0
            for i in range(0, len(vals), 5000):
                part = vals[i:i + 5000]
                found += conn(r["parent_db"]).q(f'select count(*) from {parent} where "{r["parent_col"]}" = any(%s::uuid[])', (part,))[0][0]
            missing = len(vals) - found
        except Exception as e:
            missing = f"ERROR {e}"
        ok = missing == 0
        if not ok:
            fails += 1
        print(f"  {'PASS' if ok else 'FAIL'}  {r['child_db']}.{child}.{r['child_col']} -> {r['parent_db']}.{parent}" + ("" if ok else f"  -> {missing} missing of sample"))
    print("\n== 3. business rules")
    for name, db, sql in RULES:
        total += 1
        t0 = time.time()
        try:
            n = conn(db).q(sql.replace("@ASOF@", asof))[0][0]
        except Exception as e:
            n = f"ERROR {str(e).splitlines()[0]}"
        ok = n == 0
        if not ok:
            fails += 1
        print(f"  {'PASS' if ok else 'FAIL'}  {db}: {name}" + ("" if ok else f"  -> {n}") + (f"   ({time.time() - t0:.1f}s)" if time.time() - t0 > 5 else ""))
    for t in conns.values():
        t.close()
    print(f"\n{total - fails}/{total} checks passed" + (f", {fails} FAILED" if fails else ""))
    return 1 if fails else 0
