# Requirements F-20261119-05: seller offer + stock integrity, stock events (TD-P9-04, TD-P9-05)

Paths below are under services/ in the worktree. Inventory = inventory-service-app/.../inventory/, product = product-service-app/.../product/.

## 1. Problem and users
Sellers can create offers that never get stock, sellers' stock changes are invisible to subscribers, and an
order naming such an offer can consume platform stock. Affects sellers (portal/API), buyers (back-in-stock
alerts), the platform (oversell, support load).

## 2. Register claims checked against code
- TRUE, no events on seller stock writes: `WarehouseService.writeStock` (service/WarehouseService.java:122-143)
  publishes nothing for seller rows; only the platform branch calls `shiftLegacyTotals` (:137-139), which publishes
  (InventoryService.java:382,400). The only post-commit hook there is `syncPricesAsync` (:141).
- TRUE, oversell fallback: ReservationService.java:109-112 re-queries platform rows when an offer has no rows.
  Caveat: the fallback is also what serves platform-owned offers, whose rows store `offer_id = NULL`
  (WarehouseService.java:136; WarehouseStockRepository.java:52-67). Fail-closed applies only to the no-rows
  case at ReservationService.java:113-121.
- TRUE, non-atomic offer+stock: `SellerCatalogService.createOffer` (:168-185) commits the offer via
  `offerService.create`, then calls `writeStock` (:220-241) as a separate Feign call; no @Transactional on the
  method. A null `quantity` skips the stock write entirely (:221-223), so an offer with no rows is also a
  normal outcome, not just a failure.
- TRUE, bulk is per row: `bulk` (:314-360) loops rows, catches each failure into `errors`; there is no
  duplicate-SKU tracking and no async/progress. Rows are capped by MAX_ROWS (:330).

## 3. Scope
- Seller (and any non-platform) stock writes publish the same `inventory.events` quantity-changed events as
  platform writes, after commit, reusing the existing publisher.
- A reservation line with an `offerId` owned by a non-platform seller and with no stock rows is rejected
  as out of stock, never served from platform rows. Platform-owned offers keep working.
- Offer create no longer leaves a silently stockless offer when the stock write fails.
- Bulk CSV: in-file duplicate SKU detection (cheap, synchronous).

## 4. Non-goals
- Async bulk job, status polling, progress (register text TD-P9-04 to be narrowed: "async bulk job with
  status polling" remains open as a new item; in-file de-dup is closed here).
- Fulfilment-by-platform: sellers stocking platform warehouses (TD-P9-05 bullet 3). Business decision.
- Portal per-warehouse stock matrix; `PUT /api/admin/warehouses/{id}` contract (TD-P9-05 bullets 3-4 UI/contract).
- New outbox (O-08), new service, datastore or vendor. Variant/other non-atomic flows (TD-P7-06, TD-P6-33).

## 5. Acceptance criteria
- AC-1: Given a seller-owned warehouse, when the seller writes stock for an offer from 0 to N>0 via
  `PUT /api/seller/stock` (200), then exactly one `inventory.events` quantity-changed event for that
  product/variant with the new quantity is published after the transaction commits.
- AC-2: Given the stock write is rolled back (e.g. quantity < 0 -> 400, or forbidden warehouse -> 403),
  then no event is published.
- AC-3: Given a seller stock write that leaves the quantity unchanged, then no event is published.
- AC-4: Given a seller write moving stock from 0 to >0 for a product with a back-in-stock subscription,
  when the event is consumed, then the alert fires as it does for platform stock.
- AC-5: Given a reservation line whose `offerId` belongs to a non-platform seller and that offer has no
  stock rows, when reserving, then the reservation is rejected with the existing out-of-stock shortfall
  response (same status/body shape as other shortfalls) and platform stock is unchanged.
- AC-6: Given a platform-owned offer (rows stored without `offerId`), when reserving by that `offerId` with
  enough platform stock, then the reservation succeeds as today.
- AC-7: Given a seller offer with its own stock rows totalling < requested qty while platform stock suffices,
  then the reservation is rejected (no top-up from platform stock).
- AC-8: Given `POST /api/seller/products/offers` (create) with a quantity where the inventory call fails
  (5xx/timeout), then the response is an error (not 201) and no offer remains visible in
  `GET` of the seller's offers (or, if the design cannot roll back, the offer is returned flagged
  `stockPending` and listed as such; Architect chooses, behaviour must be observable).
- AC-9: Given offer create with no `quantity`, then the offer is created and is clearly reported with no
  stock (zero available), and reserving it by `offerId` is rejected per AC-5.
- AC-10: Given a bulk CSV in which two rows share a SKU, when posted to `POST /api/seller/products/bulk`,
  then the first row is processed, the later row is reported in `errors` with its row number and a
  "duplicate sku in file" message, and the response is still 200 with created/updated counts.
- AC-11: Given a bulk row whose stock write fails, then that row is reported in `errors`, no offer from that
  row is left without stock rows, and other rows are unaffected.

## 6. Edge and abuse cases
- Seller A reserves/writes stock using seller B's `offerId` -> 403 (existing check WarehouseService.java:97-99 must stay).
- Concurrent stock write and reservation on the same offer: no negative quantity, no lost update.
- Retries of the same create (client timeout) must not produce duplicate offers or double events.
- SKU case/whitespace variants in one CSV count as duplicates; 1000 rows / 2 MB limits unchanged.
- Event publish failure must not fail or roll back the committed stock write.
- Orders already in flight with fallback-served reservations are not retro-changed.

## 7. Open questions
None blocking. Note for the Owner (non-blocking): fulfilment-by-platform for sellers remains undecided.
