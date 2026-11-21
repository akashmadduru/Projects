# Tasks F-20261119-05: seller offer + stock integrity, stock events (class M)

Owner for every task: forge-backend-engineer. Each task includes its tests. Paths are under `services/` in the worktree
(`.openforge/worktrees/F-20261119-05`); `inv/`, `prod/`, `ord/` as in design.md. One Gradle build at a time.
Verify command shorthand: `G` = `bash .openforge/scripts/gradle.sh`. Final gate for the whole objective: `node .claude/hooks/forge-verify.mjs --changed`.

Order follows the deploy order: product, order, inventory (then cart test and docs).

Slices (production diff estimated 250-300 lines with the best-effort zeroing, so split to keep each reviewable):
- Slice 1 = T1-T4 (product-service and order-service). Ends at T4.
- Slice 2 = T5-T9 (inventory-service, cart test, docs). Ends at T9.

## Slice 1: product, then order

### T1. product listener guard (deploys first)
- Goal: product `InventoryEvent` gains nullable `offerId`; `InventoryEventListener` skips the `last_known_quantity` update when `offerId != null`.
- Files: prod/event/InventoryEvent.java, prod/event/InventoryEventListener.java, test: product listener test (offerId set is skipped, null still updates, unknown fields tolerated).
- Covers: CTO 3 (unknown-field tolerance, product side). Supports AC-1/AC-4 safety.
- Verify: `G :services:product-service:product-service-app:test`

### T2. offer create compensation (createOffer, discard, zeroing)
- Goal: add `OfferService.discard(offerId)` (@Transactional: delete offer, then syncPrices). In `createOffer`, on any RuntimeException from `writeStock`: best-effort `PUT` quantity 0 for that offer (failure only logged), then `discard`, then rethrow (validation stays 400, anything else becomes `UpstreamUnavailableException("inventory-service")` 503). If discard fails, log `ERROR OFFER_STOCKLESS offerId=`. No quantity: offer created with availableQuantity 0.
- Files: prod/service/OfferService.java, prod/service/SellerCatalogService.java, test: SellerCatalogServiceTest (Feign 5xx, open-circuit fallback ISE, timeout, zeroing call made and its failure only logged, discard failure logs, 400 stays 400, null quantity gives 201 with 0 available, retried create returns 409 with no duplicate offer).
- Covers: AC-8, AC-9 (create side); CTO 1, CTO 5 (retry 409).
- Verify: `G :services:product-service:product-service-app:test`

### T3. createProduct and bulk compensation + bulk de-dup
- Goal: `createProduct` and bulk `processRow` compensate on stock failure: row that created the product uses `catalog.delete(productId)`; row that created only the offer uses `discard` (with zeroing); row that updated an existing offer only reports the error. Bulk loop keeps `Set<String> seen` on `sku.trim().toUpperCase(Locale.ROOT)`; later duplicate becomes `RowError(rowNo, "duplicate sku in file")` then `continue`; limits unchanged; response 200 with counts.
- Files: prod/service/SellerCatalogService.java, test: SellerCatalogServiceTest / bulk test (duplicate exact, case and whitespace variants, row numbers; stock failure on one row leaves no stockless offer and other rows unaffected; product-create 503 path deletes product; update-only row not discarded).
- Covers: AC-10, AC-11; CTO 1 (bulk path), CTO 5 (retry safety on rows).
- Verify: `G :services:product-service:product-service-app:test`
- Slice 1 product part complete.

### T4. order-service sends sellerId on reserve items
- Goal: add `sellerId` to `Item` and `StockLine` mapping, populated from the order line's sellerId (OrderService:592-597, 288-294).
- Files: ord/client/InventoryFeignClient.java, ord/service/OrderRemote.java, ord/service/OrderService.java, test: request-mapping test (platform line sends PLATFORM, seller line sends that seller).
- Covers: AC-5/AC-6 prerequisite; CTO 3 (order body with sellerId).
- Verify: `G :services:order-service:order-service-app:test`
- End of Slice 1.

## Slice 2: inventory, cart test, docs

### T5. inventory event fields, publisher overload, offerTotal
- Goal: `InventoryEvent` gets nullable `sellerId`/`offerId`. `InventoryEventPublisher` gets overload `publishAfterCommit(type, productId, variantId, qty, sellerId, offerId)`; existing overloads pass nulls. `WarehouseStockRepository.offerTotal(productId, variantId, sellerId)`: `SELECT COALESCE(SUM(quantity),0)` with same `variant_key` predicate as `find`, filtered `offer_id IS NOT NULL` and by seller.
- Files: inv/event/InventoryEvent.java, inv/event/InventoryEventPublisher.java, inv/repository/WarehouseStockRepository.java, tests: publisher test (old overloads unchanged, new fields serialised), repository test for offerTotal (variant key, excludes platform NULL rows, excludes other sellers).
- Covers: CTO 2 (offerTotal filter); foundation for AC-1..4.
- Verify: `G :services:inventory-service:inventory-service-app:test`

### T6. seller stock write publishes events
- Goal: in `writeStock` non-platform branch, `if (quantity != previous)` publish QUANTITY_CHANGED after commit with qty = `offerTotal`, sellerId, offerId. Publish failure never fails the write.
- Files: inv/service/WarehouseService.java, test: WarehouseServiceTest (0 to N publishes exactly one event, registered after commit and only fires on commit; quantity < 0 and forbidden warehouse publish nothing; unchanged quantity publishes nothing; seller B's offerId gives 403; publish failure does not roll back; concurrent write vs reserve gives no negative quantity and no lost update).
- Covers: AC-1, AC-2, AC-3; CTO 5 (concurrency).
- Verify: `G :services:inventory-service:inventory-service-app:test`

### T7. fail-closed reservations and owner resolution
- Goal: `ItemDto` and `Line` get nullable `sellerId`. In `reserve`, platform-row fallback runs only when `Platform.SELLER_ID.equals(sellerId)`; otherwise an offer with no rows adds `Shortfall(productId, wanted, 0)`, also under `missing-rows=UNLIMITED`. `InternalReservationController`: when `sellerId` null and `offerId` set, resolve owner via `SellerDirectory.offer` before the transaction opens; 404 gives non-platform (rejected); upstream error gives 503 before any lock or stock change.
- Files: inv/service/ReservationService.java, inv/web/internal/InternalReservationController.java, tests: ReservationService IT (seller offer no rows rejected with standard shortfall and platform stock unchanged; platform offer by offerId still succeeds; seller rows short while platform suffices is rejected with no top-up; UNLIMITED case; stale/orphan offerId yields shortfall; no-quantity offer rejected) and controller test (null sellerId resolved via directory, 404 rejected, upstream error returns 503 with no stock change).
- Covers: AC-5, AC-6, AC-7, AC-9 (reserve side); CTO 2 (orphan unreachable), CTO 4.
- Verify: `G :services:inventory-service:inventory-service-app:test`

### T8. inventory backward-compat tests
- Goal: tests only. `ItemDto` deserialises a body without `sellerId`; the pre-change shape tolerates an extra `sellerId` field (unknown-field tolerance verified against the Item/ItemDto Jackson config and a captured old-style DTO in the test).
- Files: inventory test for ItemDto JSON compat (new test file under the inventory web/internal test package).
- Covers: CTO 3.
- Verify: `G :services:inventory-service:inventory-service-app:test`

### T9. cart back-in-stock consumer test and docs
- Goal: (a) cart consumer test: QUANTITY_CHANGED with extra `sellerId`/`offerId`, qty > 0 from 0 fires the alert. (b) docs: `docs/reference/endpoints.md` (reserve `sellerId`, create 503 on offer and product create), `docs/reference/events.md` (additive fields), `docs/techdebt/phase9.md` (narrow TD-P9-04 to async bulk job with status polling; close TD-P9-05 bullets 1-2), `docs/techdebt/remediation-plan.md` (rows). Note deploy order product, order, inventory and the orphan-row listing caveat for the PR description.
- Files: cart AlertEventConsumers test (existing consumer test class), docs files above.
- Covers: AC-4; CTO 6; CTO 7 (confirm production line count at the end, re-score if over 600 or a migration/flag appeared).
- Verify: `G :services:cart-service:cart-service-app:test`, then `node .claude/hooks/forge-verify.mjs --changed`
- End of Slice 2.

## Coverage table

| AC / condition | Tasks |
|---|---|
| AC-1 | T5, T6 |
| AC-2 | T6 |
| AC-3 | T6 |
| AC-4 | T6, T9 |
| AC-5 | T4, T7 |
| AC-6 | T4, T7 |
| AC-7 | T7 |
| AC-8 | T2 |
| AC-9 | T2, T7 |
| AC-10 | T3 |
| AC-11 | T3 |
| CTO 1 (best-effort zeroing before discard) | T2, T3 |
| CTO 2 (offerTotal filter, orphan unreachable test) | T5, T7 |
| CTO 3 (backward compat tests) | T1, T4, T8 |
| CTO 4 (SellerDirectory error gives 503) | T7 |
| CTO 5 (retry 409, concurrency) | T2, T3, T6 |
| CTO 6 (docs, deploy order in PR) | T9 |
| CTO 7 (re-score trigger) | T9 |
