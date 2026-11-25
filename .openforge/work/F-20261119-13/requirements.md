# F-20261119-13 Requirements: seller ledger integrity (O-09)

Checked against 820e5a8 (F-09 + F-10 included). TDs: td-p9-12, td-p9-13, td-p9-20.

## 1. Problem and users affected
The seller ledger books SALE, COMMISSION and FEE only when a shipment is DELIVERED. Reversals are incomplete or unbounded, and nothing checks the ledger against order-service. Affected: sellers (paid wrongly), finance/admin (payout runs), the Owner (platform money).

Verified in code:
- `recordDelivered` posts SALE (+items value), COMMISSION (-pct), FEE (-10.00 INR / -0.25 other, only when pct > 0).
- FEE is never reversed. `reverseRefund` reverses SALE and COMMISSION only. `reverseReturn` reverses `refundAmount` and commission pro rata, never FEE.
- `RETURN_REFUNDED` with `returnShipmentId`, `returnId` and `refundAmount` goes to `reverseReturn`. Without them, the whole shipment (or order) goes to `reverseRefund`. The event has no per-line amounts.
- `refundAmount` is the customer refund: post-discount, and it includes shipping on the completing return (ADR 0008). The SALE it reverses is items value only. Nothing caps cumulative reversals at the shipment's SALE. A `reverseRefund` after a `reverseReturn` posts again, because the two use different unique keys (`return_id` null vs set).
- Cancel (`OrderLifecycleService.cancel`) is whole-order only, and only while every shipment is pre-dispatch. No SALE exists then, so nothing needs reversing today. The ledger consumes only DELIVERED shipments and RETURN_REFUNDED/RETURNED/REFUNDED. It ignores ORDER_CANCELLED and SHIPMENT_STATUS_CHANGED.
- There is no per-shipment cancel or reject with a refund of that shipment's value, no SLA auto-cancel and no reconcile job.

## 2. Scope
In scope (money paths only):
- Fee policy on refund and return, and its reversal.
- Reversal amounts: per-line or per-shipment basis, cap, no double reversal.
- Per-shipment cancel or reject with refund of that shipment's value, and the event that lets the ledger stay consistent (td-p9-12).
- Nightly reconcile of delivered shipments against `ledger_entries` (td-p9-20).

Non-goals:
- SLA scheduler and auto-cancel, except that an SLA cancel must use the same refund path as AC9(moved to F-14).
- Shipment timeline rows, invoices, GST apportioning, seller tax identity, delivery estimates and notifications (td-p9-12/13 non-money parts).
- Multi-shipment returns, warehouse restock detail, payout-run changes, new service, datastore or vendor.
- Changing ADR 0008 leg logic.

## 3. Acceptance criteria
Policy-dependent ACs (AC-1, AC-2) assume the recommended default in section 5.

- AC-1 (td-p9-20). Given a delivered shipment with FEE f, when a return or refund covers the whole shipment's items value, then the ledger gains one FEE_REVERSAL (or equivalent) entry of +f for that shipment. Replaying the event adds nothing.
- AC-2 (td-p9-20). Given a partial return, when it is refunded, then no fee reversal is posted. The fee is reversed once, when cumulative returned items value reaches the shipment's items value.
- AC-3 (td-p9-20). Given a `RETURN_REFUNDED` event, when seller-service processes it, then the SALE reversal equals the returned items value of that shipment (not the customer refund). Shipping and discount are not reversed from the seller. Example: items 100, discount 10, shipping 5, refund 95 -> seller reversal 100, commission reversal 100 x pct.
- AC-4 (td-p9-20). Given cumulative reversals for a shipment, when a new reversal would take the total above the shipment's SALE, then the posting is capped at the remainder and a warning is logged. The ledger never shows reversals greater than the SALE.
- AC-5 (td-p9-20). Given a shipment with a per-return reversal already posted, when a whole-shipment reversal arrives (legacy `reverseRefund` path), then it posts only the unreversed remainder. Total reversed equals SALE, never 2x.
- AC-6 (td-p9-20). Given a `RETURN_REFUNDED` event with no returned-items-value or shipment id, when it is consumed, then it is not silently treated as a whole-shipment reversal. It is rejected to the DLT (or held), and an alert-level log is written. Order-service always sends the needed fields (the event carries `returnItemsValue` from F-10).
- AC-7 (td-p9-20). Given a delivered shipment (DELIVERED in order-service) with no SALE in `ledger_entries`, or a ledger shipment whose order-service state or amount differs, when the nightly reconcile runs, then a discrepancy report row is written with the shipment id, expected and actual. A second run does not duplicate it. The job is guarded so two instances do not run it at once, and by default it reports without auto-correcting.
- AC-8 (td-p9-20). Given the reconcile finds a missing SALE, when an admin triggers repair (or the job repairs, if the Owner allows), then the missing postings are created through the same idempotent path. Repeat is a no-op.
> **CEO scope ruling (2026-11-23):** AC9(moved to F-14)..AC12(moved to F-14) below were moved to F-20261119-14 (order-service per-shipment cancel) and are not in this objective. See complexity.md "CEO scope ruling for the split" and F-20261119-14/objective.md. They are relabelled AC-n so the gate counts only the in-scope criteria.
- AC9(moved to F-14) → F-20261119-14 (td-p9-12). Given a multi-shipment order where one shipment is cancelled or rejected before dispatch, when the cancel is accepted, then order-service refunds that shipment's items value (plus its pro-rated discount, coupon and coins share) to the customer under a per-shipment idempotency key, and the other shipments are unaffected. A retry sends the same key and refunds once. A failed refund returns 503 and leaves the shipment status unchanged (as the whole-order cancel does).
- AC10(moved to F-14) → F-20261119-14 (td-p9-12). Given a per-shipment cancel of a shipment never delivered, when the ledger consumes the resulting event, then no SALE, COMMISSION or FEE is booked for it later, and nothing is reversed. If a stale DELIVERED is replayed after the cancel, no SALE is booked for a cancelled shipment.
- AC11(moved to F-14) → F-20261119-14 (td-p9-12). Given a shipment that is already SHIPPED or DELIVERED, when a cancel is requested, then the response is 409 `ORDER_NOT_CANCELLABLE` and no refund or ledger entry is created. A seller may only cancel their own shipment (otherwise 404, as other seller shipment endpoints).
- AC12(moved to F-14) → F-20261119-14 (td-p9-13). Given a multi-shipment order, when its returns complete, then the sum of the shipping refund over all returns is the order's shipping exactly once (ADR 0008, regression). The seller ledger never carries shipping in any reversal (see AC-3).
- AC-13. Given any sequence of delivery, partial returns, whole refund and replays, when the final entries are summed per shipment, then net = SALE - COMMISSION - FEE minus the policy-defined reversals, to the cent, and the sum is the same under any event order.

## 4. Edge cases and abuse cases
- Replayed, duplicated or out-of-order events (RETURN_REFUNDED before DELIVERED: skip with warn, then recover through reconcile).
- Return rows created before the migration, with `refundAmount` including shipping.
- Platform seller (0% commission, no fee): no fee reversal is posted. A zero reversal row is not created.
- Currency differences: INR vs other fee amounts. Reverse the exact fee booked, not a recomputed one.
- Rounding: commission reversal per return may differ by a cent from the sum; the final return clears the remainder so the totals match.
- Entries already in a payout (`payout_id` set): reversals are new entries, never edits. A negative balance carries forward, as today.
- A seller cancelling their own shipment after payment to push a refund, or cancelling another seller's shipment (AC11(moved to F-14)).
- Concurrent cancel and ship of the same shipment: exactly one wins; no refund after SHIPPED.
- Reconcile load: it must be paged and must not lock the ledger table during payouts.

## 5. Open questions
Q1 (Owner, policy, not blocking if the default is accepted): does the platform keep its per-shipment FEE when goods are returned or refunded?
- A. Always keep it. Simplest, but sellers pay for failed sales. This is today's behaviour.
- B. Reverse it only when the whole shipment is returned or refunded. **Recommended default**: the fee pays for a handled shipment, and a fully refunded shipment earns no sale. AC-1 and AC-2 assume this.
- C. Always reverse it, including on partial returns.

Q2 (Owner, minor): may the reconcile auto-repair missing SALE postings? Default: report only; repair is an admin action (AC-7, AC-8).

Q3 (CPO note): the commission on returned goods is already reversed pro rata, and no change is proposed. The discount is funded by the platform, not the seller (AC-3). Confirm if sellers fund discounts.

## 6. Suggested split
- Part A, seller-service only (AC-1 to AC-8, AC-13): fee policy, items-value basis, cap, dedupe, malformed events, reconcile. It needs `returnItemsValue` in the event, which is already published at 820e5a8, so it can base on the F-10 head.
- Part B, order-service (AC9(moved to F-14) to AC12(moved to F-14)): per-shipment cancel and refund. It touches order-service, so base it on the F-11 head if F-11 changes the same files. Part A first, because it is the larger money risk.
