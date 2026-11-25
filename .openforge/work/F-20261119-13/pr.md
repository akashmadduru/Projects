# Seller ledger: capped per-shipment reversals, fee give-back, nightly reconcile and audited repair (TD-P9-20, ADR 0012)

**Two stacked PRs that merge together.**
- **Part A:** `820e5a8..c3162367`, 298 production lines. It covers posting correctness, AC-1..AC-6.
- **Part B:** `c3162367..1662edc4`, 496 lines. It covers reconcile and repair (AC-7, AC-8) and the event-order proof (AC-13). It also carries every follow-up fix from both reviews of Part A.
- **Never merge A alone.** On its own, A records returns that arrive before delivery but has no way to repair them.
- **Branch:** `forge/F-20261119-13-seller-ledger`, built on F-20261119-10 (820e5a8). Merge after the F-01 → F-04 → F-09 → F-10 chain.

## Before merge: the Owner must confirm
1. **Fee policy (PROVISIONAL):** the platform fee is given back once, only when a shipment is fully reversed, and partial returns keep it. The rule lives in one method, `LedgerService.feeReversible`. "Never give it back" or "pro rata" would be a one-method change.

## Why
- Refund and return reversals used different keys, so one sale could be reversed twice.
- Return reversals had no cap, and they were based on the customer refund (shipping included) instead of the items value.
- The platform fee was never given back.
- There was no reconciliation.

## What changed
- **Reversal core:**
  - one capped reversal under a lock on the shipment's SALE row, shared by the per-return and legacy keys;
  - the basis is `returnItemsValue`;
  - commission is reversed at the booked ratio, and the completing return clears the remainder;
  - a reversal naming another order than the SALE goes to the DLT.
- **Fee:** `FEE_REVERSAL` copies the exact booked fee row, once per shipment.
- **Strict events:** a malformed `RETURN_REFUNDED` (missing or non-UUID ids, a value of zero or less) logs `LEDGER ALERT` with presence flags only and goes to `order.events.DLT`. The old whole-order fallback is removed.
- **Shipment register** (`ledger_shipments`): written in its own transaction, so a failed posting still leaves a row for reconcile. Status only moves forward.
- **Nightly reconcile:**
  - runs under a job lock, pages through shipments, takes no row locks, and only writes reports;
  - reports a missing SALE, an amount mismatch and a state mismatch, deduplicated per shipment, kind and return;
  - stops early after 5 consecutive order-service errors, and skips legacy order-level SALEs.
- **Admin endpoints** (ADMIN/FINANCE): list (capped at 200), run now, and repair.
  - A MISSING_SALE repair needs an admin-entered amount. It is rounded to cents, must be above 0, and can be **no more than the amount the register expects**; otherwise it gets 409.
  - The repair locks the discrepancy row, and a durable audit event records the amount and the expected amount.
  - `resolved_by` is the caller's identity. Refusals are logged at WARN with the actor.
- **Migrations:**
  - V3 (additive): widens the type CHECK and adds 2 tables.
  - V4: adds an index on `ledger_entries(shipment_id)`.

## Evidence
- `forge-verify --changed` PASS at 1662edc4 (env reference check, common-web and seller-service tests). Part A was verified at c316236 with its tests passing; its env check failure is fixed in Part B.
- QA mapped AC-1..AC-8 and AC-13 to passing tests in the JUnit XML. `forge-gate` PASS: 2 contiguous parts, each under 600, with Part A approved at its own head.
- Guard tests shown failing on the old code:
  - an uncapped reversal (-135 vs -100);
  - a double reversal (2 rows);
  - the register rolling back with a failed posting;
  - the second-run dedupe;
  - `resolved_by`;
  - the 403;
  - the order check;
  - the amount range;
  - the amount cap;
  - the order mismatch returning 409.
- A permutation test covers all 120 event orders, plus a return arriving before delivery.

## Review
- Independent code and security review, 4 rounds in total.
- Part A: the code reviewer found that the AC-13 proof sat in Part B. Resolved by moving AC-13 to Part B, the fix the reviewer itself offered.
- Part B round 1: security found a **major**. The repair amount had no upper bound, so one admin could book a SALE of up to 10^12 that would flow into payout. It is fixed with the cap and the audit event.
- Both approve Part A at c316236 and Part B at 1662edc4.
- The CTO split the work when the reconciler came in at 320 lines against an estimate of 170. ADR 0012 is Accepted.

## Deploy (Owner or ops)
- **Deploy order-service with F-09/F-10 BEFORE seller-service.** Otherwise every `RETURN_REFUNDED` is dead-lettered.
- **DLT:** expect `LEDGER ALERT` logs and `order.events.DLT` messages for malformed events. Replay by republishing to `order.events`; the consumer is idempotent per return.
- **Larger repairs:** a SALE above the expected amount needs the manual database procedure in `docs/reference/events.md`. There is no API bypass.

## What reconcile cannot see (Owner-visible)
1. Shipments for which no event ever reached seller-service.
2. Amount changes in order-service made without an event.
3. Returns whose `RETURN_REFUNDED` was dead-lettered.
4. Deliveries before this deploy that have no SALE.
5. Historical over-reversals (old refundAmount reversals that included shipping).

Closing 1-4 needs an order-service list endpoint, which is in the backlog.

## Known issues (approved minors, follow-ups)
- **Repair endpoint, admin-only:**
  - the audit event still names the entered amount when the SALE was already present;
  - a huge exponent value can burn CPU and memory before it is refused;
  - the amount cap fails open if a row has no expected amount (none can today);
  - the losing side of two simultaneous repairs leaves no trace.
- Manual-kind discrepancies cannot be closed through the API (TD-P9-23).
- The multi-shipment `reverseRefund` has no caller yet and could deadlock with a payout (TD-P9-22).
- A cancelled shipment is not yet terminal in the register. This is the F-20261119-14 follow-up, after merge.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
