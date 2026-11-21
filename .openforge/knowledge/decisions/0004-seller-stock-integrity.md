# 0004 Seller stock integrity: fail-closed seller reservations, compensate half-created offers, seller-scoped stock events

## Status
Accepted (2026-11-19). Class M, accepted by the CTO with one condition (see Consequences). Owner design approval is not required.

## Context
Three problems, from TD-P9-04 and TD-P9-05:
- **No seller stock events.** Seller stock writes publish nothing on `inventory.events` (WarehouseService.java:122-143).
- **Oversell fallback.** When a reservation line names an offer that has no stock rows, it is served from platform rows (ReservationService.java:109-112). The same fallback is how platform offers work, because their rows store `offer_id NULL` (WarehouseService.java:136). A reservation request does not say who owns the offer.
- **Non-atomic create.** Offer creation commits in product-service and then writes stock through a separate Feign call (SellerCatalogService.java:168-185, 220-241). When that call fails, the offer stays.

O-08 owns outbox work, and no new service, datastore or migration is wanted.

## Decision
**Seller-scoped events.**
- Non-platform stock writes whose quantity changed publish one `INVENTORY_QUANTITY_CHANGED` through the existing after-commit `InventoryEventPublisher`.
- The event's quantity is the seller's total for that product/variant.
- The event carries two new nullable fields, `sellerId` and `offerId`.
- product-service ignores events that carry `offerId` when it updates `last_known_quantity`.

**Fail-closed reservations.**
- order-service sends the line's `sellerId`, which it already resolves from the offer (OrderService.java:592-597).
- inventory falls back to platform rows only when that seller is the platform. A seller offer with no rows gets the existing zero-available shortfall.
- During a rolling deploy, if `sellerId` is missing, the owner is resolved before the transaction opens via `SellerDirectory.offer`. When the owner cannot be resolved, the reservation is rejected.

**Compensation, not atomicity.**
- If the stock write fails after the offer committed, product-service deletes the offer it just created (`OfferService.discard`). If the product was created in the same call, it deletes the product with `ProductCatalogService.delete`, the same compensation the creation saga already uses.
- The caller then gets 400 or 503.
- If the compensation itself fails, the offer is logged as `OFFER_STOCKLESS`. It is still safe because it shows 0 available and cannot be reserved.

**Bulk.** Bulk CSV rejects later rows whose SKU repeats one earlier in the file (trimmed, case-insensitive).

## Alternatives considered
- **Inventory-owned create:** it inverts ownership and moves the same two-step problem rather than removing it. Rejected.
- **Reconcile job:** it needs a cross-service scan plus a JobLock job, and it finds problems late. Fail-closed reservations already make stockless offers harmless. Rejected.
- **`stockPending` column plus migration:** `availableQuantity 0` already reports the state. Rejected.
- **Owner lookup on every offer line inside the reservation transaction:** it makes an HTTP call while holding row locks and makes checkout depend on product-service. Kept only as a pre-transaction fallback during rollout.
- **Product-wide quantity on seller events:** it would flap against the platform-only totals that platform events carry. Rejected.
- **New outbox:** O-08 owns it.

## Consequences
- Additive event and DTO fields; no migration. Rollback means reverting the images.
- Deploy order is product-service, then order-service, then inventory.
- A timeout after inventory has committed can leave orphan seller rows. Reservations cannot reach them, and they are re-pointed when the create is retried (`upsert` COALESCE, WarehouseStockRepository.java:115-117).
- **CTO condition 1:** on discard, product-service zeroes the offer's stock as a best effort.
  - It does this by writing quantity 0 for the discarded offer through the existing `putStock`.
  - Without it, a seller row whose write timed out but committed would leave a false back-in-stock event and an inflated `offerTotal`.
  - If zeroing fails, it is logged and does not change the error returned to the caller.
  - The CTO decision is recorded in `.openforge/work/F-20261119-05/design.md`.
- Backlog:
  - product's listener ignores `variantId`.
  - Seller-row reservations and restores publish no events.
  - `setOfferStock` makes an HTTP call inside its transaction.
  - TD-P9-04 stays open for the async bulk job.

## Work id
F-20261119-05 (TD-P9-04, TD-P9-05). Design: `.openforge/work/F-20261119-05/design.md`.
