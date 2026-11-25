# F-20261119-05 Seller offer + stock integrity and stock events (portfolio O-10)

## Objective (verbatim, run R-20261119-01 portfolio)
> O-10 | M | TD-P9-04, P9-05 | seller, inventory | Atomic offer+stock writes, stock events.

## Goal
A seller offer never sells stock it does not own, stock changes always publish an inventory event, and an
offer cannot be left half-created without anyone knowing.

## Register items (full text: docs/techdebt/phase9.md#td-p9-04, #td-p9-05)
- TD-P9-04 offer (product-service) then stock (inventory `PUT /api/seller/stock`) is not atomic; bulk CSV is
  per-row, no in-file de-dup, no async, no progress.
- TD-P9-05 seller stock writes publish no `inventory.events`; a reservation line with an `offerId` that has no
  stock rows falls back to platform stock (oversell); sellers cannot stock platform warehouses; warehouse PUT
  not in the contract.

## Affected areas (proved by grep; excerpts only)
- inventory `service/WarehouseService.java:100-183` (`writeStock` :122; `stock.upsert(... platform ? null : offerId ...)` :136).
- inventory `repository/WarehouseStockRepository.java:52-66`: candidate rows for a reservation line —
  `offerId != null` selects the offer's rows, else platform rows (`Platform.SELLER_ID`). The design must show,
  with file:line, where the platform fallback for an offer with no rows actually happens (the register claims
  it; this excerpt alone does not prove it).
- inventory `event/InventoryEventPublisher.java` publishes `inventory.events` strictly after commit (javadoc).
- product `web/SellerController.java:74-83` `POST /api/seller/products/bulk` (multipart) → `SellerCatalogService.bulk`.

## Runtime facts / assumptions
- No external system involved. Every behavioural claim (fallback, publish timing, transaction boundaries)
  needs a file:line in requirements/design (G-1).
- O-08 (outbox for remaining publishers) is a separate objective; do not build a new outbox here — reuse the
  existing after-commit publisher. Product-design questions (may sellers hold stock in platform warehouses
  = fulfilment-by-platform) are business decisions: the CPO keeps them as non-goals unless clearly required.

## Blocking questions
None (Owner delegation, run R-20261119-01).
