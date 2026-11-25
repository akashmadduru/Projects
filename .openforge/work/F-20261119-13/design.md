# F-20261119-13 Design: seller ledger integrity, part A (seller-service only, class M)

Base 820e5a8. Scope: AC-1..AC-8 and AC-13. Binding rulings: Q1 = B (provisional), Q2 report-only, Q3 platform-funded discounts. ADR: `.openforge/knowledge/decisions/0012-seller-ledger-reversals.md`.

## 1. Current behaviour (verified)
- `LedgerService.recordDelivered` (LedgerService.java:70-112) posts SALE, COMMISSION (-pct), FEE (-10.00 INR / -0.25 other, only when pct > 0, :90-94). It is idempotent through `ux_ledger_shipment_type (shipment_id, type) WHERE return_id IS NULL` (V2:4).
- `reverseRefund` (:115-152) reverses the whole SALE and COMMISSION per shipment, keyed `return_id IS NULL`. It never reverses FEE.
- `reverseReturn` (:159-189) posts `-refundAmount` and `+refundAmount x current pct`, keyed `ux_ledger_return_type (return_id, type)` (V2:5). The basis is the customer refund (post-discount, plus shipping on the completing return). There is no cap, no lock and no FEE reversal. The two keys never see each other, so legacy after per-return reverses twice.
- CHECK `type IN ('SALE','COMMISSION','FEE','REFUND_REVERSAL','COMMISSION_REVERSAL')` is inline at V1:69-70. Postgres names it `ledger_entries_type_check`.
- Consumer `OrderEventConsumer.Listener.onEvent` (:84-127). For RETURN_REFUNDED with returnShipmentId, returnId and refundAmount it calls `reverseReturn` (:92-99). Otherwise it falls back to `reverseRefund` for shipments with status RETURNED/REFUNDED, or the whole order if paymentStatus is REFUNDED (:101-110). Shipment status is an `OrderStatus` (no RETURNED/REFUNDED value), so the first fallback is dead. `OrderEvt` (:33-44) has no `returnItemsValue`, but the producer sends it (order `OrderEvent.java:38`, `OrderEventPublisher.java:116-120`). RETURN_REFUNDED is only published from `ReturnService.java:163`.
- Error handling: `DlqErrorHandlers.create` (common-web DlqErrorHandlers.java:35-77) is wired at OrderEventConsumer.java:62. It retries 3 times, then publishes to `order.events.DLT` with an ERROR log. `IllegalArgumentException` is not retryable and goes to the DLT at once. **A DLT exists; nothing needs adding.**
- `OrderFeignClient.shipment(id)` calls `GET /internal/orders/shipments/{id}`. It returns `ShipmentInfo(shipmentId, orderId, userId, sellerId, status)` with no amount (OrderFeignClient.java:16-19; order `OrderStore.shipmentRef` :460-468).
- `JobLock.runExclusive` (common-web JobLock.java:49) is a bean from CommonWebAutoConfiguration:53. Seller-service already uses it (AdService.java:244-249) and has `@EnableScheduling`.
- Payout `run` (:291-343) takes `SELECT ... WHERE payout_id IS NULL AND created_at <= :c FOR UPDATE`, then sets `payout_id`. Rows are never edited otherwise.

## 2. Proposed change
**2.1 One capped reversal core (LedgerService).** `reverseReturn` and `reverseRefund` both call a private `reverse(shipmentId, returnId /*null = whole*/, Optional<BigDecimal> basis)`, in one transaction:
1. Per-return replay check: if a REFUND_REVERSAL with this `return_id` exists, return (no-op).
2. Lock and read: `SELECT seller_id, order_id, amount, currency FROM ledger_entries WHERE shipment_id=:s AND type='SALE' AND return_id IS NULL FOR UPDATE`. This row lock serialises every reversal of the shipment. If there is no row, WARN, write an UNMATCHED_REVERSAL report row (2.4), and return.
3. Read under the lock (plain SELECT): `R = -SUM(REFUND_REVERSAL)`, `C = -COMMISSION`, `RC = SUM(COMMISSION_REVERSAL)` for the shipment, across **both** keys. `remaining = S - R`.
4. `amt = min(basis or remaining, remaining)`. If basis > remaining, WARN "capped". If `amt <= 0`, post nothing. Post `REFUND_REVERSAL -amt` with the given key.
5. Commission: if `amt == remaining` (completing), post `C - RC`. Otherwise post `min(round(amt x C / S, HALF_UP), C - RC)`. The ratio is the **booked** C/S, not the current `commission_percent`. Zero rows are not written.
6. **Fee-policy decision point** `feeReversible(completing)`, one method, Q1 = B, PROVISIONAL. When the shipment is now fully reversed and a FEE row exists, post `FEE_REVERSAL = -FEE.amount` in the FEE row's currency. It uses the per-shipment key (`return_id NULL`), so it is posted at most once, whichever path completes. The fee is never recomputed.
- `reverseReturn(orderId, shipmentId, returnId, returnItemsValue, currency)`: the basis is returnItemsValue (AC-3). `reverseRefund(orderId, shipmentIds)` keeps its signature: per SALE of the order, basis = remainder. No event routes to it after 2.2. It stays as the whole-shipment entry on the shared cap (AC-5) for part B's per-shipment refund. If part B does not use it, the EM may delete it.
- Discounts and shipping are never charged to the seller: the basis is items value only (Q3, AC-3).

**2.2 Consumer (OrderEventConsumer).**
- Add `BigDecimal returnItemsValue` to `OrderEvt`. Jackson ignores unknown fields, and the field order does not matter for JSON.
- RETURN_REFUNDED requires returnId, returnShipmentId and returnItemsValue. If any is missing: `log.error("LEDGER ALERT malformed RETURN_REFUNDED order={} ...")`, then throw `IllegalArgumentException`. DlqErrorHandlers does not retry it and sends it straight to `order.events.DLT` (AC-6).
- Remove the fallback (:101-110). `refundAmount` is no longer read.
- Every event that carries `shipments` calls `ledger.track(orderId, parts, currency)` (2.3) before the postings, in the same listener call.

**2.3 Shipment register (reconcile data source, risk 1).** New table `ledger_shipments(shipment_id PK, order_id, seller_id, status, amount, currency, updated_at)`. It is upserted from every order.events message with a `shipments` list (ORDER_PLACED, SHIPMENT_STATUS_CHANGED, ORDER_STATUS_CHANGED, RETURN_*), which carry each shipment's status and items value. V3 seeds it from existing SALE rows. This is seller-service's own record of what order-service announced, and it needs no order-service change.

**2.4 Reconcile (new `LedgerReconciler`, @Scheduled cron + `JobLock.runExclusive("seller-ledger-reconcile")`).** Read-only on the ledger. Keyset paging by shipment_id (`seller.ledger.reconcile.page-size` 200). Each page is a short read without FOR UPDATE, so it never blocks or waits on a payout run. Checks:
- **MISSING_SALE**: the register says DELIVERED, last updated more than the grace period ago (PT1H), and there is no SALE. Expected = register amount, actual = 0.
- **AMOUNT_MISMATCH**: SALE.amount differs from the register amount.
- **OVER_REVERSED**: R > S (for example, historical rows that reversed refundAmount including shipping).
- **STATE_MISMATCH**: SALE rows created within `lookback-days` (35). `OrderFeignClient.shipment` returns 404, a status other than DELIVERED, or a different sellerId. Feign errors are counted and WARNed, not reported.
- **UNMATCHED_REVERSAL** is written by 2.1 step 2 (expected = returnItemsValue, ref = returnId).

Report table `ledger_discrepancies(id, shipment_id, order_id, seller_id, kind CHECK(...), ref uuid, expected, actual, detail, first_seen_at, last_seen_at, resolved_at, resolved_by)` with `UNIQUE NULLS NOT DISTINCT (shipment_id, kind, ref)`. It uses `INSERT ... ON CONFLICT DO UPDATE SET expected, actual, last_seen_at`, so a second run updates and never duplicates (AC-7). It never posts to the ledger (Q2).

**2.5 Admin API (FinanceController, `hasAnyRole('ADMIN','FINANCE')`; audited by the common AuditInterceptor for `/api/admin/**` writes).**
- `GET /api/admin/ledger/discrepancies?open=true&page&size` returns `Paged<Discrepancy>`.
- `POST /api/admin/ledger/reconcile` runs now under the same JobLock and returns counts per kind. 409 `RESOURCE_CONFLICT` if the lock is held.
- `POST /api/admin/ledger/discrepancies/{id}/repair`:
  - MISSING_SALE: re-confirms through `OrderFeignClient` that the status is DELIVERED and the seller matches. Otherwise 409; Feign down gives 503 DEPENDENCY_UNAVAILABLE. It then calls `recordDelivered` with the register amount and no sale_lines.
  - UNMATCHED_REVERSAL: calls `reverseReturn(order, shipment, ref, expected)` once the SALE exists, otherwise 409.
  - Other kinds: 409 (manual).
  - It sets `resolved_at/resolved_by` and logs INFO with the actor. Repeating it is a no-op: the postings are idempotent and a resolved row returns 200 unchanged (AC-8).

**2.6 Migration V3__ledger_reversals_reconcile.sql (additive).** It runs `DROP CONSTRAINT ledger_entries_type_check`, then re-adds the CHECK with `'FEE_REVERSAL'` (a plain DROP, so a wrong name fails the migration loudly). It also creates `ledger_shipments` with the seed, and `ledger_discrepancies`. No backfill of ledger rows.

**Config** (`application.yml`): `seller.ledger.reconcile.cron` (default `0 30 2 * * *` UTC), `.grace` PT1H, `.lookback-days` 35, `.page-size` 200.

**Contracts.** The consumer now requires `returnItemsValue`. The producer is unchanged since F-10, so **order-service with F-10 must deploy before this** (runbook). `FEE_REVERSAL` is a new `type` string in `GET /api/seller/ledger`: the shape is unchanged and no frontend enumerates types (grep). Three admin endpoints are new. ADR 0008 leg logic is unchanged. Docs: `docs/reference/endpoints.md`, `docs/reference/events.md`, and the td-p9-20 row in `docs/techdebt/phase9.md` and `remediation-plan.md` (td-p9-12/13 stay for part B).

## 3. AC mapping and tests (new `LedgerReversalIntegrationTest`, `LedgerReconcilerIntegrationTest`, `OrderEventConsumerTest`; Testcontainers Postgres, `@MockitoBean OrderFeignClient`)
| AC | Change | Test |
|---|---|---|
| AC-1 | 2.1 step 6 | `wholeReturnReversesBookedFeeOnce` (replay twice: one FEE_REVERSAL of +10.00; USD shipment gives +0.25; 0% seller gives none) |
| AC-2 | 2.1 steps 4-6 | `partialReturnsReverseFeeOnlyWhenCumulativeCoversShipment` (30, then 70) |
| AC-3 | 2.1 basis, 2.2 | `reversalUsesItemsValueNotRefund` (items 100, refund 95, pct 10: -100 / +10) |
| AC-4 | 2.1 steps 2-4 | `reversalCappedAtRemainderAndWarns` (+ OutputCapture); `concurrentReturnsShareCap` (two threads, 60 + 60 on 100: total 100) |
| AC-5 | shared cap across keys | `wholeShipmentAfterPerReturnPostsRemainderOnly`; `perReturnAfterLegacyReversalPostsNothing` |
| AC-6 | 2.2 | `malformedReturnRefundedThrowsNonRetryableAndPostsNothing` (each missing field; replayed twice); `dlqHandlerDoesNotRetryIllegalArgument` (common-web; add it if absent) |
| AC-7 | 2.3, 2.4 | `missingSaleReported`, `amountMismatchReported`, `stateMismatchReported`, `secondRunDoesNotDuplicate`, `runSkippedWhenLockHeld` (lock held on a second connection), `reconcileWritesNoLedgerRows` |
| AC-8 | 2.5 | `repairPostsMissingSaleOnce` (twice: one SALE/COMMISSION/FEE, row resolved); `repairRefusedWhenNotDelivered`; `repairUnmatchedReversalAfterSale` |
| AC-13 | 2.1 | `LedgerPermutationTest`: after DELIVERED, all 120 orders of {R1 33.33, R2 66.68, R1 replay, legacy whole, DELIVERED replay} on S 100.01, pct 12.5. Net is the same to the cent in every order (0 when fully reversed; S-C-F-R1+c1 for the {R1} subset) |
Existing `LedgerIntegrationTest` still passes. In `negativeBalanceCarriesForward`, the net is now 0 (fee reversed), and the assertion holds.

## 4. Alternatives considered
- **Order-service list endpoint for reconcile** (DELIVERED shipments with amounts). This is the authoritative source, but it is a 2-service change that breaks the CEO split. Rejected here; it goes to the backlog for part B.
- **Reconcile from ledger rows only (option 1).** It cannot detect a delivered shipment without a SALE, which is AC-7's main case. Rejected.
- **Unique-key trick for the cap** (one shared key). Partial returns need several rows per shipment, so the keys cannot express a sum. Rejected (risk 3).
- **`pg_advisory_xact_lock(hash(shipment))` instead of the SALE row lock.** It does not wait on payout locks, but it adds hash-collision reasoning, and with no SALE there is nothing to reverse anyway. Rejected. The row lock only waits for a running payout transaction, and cannot deadlock because the reversal holds one row and only inserts.
- **Fall back to `refundAmount` when `returnItemsValue` is missing.** It would charge shipping and discount to the seller (AC-3). Rejected (AC-6).

## 5. Risks, failure modes, rollback
- **What reconcile cannot detect:**
  - a shipment none of whose events reached seller-service (outbox loss, all DLT'd);
  - an amount that order-service changed without an event;
  - returns order-service refunded whose RETURN_REFUNDED was DLT'd (seller-service never learns of them);
  - shipments delivered before deploy that have no SALE (not in the seed).

  Fixing these needs an order-service list or returns endpoint: part B or the backlog, with the Owner told in pr.md.
- **Deploy order:** seller-service before F-10's order-service would DLT every return refund. The runbook must deploy F-09/F-10 first. If a consumer group is reset (earliest), old events without `returnItemsValue` go to the DLT with ERROR logs. They have no money effect, because they were applied before.
- **Historical over-reversals** (refundAmount with shipping) are not corrected. They are reported as OVER_REVERSED, and the cap stops further reversals.
- **Analytics:** a return capped to 0 writes no REFUND_REVERSAL row, so the returns count (AnalyticsService:63) skips it. This is accepted.
- **Load:** STATE_MISMATCH makes at most one Feign call per SALE in the 35-day window, paged and outside transactions.
- **Fee policy** is one method. Changing it to A or C touches only `feeReversible`.
- **Rollback:** revert the image. V3 is additive. Old code ignores the new tables, the widened CHECK is harmless, and FEE_REVERSAL rows stay valid money. Never drop them; correct only with new rows.

## 6. Files and estimate (production lines; target 450, max 600)
| File | Est. |
|---|---|
| `seller/ledger/LedgerService.java` (core, track, fee policy) | 140 |
| `seller/ledger/OrderEventConsumer.java` | 25 |
| `seller/ledger/LedgerReconciler.java` (new: job, checks, repair, list) | 170 |
| `seller/ledger/FinanceController.java` | 35 |
| `db/migration/V3__ledger_reversals_reconcile.sql` | 40 |
| `application.yml` | 6 |
| **Total** | **~416** |
Tests (not counted, ~600): the three test classes above, plus `LedgerIntegrationTest` updates. Docs: endpoints.md, events.md, phase9.md, remediation-plan.md.

**Reuse:**
- JobLock, DlqErrorHandlers and AuditInterceptor (common-web).
- OrderFeignClient with the FeedbackService error mapping (FeedbackService.java:51-58).
- CodedException/ErrorCode (common-core).
- The `Paged` and JdbcClient patterns.
- The StockTotalsReconciler pattern (inventory-service).

No existing reconcile or discrepancy helper exists to share, and the reconciler is domain-specific, so nothing is extracted.

## CTO decision

**Accepted** (forge-cto, 2026-11-21; copied from its reply by the CEO). All 10 design acceptance criteria are met. Class stays M, about 416 production lines. ADR 0012 is Accepted. The fee policy (Q1 = B) stays **PROVISIONAL** until the Owner confirms it in pr.md.

Rulings:
- **(a)** `ledger_shipments` stays in part A. It is a table in the same database, not a new datastore, and the reconcile is worth keeping only if MISSING_SALE can actually fire (condition 1).
- **(b)** Malformed events go to the DLT, with no fallback to a capped refundAmount, because that basis would include shipping and discount and break AC-3 and AC-6.
- **(c)** No deadlock with payout runs: a reversal holds one SALE row lock and then only inserts.
- **(d)** Keep `reverseRefund`, with a comment that it has no event caller until part B. Part B must use it or delete it, and the EM records that as a debt line.
- **(e)** `hasAnyRole('ADMIN','FINANCE')` with AuditInterceptor is acceptable. Failed repairs are not covered by the audit, so they need condition 7.

Conditions (these amend section 2.3):
1. **Register independent of postings.** The `track` register write cannot be rolled back by a failed posting: it commits first in its own transaction, or is written outside the posting transaction. Test: a DELIVERED event whose recordDelivered throws leaves a register row, and reconcile reports MISSING_SALE after the grace period.
2. **No regression on out-of-order events.** Register upserts never move status or amount backwards (an updated_at or monotonic guard). One test.
3. **Record conditions 1 and 2** in this design and in ADR 0012, keeping the PROVISIONAL fee marker. Done by the CEO in this section and in the ADR.
4. **Runbook and pr.md:**
   - order-service with F-09/F-10 deploys first;
   - expect ERROR "LEDGER ALERT" logs and `order.events.DLT` messages for malformed RETURN_REFUNDED;
   - how to replay dead-lettered returns;
   - repair handles UNMATCHED_REVERSAL.
5. **Owner-visible items in pr.md:** the four undetectable gaps and the open Q1 fee confirmation.
6. **Lock-wait test:** a test on a second connection where a reversal waits on a held SALE row lock and completes after commit. The lock-ordering argument goes in the ADR.
7. **Repair actor and failures:**
   - `resolved_by` comes from the authenticated principal, not the request body;
   - failed repairs (409/503) are logged at WARN with the actor;
   - test that a non-ADMIN/FINANCE role gets 403.
8. **Size and verify:** above about 500 production lines, or any order-service change, returns to the CTO. Run forge-verify `--changed` and record its output.
9. **Independent review:** an agent other than the implementer reviews the final SHA.
