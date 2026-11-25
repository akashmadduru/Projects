# 0012 Seller ledger reversals are capped per shipment on items value; reconcile reads an event-fed shipment register and only reports

## Status
Accepted (2026-11-21, CTO, class M; conditions in .openforge/work/F-20261119-13/design.md "CTO decision"). **The fee policy is PROVISIONAL.** It awaits Owner confirmation of Q1 = B in pr.md before merge.

## Context
- seller-service books SALE, COMMISSION and FEE when a shipment is DELIVERED.
- Reversals have three problems:
  - Their basis is the customer refund, which includes shipping and is net of discount.
  - They are uncapped, and the per-return key and the whole-shipment key do not see each other, so a shipment can be reversed twice.
  - They never return the FEE.
- Nothing checks the ledger against order-service. The only order-service lookup is `GET /internal/orders/shipments/{id}`, which has no amount. The CEO split forbids order-service changes in this objective (part A).

## Decision
- **Basis.** A return reversal is `returnItemsValue` (the items value from F-10's RETURN_REFUNDED). Shipping and discounts are platform-funded and never charged to the seller (Q3).
- **Cap.** All reversals of a shipment go through one transaction:
  - It locks the shipment's SALE row with `FOR UPDATE`.
  - It sums REFUND_REVERSAL across both keys (per-return and `return_id NULL`).
  - It posts `min(basis, SALE - reversed)` and WARNs when it caps.
  - Commission is reversed at the booked ratio `COMMISSION / SALE`. The completing reversal posts the exact remaining commission, so the totals match to the cent under any event order.
- **Fee (Q1 = B, PROVISIONAL).** One decision point: when a shipment becomes fully reversed, post `FEE_REVERSAL = -booked FEE`, once, on the per-shipment key. Partial returns keep the fee. Options A (never reverse) and C (always reverse) would change only that method.
- **Schema.** V3 widens the `ledger_entries.type` CHECK to add `FEE_REVERSAL` (drop and re-add). It also adds `ledger_shipments` and `ledger_discrepancies`. Both are additive.
- **Malformed events.** A RETURN_REFUNDED without returnId, returnShipmentId or returnItemsValue is logged at ERROR and thrown as IllegalArgumentException. The existing DlqErrorHandlers sends it to `order.events.DLT` without retries. The old whole-order fallback is removed.
- **Reconcile source.** The consumer upserts every shipment it sees in order.events, with status, amount and seller, into `ledger_shipments`. V3 seeds the table from SALE rows.
- **The nightly job** runs under JobLock and uses keyset-paged, lock-free reads. It reports these kinds:
  - MISSING_SALE and AMOUNT_MISMATCH, from local data (an OVER_REVERSED check was dropped in the part-B trim: the cap stops new over-reversals);
  - STATE_MISMATCH, through OrderFeignClient for SALEs in the last 35 days;
  - UNMATCHED_REVERSAL, written by the consumer.

  Rows are deduplicated on `(shipment_id, kind, ref)`. The job never posts (Q2).
- **Repair** is admin-only (ADMIN or FINANCE) and audited. It re-confirms DELIVERED and the seller through order-service (which does not confirm amounts), then calls the idempotent `recordDelivered` with an admin-entered amount (rounded to cents, above 0, never above the register amount of the row, so 409 otherwise; a larger one is a manual DB procedure for the Owner) or `reverseReturn`. The row is locked while booking; the amount goes into the detail, the log and an `audit.events` record only when it was actually booked.

## Alternatives considered
- **An order-service endpoint that lists delivered shipments.** It is authoritative, but it changes two services and breaks the split. Deferred to part B or the backlog.
- **Reconciling from ledger rows only.** It cannot find a delivered shipment that has no SALE. Rejected.
- **A unique-key-only cap.** It cannot express a sum over several partial returns. Rejected.
- **An advisory transaction lock per shipment.** It adds hash reasoning for no gain over locking the SALE row. Rejected.
- **Falling back to refundAmount when returnItemsValue is missing.** It would charge shipping and discount to sellers. Rejected.

## Consequences
- order-service with F-10 must deploy before this seller-service (runbook). Otherwise every return refund is dead-lettered.
- The reconcile cannot detect:
  - shipments none of whose events reached seller-service;
  - amount changes in order-service that published no event;
  - refunded returns whose event was dead-lettered;
  - pre-deploy deliveries that never got a SALE.

  These need an order-service list or returns endpoint (part B or the backlog).
- Historical over-reversals are neither reported nor corrected; the cap stops new ones.
- Rollback means reverting the image. V3 stays, and FEE_REVERSAL rows remain valid ledger money. Corrections are only ever new rows; rows with `payout_id` set are never edited.

## Work id
F-20261119-13 (TD-P9-20). Design: `.openforge/work/F-20261119-13/design.md`. Related: ADR 0008 (unchanged).

## CTO conditions (2026-11-21)
- **Register independence:** the `ledger_shipments` write (`track`) commits independently of the posting transaction, so a posting that throws and is dead-lettered still leaves a register row. Reconcile then reports MISSING_SALE after the grace period.
- **Monotonic register:** out-of-order events never move a register row's status or amount backwards.
- **Lock ordering (single shipment only):** a reversal of one shipment takes exactly one row lock, on the shipment's SALE row, and then only inserts. A payout run locks unpaid rows and sets payout_id, but never changes amount. Neither side waits for a second lock, so there is no cycle. This does not hold for `reverseRefund` over several shipments: it locks several SALE rows one after another while a payout run locks its rows in one unordered statement, so those two can deadlock (Postgres aborts one; nothing is corrupted). It has no event caller; any caller must reverse one shipment per transaction (td-p9-22). The worst case is waiting for the other side's commit, and the reversal's read stays valid after it.
- **Repair actor:** `resolved_by` comes from the authenticated principal. A failed repair is logged at WARN with the actor.
