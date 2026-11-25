# F-20261119-15 Requirements: COD orders without a payment row (backlog B-10)

## 1. Problem and users affected
- At placement a COD order is saved, then `OrderService` (order-service, ~:406-419) calls `createPayment`. For COD any
  failure is only logged ("COD order {} placed but payment record not created") and placement still succeeds, so the
  order has no payment-service row and `payment_id` stays null. CARD unwinds instead.
- payment-service `create` (`PaymentService.java:114-162`) is idempotent by order id and inserts method COD as
  `COD_PENDING`. `codCollected` (:940) and `refund` (:691) both use `find()`, which throws 404 PAYMENT_NOT_FOUND.
- Since F-09, a COD return refund routes through payment-service (`OrderStore.route`, ~:672-684, PAYMENT_WALLET) with no
  direct wallet fallback (ADR 0008, F-09 design "COD"). With no row it fails 404 and the return stays PICKED_UP. The
  same 404 hits the COD_COLLECTED outbox call on delivery, so the row also never reaches SUCCEEDED.
- Affected: customers owed a COD refund, support/admins who retry it, finance (COD collection not recorded).

## 2. Scope
- Every new COD order gets a payment row at placement (amount = total minus wallet part, which is what is created today).
- A safe, re-runnable path (architect picks lazy-on-refund or admin/job backfill) gives existing rowless COD orders a row.
- COD return refunds then succeed for new and legacy orders, and the delivery collect-mark succeeds too.
- Docs: payment-problems runbook (replace the "404 on COD refund" note), data-model/api only if contracts change.

## Non-goals
- No new service, datastore or vendor. Migrations additive only. No change to the F-04 refund state machine.
- No change to CARD or WALLET placement, refund routing, or the shipping/coin rules from F-09/F-10.
- No re-introduction of the direct wallet fallback. No reconciliation of rows that already exist.
- No change to when COD is allowed (risk, pincode, limits).

## 3. Acceptance criteria
- AC-1: Given payment-service is healthy, when a COD order is placed, then the order is created as today (same status
  and response), exactly one payment row exists for the order id with method COD, status COD_PENDING, amount = total
  minus wallet part, and the order's `payment_id` is set.
- AC-2: Given payment-service is down or returns an error at placement, when a COD order is placed, then placement
  still returns its normal success response (no new 5xx or 4xx), stock/coupon/coins/wallet are not unwound, and the
  missing row is recorded for later repair (not only logged).
- AC-3: Given AC-2 occurred, when the row is later ensured (retry), then the row is created with the AC-1 values and
  no second row exists.
- AC-4: Given create is called twice or concurrently for one order id (placement retry, replay), then exactly one
  row exists, amount unchanged, and no wallet credit, debit or charge occurs from create.
- AC-5: Given a COD order, then no wallet debit or provider charge is made by creating the row, and a COD payment
  never leaves COD_PENDING except through the delivery collect-mark.
- AC-6: Given a legacy COD order with no row, when the backfill runs, then one row exists whose amount and currency
  equal the order's non-wallet remainder and whose user matches the order; the order's `payment_id` is set.
- AC-7: Given a legacy COD order whose order payment status is COD_COLLECTED (delivered), when backfilled, then its
  row ends SUCCEEDED (collected); given COD_PENDING, the row is COD_PENDING.
- AC-8: Given the backfill ran, when it runs again (or two runs overlap), then no row is added or changed, no error
  is raised, and the result reports zero new rows for already-fixed orders.
- AC-9: Given a legacy COD order that is cancelled or never reached COD_COLLECTED, then the backfill creates no
  COD_COLLECTED row for it and it is never refunded money it did not pay (COD_PENDING still routes to no refund).
- AC-10: Given a legacy COD order in COD_COLLECTED with a PICKED_UP return stuck on the 404, when the refund is
  retried after backfill, then it completes: the refund row is SUCCEEDED, the customer's wallet is credited once
  (idempotency key `refund-<key>`), the return becomes REFUNDED, and a second retry credits nothing more.
- AC-11: Given a legacy COD order delivered before the fix whose collect-mark failed with 404, when the backfilled row
  is processed, then the row is SUCCEEDED and re-marking is a no-op.
- AC-12: Given a COD order with a wallet part, when refunded, then wallet-part and COD-remainder legs together never
  exceed the order's refundable amount (refund of 100% returns exactly the total paid, once).
- AC-13: Given payment-service has no row and is also slow or down during a refund, then the refund still fails
  loudly (503 propagates), the return stays PICKED_UP and no money moves.
- AC-14: Given the backfill is running, when customers place or return other orders, then those requests are
  not blocked (no long-held locks; batch-wise, errors on one order do not stop the rest).
- AC-15: Given the fix is deployed in either order of the two services, then no request that worked before fails.

## 4. Edge cases and abuse cases
- Order total fully covered by wallet: method becomes WALLET, no COD row (existing behaviour; unchanged).
- Remainder zero or negative on a legacy COD row: skip and report, never create a zero or negative row.
- Order id already has a CARD row (provider switch, bad data): backfill must not overwrite or convert it; report it.
- Wrong user or amount supplied to create for an existing row: existing row wins, nothing is updated.
- Partial refunds before backfill (legacy wallet refunds under old fallback): backfilled amount must not allow
  refunding more than was paid; the already-refunded amount must be accounted for. Flag as a design point.
- Backfill access: admin-only if exposed over HTTP (401/403 otherwise); no customer can trigger or target it.
- Crash mid-backfill, or retry after partial failure: re-run completes the rest without duplicates.
- Concurrent lazy create at refund by two admins/retries: one row, one credit.

## 5. Open questions
None blocking. Defaults for the architect:
- Backfill scope: COD orders with no payment row and status not CANCELLED; delivered ones end COLLECTED.
- Refunds already paid by the old wallet fallback (pre-F-09) are not re-paid; AC-12 covers the cap.
- Placement failure is repaired by the same ensure-row path (AC-3), not by failing placement.
