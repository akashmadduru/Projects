# Design F-20261119-05: seller offer + stock integrity, stock events (TD-P9-04, TD-P9-05). Class M
Paths: `inv/` = services/inventory-service/inventory-service-app/src/main/java/com/acme/ecommerce/inventory/,
`prod/` = services/product-service/product-service-app/src/main/java/com/acme/ecommerce/product/, `ord/` = the order-service equivalent.

## 1. Current behaviour
- **No seller stock events.** `writeStock` (inv/service/WarehouseService.java:122-143) publishes only through `shiftLegacyTotals`, and only on the platform branch (:137-139; publishes at InventoryService.java:382,400, skips delta 0 at :374-376). The publisher registers an `afterCommit` sync and does nothing on rollback (inv/event/InventoryEventPublisher.java:72-96, javadoc :25-26). A send failure is logged and never rethrown (:142-149).
- **Oversell fallback.** `reserve` locks the offer's rows (inv/service/ReservationService.java:108). If there are none, it re-locks the platform rows (`offerId=null`, :109-112; the platform branch of the query is WarehouseStockRepository.java:59-66). The fallback is also how platform offers work: their rows store `offer_id NULL` (WarehouseService.java:136). A reservation request carries no seller (inv/web/internal/InternalReservationController.java:27-28; ord/client/InventoryFeignClient.java:21). order-service already knows each line's seller (ord/service/OrderService.java:592-597). The platform UUID `...a11e` is the same constant in every service (inv/domain/Platform.java:8, ord/domain/Order.java:45, prod/domain/Offer.java:27).
- **Own rows that fall short never top up from platform stock**, because the fallback only runs on `rows.isEmpty()` (:109, shortfall :123-126).
- **Non-atomic create.** `createOffer` commits the offer (OfferService.java:193-239), then makes the stock Feign call (SellerCatalogService.java:168-185, 220-241). When the circuit is open, the fallback throws `IllegalStateException` (prod/client/InventoryServiceClientFallback.java:35-37). Only 400/403 errors are mapped (:237-239). The offer survives in every failure case. `createProduct` (:299-303) and bulk `processRow` (:495-512) do the same.
- **No quantity = 0 shown.** A seller offer with no rows already shows `availableQuantity 0`; a platform offer with no rows shows null (prod/service/OfferService.java:117-133).
- **Bulk loop** (SellerCatalogService.java:336-358) does not de-duplicate. `findBySku` is exact-match (:427).
- **Consumers.** cart fires back-in-stock when `quantity>0` (cart AlertEventConsumers.java:93-104, AlertService.java:115-121) and ignores unknown fields (AlertEventConsumers.java:37). product-service overwrites `last_known_quantity` with every QUANTITY_CHANGED (prod/event/InventoryEventListener.java:45-51, ProductCatalogService.java:638-643), and that field drives the in-stock filter (ProductFilterNativeSql.java:108). product's event class already lacks fields that inventory sends (`publishedAt`, `variantId`, `reservedQuantity`; prod/event/InventoryEvent.java:18-26 vs inv/event/InventoryEvent.java:15-24), so unknown fields are already tolerated.

## 2. Proposed change
**A. Seller stock events (inventory, reusing the after-commit publisher)**
- `InventoryEvent` gets nullable `sellerId` and `offerId` fields (additive).
- `InventoryEventPublisher` gets a new overload `publishAfterCommit(type, productId, variantId, qty, sellerId, offerId)`. The existing overloads pass nulls, so their behaviour is unchanged.
- `writeStock`, non-platform branch: `if (quantity != previous)`, publish QUANTITY_CHANGED with `qty = stock.offerTotal(productId, variantId, sellerId)`. That is the seller's total across warehouses for this product/variant, which is what that offer can sell.
- New repo method `offerTotal`: one `SELECT COALESCE(SUM(quantity),0)`, with the same `variant_key` predicate as `find` (WarehouseStockRepository.java:84-96).
- product-service `InventoryEventListener` skips `last_known_quantity` when `offerId != null`. The product-level snapshot stays platform-derived, as it is today. product's `InventoryEvent` gets an `offerId` field.

**B. Fail-closed reservations**
- order-service sends `sellerId` on each `Item`. That is the line's `sellerId` from OrderService.java:592-597; the record is at ord/client/InventoryFeignClient.java:21.
- Inventory `ItemDto` and `Line` get a nullable `sellerId`.
- In `reserve`, the fallback at :109-112 runs only when `Platform.SELLER_ID.equals(sellerId)`. Otherwise an offer with no rows adds `Shortfall(productId, wanted, 0)` (the existing shape, :120). This happens even under `missing-rows=UNLIMITED`, because that setting is for untracked catalogue items, not seller offers.
- Rolling-deploy safety: when `sellerId` is null and `offerId` is set, the controller resolves the owner before the transaction opens, via the existing `SellerDirectory.offer` (inv/service/SellerDirectory.java:86-103). A 404 leaves the owner null, which is treated as non-platform and rejected. An upstream error returns 503, so the order fails instead of overselling.

**C. Compensate half-created offers (product-service)**
- New `OfferService.discard(offerId)` (`@Transactional`): delete the offer, then `syncPrices`.
- In `createOffer`, any `RuntimeException` from `writeStock` triggers `discard(o.getId())` and is rethrown. A validation error stays 400; anything else becomes `UpstreamUnavailableException("inventory-service")` (503).
- In `createProduct` and bulk rows that created the product, the compensation is `catalog.delete(productId)`: offers cascade (V7__offers_marketplace.sql:5) and PRODUCT_DELETED is published (ProductCatalogService.java:592-602). This is the same compensation the saga uses (InventoryEventListener.java:57-63).
- A bulk row that created only the offer gets `discard`. A row that updated an existing offer only reports the error.
- If the compensation itself fails, log `ERROR OFFER_STOCKLESS offerId=`. The offer still shows 0 available (OfferService.java:129-130) and cannot be reserved (B), so it cannot oversell.

**D. Bulk de-dup**
- The `bulk` loop keeps `Set<String> seen` keyed on `sku.trim().toUpperCase(Locale.ROOT)`.
- A later duplicate becomes `RowError(rowNo, "duplicate sku in file")`, followed by `continue`. Limits are unchanged (:330).

**Interfaces, data, config.**
- `inventory.events`: two additive nullable fields.
- Internal reserve DTO: one additive nullable `sellerId`.
- Offer create and product create can now return 503 (previously 201 with no stock).
- No migration, no new config, no new endpoint. `docs/reference/endpoints.md` gets notes on the reserve body and the create errors.

## 3. AC mapping
| AC | Change / proof |
|---|---|
| AC-1 | A. One publish per changed write, registered after commit (Publisher:77-95). |
| AC-2 | Validation (:130-131) and 403 (:97-99, :174-175) throw before the publish. Rollback runs no sync (Publisher:25-26). |
| AC-3 | `quantity != previous` guard. `upsert` returns the previous quantity (Repo:107-127). |
| AC-4 | The event carries `variantId` and qty>0, so cart `onRestock` fires (AlertService:115-121). |
| AC-5 | B: seller offer with no rows gives the shortfall. The throw at :129-131 happens before any `stock.add` (:169). |
| AC-6 | B: `sellerId = PLATFORM` keeps the fallback at :109-112. |
| AC-7 | Fallback only on empty rows (:109). Shortfall at :124-126 (test only; no code change). |
| AC-8 | C: discard plus 400/503. GET of the seller's offers (`bySeller`, OfferService:178-180) no longer lists it. |
| AC-9 | No quantity: offer created with `availableQuantity 0` (OfferService:129-130). Reserve is rejected by B. |
| AC-10 | D. |
| AC-11 | C, bulk branch. The existing per-row catch (:353-357) keeps other rows unaffected. |

## 4. Reuse before build
- **Reused as-is:** `InventoryEventPublisher` (extended with an overload; no outbox, which O-08 owns); `SellerDirectory.offer` (owner lookup); `OfferService.quantity` (zero-stock reporting); `ProductCatalogService.delete` (compensation, as in the saga); the duplicate-offer 409 guard (OfferService:213-216), which makes create retries idempotent; and `upsert`'s `COALESCE(offer_id)` (Repo:117), which re-points rows orphaned by a timeout once the create is retried.
- **New:** only `offerTotal` (no existing query sums per seller; `bySeller` at :77-82 loads every row the seller has) and `discard`. No shared-lib candidate: each piece is service-local.
- **Backlog (out of scope):**
  - product's listener ignores `variantId`, so variant events overwrite the product-level snapshot.
  - Seller-row reservations and restores publish nothing (ReservationService:180, :296-297).
  - `setOfferStock` makes an HTTP call inside the transaction (WarehouseService:90-93).

## 5. Alternatives considered
- **Inventory-owned create** (inventory creates the offer through product-service). Rejected: it inverts ownership and moves the same non-atomicity rather than removing it.
- **Reconcile job** for offers with no rows. Rejected: it is a cross-service scan plus a JobLock job, and it is late. Fail-closed (B) already makes a stockless offer harmless.
- **`stockPending` flag plus a migration.** Rejected: `availableQuantity 0` already reports the state.
- **Owner lookup on every offer line inside `reserve`.** Rejected: it would mean HTTP while holding row locks, and a new product-service dependency on every platform checkout. The lookup is kept only as the rolling-deploy fallback, and it runs before the transaction.
- **Event qty = product total across all sellers.** Rejected: it would flap against platform events, which carry the legacy platform total.

## 6. Risks, failure modes, rollback, tests
- **Deploy order:** product-service (listener guard), then order-service (`sellerId`), then inventory.
  - If inventory goes out first, seller events overwrite `last_known_quantity`. That field is display-only.
  - If order-service has not been deployed yet, the SellerDirectory fallback covers it, at the cost of extra calls.
- **Timeout after inventory committed:**
  - The offer is discarded, leaving orphan rows whose `offer_id` points at no offer. Those rows cannot be reached by reservation.
  - A retried create at the same warehouse re-points them. They remain listed in `GET /api/seller/stock` until then (documented).
- **Trust:** a buggy order-service that sent PLATFORM for a seller offer would re-open the fallback. order-service derives the seller from product-service's offer (OrderService:596-597).
- **Rollback:** revert the images. Nothing is persisted in a new shape, and the event fields are additive.
- **Tests:**
  - inventory: `WarehouseServiceTest` (AC-1..3: event count and ordering against a captured sync) and `ReservationService` IT (AC-5..7, including the UNLIMITED case).
  - product: `SellerCatalogServiceTest` (AC-8, 9, 11: Feign 5xx, fallback ISE, and a discard that fails), bulk test (AC-10: case and whitespace variants), and the listener test for `offerId` skip.
  - cart: an existing consumer test with an extra field (AC-4).
  - order: request mapping.

## 7. Files expected to change (estimate 230-280 production lines)
- **inventory:** event/InventoryEvent.java, event/InventoryEventPublisher.java, service/WarehouseService.java, repository/WarehouseStockRepository.java, service/ReservationService.java, web/internal/InternalReservationController.java.
- **product:** service/SellerCatalogService.java, service/OfferService.java, event/InventoryEventListener.java, event/InventoryEvent.java.
- **order:** client/InventoryFeignClient.java, service/OrderRemote.java (StockLine and Item mapping, :114, :128), service/OrderService.java (:288-294).
- **docs:** docs/reference/endpoints.md, docs/techdebt/phase9.md (narrow TD-P9-04; close TD-P9-05 bullets 1-2), docs/techdebt/remediation-plan.md.
- **Tests** as listed in section 6.

## CTO decision
**ACCEPT** (class M, total 4; three services still scores 1; no Owner approval needed). Minimal: no new service, datastore, vendor or migration, one new query and one new method. Every AC (1-11) maps to a change or a test.

Checked and found sound:
- Deploy order product, order, inventory is correct. Product's listener guard lands before seller events exist. Cart and product already tolerate unknown event fields. The `sellerId`/`offerId` additions are nullable and additive, and old order-service is covered by the pre-transaction `SellerDirectory` lookup.
- Compensation: discard after a failed write is correct and fail-safe, because fail-closed B makes any surviving stockless offer unreservable.
- `upsert` `COALESCE(EXCLUDED.offer_id, existing)` (WarehouseStockRepository.java:117) does let a retried create re-point orphan rows.

Conditions:
1. Timeout orphan gap: if inventory committed but the caller timed out, the seller write has already published a QUANTITY_CHANGED (qty>0) for an offer that is then discarded. Cart can fire a false back-in-stock alert, and `offerTotal` (summed by seller/product/variant) counts orphan rows. Implementation must, in the discard-after-5xx/timeout path, make a best-effort `PUT` of quantity 0 for that offer before discard (failure only logged), or the design must record this as a known, documented limitation with a test. Prefer the best-effort zeroing. Reason: AC-4 integrity and no phantom stock signal.
2. `offerTotal` must filter on `offer_id IS NOT NULL` pointing at the written offer's seller only, and the design's claim that orphans are unreachable must be asserted in an inventory test (reserve with a stale offerId yields a shortfall). Reason: B depends on it.
3. Backward compat tests: inventory `ItemDto` deserialises a body without `sellerId`, and order-service's body with `sellerId` is accepted by the pre-change inventory (unknown-field tolerance verified, not assumed). Reason: rolling deploy safety.
4. When `SellerDirectory` returns an upstream error the reserve returns 503 before any lock or stock change; add a test. Reason: fail closed, never oversell.
5. Retry and concurrency: a test that a retried create returns 409 (no duplicate offer or second event), and a concurrent write/reserve test shows no negative quantity. Reason: section 6 of requirements.
6. Document the 503 on create and the reserve `sellerId` in `docs/reference/endpoints.md` (already planned); the PR description states the deploy order. Reason: public contract change must be explicit.
7. Re-score if production lines exceed 600 or a migration or flag appears.
