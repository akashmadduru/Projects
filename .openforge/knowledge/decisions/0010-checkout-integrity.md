# 0010 Checkout integrity: fail-closed cart fingerprint, opt-in detail batch, scoped cart clear on the order outbox

## Status
Accepted (2026-11-21, CTO; conditions in .openforge/work/F-20261119-11/design.md "CTO decision"). Class L, standing Owner approval R-20261119-01.

## Context
Three problems remain in order-service checkout.
- **Unprotected carts.** A cart whose lines lack `addedAt` gets no fingerprint, so the V12 duplicate-order index does not protect it (OrderService.java:499-504).
- **One product call per line.** `draft` makes one product-service call per line (OrderService.java:556-559). The existing `/api/products/batch` is lean: no variants, offers or buy box, and it drops inactive products, so checkout cannot use it.
- **Cart clear is fire-and-forget.** The clear after placement runs once with the user's token, is lost on failure, and wipes the whole cart, including lines added during checkout (OrderService.java:763-769, CartRepository.java:135-139).

No new service, datastore or vendor is allowed.

## Decision
**Fail closed on a missing fingerprint.**
- A cart checkout whose lines lack `addedAt` is refused with 503 DEPENDENCY_UNAVAILABLE before any order row is written.
- cart-service stores `added_at NOT NULL`, so this happens only on contract drift.
- The fingerprint algorithm does not change. There is no migration and no backfill.

**Opt-in detail batch.**
- `GET /api/products/batch?ids=&detail=true` returns the same `detail()` view as `/{id}`, including inactive products. It skips the variant stock call.
- The default lean response is unchanged.
- order-service makes one call over the distinct ids, through its own Feign contextId with a 5 s read timeout.
- A requested id that is missing from the result gives PRODUCT_UNAVAILABLE before the order row is saved.

**Durable, scoped cart clear on the existing order outbox.**
- The outbox gets a new kind, `CLEAR_CART`. No migration is needed because `kind` is an unconstrained varchar.
- The payload holds the user id taken from the saved order and the consumed lines: product, variant, offer, quantity and addedAt.
- The aggregate is `nameUUIDFromBytes("cart:"+userId)`, so a pending clear never blocks the order's own effects. The idem key is `CLEAR_CART:<orderId>`.
- order-service enqueues the row, tries it inline once and marks it DONE on success. Otherwise `OutboxRelay` (JobLock, single runner) retries it with a 240 s backoff cap and up to 400 attempts.
- Delivery uses a new internal endpoint, `POST /internal/carts/{userId}/consumed` (X-Internal-Token). It deletes only exact matching lines in that user's cart and is idempotent.
- **Relay tolerance (PR-A, shipped before PR-C):** `OutboxRelay` logs an unknown `kind` at ERROR with the row id and skips it. The row stays PENDING, is never marked DONE, and does not stall the pass. PR-A must be live before PR-C is deployed.

## Alternatives considered
- **Content-only fingerprint fallback.** It blocks deliberate repeat orders indefinitely, silently. Rejected.
- **Parallel per-line calls.** Still N requests, plus token propagation across threads. Rejected.
- **Detail by default on `/batch`.** A silent change to a public contract. Rejected.
- **A dedicated `pending_cart_clears` table and job.** It needs a migration and duplicates the outbox's retry and DEAD handling. Rejected.
- **Aggregate keyed by order id.** Head-of-line blocking of the order's PAID and cancel effects. Rejected.
- **Relay calling `DELETE /api/cart`.** There is no user token, and it wipes newer lines. Rejected.

## Consequences
- **Deploy order:** cart-service and product-service before order-service.
- **Clear timing:** the clear completes within 5 minutes of cart-service recovery, for outages up to about 26 h. After that the row is DEAD, logs "OUTBOX DEAD" and can be retried by an admin.
- **Rollback of PR-C (ordered runbook):** (i) stop or scale down the new order-service pods; (ii) `UPDATE orders.outbox SET status='DEAD' WHERE kind='CLEAR_CART' AND status='PENDING'`; (iii) start the old image. Once PR-A is live, step (ii) is only a backstop, because the relay tolerates unknown kinds.
- **Residual risk:** a crash in the milliseconds between the commit of a CARD/COD order and the enqueue loses only the clear. The fingerprint still blocks a re-order.
- **Backlog:** a batched inventory stock-rows endpoint, because product-service still fans out per product.

## Work id
F-20261119-11 (TD-BE-03, TD-BE-06, TD-BE-07, TD-BE-15). Design: `.openforge/work/F-20261119-11/design.md`.
