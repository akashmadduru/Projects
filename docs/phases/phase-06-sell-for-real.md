# Phase 6: Sell for real

**Tech debt:** [techdebt/phase6.md](../techdebt/phase6.md). Built under the [roadmap working rules](../roadmap.md#working-rules): no tests, no per-feature HLD/LLD, every shortcut logged.

## Features

**Status (2026-11-12): built, prototype-grade, no automated tests.** Integration run: 98 of 99 end-to-end checks passed (the one failure was a script bug). Only SIMULATED payment mode was exercised. Shortcuts: [techdebt/phase6.md](../techdebt/phase6.md). The "Today" column below is the pre-Phase-6 baseline.

| # | Feature | Why it matters | Today | Touches | Size | Status |
|---|---|---|---|---|---|---|
| 1 | **Online payments** (cards, UPI, net banking, wallets) via Razorpay/Stripe; payment status on the order | No revenue without it | Order is placed with no payment | **new** payment-service, order-service, storefront checkout | L | ✅ Built, partial: Stripe path never exercised, only SIMULATED mode verified ([TD-P6-02](../techdebt/phase6.md#td-p6-02)) |
| 2 | **Cash on Delivery** option with COD limits | Large share of Flipkart orders; lowers first-purchase friction | — | payment-service, order-service | S | ✅ Built; limits also duplicated client-side ([TD-P6-17](../techdebt/phase6.md#td-p6-17)) |
| 3 | **Stock reservation and decrement** at checkout; release on cancel/payment failure | Prevents overselling (TD-BE-01) | Inventory is advisory only | inventory-service, order-service | M | ✅ Built, partial: products without an inventory row are unlimited ([TD-P6-04](../techdebt/phase6.md#td-p6-04)) |
| 4 | **Order lifecycle**: PLACED → CONFIRMED → PACKED → SHIPPED → OUT_FOR_DELIVERY → DELIVERED, with a tracking timeline on the order page | Customers' #1 post-purchase question is "where is my order" | PLACED / CANCELLED only | order-service, storefront OrderPage | M | ✅ Built (also PENDING_PAYMENT and expiry job) |
| 5 | **Admin order management**: list/search orders, advance status, add tracking number, cancel, refund | Operations cannot fulfil without it | — | admin SPA, order-service | M | ✅ Built; admin views are unrefined ([TD-P6-21](../techdebt/phase6.md#td-p6-21)) |
| 6 | **Shipping charges and delivery-date estimate** by pincode (free above a threshold) | Price transparency; Amazon/Flipkart show "Delivery by Tue" everywhere | — | order-service (+ pincode table), product page, checkout | M | ✅ Built; rules config-driven, no real carrier ([TD-P6-17](../techdebt/phase6.md#td-p6-17)) |
| 7 | **Tax (GST) calculation and downloadable invoice PDF** | Legal requirement for selling in India; B2B buyers need invoices | — | order-service | M | ✅ Built, partial: invoice is a minimal text-only PDF with no rupee glyph ([TD-P6-15](../techdebt/phase6.md#td-p6-15)) |
| 8 | **Order notifications** by email (and SMS hook): placed, shipped, delivered, cancelled, refunded | Trust; reduces support load | — | **new** notification-service (consumes `order.events`) | M | ✅ Built, partial: email via Mailpit in dev; SMS is a dev stub that logs; no retry ([TD-P6-07](../techdebt/phase6.md#td-p6-07), [TD-P6-08](../techdebt/phase6.md#td-p6-08)) |
| 9 | **Returns and refunds**: return window per product, return request with reason, pickup status, refund to source/wallet | Expected by every Amazon/Flipkart customer; drives purchase confidence | — | order-service, payment-service, admin | L | ✅ Built, partial: shipping not refunded, COD refunds only marked ([TD-P6-14](../techdebt/phase6.md#td-p6-14)) |
| 10 | **Coupons / promo codes** at checkout (flat, %, min order, first order, per-user limits) | Primary acquisition lever | — | **new** promotion-service (or order-service module), checkout, admin | M | ✅ Built (WELCOME10, FLAT100, SAVE5, FESTIVE20 seeded); first-order coupons still shown to returning customers ([TD-P6-16](../techdebt/phase6.md#td-p6-16)) |
| 11 | **Password reset, email verification, phone OTP login** | Account recovery; Flipkart is phone-first | Email/password + Google only | auth-service, storefront | M | ✅ Built, partial: login not blocked for unverified email; OTP SMS is a dev stub ([TD-P6-08](../techdebt/phase6.md#td-p6-08), [TD-P6-10](../techdebt/phase6.md#td-p6-10)) |
| 12 | **Buy Now** (skip cart) and **Save for later** in cart | Faster checkout; recovers removed items | Cart only | cart-service, storefront | S | ✅ Built (Buy Now on product page and checkout; Save for later signed-in only) |

## Run notes

- New services: payment-service `8088`, promotion-service `8089`, notification-service `8090`; Mailpit (dev mail catcher) UI at http://127.0.0.1:8025.
- New env vars in `infra/.env`: `INTERNAL_API_TOKEN` (shared service-to-service secret), `PAYMENT_DB_PASSWORD`, `PROMOTION_DB_PASSWORD`, `STRIPE_SECRET_KEY` / `STRIPE_PUBLISHABLE_KEY` / `STRIPE_WEBHOOK_SECRET`, `STOREFRONT_BASE_URL`.
- Existing Postgres volume: create the new databases with `docker exec -i ecommerce-postgres psql -U postgres < infra/scripts/create-customer-dbs.sql`.
- Payments run in **SIMULATED** mode until Stripe keys are set; the Stripe path has never been exercised ([TD-P6-02](../techdebt/phase6.md#td-p6-02)).
- Seeded coupons: `WELCOME10`, `FLAT100`, `SAVE5`, `FESTIVE20`.
- Integration run: 98 of 99 e2e checks passed (the failure was a script bug). Images were built offline because Maven Central returned 403; run `docker compose -f infra/docker-compose.yml up --build` once it is reachable ([TD-P6-06](../techdebt/phase6.md#td-p6-06)).
- The dev DB still holds test data (an admin user, `p6e2e-*` users, a deactivated test product and coupons); see [TD-P6-25](../techdebt/phase6.md#td-p6-25).
