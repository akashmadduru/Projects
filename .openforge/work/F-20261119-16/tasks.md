# F-20261119-16 Tasks

Owner role for every task: forge-backend-engineer. Worktree: `.openforge/worktrees/F-20261119-16` (seller-service only).
Production limit: 300 lines (expected 40-80). One gradle build at a time.
Paths: `M` = `services/seller-service/seller-service-app/src/main/java/com/acme/ecommerce/seller/ledger/`, `T` = `services/seller-service/seller-service-app/src/test/java/com/acme/ecommerce/seller/ledger/`.
Gradle: `bash .openforge/scripts/gradle.sh :services:seller-service:seller-service-app:test --tests '<Class>'` (docker, run from the worktree; check `gradle_rc=0`).
Red rule: for each guard test, write the test first (Task 1), run it against the unchanged base, and record the failure on its real assertion (not a compile or setup error) in `evidence.md`. Tests that already hold on the base (marked HOLDS) are regression tests and must pass on the base.

## Task 1. Guard tests first (red on base)
- Goal: add `LedgerCancelledShipmentIntegrationTest` (new) covering the guard behaviours. Run on base. The tests marked RED must fail on their assertions (SALE booked after cancel, amount/updated_at rewritten, repair message). Record outputs.
- Files: `T`LedgerCancelledShipmentIntegrationTest.java (new); `T`OrderEventConsumerTest.java (add consumer-level tests).
- ACs: AC-1..AC-12.
- Tests: see the coverage table.
- Verify: `bash .openforge/scripts/gradle.sh :services:seller-service:seller-service-app:test --tests '*LedgerCancelledShipmentIntegrationTest' --tests '*OrderEventConsumerTest'` on the base: RED tests fail on assertion, HOLDS tests pass.

## Task 2. Delivery guard in recordDelivered (AC-3, AC-6, AC-10)
- Goal: in `recordDelivered`, inside the booking transaction, read `SELECT status FROM ledger_shipments WHERE shipment_id = :s FOR SHARE`. If CANCELLED, log one WARN (orderId and shipmentId only) and return with no booking. Do not throw. Prove no deadlock with `track` (REQUIRES_NEW commits first) via the concurrent test.
- Files: `M`LedgerService.java; `M`OrderEventConsumer.java only if the consumer must pass through (no change expected).
- ACs: AC-3, AC-4, AC-6, AC-10, AC-12 (plus the concurrency edge case).
- Verify: `bash .openforge/scripts/gradle.sh :services:seller-service:seller-service-app:test --tests '*LedgerCancelledShipmentIntegrationTest' --tests '*LedgerPermutationTest' --tests '*LedgerIntegrationTest'`

## Task 3. Terminal CANCELLED in track (AC-1, AC-2, AC-5, AC-7)
- Goal: tighten the `track` upsert so when the stored status is CANCELLED nothing changes (no amount, seller or updated_at rewrite), for same-rank replay and earlier statuses. Unknown statuses never unseat CANCELLED. Legacy events (no `shipments` array): call `track` for that path too so a CANCELLED legacy event blocks a later DELIVERED; keep it minimal and covered by the legacy test.
- Files: `M`LedgerService.java; `M`OrderEventConsumer.java (legacy path only if needed).
- ACs: AC-1, AC-2, AC-5, AC-7, AC-12.
- Verify: `bash .openforge/scripts/gradle.sh :services:seller-service:seller-service-app:test --tests '*LedgerCancelledShipmentIntegrationTest' --tests '*OrderEventConsumerTest' --tests '*LedgerPermutationTest'`

## Task 4. Repair message for a cancelled shipment (AC-11)
- Goal: in the admin repair `book` (LedgerReconciler ~line 354-361), when no SALE results and the register is CANCELLED, raise the existing 409 `ResourceConflictException` with a "shipment is cancelled" message. The transaction rolls back so the discrepancy stays open. No new endpoint. Reconcile `check` is not changed (AC-8 and AC-9 are HOLDS tests).
- Files: `M`LedgerReconciler.java; `T`LedgerReconcilerIntegrationTest.java (only if an existing assertion needs the message update).
- ACs: AC-11, AC-8, AC-9, AC-12.
- Verify: `bash .openforge/scripts/gradle.sh :services:seller-service:seller-service-app:test --tests '*LedgerReconcilerIntegrationTest' --tests '*LedgerCancelledShipmentIntegrationTest'`

## Task 5. Full module run and forge-verify (QA gate)
- Goal: run the whole seller-service test module (AC-12 regression), then the changed-files gate. Record both outputs in `evidence.md`. Check that production lines changed are at most 300.
- Files: none (evidence only: `.openforge/work/F-20261119-16/evidence.md`).
- ACs: AC-12 (all).
- Verify (worktree): `bash .openforge/scripts/gradle.sh :services:seller-service:seller-service-app:test`
- Verify (main checkout): `node .claude/hooks/forge-verify.mjs --changed --base 1662edc4084dd4bcf9fc26cba9283025c3179624 --dir c:\Users\Akash\Desktop\Projects\.openforge\worktrees\F-20261119-16`

## Coverage table (test class `LedgerCancelledShipmentIntegrationTest` = LCS; `OrderEventConsumerTest` = OEC)

| AC | Task(s) | Named test | Base result (Task 1) |
|---|---|---|---|
| AC-1 | 1, 3 | LCS.cancelledPartCreatesCancelledRowWithoutEntries; OEC.shipmentStatusChangedCancelledTracksAndBooksNothing | HOLDS (regression) |
| AC-2 | 1, 3 | LCS.orderCancelledMarksOnlyCancelledParts_deliveredNotForced | HOLDS (regression) |
| AC-3 | 1, 2 | LCS.deliveredAfterCancelledBooksNothingAndWarns | RED: asserts zero SALE/COMMISSION/FEE/sale_lines, base books SALE |
| AC-4 | 1, 2 | LCS.cancelAfterDeliveredKeepsEntriesUnchanged | HOLDS (regression) |
| AC-5 | 1, 3 | LCS.replayAndEarlierStatusKeepCancelledAndAmountAndUpdatedAt | RED: asserts amount and updated_at unchanged, base rewrites on same-rank replay |
| AC-6 | 1, 2 | LCS.allPermutationsOfShippedDeliveredCancelled | RED: asserts no entries when CANCELLED precedes DELIVERED, base books |
| AC-7 | 1, 3 | LCS.duplicateCancelledEventsAreIdempotent; OEC.duplicateOrderCancelledDoesNotThrow | RED on updated_at/identical-state assertion on base; error/dup parts HOLD |
| AC-8 | 1, 4 | LCS.reconcileQuietForCancelledWithoutSale (past grace, order-service up and down) | HOLDS (regression) |
| AC-9 | 1, 4 | LCS.reconcileStillFlagsStateMismatchForCancelledWithSale | HOLDS (regression) |
| AC-10 | 1, 2 | LCS.mixedEventBooksOnlyDeliveredAndCancelledWriteSurvivesPartError | RED: asserts CANCELLED part books none, base books it when delivered arrives after cancel |
| AC-11 | 1, 4 | LCS.repairMissingSaleOnCancelledReturns409AndLeavesDiscrepancyOpen | RED: asserts message "shipment is cancelled" (and no SALE); base message differs, or books |
| AC-12 | 1, 5 | existing seller-service suite (LedgerIntegrationTest, LedgerReversalIntegrationTest, LedgerPermutationTest); LCS.nonCancelledShipmentUnchanged | HOLDS (full module green) |

Legacy and edge tests (all in LCS): legacyCancelledEventBlocksLaterDelivered (RED on base, covered by AC-3/AC-1), concurrentCancelAndDeliverNeverBookAfterCancel (RED/flaky on base, covered by AC-3/AC-4), warnLogCarriesIdsOnly (RED on base: no WARN), nullShipmentOrStatusPartSkipped (HOLDS).
