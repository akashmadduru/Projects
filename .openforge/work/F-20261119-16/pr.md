# Seller ledger: a cancelled shipment is terminal, so a late or replayed DELIVERED books nothing (F-14 #4 follow-up)

**Stacked on F-20261119-13** (`forge/F-20261119-13-seller-ledger`, head 1662edc4). Merge after F-13.
- Range: `1662edc4..3e349239`.
- Size: 37 production lines, seller-service only.
- Scope: no schema, endpoint or event change.

## Why
- F-14 lets sellers and admins cancel one shipment. seller-service already ranked CANCELLED above DELIVERED in its `ledger_shipments` register, but `recordDelivered` never checked the register.
- So a stale, replayed or reordered DELIVERED event for a cancelled shipment still booked SALE, COMMISSION and FEE. The seller would be paid for goods that were never sent.
- F-14 disclosed this gap (CTO condition 5); this PR closes it.

## What changed
- **`LedgerService.recordDelivered`:** reads the register status with `SELECT ... FOR SHARE`. If it is CANCELLED, it logs one WARN with the order and shipment ids only and returns, booking nothing and not dead-lettering.
- **`LedgerService.track`:** the upsert gets `WHERE ledger_shipments.status <> 'CANCELLED'`. A CANCELLED row never changes again: not its status, amount, seller or `updated_at`, whatever the event order or replay.
- **`OrderEventConsumer`:** a legacy CANCELLED event with no shipments array is now recorded, using shipmentId = orderId and the platform seller, the same key that legacy DELIVERED and the V3 backfill use.
- **`LedgerReconciler.book`:** an admin repair of MISSING_SALE for a cancelled shipment answers 409 "SALE not booked: shipment is cancelled". The discrepancy stays open (CTO ruling).
- **Unchanged:** a DELIVERED that was booked *before* the cancel is kept. Nothing is reversed and FEE policy is unchanged (AC-4, AC-12).

## Evidence
- Full seller-service module: 75 tests, 0 failed (gradle_rc=0).
- `forge-verify --changed`: PASS. The engineer ran it, and the CEO re-ran it on a detached copy of 3e34923.
- QA mapped AC-1..AC-12 to passing tests (LedgerCancelledShipmentIntegrationTest, OrderEventConsumerTest).
- `forge-gate`: PASS.
- Guard demos: 8 of the new tests fail on their real assertions against the base:
  - AC-3: a SALE is booked after the cancel (0 expected, 1 found);
  - AC-5: the amount is rewritten (100.00 becomes 999.00);
  - AC-6: the order permutations;
  - AC-7: `updated_at` is rewritten on replay;
  - AC-10: the cancelled part books SALE, COMMISSION and FEE (0 expected, 3 found);
  - AC-11: no 409;
  - both legacy tests.

## Review
- Independent code and security review: both approve 3e34923 in round 1, with no blocker or major findings.
- The code reviewer checked the locking. ON CONFLICT DO UPDATE conflicts with FOR SHARE, so a concurrent cancel waits for a booking already in progress, and a booking waits for an uncommitted cancel and then reads it. No deadlock is possible.

## Known gaps (approved minors, on the backlog)
- **No order check.** The register upsert and the skip do not check that the shipment belongs to the event's order. A forged or buggy cross-order CANCELLED could suppress another order's SALE. This needs write access to Kafka.
- **Suppressed SALE goes unreported.** A CANCELLED register row with no SALE is never checked against order-service.
- **Legacy DELIVERED has no lock.** It does not call `track` first, so FOR SHARE has no row to lock. The ordering guarantee there depends on order events being keyed by orderId.
- **Race test is weak.** The concurrency test proves only that there is no deadlock and that booking is atomic. It cannot detect whether the lock is present.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
