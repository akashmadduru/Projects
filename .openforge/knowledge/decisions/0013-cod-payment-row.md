# 0013 Every COD order gets a payment row: outbox repair at placement, lazy ensure for legacy orders

## Status
Accepted (2026-11-23, CTO, class L, standing Owner approval R-20261119-01). The 9 binding conditions are in `.openforge/work/F-20261119-15/design.md` "## CTO decision".

## Context
- Placement logs and ignores a failed COD `createPayment` (OrderService.java:406-419), so some COD orders have no payment-service row.
- Since F-09 (ADR 0008), a COD return refund goes only through payment-service. A rowless order fails 404 and the return stays
  PICKED_UP. The delivery collect-mark (`COD_COLLECTED` outbox) fails the same way and goes DEAD.
- Pre-F-09 returns may already have been paid by the old wallet fallback under `return-rest-<returnId>`.
- No new service, datastore, vendor, queue or scheduler is allowed. Migrations must be additive.

## Decision
- **The ensure is the existing `POST /internal/payments` create.**
  - It is already insert-on-conflict on `order_id`, the existing row wins, and COD makes no provider or wallet call.
  - order-service adds a narrower Feign view that returns `method` and `amount`. A non-COD or other-amount row is reported and
    never changed.
- **Durable repair at placement (PR-A).**
  - A COD order's save transaction also writes an outbox row `ENSURE_COD_PAYMENT`:
    - aggregate `nameUUIDFromBytes("cod-payment:"+orderId)`, so it never blocks the order's own events;
    - idem key `ENSURE_COD_PAYMENT:<id>`;
    - not before 60 s.
  - A successful inline create marks the row DONE.
  - Otherwise the relay calls `CodPaymentRows.ensure`. It reads the order, skips CANCELLED, non-COD and remainders <= 0
    (reported), creates the row with amount `total - walletAmount`, and attaches `payment_id` only if it is null.
- **Legacy path: lazy ensure, not a batch backfill (PR-B).**
  - The `COD_COLLECTED` delivery ensures and then collects.
  - A COD refund on route PAYMENT_WALLET runs `prepareRefund` before the wallet leg. It does three things:
    1. checks for a legacy credit;
    2. ensures the row;
    3. marks the row collected when the order state says it was collected. That means `deliveredAt` is set and the payment
       status is COD_COLLECTED, PARTIALLY_REFUNDED or REFUNDED.
  - DEAD legacy collect-marks are re-armed from the runbook in batches.
- **Already-refunded source of truth.**
  - Earlier returns: `orders.order_returns` REFUNDED rows. The existing `freezeLegs` sibling cap uses them.
  - The current return: a `payment.wallet_txns` CREDIT under `return-rest-<returnId>`. It is read through the new internal
    `GET /internal/wallet/credits/{key}`, which always returns 200.
    - Equal user and amount: the rest leg is settled with no money moved.
    - Different: 409, reported, and the return stays PICKED_UP.
- **Cap.**
  - Per return, wallet plus rest equals the frozen R.
  - Σ rest <= total - wallet - prior rest. Σ R <= the refundable total.
  - payment-service also caps refunds at the row amount.
- **Deploy order.** PR-A needs only order-service. For PR-B, deploy payment-service first. An old payment-service (404 on the
  lookup) leaves the refund of an order with a row unchanged and fails a rowless one 503.

## Alternatives considered
- **Batch backfill (SQL/Flyway enqueue or an admin job).** It floods the relay ahead of live effects, needs a scheduler or an
  admin endpoint, and makes stuck refunds wait for it. Rejected.
- **A dedicated COD ensure endpoint.** It duplicates `create` and adds a deploy constraint. Rejected.
- **ENSURE on the order aggregate.** A payment-service outage, or a rollback that leaves an unknown kind, would block the order's
  events. Rejected.
- **Failing placement.** This violates the requirement that COD placement never fails on payment-service. Rejected.

## Consequences
- No migrations. Contracts are additive: an outbox kind, a Feign view, one internal GET.
- Legacy rowless orders get a row only when they are refunded or delivered, or when an admin re-arms their collect-mark.
- A COD_PENDING row can exist on an order abandoned more than 60 s into placement. It holds no money.
- Rollback: revert the images (order-service first). Old-build ENSURE rows stay PENDING on their own aggregate (ADR 0010).

## Work id
F-20261119-15 (backlog B-10). Related: ADR 0008, ADR 0010.
