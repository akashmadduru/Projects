# F-20261119-15 Complexity (backlog B-10, COD orders without a payment row)

The CTO's ruling (forge-cto, 2026-11-23), copied from its reply by the CEO. Code inspected: OrderService ~:406-419 (a COD createPayment failure is only logged, :418), OrderRemote codCollectedNow :250 and codCollectedBestEffort :393 (outbox COD_COLLECTED), OutboxRelay :185, PaymentService create, codCollected and refund, and InternalPaymentController.

| Factor | Score | Reason |
|---|---|---|
| Services or apps touched | 1 | order-service and payment-service, and possibly the payment-api contract module |
| Data change | 2 | A money-relevant backfill writes rows into existing data; other schema changes are additive |
| Public API / event contract | 1 | Additive: an internal ensure or backfill path, possibly a new outbox Kind; either deploy order must work (AC-15) |
| Security or money path | 2 | Payment rows, COD collection state and wallet-credited refunds |
| New dependency or infrastructure | 0 | Reuses the outbox and relay and the Feign client |
| Estimated changed lines | 1 | About 350-550 production lines in total |
| **Total** | **7** | |

**Class: L.** Owner approval: standing (R-20261119-01). The Owner's rule, recorded in the run log, is that CTO-accepted L designs count as Owner-approved. Limit: 600 production lines per PR.

**Split (stacked PRs on the F-11 head):**
- **PR-A** covers AC-1..5, AC-13 and AC-15: placement creates the row, a failure is recorded durably as an outbox repair entry in the order-save transaction, the relay retries an idempotent ensure, and the collect-mark works on new rows. About 200-350 lines.
- **PR-B** covers AC-6..12 and AC-14: the legacy backfill or lazy ensure, legacy refunds unblocked, the refund cap including earlier refunds, and the runbook. About 250-450 lines.
- One PR is allowed only if the total stays under 600.

## Risks
1. **Double money movement** from a lazy create racing a retry. Create must use a unique key on order id (insert-on-conflict), and the credit is keyed `refund-<key>`.
2. **Legacy over-refund (AC-12, highest risk).** The old wallet fallback may already have paid part of the refund. Use a defined source of truth for the amount already refunded; otherwise refuse and report.
3. **COLLECTED state** is derived only from the order's COD_COLLECTED status. Never mark a cancelled or undelivered order collected.
4. An existing row with another method (CARD) is never overwritten; it is only reported.
5. The repair record cannot fail placement; it goes in the same transaction as the order save.
6. **Deploy order (AC-15):** each service tolerates the other's old version.
7. **Backfill:** batches, small transactions, per-order error isolation, no long locks.
8. Any HTTP surface is admin or internal only, with auth tests. Prefer the internal job or refund-retry path.
9. The wallet-part amount uses the same calculation as placement.

## Design acceptance criteria (step 4)
- The existing outbox and relay are reused, with no new service, datastore, vendor, queue or scheduler.
- Exactly one legacy path (lazy create-on-refund or batch backfill), justified. If lazy, the collect-mark also ensures the row (AC-11).
- The ensure is idempotent: a unique constraint on order id, insert-or-return-existing, the existing row wins, and create makes no wallet or provider call.
- An explicit already-refunded rule and refund cap for legacy orders, tested.
- Migrations and contracts are additive, and the deploy order is stated.
- COD_COLLECTED for backfilled rows comes from order state only. Cancelled orders and bad remainders are skipped and reported.
- No change to the F-04 refund state machine, the CARD and WALLET paths, or routing.
- Any new endpoint is admin or internal, with auth tests.
- A test for every AC, including concurrency tests (AC-4, AC-8), payment-service-down tests (AC-2, AC-13) and an end-to-end legacy refund (AC-10).
- A per-PR estimate under 600.
- An ADR, and the payment-problems runbook updated.

## Design review (2026-11-23)
The CTO accepted the design with 9 conditions and a 2-PR split; see design.md "## CTO decision". Owner approval: standing (R-20261119-01).
