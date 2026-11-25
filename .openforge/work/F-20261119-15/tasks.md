# F-20261119-15 Tasks: every COD order gets a payment row (B-10, class L, 2 stacked PRs)

All tasks: owner `forge-backend-engineer`. Each task includes its own tests and ends with a commit on the PR branch
(branch `forge/F-20261119-15-<slug>`, worktree only; no push without the Owner). Run one Gradle build at a time.
Design refs: `design.md` sections 3, 4 and "## CTO decision" (conditions C1..C9).

Verification commands (from project.md):
- ORDER = `bash .openforge/scripts/gradle.sh :services:order-service:order-service-app:test` (expect `gradle_rc=0`)
- PAY   = `bash .openforge/scripts/gradle.sh :services:payment-service:payment-service-app:test` (expect `gradle_rc=0`)
- BOUNDARY = `node .claude/hooks/forge-verify.mjs --changed` (output recorded in the work folder)

Note: if gradle.sh cannot filter tests, run the whole module; the named test classes must be in the module's run.

## PR-A (order-service only; base = F-11 head; AC-1..5, AC-13, AC-15; C9)

### A1. Outbox kind and delayed enqueue
- Files: `services/order-service/order-service-app/.../outbox/Outbox.java`; test `OutboxIntegrationTest`.
- Goal: add `Kind.ENSURE_COD_PAYMENT`; add `enqueue(..., Duration notBefore)` overload (`next_attempt_at = now()+d`). No migration.
  Test: notBefore row is not claimed before due. Test (old-build relay, C9): a relay without the kind meeting an
  ENSURE_COD_PAYMENT row on its own aggregate does not mark it DEAD with side effects and does not delay other aggregates.
- ACs: AC-15. Conditions: C9.
- Verify: ORDER.

### A2. Feign `ensureRow` and OrderStore helpers
- Files: `client/PaymentFeignClient.java` (new `ensureRow` on the same POST returning `PaymentRow(paymentId, status, method, amount)`);
  `service/OrderStore.java` (read-only `codRowSpec(id)`, `attachPaymentIfAbsent` with `WHERE payment_id IS NULL`); tests in the
  existing Feign and OrderStore test classes.
- Goal: additive client view and store methods. Test: `ensureRow` parses the real PaymentResponse JSON incl. unknown fields;
  `attachPaymentIfAbsent` attaches once, second call is a no-op.
- ACs: AC-3, AC-8 (attach once), AC-15. Conditions: none.
- Verify: ORDER.

### A3. CodPaymentRows (enqueueRepair, repairDone, ensure)
- Files: `service/CodPaymentRows.java` (new); test `CodPaymentRowsIntegrationTest` (PR-A cases).
- Goal: `enqueueRepair(id)` (aggregate `nameUUIDFromBytes("cod-payment:"+id)`, key `ENSURE_COD_PAYMENT:<id>`, notBefore 60 s),
  `repairDone(id)` via `outbox.complete(key)`, `Outcome ensure(id)` per design (SKIPPED_NOT_COD, SKIPPED_CANCELLED, EXISTING,
  SKIPPED_BAD_AMOUNT, CONFLICT_METHOD, CREATED; amount mismatch WARN, existing wins). No tx around remote calls; transient errors throw.
  Tests: ensure twice gives CREATED then EXISTING with one attach (C1); abandoned/cancelled order -> SKIPPED_CANCELLED, no call;
  CARD row -> CONFLICT_METHOD, no attach; zero remainder -> SKIPPED_BAD_AMOUNT.
- ACs: AC-3, AC-8 (ensure idempotent, applies in PR-A), AC-9 (cancelled skipped). Conditions: C1.
- Verify: ORDER.

### A4. Relay case and placement wiring
- Files: `outbox/OutboxRelay.java` (case `ENSURE_COD_PAYMENT -> codRows.ensure(payload.orderId)`, constructor parameter);
  `service/OrderService.java` (:268 save+enqueueRepair in one tx, `DataIntegrityViolationException` still surfaces outside the tx;
  :407-409 `repairDone` after `attachPayment` for COD; :418 log "recorded for repair"; cart-clear lines untouched);
  tests `OrderPlacementIntegrationTest` (`codCreatesRowAndAttaches`, `codPaymentDownStillPlaces`), `OutboxIntegrationTest.ensureRepairsRow`.
- Goal: placement creates the row and attaches; on payment-service failure placement is unchanged and the ENSURE row stays PENDING
  with notBefore >= 60 s; relay repairs it, a second pass makes no call. Regression: rowless + 503 on refund path stays 503, PICKED_UP,
  creditWallet never called.
- ACs: AC-1, AC-2, AC-3, AC-13, AC-15. Conditions: C9 (unknown-kind safety retained).
- Verify: ORDER.

### A5. payment-service ensure/idempotency tests (test-only, no production change)
- Files: payment-service `CodEnsureConcurrencyIntegrationTest` (new).
- Goal: 8 threads with mixed amounts -> one row, first amount wins, 0 `wallet_txns`, no provider call; row stays COD_PENDING;
  `cancelUnpaid` leaves it; only `codCollected` -> SUCCEEDED.
- ACs: AC-4, AC-5. Conditions: none.
- Verify: PAY.

### A6. PR-A runbook and boundary (PR-A head)
- Files: `docs/runbooks/payment-problems.md` (replace the "404 on COD refund" note with the ENSURE_COD_PAYMENT repair/DEAD note and
  the rollback note "optionally mark old-build ENSURE rows DEAD"; find the file by search).
- Goal: docs accurate for PR-A; run the full gate and record it. Commit = **PR-A head**.
- ACs: AC-2 (repair recorded and documented). Conditions: C9 (rollback note).
- Verify: ORDER, then BOUNDARY.

## PR-B (stacked on PR-A head; payment-service deployed first; AC-6..12, AC-14; C1..C8)

### B1. payment-service internal credits endpoint
- Files: payment-service `WalletRepository.java` (`findCredit(key)`, type CREDIT), `WalletService.java`, `InternalWalletController.java`
  (`GET /internal/wallet/credits/{key}`, always 200 `{found, userId, amount, currency}`, nothing else, logs at DEBUG at most,
  excluded from public OpenAPI); `docs/reference/endpoints.md` (listed internal); tests `InternalWalletControllerTest`
  and a gateway test that `/internal/**` is not routed.
- Goal: tests for found/not found, missing or wrong `X-Internal-Token` -> 401/403, response has no balance or txn ids, gateway does
  not route the path.
- ACs: AC-10, AC-15. Conditions: C8.
- Verify: PAY (add the gateway module test `bash .openforge/scripts/gradle.sh :services:gateway:test` if the routing test lives there), then commit.

### B2. Feign credits lookup and `CodPaymentRows.collect`
- Files: order-service `client/PaymentFeignClient.java` (credits lookup, 404 tolerated as "old payment-service");
  `service/CodPaymentRows.java` (`collect(id)`: ensure, then `remote.codCollectedNow` only if CREATED or EXISTING and the order is
  collected); `outbox/OutboxRelay.java` (COD_COLLECTED case calls `collect`); test `CodPaymentRowsIntegrationTest` (collect cases),
  `OutboxIntegrationTest`.
- Goal: re-armed DEAD COD_COLLECTED row -> ensure then collect, row SUCCEEDED; redelivery is a no-op; cancelled or undelivered ->
  no `codCollectedNow`; COD_PENDING legacy order -> ensure only, never collected.
- ACs: AC-7, AC-9, AC-11, AC-15. Conditions: C1, C3.
- Verify: ORDER.

### B3. `prepareRefund` legacy-credit lookup
- Files: `service/CodPaymentRows.java` (`prepareRefund(plan) -> legacyPaid`); test `ReturnRefundLegacyCodIntegrationTest` (unit-level cases).
- Goal: lookup `return-rest-<returnId>` on EVERY COD route-PAYMENT_WALLET attempt before ensure and any refund call, even if the row
  exists; never for DIRECT_WALLET. Exact match (same user, `compareTo` amount, same currency) -> true; otherwise 409, ERROR
  "COD LEGACY CREDIT MISMATCH", no creditWallet and no refund call. Lookup 404 (old payment-service): row exists -> proceed, rowless
  -> 503. Then ensure (anything but CREATED/EXISTING -> 409 and report); collected states -> `codCollectedNow`. 503 propagates.
  Tests: different user, different amount, different currency, exact match (no refund call, return REFUNDED).
- ACs: AC-9, AC-10, AC-12, AC-13, AC-15. Conditions: C5, C6.
- Verify: ORDER.

### B4. `runLegs` hook and end-to-end refund tests
- Files: `service/ReturnService.java` (call `prepareRefund` before the wallet leg for COD, route PAYMENT_WALLET, rest not SUCCEEDED;
  legacyPaid -> mark rest SUCCEEDED without a refund call and finalize; wallet leg replays `return-wallet-<id>`);
  tests `ReturnRefundLegacyCodIntegrationTest`; payment e2e test create->collect->refund credits `refund-return-<id>` once.
- Goal: stuck PICKED_UP COD return completes -> refund SUCCEEDED, wallet credited once, return REFUNDED, second retry credits nothing;
  100 total / 30 wallet with pre-V13 REFUNDED 40 plus new 60 -> sum exactly 100 once; rowless + 503 -> 503, PICKED_UP, no money;
  COD_PENDING return -> route NONE, no ensure, no money.
- ACs: AC-9, AC-10, AC-12, AC-13. Conditions: C5, C6.
- Verify: ORDER and PAY (e2e test), then commit.

### B5. Legacy ensure, idempotency and non-blocking tests
- Files: `CodPaymentRowsIntegrationTest` (`legacyRowless`, concurrency, latch test); no production change expected.
- Goal: SQL-seeded legacy order -> create(total-wallet, currency, user), `payment_id` set; collected order ends collected, COD_PENDING
  does not; ensure twice and from 2 threads -> exactly one CREATED then EXISTING, one attach; ensure blocked on a latch while another
  order places does not block it; two ENSURE rows with one failing -> the other DONE; a CARD row is reported not changed.
- ACs: AC-6, AC-7, AC-8, AC-14. Conditions: C1, C3.
- Verify: ORDER.

### B6. PR-B runbook, docs and boundary (PR-B head)
- Files: `docs/runbooks/payment-problems.md`, `docs/reference/endpoints.md` (confirm), `docs/techdebt/` and `remediation-plan.md`
  (remove B-10 entry if it lives there; add a remediation row), backlog note for the admin "retry DEAD by kind" action (CEO-owned; report only).
- Goal: runbook contains (C2) one exact SQL statement re-arming `kind='COD_COLLECTED' AND status='DEAD'` rows, oldest first, at most
  500 per statement, one batch then wait for the drain; (C4) statement that legacy orders never delivered, returned or re-armed
  keep no row, no money involved; (C7) 409 mismatch procedure (find the return, find the `return-rest-<returnId>` credit, human settles).
  Run the full gate for both services and record it. Commit = **PR-B head**. Note: F-14 rebases on this head and re-runs the F-15 tests.
- ACs: AC-11, AC-10. Conditions: C2, C4, C7.
- Verify: ORDER, PAY, then BOUNDARY.

## Coverage: AC -> tasks
| AC | Tasks |
|---|---|
| AC-1 | A4 |
| AC-2 | A4, A6 |
| AC-3 | A2, A3, A4 |
| AC-4 | A5 |
| AC-5 | A5 |
| AC-6 | B5 |
| AC-7 | B2, B5 |
| AC-8 | A2, A3, B5 |
| AC-9 | A3, B2, B3, B4 |
| AC-10 | B1, B3, B4, B6 |
| AC-11 | B2, B6 |
| AC-12 | B3, B4 |
| AC-13 | A4, B3, B4 |
| AC-14 | B5 |
| AC-15 | A1, A2, A4, B1, B2, B3 |

## Coverage: CTO conditions -> tasks
| Condition | Tasks |
|---|---|
| C1 lazy ensure | A3, B2, B5 |
| C2 runbook re-arm SQL | B6 |
| C3 no money cancelled/undelivered | B2, B5 |
| C4 rowless legacy runbook | B6 |
| C5 lookup on every attempt | B3, B4 |
| C6 exact match | B3, B4 |
| C7 mismatch runbook | B6 |
| C8 credits endpoint | B1 |
| C9 old-build relay | A1, A4, A6 |

Total: 12 tasks (PR-A: A1..A6, PR-B: B1..B6).
