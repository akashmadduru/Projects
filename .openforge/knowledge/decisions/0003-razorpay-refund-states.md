# 0003 Razorpay refunds are PENDING until a signed webhook settles them; one Razorpay order per payment via an insert-first claim

## Status
Proposed (2026-11-19). Class L, so the Owner approves the design before implementation.

## Context
Today a Razorpay refund is final as soon as Razorpay accepts it. `payment.refunds` has no status column, and `refund.processed` / `refund.failed` are ignored (TD-P6-31). As a result, a refund that fails leaves the customer unpaid while the system shows REFUNDED.

Concurrent `create` calls each call Razorpay `createOrder` before inserting the row, so one payment can produce two Razorpay orders (TD-P6-30). Razorpay offers no idempotency key for orders.

Payments in manual capture mode are authorised but never captured, and nothing reports it (TD-P6-32, narrowed).

## Decision
**Refund status.**
- `payment.refunds.status` takes the values PENDING, SUCCEEDED or FAILED.
- Existing rows are backfilled to SUCCEEDED through the column default. They were already counted in `refunded_amount` and shown as refunded downstream.
- A Razorpay refund is inserted as PENDING. Its amount stays reserved in `payments.refunded_amount`, so over-refund remains impossible. Stripe, COD and wallet refunds stay SUCCEEDED.

**Settling a refund.**
- A signed `refund.processed` / `refund.failed` moves the refund out of PENDING with a conditional update (`WHERE status='PENDING'`). This runs under the payment row lock, and the event id is recorded in `processed_events` in the same transaction.
- FAILED releases the reserved amount and logs a `REFUND FAILED` alert.
- A terminal state is never overwritten. Unknown or mismatched events are acknowledged with 200 and change nothing.

**Early refund events.** A refund event can arrive before its refund row is committed. Such events are parked in `payment.razorpay_refund_parked`. The refund transaction applies any parked event after inserting its row. Both sides lock the same `payments` row, so at least one of them always sees the other.

**One Razorpay order per payment.**
- The payment row is inserted first, with `provider_order_id` NULL and `provision_claimed_at` set to now. Only the inserting caller (or a caller that reclaims after a lease expires) calls `createOrder`.
- The order id is published with `WHERE provider_order_id IS NULL`, so only one order is ever reachable.
- Other callers wait up to the lease for that id.

**Capture alert.**
- `payment.authorized` sets `authorized_at`.
- A `@Scheduled` + `JobLock` job claims payments that are still unpaid after a grace period, using `UPDATE ... SET capture_alerted_at=now() ... RETURNING`. It logs one alert per payment and never changes status.
- The same job alerts once on refunds that stay PENDING for too long.

## Alternatives considered
- **Advisory transaction lock held across `createOrder`:** it keeps a DB transaction and connection open during a provider HTTP call, which breaks the service invariant against provider calls inside a transaction. Rejected.
- **Razorpay-side dedupe:** no documented idempotency for orders, and the receipt lookup is unproven. Rejected.
- **Reserve the amount only on SUCCEEDED:** this allows over-refund while a refund is PENDING. Rejected.
- **Poll Razorpay for refund status:** it relies on an unproven API and needs per-refund calls. Rejected; webhooks are documented.
- **Backfill historic refunds as PENDING:** every historic refund would raise the stuck alert, and their webhooks are long gone. Rejected.

## Consequences
- Migrations are forward-only and additive (V7, V8). Rolling back means reverting the image. PENDING rows written in the meantime stay reserved, which matches the old semantics.
- order-service still shows REFUNDED at acceptance. A FAILED refund surfaces only as an operator alert; order-service reconciliation is a follow-up.
- During a rolling deploy, old pods write SUCCEEDED Razorpay refunds. A later `refund.failed` for one of these only logs a warning.
- `switchProvider` can still race and create two Razorpay orders. This is in the backlog.
- Operations must subscribe the Razorpay webhook to `refund.processed`, `refund.failed` and `payment.authorized` (runbook).

## Work id
F-20261119-04 (TD-P6-30, TD-P6-31, TD-P6-32). Design: `.openforge/work/F-20261119-04/design.md`.
