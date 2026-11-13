# Tech Debt: Phase 7 "Discovery and conversion"

Index: [README](README.md). Feature scope: [Product Roadmap, Phase 7](../phases/phase-07-discovery-and-conversion.md).

Phase 7 was built under "no tests, no HLD/LLD, record shortcuts as debt". The integration run passed **68 of 68** Phase 7 end-to-end checks and **99 of 99** Phase 6 regression checks. Integration also fixed five problems found in the first build (see "Resolved by Phase 7 integration" in the [README](README.md#resolved-by-phase-7-checked-against-the-code)). Paths below are relative to the repo root; `<svc>` means `services/<name>/<name>-app/src/main`.

<a id="td-p7-01"></a>
## TD-P7-01 No tests for Phase 7
- **Severity / Effort:** High / L
- **Where:** product-service (search, facets, variants, slug, views, sitemap), cart-service (alerts, variant lines), order-service (variant pricing, deals, bought-together, variant returns), inventory-service (variant stock), promotion-service (offers, deals), rating-service (Q&A, media, filters); storefront (SearchBar, FilterPanel, variant picker, compare, lightbox, SEO hook, alerts page); admin (product form variants, Deals view).
- **Risk:** The only safety net is the one-off e2e scripts. Existing storefront component tests (ProductCard etc.) were not updated for the new deal fetch and Compare button, so they are likely stale. `phase6-e2e.mjs` check D3 was reordered during integration (invoice fetched before cancel, because cancelled orders return 409 by design). Extends [TD-P6-05](phase6.md#td-p6-05), TD-TEST-01/02.
- **Fix:** Contract tests for search/facets SQL, variant pricing, deal claim/release, alert triggers, media upload validation; fix stale storefront tests; Playwright for PDP variant to checkout.

<a id="td-p7-02"></a>
## TD-P7-02 A `git stash` swept uncommitted work during Phase 7 (process risk)
- **Severity / Effort:** Med / S
- **Where:** repository working tree; `stash@{0}` (created 12:25 on 2026-11-12).
- **Risk:** A `git stash` by an unknown actor removed all uncommitted work while several agents were mid-edit. It was restored by a 3-way merge; `stash@{0}` is kept as a backup. The cause was not identified. Because much of the Phase 6-7 work is uncommitted, any state-changing git command can destroy or scramble work, and a merge restore can silently take the wrong side of a conflicting hunk.
- **Fix:** Commit the Phase 6-7 work in reviewable slices now; diff the working tree against `stash@{0}` once to confirm nothing was lost, then drop the stash deliberately. Agents must never run state-changing git commands (stash, checkout, reset, clean, commit); say so in the agent contracts and enforce it with a permission rule.

<a id="td-p7-03"></a>
## TD-P7-03 Search does not scale past a few thousand hits
- **Severity / Effort:** Med / M
- **Where:** `<product>/repository/ProductNativeQueries.java` (`MAX_SEARCH_IDS = 2000`), `service/ProductDiscoveryService.java`, product-service migration that creates `search_vector`.
- **Risk:** Search resolves matching ids natively (capped at 2000), then applies filters with `id IN (...)` and sorts `relevance` in memory, so ranking beyond the cap is silently truncated. The typo match uses `product.similarity(lower(name), q) > 0.25`, which does not use the trigram GIN index (the indexed `%` operator is not applied), so it is a sequential scan: fine for hundreds of rows, slow at scale. `search_vector` cannot include the category name (generated columns cannot read other tables), so category matches work only through suggest. `pg_trgm` is installed in the `product` schema because the service role cannot create objects in `public`.
- **Fix:** Single SQL query with filters, ranking and paging in the database; use `%` with `SET pg_trgm.similarity_threshold` so the index is used; maintain a category-name column via trigger; or move to OpenSearch (the roadmap's stated next step).

<a id="td-p7-04"></a>
## TD-P7-04 Facets are computed naively
- **Severity / Effort:** Med / S
- **Where:** `<product>/service/ProductDiscoveryService.java` (facets); `apps/product-catalog/src/components/FilterPanel.tsx`, `hooks/useCatalogParams.ts`.
- **Risk:** Facets are computed in Java over the whole filtered set with no caching. Counts do not exclude a facet's own filter, so selecting one brand makes every other brand show zero. Attribute filters use case-sensitive `jsonb_extract_path_text` equality. The URL joins multi-values with commas, so a brand or attribute value containing a comma breaks.
- **Fix:** Per-facet queries that drop that facet's own filter; normalise attribute casing; repeat the query parameter instead of comma-joining; cache by filter hash.

<a id="td-p7-05"></a>
## TD-P7-05 Catalog data model shortcuts (brand, attributes, slug, price)
- **Severity / Effort:** Low / S
- **Where:** `<product>/service/ProductCatalogService.java`, product migrations (brand, attributes, slug trigger).
- **Risk:** Brand was backfilled from `seller_name`; only a few seeded products have attributes. Update keeps brand/attributes when the request omits them, so attributes cannot be cleared with `null`. Slug is stable after a rename (never regenerated); the slug trigger covers raw/seed inserts only. A product price edit mirrors into `sale_price` only when `sale_price` was already set and recomputes `discount_percent` against `list_price`; the admin API has no explicit `salePrice`/`listPrice` fields, so separate sale pricing is not editable.
- **Fix:** Explicit clear semantics (empty object); optional slug regeneration with a redirect table; add `salePrice`/`listPrice` to the admin DTO.

<a id="td-p7-06"></a>
## TD-P7-06 Variant model is thin
- **Severity / Effort:** Med / M
- **Where:** `<product>/service/` variant replace (`PUT /api/products/{id}/variants`); cart/order tables that hold `variant_id`; order-service `ProductView` DTO.
- **Risk:** The PUT is delete-and-recreate for ids missing from the request (no soft delete). Variant ids stored in carts, wishlists, orders and alerts are plain uuids with no foreign key, so a deleted variant leaves dangling references. No validation beyond blank/length on SKU and price. Order-service reads variants through its own `ProductView` DTO (a second Feign client) instead of a shared response type.
- **Fix:** Soft-delete (`active=false`) instead of removal; validate SKU format and price; shared contract type for product responses.

<a id="td-p7-07"></a>
## TD-P7-07 Variant stock gaps
- **Severity / Effort:** High / M
- **Where:** `services/inventory-service/.../` (`variant_stock`, `GET /api/inventory/by-product/{id}`, `GET /internal/inventory/variants`); admin product form.
- **Risk:** A variant with no `variant_stock` row is unlimited (same hole as [TD-P6-04](phase6.md#td-p6-04)), so admin-created variants can be oversold until someone calls `PUT /api/inventory/variants/{id}`; the admin variant editor does not set stock and nothing syncs product-service variant edits to inventory. Variant stock is not aggregated into the product-level `stock_levels` row, so product-level "out of stock" is not derived for variant products. `by-product` returns a synthetic stub (id null, quantity = sum of variants) for variant-only products. The inventory V6 migration backfills `product_id` on seeded variant rows with hard-coded ids, which fixes seed data only. Seed variant rows had `product_id` NULL until V6, so the breakdown omitted them.
- **Fix:** Create a stock row (0 or a default) when a variant is created; call it from the admin editor; derive product availability from variant stock; fail closed on missing rows.

<a id="td-p7-08"></a>
## TD-P7-08 Returns keyed on (productId, variantId) only for new rows
- **Severity / Effort:** Med / S
- **Where:** `order-service/.../service/ReturnService.java`, `order_return_items.variant_id` (V4).
- **Risk:** Rows created before V4 keep `variant_id` null. When a client omits `variantId`, a product with exactly one variant line resolves to it; an ambiguous omission falls back to the non-variant key and is rejected. Returns of pre-V4 variant lines restock to the product row, not the variant. Residual of [TD-P6-14](phase6.md#td-p6-14) (return rules).
- **Fix:** Backfill `variant_id` from `order_items` for existing returns; make `variantId` required when the line has one.

<a id="td-p7-09"></a>
## TD-P7-09 Deals pricing is best-effort and loosely scoped
- **Severity / Effort:** Med / M
- **Where:** `order-service/.../` deal pricing/claims calls; `promotion-service/.../` deals; `apps/product-catalog/src/` deal badge (`useDeal`).
- **Risk:** If promotion-service is down, order-service silently charges the list price and claims nothing. Deal currency is not compared with product currency. A product-level deal also matches variant lines. Claim release lives inside `restoreStockBestEffort` (covers cancel, expiry and payment failure, but couples two concerns, and is as lossy as [TD-P6-01](phase6.md#td-p6-01)). No per-user quantity cap. The cart snapshot gets the deal price only from the PDP/card single add; "add all" bought-together items do not.
- **Fix:** Reject or retry on promotion-service failure at checkout instead of silently falling back; compare currencies; a separate release step in the outbox; per-user cap.

<a id="td-p7-10"></a>
## TD-P7-10 Bank offers and EMI are display-only
- **Severity / Effort:** Med / M
- **Where:** `promotion-service/.../` (bank offers, EMI config); storefront PDP Offers card.
- **Risk:** Shown on the product page but not applied at checkout, so a customer sees "10% off with HDFC" that never materialises (same shape as [TD-P6-16](phase6.md#td-p6-16)). The three bank offers and the EMI plan config are seeded/hard-coded with no admin CRUD.
- **Fix:** Apply at payment time using card BIN/bank from the payment provider; admin CRUD for offers; or label as "indicative" in the UI until then.

<a id="td-p7-11"></a>
## TD-P7-11 Price-drop and back-in-stock alerts are rough
- **Severity / Effort:** Med / M
- **Where:** `cart-service/.../` alert service and Kafka consumers; notification-service `ALERT_*` templates; storefront PDP and `/account/alerts`.
- **Risk:** Alert `currentPrice`, `currency` and `productName` are sent by the PDP and not validated (no product Feign call). PRICE_DROP for a variant alert compares against the product-level price from `product.events`. BACK_IN_STOCK fires on any positive quantity event (no 0 to N tracking). Consumers start at `latest`, so events during a cart-service outage are lost. No de-dupe of Kafka redelivery beyond the 24 h rate limit. The email links to `/products/{id}`, not the slug URL. The alerts page fetches each product by id (N calls) for names.
- **Fix:** Fetch price/name server-side at creation; carry variant price in events; track previous quantity; commit offsets from `earliest` with idempotent de-dupe; use slug links; batch product fetch.

<a id="td-p7-12"></a>
## TD-P7-12 Recommendations and view tracking
- **Severity / Effort:** Low / M
- **Where:** `<product>/` `product_views`, also-viewed; `order-service/.../` bought-together; `user-service/.../` recently-viewed.
- **Risk:** `POST /api/products/{id}/views` is public with a client-chosen `sessionId`: no rate limit or de-dupe, so view counts can be poisoned. Also-viewed is a live self-join (no materialisation); bought-together is a live co-occurrence SQL query with no cache and no recency weighting. The 30-day cleanup job runs on every instance (no lock, see [TD-P6-13](phase6.md#td-p6-13)). Recently-viewed (signed-in) does not validate the product id and keeps only the last 50. Bought-together "add all" skips products with variants and links to the PDP instead.
- **Fix:** Rate limit by IP/session; precompute co-views/co-purchases nightly; validate ids; ShedLock.

<a id="td-p7-13"></a>
## TD-P7-13 Q&A is unmoderated
- **Severity / Effort:** Med / M
- **Where:** `<rating>/` questions/answers/votes; storefront Q&A section; admin has no view.
- **Risk:** No report, delete or edit for questions or answers, no admin view, no spam/profanity filter, no rate limit on asking, so a signed-in user can flood a product page. `verifiedBuyer` on an answer is fixed at write time (false if order-service was down) and never re-evaluated. Upvotes are one-way (no un-vote). Author names are snapshots, as in [TD-RT-04](ratings.md#td-rt-04). The question list loads the top 3 answers with one query per question (N+1, max 50 per page), and no job reconciles `upvotes`/`answer_count` counters.
- **Fix:** Admin Q&A moderation view with delete; rate limit; fetch answers in one windowed query; reconcile counters nightly.

<a id="td-p7-14"></a>
## TD-P7-14 Review media storage is single-node and unhardened
- **Severity / Effort:** Med / M
- **Where:** `<rating>/` media service/controller; `infra/docker-compose.yml` volume `rating-media` (`/data/media`); gateway route.
- **Risk:** Files sit on a local docker volume: single node only, no CDN or object storage, not covered by backup. No virus scan, no EXIF stripping, no re-encode or thumbnails; files are read fully into memory when streamed. Deletes remove the file before the DB commit, so a DB failure leaves rows pointing at missing files (GET returns 404), and there is no sweeper for files whose row is gone. No per-user upload rate limit or storage quota. Multipart over 6 MB is rejected by Spring before the 5 MB check, so the error body is the generic multipart one, not a field-level 400; the gateway has no explicit body-size cap (default). Admin ReviewsView does not show photos. `media` is always empty in `rating.events` because photos are attached after the review is saved.
- **Fix:** S3/MinIO with signed or CDN URLs; thumbnailing and re-encode (strips EXIF); delete DB row first then file via a sweeper; quotas; gateway request-size filter.

<a id="td-p7-15"></a>
## TD-P7-15 rating-service runs as root
- **Severity / Effort:** Med / S
- **Where:** `infra/docker-compose.yml` (rating-service `user: "0:0"`); rating-service Dockerfile and the offline thin Dockerfile.
- **Risk:** The service runs as uid 0 so the fresh named volume is writable. A compromise of an upload path (the only service accepting file uploads) gets root inside the container.
- **Fix:** `chown` `/data/media` to the app user in the image (both the repo Dockerfile and the thin Dockerfile) and drop the `user:` override. Existing volumes need a one-time chown.

<a id="td-p7-16"></a>
## TD-P7-16 SEO is client-side only; sitemap is minimal
- **Severity / Effort:** Med / L
- **Where:** `apps/product-catalog/` (`useHead` hook, routes `/p/:slug`); `<product>/web/SitemapController.java`; `product.storefront-base-url` (default `http://localhost:3000`, not set in compose); `public/robots.txt`.
- **Risk:** Meta, canonical, OG/Twitter tags and JSON-LD are set after JavaScript runs, so crawlers and link-preview bots that do not execute JS see the empty shell; there is no SSR or prerender. Sitemap has no pagination or index (50,000 URL limit) and category URLs are `/?categoryId=<id>`, not clean paths. Sitemap URLs use the default localhost base unless the property is set. The roadmap asked for SSR/prerender; that part is **not built**.
- **Fix:** Prerender product/category pages (Vite SSG or a rendering service for bots); sitemap index with paging; set `STOREFRONT_BASE_URL` for product-service in compose; slug-based category routes.

<a id="td-p7-17"></a>
## TD-P7-17 Storefront Phase 7 UX shortcuts
- **Severity / Effort:** Low / S
- **Where:** `apps/product-catalog/src/` WishlistPage, ReviewForm, gallery lightbox, compare, lint config.
- **Risk:** Wishlist is variant-aware in the store (key productId + variantId) but WishlistPage renders one card per product (deduped), not per saved variant. Review photo upload runs after the review save and failures only toast. Lightbox zoom is hand-rolled pointer/wheel (no inertia, untested); the 360-degree view is **not built**. The `react-hooks` lint plugin is not installed, so `exhaustive-deps` is unchecked.
- **Fix:** Render saved variants on the wishlist; retry UI for failed uploads; adopt a gallery library; install `eslint-plugin-react-hooks`.

<a id="td-p7-18"></a>
## TD-P7-18 Storefront main chunk grew to 584 kB
- **Severity / Effort:** Low / S
- **Where:** `apps/product-catalog/` (Vite build). Supersedes the size figure in [TD-P6-24](phase6.md#td-p6-24).
- **Risk:** Main chunk grew from about 545 kB to 584 kB. Only compare, alerts page, lightbox and Q&A are lazy; checkout/Stripe, SearchBar, variant picker and offers are still in the main bundle. Vite warns on every build.
- **Fix:** Lazy-load checkout/payment routes and Stripe, manual chunks for vendor libs.

<a id="td-p7-19"></a>
## TD-P7-19 Admin console Phase 7 shortcuts
- **Severity / Effort:** Low / S
- **Where:** `apps/admin-product-catalog/src/components/ProductFormModal.vue`, `views/` Deals, `lib/adminApi.ts`.
- **Risk:** Variant attributes are edited as free text `key=value, key=value` per row (commas or `=` inside values unsupported). Variants are saved by a separate PUT after the product create/update, so a failure after create leaves the product without variants (the modal stays open and retries only variants). On edit the form fetches `GET /api/products/{id}` to seed brand/attributes/variants; if that fails they are not sent (avoids wiping, but the edit looks empty). The Deals product picker loads only the first 200 products (no search or paging) and the variant picker shows SKU only. Deals list assumes a bare array or paged model (page 0, size 100) with no pagination UI. Deals CRUD is restricted to ADMIN (matching backend `hasRole('ADMIN')`; CATALOG_MANAGER cannot use it) and `quantityLimit` is required. Q&A moderation is not built (TD-P7-13).
- **Fix:** Per-pair key/value inputs; single transactional product+variants endpoint; searchable product picker; paged deals list.

<a id="td-p7-20"></a>
## TD-P7-20 Test data left in the dev database
- **Severity / Effort:** Low / S
- **Where:** `auth_db`, `product_db`, `inventory_db`, `promotion_db`, `cart_db`, `order_db`, `rating_db` on the dev volume.
- **Risk:** Admin users `p7c-admin-*` (ROLE_ADMIN inserted via SQL) and users `p7c-user-*`, `p7e2e-*`, `p7e2eb-*` remain with their orders (COD, some cancelled, one delivered and returned), questions/answers, one review with a photo on Hoodie Fleece, a deactivated "E2E deal", an admin deal "Bed deal", and a still-active triggered BACK_IN_STOCK alert on hoodie variant 14. Variant stock was altered for testing: variants 01/02/06 set to 50/49/2 (06 sold out), hoodie variants restored to 50; the Blender price was restored to 99.99 by editing `sale_price` directly in `product_db`. If the volume is ever shared or promoted, the admin accounts are a backdoor. Credentials are intentionally not recorded here. Extends [TD-P6-25](phase6.md#td-p6-25).
- **Fix:** Delete these rows (or `docker compose down -v`) before sharing the environment.

<a id="td-p7-21"></a>
## TD-P7-21 Internal-token wiring and dead code from integration
- **Severity / Effort:** Low / S
- **Where:** `infra/docker-compose.yml` (product-service `INTERNAL_API_TOKEN`); product-service Feign inventory client (`getByProductWithAuth`).
- **Risk:** product-service now reads variant stock via `GET /internal/inventory/variants?ids=` with `X-Internal-Token`, adding another consumer of the shared static secret ([TD-P6-12](phase6.md#td-p6-12)). The earlier JWT-forwarding method `getByProductWithAuth` was left in place, unused. Variants with no `variant_stock` row still return `availableQuantity` null (unlimited, see TD-P7-07). All Phase 7 service images were again built through the offline mirror, not the repo Dockerfiles ([TD-P6-06](phase6.md#td-p6-06) covers this).
- **Fix:** Delete the unused Feign method; fold into the shared-token cleanup.
