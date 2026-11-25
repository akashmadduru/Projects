# F-20261119-16 Complexity

| Factor | Score | Reason |
|---|---|---|
| Services or apps touched | 0 | seller-service only |
| Data change | 0 | no table, column or migration; register already exists (F-13) |
| Public API / event contract | 0 | no endpoint or event change; the admin repair still answers 409 |
| Security or money path | 2 | direct: ledger booking (SALE, COMMISSION, FEE) and payout input |
| New dependency or infrastructure | 0 | none |
| Estimated changed lines | 0 | about 40-80 production lines (tests excluded) |
| **Total** | **2** | |

Class: **S** (0-2). Required: requirements, tasks, implement, test, review. No architect design.md and no ADR.
Production line limit: 300 (S). Owner override: none.
Owner approval: not required for the design (S). Push and merge still need the Owner.

## Code findings that shape the work (for implementer and reviewer)
- `LedgerService.track` already ranks CANCELLED highest, so a lower-rank event never unseats it. Two gaps remain. A same-rank replay of CANCELLED still rewrites `amount` and `updated_at`, which AC-5 forbids. A CANCELLED part for an unknown shipment is already inserted, so AC-1 holds today. Tighten the upsert: when the stored status is CANCELLED, change nothing.
- `recordDelivered` has no register check. This is the real fix. Read the register row inside the booking transaction with a row lock (`SELECT status FROM ledger_shipments WHERE shipment_id = :s FOR SHARE`). If it is CANCELLED, log one WARN with ids only and return. The lock makes the concurrent cancel versus deliver race land as AC-3 or AC-4 and never as booked-after-cancel. `track` runs REQUIRES_NEW and commits first, so the lock must not deadlock with it. The tests must prove that.
- The legacy path (no `shipments` array, shipmentId = orderId) has no register row, so it is unguarded. If a CANCELLED legacy event is to block a later DELIVERED, `track` must also run for that path. This is a decision for the architect-less implementer: keep it minimal and test it.
- The reconcile `check` already reports nothing for CANCELLED with no SALE (AC-8). It already reports STATE_MISMATCH for a CANCELLED shipment with a SALE (AC-9). This needs tests, not code.
- `ORDER_CANCELLED` parts that are not CANCELLED must not be forced: `track` takes each part's own status, so this already holds. Test it.
- No FEE, commission or reversal code may change (AC-12).

## Rulings on open questions
- Q1: WARN, not ERROR, and no dead-letter. Accepted.
- Q2 (admin repair of MISSING_SALE for a CANCELLED shipment): **ruling is 409 with the discrepancy left open.** The repair never auto-resolves and never books. Reasons: auto-resolving would close a money discrepancy without a human decision, and the Owner has not asked for it. `precheck` already returns 409 when order-service does not confirm DELIVERED. With the new guard, `book` finds no SALE after `recordDelivered` and raises the existing 409 "SALE could not be booked". The repair transaction must roll back so the discrepancy row stays unresolved. The test must assert this. Tweak the message so it says "shipment is cancelled" when the register is CANCELLED, and add no new endpoint. This stays inside AC-11 (the 409 branch).
- Q3: A SALE booked before a CANCELLED stays. Reversal is out of scope. Accepted.

## Design review
Not applicable (class S, no design.md).
