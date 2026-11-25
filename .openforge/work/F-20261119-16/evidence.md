# F-20261119-16 evidence

## Full seller-service module at HEAD (after T4 + T5 spotless)
`bash .openforge/scripts/gradle.sh -C <worktree> :services:seller-service:seller-service-app:test` -> BUILD SUCCESSFUL, gradle_rc=0.
75 tests, 0 failures, 0 errors, 0 skipped (LedgerCancelledShipmentIntegrationTest 16, OrderEventConsumerTest 8).

## Guard demos against base 1662edc src/main (new tests at HEAD)
24 tests, 8 failed, all on their real assertions; gradle_rc=1. Concurrency test passed on base (race is timing dependent; kept as a no-deadlock proof at HEAD).
- AC-3 deliveredAfterCancelled: `expected: 0L but was: 1L` (SALE booked after cancel)
- AC-5 replay/earlier status: `expected: 100.00 but was: 999.00` (register amount rewritten)
- AC-6 permutations: `[[SHIPPED, CANCELLED, DELIVERED]] expected: 0L but was: 1L`
- AC-7 duplicate events: `expected: <updated_at t1> but was: <later>` (updated_at rewritten)
- AC-10 mixed event: `expected: 0L but was: 3L` (cancelled part booked SALE/COMMISSION/FEE)
- AC-11 repair on cancelled: `Expecting code to raise a throwable.` (base books the SALE, no 409)
- legacy integration (cancel then deliver): `expected: 0L but was: 1L`
- OEC legacyCancelledEventIsTracked: `WantedButNotInvoked: ledgerService.track(...)`
src/main restored to HEAD afterwards.

## QA
Source: JUnit XML from engineer's full run at 3e34923 (no rebuild by QA). All suites 0 failures/0 errors.
AC-1: pass — LedgerCancelledShipmentIntegrationTest."AC-1: a CANCELLED part creates a CANCELLED register row and writes no entry" (TEST-com.acme.ecommerce.seller.ledger.LedgerCancelledShipmentIntegrationTest.xml); also OrderEventConsumerTest."a SHIPMENT_STATUS_CHANGED with a CANCELLED part is tracked and books nothing"
AC-2: pass — LedgerCancelledShipmentIntegrationTest."AC-2: ORDER_CANCELLED marks its CANCELLED parts and does not force the others" (TEST-com.acme.ecommerce.seller.ledger.LedgerCancelledShipmentIntegrationTest.xml)
AC-3: pass — LedgerCancelledShipmentIntegrationTest."AC-3: DELIVERED after CANCELLED books nothing, stays CANCELLED, warns with ids only" (TEST-com.acme.ecommerce.seller.ledger.LedgerCancelledShipmentIntegrationTest.xml)
AC-4: pass — LedgerCancelledShipmentIntegrationTest."AC-4: CANCELLED after a booked DELIVERED leaves the entries exactly as they were" (TEST-com.acme.ecommerce.seller.ledger.LedgerCancelledShipmentIntegrationTest.xml)
AC-5: pass — LedgerCancelledShipmentIntegrationTest."AC-5: replays and earlier statuses leave a CANCELLED row untouched" (TEST-com.acme.ecommerce.seller.ledger.LedgerCancelledShipmentIntegrationTest.xml)
AC-6: pass — LedgerCancelledShipmentIntegrationTest."AC-6: every order of SHIPPED, DELIVERED, CANCELLED ends CANCELLED; books iff DELIVERED came first" (TEST-com.acme.ecommerce.seller.ledger.LedgerCancelledShipmentIntegrationTest.xml)
AC-7: pass — LedgerCancelledShipmentIntegrationTest."AC-7: the same CANCELLED and ORDER_CANCELLED event twice leaves identical state" (TEST-com.acme.ecommerce.seller.ledger.LedgerCancelledShipmentIntegrationTest.xml); OrderEventConsumerTest."the same ORDER_CANCELLED twice does not throw and books nothing"
AC-8: pass — LedgerCancelledShipmentIntegrationTest."AC-8: the reconcile reports nothing for a CANCELLED shipment without a SALE" (TEST-com.acme.ecommerce.seller.ledger.LedgerCancelledShipmentIntegrationTest.xml)
AC-9: pass — LedgerCancelledShipmentIntegrationTest."AC-9: a CANCELLED shipment that still has a SALE stays a STATE_MISMATCH" (TEST-com.acme.ecommerce.seller.ledger.LedgerCancelledShipmentIntegrationTest.xml)
AC-10: pass — LedgerCancelledShipmentIntegrationTest."AC-10: a mixed event books only the DELIVERED part" and "AC-10: an error on a later part does not undo the CANCELLED register write" (TEST-com.acme.ecommerce.seller.ledger.LedgerCancelledShipmentIntegrationTest.xml)
AC-11: pass — LedgerCancelledShipmentIntegrationTest."AC-11: repairing a MISSING_SALE of a cancelled shipment is a 409 and books nothing" (TEST-com.acme.ecommerce.seller.ledger.LedgerCancelledShipmentIntegrationTest.xml)
AC-12: pass — LedgerCancelledShipmentIntegrationTest."a shipment that was never cancelled books exactly as before" (TEST-com.acme.ecommerce.seller.ledger.LedgerCancelledShipmentIntegrationTest.xml) plus unchanged existing suites (LedgerIntegrationTest 4, LedgerReversalIntegrationTest 12, LedgerReconcilerIntegrationTest 27, LedgerPermutationTest 3, LedgerMigrationTest 2, SellerServiceSuspendTest 3), all green.
Gaps: none blocking. AC-12 "no FEE/commission/reversal difference" is shown by existing-suite regression plus one non-cancelled test, not a dedicated diff test. AC-11 asserts the 409 default (open question 2).

## CEO verify (3e34923, detached copy 16R, 2026-11-23T15:10:49Z)
```
forge-verify: 5 changed file(s) in C:/Users/Akash/Desktop/Projects/.openforge/worktrees/F-20261119-16R (base 1662edc4084d)
  PASS gradle :services:seller-service:seller-service-app:test (exit 0)
      BUILD SUCCESSFUL in 13s
      gradle_rc=0
forge-verify: PASS (1 checks)
rc=0
```
