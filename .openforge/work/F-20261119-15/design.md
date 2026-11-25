# F-20261119-15 Design: every COD order gets a payment row (B-10, class L, 2 stacked PRs)

Code refs are to the snapshot `.openforge/worktrees/F-20261119-11R` (71bba97). ADR: `decisions/0013-cod-payment-row.md`.

## 1. Current behaviour
- Placement saves the order in its own tx (`OrderService.java:266-268`, `OrderStore.save` :134-137), then (after stock, coupon,
  coins, wallet) calls `createPayment` (:406-409). For COD a failure is only logged (:418), and `payment_id` stays null.
- payment-service `create` (`PaymentService.java:114-162`) is idempotent by order id: a lookup (:123-127), then an insert with
  `ON CONFLICT (order_id) DO NOTHING` (`PaymentRepository.java:113-117`). The existing row wins. COD gets `COD_PENDING` with no
  provider or wallet call (:134-136).
- `codCollected` (:940-947) and `refund` (:691-692) return 404 when there is no row. `markCodCollected` is a conditional
  COD_PENDING->SUCCEEDED (`PaymentRepository.java:184-190`); `cancelUnpaid` ignores COD (:331-336). On delivery, `COD_COLLECTED` is
  enqueued (`OrderRemote.java:393-395`). The relay call (`OutboxRelay.java:185`) gets 404 and goes DEAD after 20 tries (about 10 h).
- Refunds: `freezeLegs` (`OrderStore.java:624-662`) caps the rest leg at `total - wallet - siblingRest`, counting pre-V13 REFUNDED
  siblings with the legacy split (:641-647). `route` sends paid COD to PAYMENT_WALLET and COD_PENDING to NONE (:672-685).
  `runLegs` runs the wallet leg first (`ReturnService.java:103-118`). The COD rest leg calls `refund(return-<id>, WALLET)` (:154-155),
  which credits the wallet under `refund-<key>` (`PaymentService.java:743-751`).
- Before F-09, a failed COD refund fell back to a wallet credit under `return-rest-<returnId>` (main `ReturnService.java:120-128`).
  It is in `payment.wallet_txns` but not in `payment.refunds`.

## 2. Reuse before build (searched order-service, payment-service, libs; nothing belongs in a shared lib)
| Found | Decision |
|---|---|
| `create` / `POST /internal/payments` (insert-on-conflict on order_id, existing row wins, no money for COD) | **Reuse as the ensure.** No new write endpoint |
| `Outbox`/`OutboxRelay` (JobLock, backoff, DEAD, unknown-kind tolerance, ADR 0010), `Outbox.complete` inline pattern | Reuse. New kind, own aggregate as in CLEAR_CART |
| Amount `total - walletAmount` (`OrderService.java:234`, `:440`); `freezeLegs` cap; `OutboxAdminController` retry | Reuse unchanged |
| `WalletRepository.findDebit`/`txnExists` | Extend: add `findCredit(key)` beside them |
Backlog for the CEO: an admin "retry DEAD outbox rows by kind" action.

## 3. Proposed change
**PR-A (order-service only, about 170 production lines; payment-service unchanged).**
- `Outbox`: add `Kind.ENSURE_COD_PAYMENT` and an `enqueue(..., Duration notBefore)` overload (`next_attempt_at = now()+d`). No migration.
- New `service/CodPaymentRows`, a new file kept clear of F-11's OrderRemote edits.
  - `enqueueRepair(id)`: aggregate `nameUUIDFromBytes("cod-payment:"+id)` (never blocks the order's events). Payload `{orderId}`,
    key `ENSURE_COD_PAYMENT:<id>`, notBefore 60 s (no race with an in-flight placement). `repairDone(id)` calls `outbox.complete(key)`.
  - `Outcome ensure(id)` reads `OrderStore.codRowSpec` (new, read-only), then:
    - not COD -> SKIPPED_NOT_COD; CANCELLED -> SKIPPED_CANCELLED; `payment_id` set -> EXISTING; `total-wallet <= 0` ->
      SKIPPED_BAD_AMOUNT (ERROR);
    - otherwise `payments.ensureRow(CreatePayment(..,"COD"))`, a new Feign method on the same POST that returns
      `PaymentRow(paymentId, status, method, amount)`;
    - method != COD -> CONFLICT_METHOD (ERROR, no attach); an amount mismatch gives a WARN and the existing row wins;
    - otherwise `OrderStore.attachPaymentIfAbsent` (`WHERE payment_id IS NULL`) -> CREATED.
  - No tx is held around remote calls. Transient errors throw (the relay retries); SKIPPED and CONFLICT return, so the row goes DONE.
- `OrderService` (cart-clear lines untouched): :268 becomes
  `tx.execute(s -> { o = store.save(order); if (COD) codRows.enqueueRepair(o.id()); return o; })`; the
  `DataIntegrityViolationException` still surfaces outside the rolled-back tx. In the :407-409 try, after `attachPayment`, call
  `codRows.repairDone(id)` for COD. The :418 log says "recorded for repair".
- `OutboxRelay`: a new case `ENSURE_COD_PAYMENT -> codRows.ensure(payload.orderId)` and a constructor parameter.

**PR-B (lazy legacy path, both services, about 150 production lines).**
- payment-service: `GET /internal/wallet/credits/{key}` (InternalTokenFilter, not routed) always returns 200
  `{found, userId, amount, currency}` (`WalletRepository.findCredit`, type CREDIT). No migration.
- `CodPaymentRows.collect(id)`: `ensure`, then `remote.codCollectedNow` if CREATED or EXISTING. The relay's COD_COLLECTED case
  calls it.
- `CodPaymentRows.prepareRefund(plan) -> legacyPaid`. `ReturnService.runLegs` calls it **before the wallet leg** for COD,
  route PAYMENT_WALLET, rest not SUCCEEDED.
  1. Look up `return-rest-<returnId>`.
     - Same user and amount == frozen rest: return true. runLegs sets the rest SUCCEEDED (no refund call) and finalizes; the
       wallet leg replays `return-wallet-<id>`, which is deduped by key.
     - Different: 409, ERROR "COD LEGACY CREDIT MISMATCH", PICKED_UP.
     - Lookup 404 (old payment-service): an order with a row proceeds as today; a rowless order fails 503.
  2. `ensure`. Anything other than CREATED or EXISTING gives 409 and a report.
  3. If collected (`deliveredAt != null` and status COD_COLLECTED, PARTIALLY_REFUNDED or REFUNDED), call `codCollectedNow`.
  4. Return false; the F-09 legs run unchanged. A 503 from any step propagates before money moves.
- Runbook: re-arm DEAD COD_COLLECTED rows in batches of 500 or fewer; delivery now ensures first (AC-11). This also closes the
  finance gap for delivered, never-returned legacy orders.

**Legacy path: lazy (refund plus collect-mark).** It repairs exactly the stuck orders, when they need it, under the existing
refund lease and the single relay runner. It adds no scheduler, no bulk HTTP surface and no relay backlog (AC-14). Legacy
COD_PENDING orders get their row on delivery; cancelled orders never get one.

**Already-refunded source of truth.** Earlier returns: REFUNDED `orders.order_returns`, using frozen legs or the legacy split for
pre-V13 rows (existing). This return: a `payment.wallet_txns` CREDIT under `return-rest-<returnId>`, the only pre-F-09 COD
fallback key.

**Cap (AC-12).** Per return, wallet + rest = R (frozen). Σ rest <= total - wallet - siblingRest. Σ R <= the refundable total
(requestReturn). payment-service also caps refunds at the row amount (:702-707, :727-730). A legacy-credited rest is never sent again.

**Contracts and deploy order.** Additive only: an outbox kind, a Feign view of an existing endpoint, one internal GET. No
migrations. PR-A: order-service alone. PR-B: payment-service first. Either order is safe (AC-15).

## 4. AC -> change -> test (order-service Testcontainers integration tests unless noted)
| AC | Change | Test |
|---|---|---|
| 1 | inline create + attach + repairDone | `OrderPlacementIntegrationTest.codCreatesRowAndAttaches`: create(id,user,total-wallet,INR,COD), payment_id set, ENSURE row DONE |
| 2 | ENSURE in save tx | `codPaymentDownStillPlaces`: createPayment 503 -> same 201 body, no unwind calls, ENSURE PENDING, notBefore >= 60 s |
| 3 | relay -> ensure | `OutboxIntegrationTest.ensureRepairsRow`: attaches; second pass makes no call; abandoned order -> SKIPPED_CANCELLED |
| 4 | reuse insert-on-conflict | payment `CodEnsureConcurrencyIntegrationTest`: 8 threads, mixed amounts -> 1 row, first amount, 0 wallet_txns, no provider |
| 5 | COD create has no money | same class: COD_PENDING; cancelUnpaid leaves it; only codCollected -> SUCCEEDED |
| 6 | lazy ensure | `CodPaymentRowsIntegrationTest.legacyRowless`: SQL-seeded order -> create(total-wallet, currency, user), payment_id set |
| 7 | collected from order state | collected -> codCollectedNow; COD_PENDING -> ensure only, never collected |
| 8 | idempotent ensure | twice and 2 threads -> exactly one CREATED, then EXISTING, one attach |
| 9 | route NONE + skip cancelled | cancelled -> no call; COD_PENDING return -> route NONE, no ensure, no money |
| 10 | prepareRefund | `ReturnRefundLegacyCodIntegrationTest`: stuck PICKED_UP -> REFUNDED, refund once, retry no calls; payment e2e create->collect->refund credits `refund-return-<id>` once |
| 11 | relay COD_COLLECTED -> collect | re-armed DEAD row -> ensure then collect; redelivery no-op |
| 12 | cap + legacy credit | 100 total / 30 wallet, pre-V13 REFUNDED 40 + new 60 -> Σ 100 once; legacy credit equal -> no refund call; unequal -> 409, no money |
| 13 | ensure before wallet leg | rowless + 503 -> 503, PICKED_UP, creditWallet never called (PR-A: regression on the current path) |
| 14 | lazy, no tx around remote calls | ensure blocked on a latch while another order places; two ENSURE rows, one failing -> the other DONE |
| 15 | additive | PR-A: `ensureRow` parses PaymentResponse JSON; an unknown kind does not block the order aggregate. PR-B: lookup 404 -> refund with a row works, rowless -> 503 |
| auth | new GET | `InternalWalletControllerTest`: missing or wrong X-Internal-Token -> 401/403 |

## 5. Alternatives considered
- **Batch backfill** (SQL/Flyway enqueue of ENSURE rows, or an admin job). Rejected: thousands of older-seq rows would delay live
  outbox effects (1000 rows per pass), it needs a scheduler or an admin endpoint, and stuck refunds would wait for the batch.
- **A new `/internal/payments/cod/ensure`.** It duplicates `create`, which already has every required property, and adds a deploy
  constraint to PR-A. Rejected.
- **ENSURE on the order aggregate.** A payment-service outage would hold back ORDER_PLACED, and a rollback would leave an unknown
  kind blocking the order. Rejected; `collect` ensures instead. **Fail placement on a create error:** violates AC-2. Rejected.

## 6. Risks, failure modes, rollback
- A relay/inline race or a crash after save is safe: insert-on-conflict, `attachPaymentIfAbsent`, and an ENSURE row written in
  the save tx. A stray COD_PENDING row on an order abandoned after 60 s carries no money (COD cancel is a no-op, route NONE).
- A CARD or other-amount row is never changed, only reported. `create` may still finish a pending Razorpay order for an existing
  CARD row; that is a provider order, not a charge. An ENSURE row DEAD after about 10 h is repaired on use (PR-B) or re-armed.
- Rollback, PR-A: revert order-service. Old-build ENSURE rows stay PENDING on their own aggregate (ADR 0010); optionally mark them
  DEAD. PR-B: revert order-service, then payment-service. There is no schema to undo.

## 7. Files expected to change
- PR-A: order-service `outbox/Outbox.java`, `outbox/OutboxRelay.java`, `service/CodPaymentRows.java` (new),
  `service/OrderService.java` (:268, :407-418), `service/OrderStore.java`, `client/PaymentFeignClient.java`; runbook `payment-problems.md`.
- PR-B: payment-service `WalletRepository.java`, `WalletService.java`, `InternalWalletController.java`; order-service
  `CodPaymentRows.java`, `ReturnService.java`, `OutboxRelay.java`, `PaymentFeignClient.java`; runbook (replaces the "404 on COD
  refund" note), `docs/reference/endpoints.md`. Tests as in section 4.

## CTO decision

**ACCEPTED** (forge-cto, 2026-11-23; copied from its reply by the CEO). Class L, standing Owner approval. Every design-acceptance criterion is met. ADR 0013 is Accepted, with these conditions recorded here as its binding amendment.

**Split:** two stacked PRs.
- **PR-A** (order-service only, about 170 production lines): AC-1..5, AC-13, AC-15. It adds the ENSURE_COD_PAYMENT kind, CodPaymentRows, the save transaction and inline repair, the relay case, and the Feign `ensureRow`, with the runbook updated.
- **PR-B** (both services, about 150 lines, stacked on A, payment-service deployed first): AC-6..12, AC-14. It adds `GET /internal/wallet/credits/{key}`, collect, prepareRefund, the runLegs hook and the relay COD_COLLECTED case, plus the docs.

Conditions:
1. **Lazy ensure.** AC-6..9 and AC-11 are met lazily, by an ensure at refund and at collect, not by a backfill run. Test: running the ensure twice gives CREATED, then EXISTING, with one attach.
2. **Runbook re-arm.** An exact, copy-pasteable SQL statement re-arms DEAD `COD_COLLECTED` rows: at most 500 rows per statement, oldest first, one batch then wait for the drain. It touches only `kind='COD_COLLECTED' AND status='DEAD'`.
3. **No money for cancelled or undelivered orders.** A re-arm on a cancelled or undelivered order creates no COD_COLLECTED row and moves no money. Test: `collect` calls `codCollectedNow` only on CREATED or EXISTING with a collected order state.
4. **Rowless legacy orders.** The runbook states that legacy orders never delivered, returned or re-armed keep no row. This is accepted because no money is involved.
5. **When the lookup runs.** The legacy `return-rest-<returnId>` lookup runs on EVERY refund attempt, before the ensure and before any refund call, even when the row exists. It applies only to COD returns on route PAYMENT_WALLET, never to DIRECT_WALLET.
6. **Exact match.** The user must be exactly equal, the amount must match by `compareTo`, and the currency must match too. Tests:
   - a different user;
   - a different amount;
   - a different currency;
   - an exact match, which makes no refund call and leaves the return REFUNDED.

   In the 409 case, assert there is no creditWallet call and no refund call.
7. **Runbook for a mismatch.** The runbook covers the 409 case, where a legacy credit exists but differs: a human settles it, and the runbook says how to find the return and the credit.
8. **Internal credits endpoint.** `GET /internal/wallet/credits/{key}`:
   - returns only userId, amount and currency, never balance or transaction ids;
   - logs no owner or amount above DEBUG;
   - has tests for 401/403 on a missing or wrong token, and for the gateway not routing `/internal/**`;
   - stays out of the public OpenAPI;
   - is listed as internal in endpoints.md.
9. **Old-build relay.** Test that an old-build relay meeting an ENSURE_COD_PAYMENT row neither marks it DEAD with side effects nor delays other aggregates. Keep the rollback note "optionally mark DEAD". A failed outbox insert rolls back the order as before; this is accepted.

**Ordering:** F-15 goes before F-14. F-15 has no migration. F-14 rebases onto the F-15 head and must re-run the F-15 tests (placement, outbox, refund legs).
