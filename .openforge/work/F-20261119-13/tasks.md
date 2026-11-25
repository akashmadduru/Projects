# F-20261119-13 Tasks: seller ledger integrity, part A (class M, seller-service only)

All tasks are owned by **forge-backend-engineer**. Worktree: `.openforge/worktrees/F-20261119-13`. Design: `design.md` (the "## CTO decision" conditions 1-9 are binding). ADR: `.openforge/knowledge/decisions/0012-seller-ledger-reversals.md`.

## Rules for the engineer
- **Commit at every task boundary** (one commit per task, on the `forge/F-20261119-13-*` branch, never push). Past engineers hit an 80-turn limit; keep each task small, finish it, commit, then start the next. Do not batch tasks.
- Do the task, run its verification, commit. If a task is going long, commit what passes and say what is left in the handoff.
- Java builds: one at a time, `bash .openforge/scripts/gradle.sh <args>` (prints `gradle_rc=`). Run long builds in the background.
- SVC = `:services:seller-service:seller-service-app`. Test commands below use `bash .openforge/scripts/gradle.sh ${SVC}:test --tests '<Class>'` unless stated.
- No order-service change (condition 8: any order-service change, or more than about 500 production lines, returns to the CTO). No frontend change.
- Q1 = B fee policy is PROVISIONAL. Keep it in the single method `feeReversible(completing)` and mark it PROVISIONAL in a comment.

## Tasks, in dependency order

### T1. Migration V3 and config
- Goal: add `V3__ledger_reversals_reconcile.sql`: `ALTER TABLE ledger_entries DROP CONSTRAINT ledger_entries_type_check` (plain DROP) then re-add the CHECK with `'FEE_REVERSAL'`; create `ledger_shipments(shipment_id PK, order_id, seller_id, status, amount, currency, updated_at)` seeded from existing SALE rows; create `ledger_discrepancies(id, shipment_id, order_id, seller_id, kind CHECK(MISSING_SALE, AMOUNT_MISMATCH, OVER_REVERSED, STATE_MISMATCH, UNMATCHED_REVERSAL), ref uuid, expected, actual, detail, first_seen_at, last_seen_at, resolved_at, resolved_by)` with `UNIQUE NULLS NOT DISTINCT (shipment_id, kind, ref)`. Add `seller.ledger.reconcile.{cron: "0 30 2 * * *", grace: PT1H, lookback-days: 35, page-size: 200}` to `application.yml`.
- Files: `services/seller-service/seller-service-app/src/main/resources/db/migration/V3__ledger_reversals_reconcile.sql`, `.../resources/application.yml`, plus a small migration test in `LedgerIntegrationTest` (or a new `LedgerMigrationTest`) that inserts a FEE_REVERSAL row and a duplicate discrepancy key (second insert rejected).
- ACs: enabler for AC-1, AC-7. Conditions: none directly (schema for 1, 2).
- Verify: `bash .openforge/scripts/gradle.sh ${SVC}:test --tests 'LedgerIntegrationTest'` (existing tests still pass).

### T2. Capped reversal core in LedgerService
- Goal: add private `reverse(shipmentId, returnId|null, Optional<BigDecimal> basis)` per design 2.1 steps 1-6, in one transaction: per-return replay check; `SELECT ... type='SALE' AND return_id IS NULL FOR UPDATE` (no SALE: WARN, write UNMATCHED_REVERSAL discrepancy row with expected = basis and ref = returnId, return); read R, C, RC across both keys; `amt = min(basis or remaining, remaining)` with WARN "capped"; commission at booked C/S ratio (completing reversal posts `C - RC`); no zero rows; `feeReversible(completing)` posts `FEE_REVERSAL = -FEE.amount` in the FEE row's currency on the per-shipment key (never recomputed). Rewire `reverseReturn(orderId, shipmentId, returnId, returnItemsValue, currency)` (basis = returnItemsValue, `refundAmount` no longer used) and `reverseRefund(orderId, shipmentIds)` (basis = remainder, same signature). Add a comment on `reverseRefund`: "no event caller until part B; part B must use it or delete it" (CTO ruling d).
- Files: `.../seller/ledger/LedgerService.java`; new `.../test/.../LedgerReversalIntegrationTest.java`; update `LedgerIntegrationTest` (`negativeBalanceCarriesForward` net is now 0).
- Tests (named as in design section 3): `wholeReturnReversesBookedFeeOnce` (replay twice, USD +0.25, 0% seller none), `partialReturnsReverseFeeOnlyWhenCumulativeCoversShipment` (30 then 70), `reversalUsesItemsValueNotRefund` (items 100, refund 95, pct 10: -100 / +10), `reversalCappedAtRemainderAndWarns` (OutputCapture), `concurrentReturnsShareCap` (60 + 60 on 100 = 100), `wholeShipmentAfterPerReturnPostsRemainderOnly`, `perReturnAfterLegacyReversalPostsNothing`, `reversalWithoutSaleWritesUnmatchedRow`.
- ACs: AC-1, AC-2, AC-3, AC-4, AC-5. Conditions: 6 (partial: lock-ordering design), (d) comment.
- Verify: `bash .openforge/scripts/gradle.sh ${SVC}:test --tests 'LedgerReversalIntegrationTest' --tests 'LedgerIntegrationTest'`

### T3. Lock-wait test (condition 6)
- Goal: a test on a second connection: hold `FOR UPDATE` on the SALE row in an open transaction, start a reversal in another thread, assert it blocks, commit the first, assert the reversal completes with the correct capped amount (read stays valid after the wait). Fix `LedgerService` only if the test exposes a defect.
- Files: `LedgerReversalIntegrationTest.java` (add `reversalWaitsOnHeldSaleLockThenCompletes`).
- ACs: AC-4 (concurrency). Conditions: 6.
- Verify: `bash .openforge/scripts/gradle.sh ${SVC}:test --tests 'LedgerReversalIntegrationTest'`

### T4. Shipment register `track` (conditions 1 and 2)
- Goal: `LedgerService.track(orderId, parts, currency)` upserts `ledger_shipments` in its **own committed transaction** (`REQUIRES_NEW` or a separate bean/JdbcClient call outside the posting transaction), so a failing posting never rolls it back. The upsert is monotonic: it never moves status or amount backwards (guard on `updated_at` from the event, or a status rank), and an out-of-order older event is ignored.
- Files: `.../seller/ledger/LedgerService.java` (or a small `LedgerShipmentRegister` class if that keeps LedgerService smaller), new tests in `LedgerReversalIntegrationTest` or `LedgerShipmentRegisterTest`.
- Tests: `registerSurvivesFailedPosting` (DELIVERED whose `recordDelivered` throws still leaves a register row; the reconcile assertion for MISSING_SALE is finished in T7), `registerNeverMovesBackwards` (DELIVERED then an older SHIPPED leaves DELIVERED and the amount unchanged).
- ACs: enabler for AC-7. Conditions: 1, 2.
- Verify: `bash .openforge/scripts/gradle.sh ${SVC}:test --tests 'LedgerReversalIntegrationTest'` (plus the new class if separate)

### T5. Consumer: strict RETURN_REFUNDED, drop fallback, call `track`
- Goal: in `OrderEventConsumer`, add `BigDecimal returnItemsValue` to `OrderEvt`. RETURN_REFUNDED requires `returnId`, `returnShipmentId`, `returnItemsValue`; if any is missing, `log.error("LEDGER ALERT malformed RETURN_REFUNDED order={} ...")` and throw `IllegalArgumentException` (non-retryable; DlqErrorHandlers sends it to `order.events.DLT`). Remove the whole-order/whole-shipment fallback (:101-110); `refundAmount` is no longer read. Every event carrying `shipments` calls `ledger.track(...)` before the postings, in the same listener call. In `libs/common-web`, add `dlqHandlerDoesNotRetryIllegalArgument` only if no such test exists (grep first).
- Files: `.../seller/ledger/OrderEventConsumer.java`, new `.../test/.../OrderEventConsumerTest.java`, possibly a test under `libs/common-web`.
- Tests: `malformedReturnRefundedThrowsNonRetryableAndPostsNothing` (each missing field; replayed twice; ERROR log contains "LEDGER ALERT"), `wellFormedReturnRefundedCallsReverseReturnWithItemsValue`, `eventsWithShipmentsAreTracked`.
- ACs: AC-6, AC-3 (event basis). Conditions: 1 (call order).
- Verify: `bash .openforge/scripts/gradle.sh ${SVC}:test --tests 'OrderEventConsumerTest'` (and `bash .openforge/scripts/gradle.sh :libs:common-web:test` only if a common-web test was added)

### T6. Permutation test (AC-13)
- Goal: `LedgerPermutationTest`: after DELIVERED, all 120 orders of {R1 33.33, R2 66.68, R1 replay, legacy whole, DELIVERED replay} on S 100.01, pct 12.5. Net per shipment is identical to the cent in every order (0 when fully reversed; S-C-F-R1+c1 for the {R1}-only subset). Fix `LedgerService` if any order differs.
- Files: new `.../test/.../LedgerPermutationTest.java`; `LedgerService.java` only if a defect shows.
- ACs: AC-13 (also exercises AC-4, AC-5). Conditions: none new.
- Verify: `bash .openforge/scripts/gradle.sh ${SVC}:test --tests 'LedgerPermutationTest'`

### T7. LedgerReconciler (job and checks)
- Goal: new `LedgerReconciler` with `@Scheduled(cron = "${seller.ledger.reconcile.cron}")` running `JobLock.runExclusive("seller-ledger-reconcile", ...)`. Read-only on the ledger, keyset paging by shipment_id (page size from config), short reads without FOR UPDATE. Checks: MISSING_SALE (register DELIVERED, older than grace, no SALE; expected = register amount, actual 0), AMOUNT_MISMATCH, OVER_REVERSED (R > S), STATE_MISMATCH (SALEs within lookback-days, via `OrderFeignClient.shipment`: 404, status not DELIVERED, or different seller; Feign errors counted and WARNed, not reported). Upsert into `ledger_discrepancies` with `ON CONFLICT (shipment_id, kind, ref) DO UPDATE SET expected, actual, last_seen_at` (use the NULLS NOT DISTINCT key). A public `runNow()` returns counts per kind, or signals lock-held.
- Files: new `.../seller/ledger/LedgerReconciler.java`, new `.../test/.../LedgerReconcilerIntegrationTest.java` (`@MockitoBean OrderFeignClient`).
- Tests: `missingSaleReported`, `missingSaleReportedAfterFailedPostingLeavesRegisterRow` (condition 1: DELIVERED event whose `recordDelivered` throws, register row remains, reconcile reports MISSING_SALE after the grace period), `amountMismatchReported`, `overReversedReported`, `stateMismatchReported`, `feignErrorIsCountedNotReported`, `secondRunDoesNotDuplicate`, `runSkippedWhenLockHeld` (lock on a second connection), `reconcileWritesNoLedgerRows`.
- ACs: AC-7. Conditions: 1.
- Verify: `bash .openforge/scripts/gradle.sh ${SVC}:test --tests 'LedgerReconcilerIntegrationTest'`

### T8. Admin API and repair
- Goal: in `FinanceController` (`hasAnyRole('ADMIN','FINANCE')`): `GET /api/admin/ledger/discrepancies?open=true&page&size` returning `Paged<Discrepancy>`; `POST /api/admin/ledger/reconcile` (runs now under the same JobLock, returns counts per kind, 409 `RESOURCE_CONFLICT` if held); `POST /api/admin/ledger/discrepancies/{id}/repair`. Repair: MISSING_SALE re-confirms DELIVERED and seller match via `OrderFeignClient` (409 otherwise, 503 `DEPENDENCY_UNAVAILABLE` if Feign is down, map errors as in FeedbackService.java:51-58), then `recordDelivered` with the register amount and no sale_lines; UNMATCHED_REVERSAL calls `reverseReturn(order, shipment, ref, expected)` once the SALE exists, else 409; other kinds 409 (manual). Sets `resolved_at` and `resolved_by` from the **authenticated principal, never the request body**; logs INFO with actor on success and **WARN with actor on 409/503** (condition 7). A repeat on a resolved row returns 200 unchanged.
- Files: `.../seller/ledger/FinanceController.java`, `LedgerReconciler.java` (repair and list methods), new tests in `LedgerReconcilerIntegrationTest.java` or `FinanceControllerTest.java`.
- Tests: `repairPostsMissingSaleOnce` (twice: one SALE/COMMISSION/FEE, row resolved), `repairRefusedWhenNotDelivered`, `repairReturns503WhenFeignDown`, `repairUnmatchedReversalAfterSale`, `repairUnmatchedReversalWithoutSaleIs409`, `resolvedByComesFromPrincipalNotBody`, `failedRepairLogsWarnWithActor`, `nonAdminFinanceRoleGets403` (cover all three endpoints), `reconcileEndpointReturns409WhenLockHeld`.
- ACs: AC-8, AC-7 (trigger). Conditions: 7 (and (e)).
- Verify: `bash .openforge/scripts/gradle.sh ${SVC}:test --tests 'LedgerReconcilerIntegrationTest' --tests 'FinanceControllerTest'` (use only the classes that exist)

### T9. Docs, debt register, runbook
- Goal: update `docs/reference/endpoints.md` (3 admin endpoints), `docs/reference/events.md` (RETURN_REFUNDED requires `returnItemsValue`; consumer DLTs malformed events; `ledger_shipments` fed by every order.events message with shipments), and the `FEE_REVERSAL` type note. Tech debt: remove td-p9-20 from `docs/techdebt/phase9.md` and add a row to `docs/techdebt/remediation-plan.md` (td-p9-12/13 stay for part B). **Add this debt line (condition d)** to `docs/techdebt/phase9.md`: `td-p9-NN | seller-service LedgerService.reverseRefund has no event caller (RETURN_REFUNDED fallback removed in F-20261119-13); part B (per-shipment cancel) must use it for the whole-shipment refund path or delete it | owner: part B follow-up`. Use the next free td-p9 number. Add a short **runbook** section (in `docs/reference/events.md` or the nearest runbook file) covering condition 4: order-service with F-09/F-10 deploys first; expect ERROR "LEDGER ALERT" logs and `order.events.DLT` messages for malformed RETURN_REFUNDED; how to replay dead-lettered returns (republish the DLT record to `order.events` after the cause is fixed); admin repair handles UNMATCHED_REVERSAL.
- Files: `docs/reference/endpoints.md`, `docs/reference/events.md`, `docs/techdebt/phase9.md`, `docs/techdebt/remediation-plan.md`.
- ACs: none (documentation of AC-1..AC-8). Conditions: 4, (d).
- Verify: `grep -n "ledger/discrepancies" docs/reference/endpoints.md` and `grep -n "reverseRefund" docs/techdebt/phase9.md` each return a line.

### T10. Final verify and handoff notes
- Goal: run the full gate, count production lines, and write the handoff message for the CEO (the CEO writes pr.md from it). The handoff must contain: forge-verify output, production line count (about 416 expected; stop and report to the CTO if above about 500), the four reconcile blind spots (shipments whose events never arrived, amount changes with no event, returns whose RETURN_REFUNDED was dead-lettered, pre-deploy deliveries with no SALE), the open Q1 fee confirmation (PROVISIONAL), the deploy order and DLT expectations (condition 4), and the commit list per task (condition 5). Do not review your own change: the independent review (condition 9) is run by another agent on the final SHA.
- Files: none (no code unless verify fails; fix and commit as `T10 fix`).
- ACs: all (gate). Conditions: 4, 5, 8, 9.
- Verify: `node .claude/hooks/forge-verify.mjs --changed` (record the output), and `bash .openforge/scripts/gradle.sh ${SVC}:test` (full seller-service suite, run once).

## Coverage: AC to tasks
| AC | Tasks |
|---|---|
| AC-1 | T2 |
| AC-2 | T2 |
| AC-3 | T2, T5 |
| AC-4 | T2, T3, T6 |
| AC-5 | T2, T6 |
| AC-6 | T5 |
| AC-7 | T1, T4, T7, T8 |
| AC-8 | T8 |
| AC-13 | T6 |
| (all, gate) | T10 |

## Coverage: CTO conditions to tasks
| Condition | Tasks |
|---|---|
| (a) register stays in part A | T1, T4 |
| (b) malformed events to DLT, no refundAmount fallback | T5 |
| (c) no deadlock with payout | T2, T3 |
| (d) keep `reverseRefund`, comment, debt line | T2 (comment), T9 (debt line) |
| (e) ADMIN/FINANCE with audit, failures logged | T8 |
| 1. register independent of postings | T4, T7 |
| 2. no regression on out-of-order events | T4 |
| 3. conditions 1-2 recorded in design and ADR | done by the CEO (already in `design.md` and ADR 0012) |
| 4. runbook and pr.md content | T9, T10 |
| 5. Owner-visible items in pr.md | T10 |
| 6. lock-wait test | T3 |
| 7. repair actor and failures, 403 test | T8 |
| 8. size and verify | T10 |
| 9. independent review of the final SHA | T10 (other agent) |
