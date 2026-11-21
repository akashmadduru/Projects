# Tech Debt: Phase 9 "Marketplace and sellers"

Index: [README](README.md). Feature scope: [Product Roadmap, Phase 9](../phases/phase-09-marketplace-and-sellers.md).

Phase 9 was built under "no tests, no HLD/LLD, record shortcuts as debt". The integration run passed **62 of 62** Phase 9 end-to-end checks, with **99 of 99** Phase 6, **68 of 68** Phase 7 and all Phase 8 regression checks still green. Integration also fixed seven problems found in the first build (see "Resolved by Phase 9 integration" in the [README](README.md#resolved-by-phase-9-checked-against-the-code)). Paths below are relative to the repo root; `<svc>` means `services/<name>/<name>-app/src/main`. Seller-portal and storefront bullets that the admin and frontend agents first recorded are consolidated here (TD-P9-17, TD-P9-18).

<a id="td-p9-01"></a>
## TD-P9-01 No tests for Phase 9; existing specs likely stale
- **Severity / Effort:** High / L
- **Where:** `services/seller-service` (new), product-service offers/buy box/bulk upload, inventory-service warehouses and reservation routing, order-service shipments, cart-service offer lines; admin seller portal and admin seller/payout/warehouse screens; storefront seller page, sponsored grid, shipment order page.
- **Progress 2026-11-17:** the stale admin specs were re-run: guards.spec fixed (role-aware landing), the admin suite passes 139/139; seller suspension and KYC masking, product moderation and the stock-totals reconcile have tests. Still untested: seller ledger arithmetic (commission, fee, reversals), buy-box scoring, warehouse routing and splits, shipment status derivation, the gateway route contract.
- **Progress 2026-11-18:** a seller-service Testcontainers suite covers the ledger (postings once per shipment, return reversal once, payout hold, never paid twice, carry-forward). Still untested: onboarding/KYC, offers and buy box, split shipments, ads, the seller portal and admin specs.
- **Risk:** The only safety net is the one-off e2e script. Ledger arithmetic (commission, fee, reversals), buy-box scoring, warehouse routing and derived order status have no unit or contract tests. In the admin app the guest-only redirect is now role-aware (`homeRouteFor`) and dashboard/products/categories/inventory now require `ADMIN` or `CATALOG_MANAGER`, so `guards.spec`, `LoginView.spec` and `ProductsView.spec` may fail (not re-run). Extends [TD-P8-01](phase8.md#td-p8-01), [TD-P7-01](phase7.md#td-p7-01), [TD-ADM-01](admin.md#td-adm-01).
- **Fix:** Ledger idempotency and reversal tests, buy-box table tests, reservation routing/split tests, shipment-status derivation tests, gateway route contract test ([TD-TEST-04](testing.md#td-test-04)); update the admin specs; Playwright for apply, approve, list, sell, fulfil, payout.

<a id="td-p9-02"></a>
## TD-P9-02 Legacy stock totals still exist beside warehouse stock
- **Severity / Effort:** Med / M
- **Where:** inventory-service `stock_levels` / `variant_stock` (legacy totals) vs `warehouse_stock`.
- **Risk:** Step 1 done 2026-11-17: `warehouse_stock` is authoritative and `StockTotalsReconciler` (hourly, one replica) recomputes every legacy total from the platform's warehouse rows, bumping `stock_levels.version` and publishing INVENTORY_QUANTITY_CHANGED for each correction; drift is logged at WARN. The legacy tables still back `/api/inventory/**`, availableQuantity and the inventory events, and the code still mirrors writes into them, so a drift can be visible for up to an hour. `stock_levels.reserved_quantity` is unused.
- **Fix:** Serve `/api/inventory/**` quantities and events from `warehouse_stock` sums, keep `stock_levels` for name/SKU/threshold/version only, delete the mirroring code and `reserved_quantity`. Chosen over database triggers, which would race the JPA writes on the same rows.
<a id="td-p9-04"></a>
## TD-P9-04 Offer and stock writes are not atomic; bulk upload is per row
- **Severity / Effort:** Med / M
- **Where:** product-service offer create (then inventory `PUT /api/seller/stock`); bulk CSV import.
- **Risk:** The offer commits in product-service and the stock write to inventory-service follows, so a failure leaves an offer with no stock rows (it is never the buy box, but it shows in the seller's list). Bulk CSV runs one transaction per row with no de-duplication within a file, no async execution and no progress reporting (1000 rows / 2 MB in one request). Same non-atomic shape as [TD-P7-06](phase7.md#td-p7-06) (variants) and [TD-P6-33](phase6.md#td-p6-33).
- **Fix:** Create offer and stock through one inventory-owned operation or an outbox-driven saga; async bulk job with status polling and in-file duplicate detection.

<a id="td-p9-05"></a>
## TD-P9-05 Stock gaps: no events, silent fallback, sellers cannot stock platform warehouses
- **Severity / Effort:** Med / M
- **Where:** inventory-service `WarehouseService.writeStock`, reservation routing; seller stock endpoints.
- **Risk:**
  - Seller stock changes publish no `inventory.events`, so back-in-stock alerts ([TD-P7-11](phase7.md#td-p7-11)) only fire for platform stock.
  - A reservation line carrying an `offerId` that has no stock rows falls back to platform stock rows, so a seller offer with no stock rows can oversell platform stock if ordered by explicit `offerId`.
  - Sellers can only write stock in warehouses they own; the platform `WH-DEL` and the other seeded platform warehouses are not writable by sellers (the e2e creates a seller-owned Delhi warehouse). There is no per-warehouse stock matrix in the portal, so stock is set one warehouse at a time.
  - Warehouse CRUD edit (`PUT /api/admin/warehouses/{id}`) is assumed by the admin app and is not in the contract.
- **Fix:** Publish `inventory.events` from the warehouse write path; reject (not fall back) a reservation for an offer with no stock; decide whether sellers may hold stock at platform warehouses (fulfilment-by-platform); per-warehouse stock table in the portal.

<a id="td-p9-08"></a>
## TD-P9-08 Payout run is manual and simulated
- **Severity / Effort:** Med / M
- **Where:** `<seller>/` payout service, `POST /api/admin/payouts/run`, `GET /api/admin/payouts`.
- **Risk:** There is no scheduler: an admin must click Run (dev: `minAgeDays=0` skips the 7-day hold; the default hold is configurable). The bank transfer is simulated (a fake `UTR...` reference); no bank file or payout API, no failed/retry status. Negative balances are carried forward. There is a small race between a ledger insert and a run (`FOR UPDATE` only on the selected rows). The run has no preview/dry-run (who triggered it is in the audit log). `GET /api/admin/payouts` is not in the contract and is assumed by the admin app.
- **Fix:** Scheduled run under `JobLock`; real payout provider (RazorpayX, Cashfree, bank API) with failure handling; dry-run preview.

<a id="td-p9-09"></a>
## TD-P9-09 KYC is format-only; bank data under one static key
- **Severity / Effort:** High / M
- **Where:** seller-service onboarding, `DataCipher` (bank account encryption), env `SELLER_DATA_KEY`.
- **Risk:** Code part fixed 2026-11-17: suspending a seller revokes `ROLE_SELLER` in auth-service (new internal `DELETE /internal/users/{userId}/roles/{role}`, SELLER only; a failed revoke rolls the suspension back; re-approval grants it again); GSTIN and PAN are masked in the admin seller list and for SUPPORT/FINANCE (only ADMIN opening one seller sees them in full); admin decisions were already audited by the Phase 10 audit interceptor. Still open, and each needs a provider choice: KYC checks only the GSTIN/PAN/IFSC formats (no GSTN lookup, no penny-drop bank check, no document upload); the bank account number is AES-GCM encrypted with one static env key, with no rotation or KMS; approved or suspended sellers cannot edit their profile. Access tokens issued before a suspension keep the role until they expire.
- **Fix:** Pick GSTN and penny-drop providers and add document upload; envelope encryption with a KMS key and rotation; a profile change request that goes back to review.
<a id="td-p9-10"></a>
## TD-P9-10 Sponsored ads: click fraud, budget and ranking shortcuts
- **Severity / Effort:** Med / M
- **Where:** `<seller>/` ads module (`POST /api/ads/{adId}/click`, `/api/ads/impressions`, `GET /internal/ads/active`); product-service sponsored injection; storefront beacons.
- **Risk:** The click endpoint is unauthenticated and not de-duplicated: bots can drain a competitor's daily budget (click fraud), and impressions are FE-driven with no server validation. The beacon fires once per ad per mounted grid (not viewport-based), and the click beacon is a plain `fetch` (no `keepalive`/`sendBeacon`) before SPA navigation, so clicks can be lost. Keyword match is "stored keyword is a substring of the query" (no stemming or relevance). The active-product check is skipped at serve time (existence is validated best-effort at creation). Injection is first page only, max 2, de-duplicated from organic. Rows are expected to carry `sponsored: boolean` and `adId` (CATALOG emits; not in the contract). There is no billing or wallet for ad spend.
- **Fix:** Signed click tokens with per-user/IP de-dup and rate limits; viewport impression tracking with `sendBeacon`; product-active check on serve; relevance model and ad-spend invoicing.

<a id="td-p9-11"></a>
## TD-P9-11 Seller lookups are uncached and fan out
- **Severity / Effort:** Med / S
- **Where:** product-service seller info (name/slug/rating), order-service seller shipment API, public seller profile.
- **Risk:** Product-service caches seller info only 5 minutes in-process; a seller-service outage degrades to name "Seller" and a default rating 4.0, which also skews the buy-box score. Order-service resolves the seller via seller-service `by-user` on every seller shipment call (the PATCH path does it twice: advance then re-read), with no cache. Public seller `productCount` depends on product-service honouring `?sellerId=`. The platform seller's rating is hard-coded 4.5 (0% commission, no fee), and sellers with 0 ratings return a neutral 4.0 internally. Views-by-seller counts views of any product the seller has an active offer on (no per-offer attribution).
- **Fix:** Shared short-TTL cache (Caffeine/Redis) with stale-while-error; pass sellerId in the JWT or token claims; cache rating in the offer row.

<a id="td-p9-12"></a>
## TD-P9-12 Shipment lifecycle and cancel/refund limits
- **Severity / Effort:** Med / M
- **Where:** order-service shipment service, seller shipment endpoints, cancel flow.
- **Risk:** Shipment status copies the order lifecycle. A seller cannot cancel or reject a single shipment (only a whole-order cancel while every live shipment is `<= PACKED`), there is no per-shipment partial refund on cancel (order-level refund only), and no seller SLA or auto-cancel for stale shipments. Order status is derived on every shipment move (min live status, DELIVERED when all live delivered); `delivered_at` is when the LAST shipment was delivered, and COD is marked collected only when all shipments are delivered, not per shipment. The shipment timeline is synthesised (order-level rows are not stored per shipment). Extends [TD-P6-14](phase6.md#td-p6-14).
- **Fix:** Per-shipment cancel/reject with refund of that shipment's value; SLA scheduler with auto-cancel; store shipment timeline rows.

<a id="td-p9-13"></a>
## TD-P9-13 Returns, invoices and delivery estimates per shipment
- **Severity / Effort:** Med / M
- **Where:** order-service `ReturnService`, invoice generator, delivery estimate.
- **Risk:**
  - One return covers one shipment; items from several shipments need separate returns. Lines are resolved against the given `shipmentId`, else the single/first delivered shipment containing the first line's product. The return window is counted per shipment from its own `deliveredAt`.
  - `RETURN_REFUNDED` carries `returnShipmentId` + `returnId` + `refundAmount` (gross incl. discount pro-rating); seller-service reverses commission itself (fixed in F-20261119-13: items value, capped, fee reversed; see the remediation plan).
  - Return restock passes `offerId` (resolved from the shipment's order item) but the warehouse is still resolved by inventory from its reservation record; a line split across two warehouses of the same offer restocks in reservation order.
  - Shipment amount = items value only; order-level discount, coupon, coins, shipping and tax are pro-rated by items-value share on shipment invoices (may differ by a cent from the order totals). A non-platform seller invoice shows name and warehouse only (no seller GSTIN/address; not fetched from seller-service).
  - Delivery estimate per shipment = ship-to pincode vs warehouse pincode (3-digit / 1-digit prefix rule, 2/3/5 days, 7 non-Indian), ignoring offer `dispatchDays`; `estimated_delivery_date` = latest shipment ETA, while the checkout-time estimate/COD check still uses the fixed warehouse prefix.
  - A line stocked across warehouses is physically split into several `order_items` rows with the same unit price (`quantity`/`line_total` became updatable); the order page shows the parts separately.
  - The inventory reservation response is consumed as an optional body `{allocations:[...]}`; a 204/empty falls back to platform `WH-BLR`/560001 with the line's seller.
  - Notifications: per-shipment SHIPPED/DELIVERED mails (+ inbox/push) are sent, and the order-level mails are suppressed when an event carries more than one shipment; single-shipment orders keep the old emails and ignore `SHIPMENT_STATUS_CHANGED`.
- **Fix:** Multi-shipment returns; seller tax identity on invoices; one shared delivery-estimate function; typed allocation response contract; GST-correct invoice apportioning.

<a id="td-p9-14"></a>
## TD-P9-14 Seller analytics are approximate
- **Severity / Effort:** Low / S
- **Where:** `<seller>/` analytics (`GET /api/seller/analytics`), product-service `GET /internal/products/views/by-seller/{sellerId}`.
- **Risk:** `unitsSold` and `topProducts` are 0/empty unless `order.events` shipments carry per-line items. `conversionRate = orders / views` uses views of any product the seller has an offer on, with unauthenticated, undeduplicated view tracking ([TD-P7-12](phase7.md#td-p7-12)). Computed live from the ledger/orders with no pre-aggregation.
- **Fix:** Always include line items in shipment events; daily aggregate table; attribute views to the offer shown.

<a id="td-p9-15"></a>
## TD-P9-15 Cart, wishlist and saved items are only partly offer-aware
- **Severity / Effort:** Med / M
- **Where:** cart-service lines (`?offerId` on item/saved routes), wishlist, saved-for-later; storefront `cartStore`.
- **Risk:** Cart lines are keyed (product, variant, offer), but wishlist and saved-for-later stay keyed by product/variant, so saving a specific seller's offer is not possible. `?offerId` on remove/update is optional; when omitted it matches **every** offer line of that (product, variant), so if two sellers' lines are both in the cart all matching lines are touched. Cart-service does not validate `offerId`/`sellerId` (display only); order-service re-resolves the offer from product-service and returns 409 `PRODUCT_UNAVAILABLE` if it is gone. Extends [TD-BE-09](backend.md#td-be-09).
- **Fix:** Make `offerId` required once the storefront always sends it; offer-keyed wishlist/saved; validate offers at add-to-cart or show a clear "offer changed" state.

<a id="td-p9-16"></a>
## TD-P9-16 Gateway routes, notifications and seller-service build setup
- **Severity / Effort:** Low / S
- **Where:** `services/gateway` routes; notification-service `SELLER_*` templates; `deploy/local/scripts/create-customer-dbs.sql`, `deploy/local/postgres/init/01-schemas.sql`, `deploy/local/.env.example`.
- **Risk:** `/api/seller/**` is routed by explicit paths (ledger, payouts, analytics, ads, feedback); any new seller-service path needs a gateway edit, or it 404s. Seller approve/reject emails link to `ADMIN_BASE_URL/seller`; there is no in-app inbox notification. seller-service uses `JdbcClient` directly (no repository layer). It has its own DB (`seller_db`, role `seller_service`, schema `seller`): existing volumes need `create-customer-dbs.sql` run manually (extends [TD-SEC-05](infra-security.md#td-sec-05)); `SELLER_DB_PASSWORD` is a committed throwaway (extends [TD-SEC-03](infra-security.md#td-sec-03)); `SELLER_DATA_KEY` has no default and compose fails fast if unset.
- **Fix:** Wildcard `/api/seller/**` route with per-path auth; inbox notification; migration runner for infra DBs; secrets manager.

<a id="td-p9-17"></a>
## TD-P9-17 Storefront Phase 9 UX shortcuts
- **Severity / Effort:** Low / M
- **Where:** `apps/product-catalog/src/` PDP "Sold by / Other sellers", seller page, sponsored grids, order page shipments, feedback form, footer.
- **Risk:**
  - Order shipments: items are matched via `shipmentId` on order items (else `shipment.items` product ids). Order-level Invoice button and tracking card are hidden when shipments exist; the return form and product rating are still order-level.
  - Per-shipment seller feedback "already rated" state is not persisted; a second submit hits the server `UNIQUE(shipment_id, user_id)` and shows its error. The seller page does not list seller feedback (`GET /api/sellers/{id}/feedback`) even though the endpoint is public.
  - The seller page calls `productApi.list` directly (no offline demo fallback; `useProducts` fallback ignores `sellerId`).
  - "Sell on Cartwise" is a footer link only (not in the header or account menu).
  - The main CTA uses the buy-box `offerId` unless a variant has its own buy box (`variantBuyBoxes`).
- **Fix:** Return per-shipment feedback state in the order response; render seller reviews; link to selling from the account menu; per-shipment returns UI.

<a id="td-p9-18"></a>
## TD-P9-18 Seller portal and admin screens: assumed shapes and thin UX
- **Severity / Effort:** Low / M
- **Where:** `apps/admin-product-catalog/src/` (`sellerApi.ts`, `adminApi.ts`, seller views, `AdminPayoutsView.vue`, `SellerOrdersView.vue`, `lib/http.ts`).
- **Risk:** Request/response shapes not pinned by the contract are assumed (apply body incl. `submit`, `bankAccountLast4`, offer/product/shipment/ad/ledger shapes; list endpoints accept a bare array or PagedModel; seller product create body). Integration corrected the shapes it checked (see README resolved list), but unverified ones remain. Create-offer and ad forms take raw product/category UUIDs (no product search picker), and the offer table shows `productId` when no name is returned. The bulk CSV template is generated client-side (the server template endpoint needs a bearer header). `lib/http.ts` gained FormData passthrough (shared file, additive). There is no reply/dispute feature for seller feedback.
- **Fix:** Pin shapes in a shared contract (OpenAPI) and generate the client; product search picker; server-driven template download via a fetch + blob.

<a id="td-p9-19"></a>
## TD-P9-19 Phase 9 test data left in the dev database
- **Severity / Effort:** Low / S
- **Where:** `seller_db`, `product_db`, `inventory_db`, `order_db`, `auth_db` on the dev volume.
- **Risk:** `p9e-*` sellers (names `P9E*`) and their users remain; seller-owned test products ("E2E Bulk ...", "E2E Sponsored ...") and test warehouses (`S*-DEL`, `T*`, inactive) remain. Test offers on seeded products (Dune "P9 Store" at 12.5, and the e2e sellers) were deactivated, not deleted. The seeded Laptop product (id starts `25e35f99`) no longer resolves on the PDP (404), and its "P9 Traders" offer could not be inspected; cause not yet established. Credentials are intentionally not recorded here. Extends [TD-P8-19](phase8.md#td-p8-19).
- **Fix:** Investigate and restore the Laptop product; delete these rows (or `docker compose down -v`) before sharing the environment.

<a id="td-p9-21"></a>
## TD-P9-21 Product review: edits after approval are not re-reviewed
- **Severity / Effort:** Low / S
- **Where:** product-service `ProductModerationService`, `SellerCatalogService`; admin `ModerationView`.
- **Risk:** Residual of TD-P9-03 (fixed 2026-11-17: a marketplace seller's new product is created inactive and PENDING_REVIEW; admins approve or reject it at Catalog > Product review, `/api/products/admin/moderation`, with automatic warnings for no image, no description, banned terms and prices far from the category average; a rejected product the seller edits goes back to the queue; the seller sees the status on their listings; seller bulk edits now publish PRODUCT_UPDATED). An approved product the seller later edits (name, description, image) goes live without a second review. Banned terms are a config list (`product.moderation.banned-terms`), not a managed list.
- **Fix:** Send material edits of an approved seller product back to review while keeping the last approved version live; manage banned terms in the admin console.

<a id="td-p9-22"></a>
## TD-P9-22 seller-service `LedgerService.reverseRefund` has no event caller
- **Severity / Effort:** Low / S
- **Where:** seller-service `LedgerService.reverseRefund`.
- **Risk:** the `RETURN_REFUNDED` whole-order fallback was removed in F-20261119-13, so nothing calls the method.
- **Deadlock note:** a multi-shipment call locks several SALE rows one after another (shipment_id order) while a payout run locks its rows in one unordered statement, so the two can deadlock (Postgres aborts one; nothing is corrupted). Call it once per shipment, each in its own transaction, or order the payout run's lock, before adding a caller.
- **Fix:** part B (per-shipment cancel, [TD-P9-12](#td-p9-12)) must use it for the whole-shipment refund path or delete it.
- **Debt line:** td-p9-22 | seller-service LedgerService.reverseRefund has no event caller (RETURN_REFUNDED fallback removed in F-20261119-13); part B (per-shipment cancel) must use it for the whole-shipment refund path or delete it; a multi-shipment call can deadlock with a payout run (lock several SALE rows vs one unordered FOR UPDATE), so call per shipment or order the payout lock first | owner: part B follow-up
