# Tech-debt remediation plan

**Written:** 2026-11-17. **Scope:** all 163 open items in the [register](README.md) (23 High, 71 Med, 69 Low).
Items are fixed one at a time, in the order below. Each fix lands with its tests, and the item is **removed from
the register in the same change** (the working rule in [roadmap.md](../roadmap.md#working-rules)). The status
column here is the progress log.

## Ordering rules

1. Severity first, then the risk the item carries: money and stock correctness before features.
2. Inside a severity, small items before large ones, so risk drops fastest.
3. Items that share a root cause are fixed together as one cluster (one design, one change).
4. Items that need something only the owner can do (accounts, credentials, history rewrite, business
   decisions) are listed under [Needs the owner](#needs-the-owner). Their code-side part is still done.

## Wave 1: High, small, code only

| # | Item | What changes | Status |
|---|---|---|---|
| 1 | [TD-BE-17](backend.md#td-be-17) | Six Kafka listeners rethrow transient failures so retry and the DLQ work | ✅ Fixed 2026-11-17 |
| 2 | [TD-SEC-02](infra-security.md#td-sec-02) | Admin Google login starts at auth-service, like the storefront | ✅ Fixed 2026-11-17 |
| 3 | [TD-P8-06](phase8.md#td-p8-06) | Gift card codes hashed (shown once), redeem rate limit, sweep of abandoned cards | ✅ Fixed 2026-11-17 (residual: [TD-P8-21](phase8.md#td-p8-21)) |
| 4 | [TD-AI-01](ai.md#td-ai-01) | Run the pgvector integration tests (Testcontainers) and fix what fails | ✅ Fixed 2026-11-17 |

## Wave 2: High, medium, money and stock correctness

| # | Item(s) | What changes | Status |
|---|---|---|---|
| 5 | [TD-P6-03](phase6.md#td-p6-03) | Cancel the payment on order cancel/expiry (Stripe intent cancel already exists); late-success refund retried | ✅ Fixed 2026-11-17 |
| 6a | [TD-P6-04](phase6.md#td-p6-04), [TD-P7-07](phase7.md#td-p7-07) | Fail closed on missing stock rows, catalog sync creates rows for every product and variant, release cannot add stock | ✅ Fixed 2026-11-17 (residual: [TD-P7-22](phase7.md#td-p7-22)) |
| 6b | [TD-P9-02](phase9.md#td-p9-02) | One source of truth: `warehouse_stock`; legacy totals derived from it | 🟡 Step 1 done 2026-11-17: hourly reconcile makes `warehouse_stock` authoritative; retiring the legacy tables remains (now Med) |
| 7 | [TD-P8-07](phase8.md#td-p8-07) | Atomic wallet reserve-and-debit; retried credit-back | ✅ Fixed 2026-11-17 (residual: [TD-P8-22](phase8.md#td-p8-22)) |
| 8 | [TD-P9-07](phase9.md#td-p9-07) | Seller ledger from `earliest`, idempotent, DLQ, fee reversal | ✅ Fixed 2026-11-17 (residual: [TD-P9-20](phase9.md#td-p9-20)) |
| 9 | [TD-P9-03](phase9.md#td-p9-03) | `PENDING_REVIEW` for seller products, admin approval queue, `PRODUCT_UPDATED` from bulk | ✅ Fixed 2026-11-17 (residual: [TD-P9-21](phase9.md#td-p9-21)) |
| 10 | [TD-P9-09](phase9.md#td-p9-09) (code part) | Revoke role on suspend, mask KYC fields, audit admin decisions | 🟡 Code part done 2026-11-17 (role revoked on suspend, KYC masked, decisions audited); provider integrations need your choice |

## Wave 3: High, large

| # | Item(s) | What changes | Status |
|---|---|---|---|
| 11 | [TD-P6-01](phase6.md#td-p6-01) | Transactional outbox in order-service (also closes the retry gaps in 5 and 7) | Design written below; waiting for your go-ahead |
| 12 | Test cluster: [TD-P6-05](phase6.md#td-p6-05), [TD-TEST-01](testing.md#td-test-01), [TD-TEST-02](testing.md#td-test-02), [TD-ADM-01](admin.md#td-adm-01), [TD-P7-01](phase7.md#td-p7-01), [TD-P8-01](phase8.md#td-p8-01), [TD-P9-01](phase9.md#td-p9-01) | Money paths first (checkout, wallet, refunds, ledgers), then the stale storefront/admin specs | 🟡 In progress: storefront type-checks fully (TD-TEST-03/05 fixed), stale storefront/admin tests fixed; auth-service suite passes again; order-, cart- and user-service have Testcontainers bases and money-path tests (TD-TEST-01 narrowed to HTTP ownership; found and fixed coupon/coins release on failure; filed TD-P8-23) |

## Item 11 design: transactional outbox in order-service (for review before building)

**Problem (TD-P6-01).** After an order changes state, order-service calls other services and Kafka *after* its database commit: restore stock, release coupon/coins/exchange, cancel the payment, mark COD collected, clear the cart, publish `order.events`. Each is "best effort": a crash or an outage between the commit and the call loses it (stock leaked, coupon stuck, no email). Wallet refunds already have a durable retry table (`pending_wallet_credits`, built for TD-P8-07/TD-P8-23); the outbox generalises it.

**Design.**
1. One table `orders.outbox`: `id`, `aggregate_id` (order id), `kind` (`ORDER_EVENT`, `RESTORE_STOCK`, `RELEASE_COUPON`, `RELEASE_COINS`, `RELEASE_EXCHANGE`, `CANCEL_PAYMENT`, `COD_COLLECTED`, `CLEAR_CART`, `WALLET_CREDIT`, `WALLET_REVERSE`), `payload` (jsonb), `idem_key` (unique), `status` (`PENDING`/`DONE`/`DEAD`), `attempts`, `next_attempt_at`, `last_error`, `created_at`.
2. **Written in the same transaction as the order change.** The cancel, expiry, payment-result and placement-compensation paths enqueue rows instead of calling out. Either both the order change and its side effects are stored, or neither is.
3. **Relay**: a job every 2 s on one replica (`JobLock`) takes due rows with `FOR UPDATE SKIP LOCKED`, runs the matching call, marks `DONE`; on failure backs off exponentially (cap 1 h). After 20 attempts the row becomes `DEAD`, logged at ERROR as `OUTBOX DEAD` (alert on it) and listed for admins.
4. **Ordering**: rows of one order run oldest first, and a row waits while an older row of the same order is still pending, so `ORDER_PLACED` is never published after `ORDER_CANCELLED`.
5. **Duplicates are expected** (at-least-once). Receivers are already idempotent: inventory release by order, coupon/coins release by order, payment cancel and wallet keys, ledger unique keys, notification inbox dedupe key. The one gap: an order email can be sent twice if the relay crashes after sending; acceptable, and noted.
6. **Not moved**: calls whose answer placement needs (reserve stock, redeem coupon/coins, debit wallet, create payment) stay synchronous; only their compensations move to the outbox.
7. **Migration**: `pending_wallet_credits` rows are copied into `outbox` as `WALLET_CREDIT`/`WALLET_REVERSE`, and the old table is dropped in a later release.

**Effort and risk.** About a day. It touches every order state transition, so it lands behind the existing order-service integration tests plus new ones: a crash between commit and call is recovered, events stay in order per order, `DEAD` after the retry limit.

## Waves 4 and 5: Med (71) and Low (69)

Planned in detail when Wave 3 is done, grouped by service so each service is opened once. Med before Low.

## Needs the owner

| Item | What only you can do | Code-side part done here |
|---|---|---|
| [TD-P6-02](phase6.md#td-p6-02) | Run the Stripe and Razorpay test-mode checklists with real test accounts | Done (integration code, checklists in [integrations](../guides/integrations.md)) |
| [TD-SEC-01](infra-security.md#td-sec-01) | Rotate the Google client secret in Google Cloud Console | Nothing to change in code |
| [TD-SEC-14](infra-security.md#td-sec-14) | Approve the git history rewrite and force-push ([runbook](../guides/runbooks/leaked-database-dump.md)) | Not started: rewriting history needs your explicit go-ahead |
| [TD-P9-09](phase9.md#td-p9-09) (part) | Choose GSTN, penny-drop and KMS providers | 🟡 Code part done 2026-11-17 (role revoked on suspend, KYC masked, decisions audited); provider integrations need your choice |
