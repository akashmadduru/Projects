# Phase 7: Discovery and conversion

**Tech debt:** [techdebt/phase7.md](../techdebt/phase7.md). Built under the [roadmap working rules](../roadmap.md#working-rules): no tests, no per-feature HLD/LLD, every shortcut logged.

## Features

**Status (2026-11-12): built, prototype-grade, no automated tests.** Integration run: 68 of 68 Phase 7 end-to-end checks and 99 of 99 Phase 6 regression checks passed. Not built: SSR/prerender and 360-degree images. Shortcuts: [techdebt/phase7.md](../techdebt/phase7.md). The "Today" column below is the pre-Phase-7 baseline.

| # | Feature | Why it matters | Today | Touches | Size | Status |
|---|---|---|---|---|---|---|
| 1 | **Search autocomplete and suggestions** (products, categories, recent searches) | Most sessions start in the search box | Plain keyword box | product-service, SearchBar | M | ✅ Built (`/api/products/suggest`, recent searches, did-you-mean) ([TD-P7-03](../techdebt/phase7.md#td-p7-03)) |
| 2 | **Typo-tolerant, relevance-ranked search** (Postgres full-text + trigram first, OpenSearch later) | "iphnoe" must still find iPhone | Substring match | product-service | M | ✅ Built, partial: Postgres full-text + trigram; in-memory ranking over a 2000-id cap, trigram index unused ([TD-P7-03](../techdebt/phase7.md#td-p7-03)) |
| 3 | **Product variants** (size, colour, storage) with per-variant price, stock and images | Fashion/electronics are unsellable without it | One SKU per product | product-service, inventory-service, PDP, cart | L | ✅ Built, partial: variant with no stock row is unlimited; thin variant model ([TD-P7-07](../techdebt/phase7.md#td-p7-07), [TD-P7-06](../techdebt/phase7.md#td-p7-06), [TD-P7-08](../techdebt/phase7.md#td-p7-08)) |
| 4 | **Brand and attribute facets** (brand, RAM, size, colour…) driven by category | Amazon's left rail; narrows big catalogues fast | 8 fixed filters | product-service, FilterPanel | M | ✅ Built, partial: counts do not exclude a facet's own filter, no caching ([TD-P7-04](../techdebt/phase7.md#td-p7-04)) |
| 5 | **Pincode delivery check on the product page** ("Delivery by Fri, free") | Removes the biggest pre-cart doubt | — | product page (reuses Phase 6 #6) | S | ✅ Already built in Phase 6 (Phase 6 #6, `PincodeCheck`); kept on the Phase 7 PDP |
| 6 | **Offers on the product page**: bank offers, coupons that apply, EMI options | Flipkart's conversion driver | — | promotion-service, PDP | M | ✅ Built, partial: bank offers and EMI are display-only, not applied at checkout ([TD-P7-10](../techdebt/phase7.md#td-p7-10)) |
| 7 | **Deals of the Day / lightning deals** with countdown and limited quantity | Urgency; homepage traffic magnet | Static promo banner | promotion-service, home page, admin | M | ✅ Built; pricing is best-effort if promotion-service is down ([TD-P7-09](../techdebt/phase7.md#td-p7-09)) |
| 8 | **Recently viewed** and **Customers also viewed / Frequently bought together** | Cross-sell; Amazon attributes a large share of revenue to recommendations | "Related products" only | **new** recommendation-service (start with co-purchase counts from orders) | M | ✅ Built inside product/order/user services (no new recommendation-service); live queries, unauthenticated view tracking ([TD-P7-12](../techdebt/phase7.md#td-p7-12)) |
| 9 | **Customer Q&A** on product pages (ask, answer, upvote) | Answers pre-purchase questions reviews don't cover | — | rating-service (new module), PDP | M | ✅ Built, partial: no moderation or admin view ([TD-P7-13](../techdebt/phase7.md#td-p7-13)) |
| 10 | **Review photos/videos** and review filters (by stars, verified, with media) | Social proof; more trusted than seller photos | Text reviews only | rating-service, media upload | M | ✅ Built, partial: photos only (no video); local volume, runs as root ([TD-P7-14](../techdebt/phase7.md#td-p7-14), [TD-P7-15](../techdebt/phase7.md#td-p7-15)) |
| 11 | **Price-drop and back-in-stock alerts** on wishlist items | Brings users back at buying moments | Wishlist without alerts | notification-service, cart-service | S | ✅ Built, partial: client-supplied prices, consumers start at `latest` ([TD-P7-11](../techdebt/phase7.md#td-p7-11)) |
| 12 | **Compare products** side by side (up to 4) | Key for electronics/appliances | — | storefront | S | ✅ Built (up to 4 products, `/compare`) |
| 13 | **Image zoom, 360° and full-screen gallery** | Replaces touching the product | Basic gallery | storefront | S | Partial: hover zoom, lightbox and variant image switch built; 360-degree not built ([TD-P7-17](../techdebt/phase7.md#td-p7-17)) |
| 14 | **SEO and shareability**: clean URLs with slugs, breadcrumbs, meta/OG tags, sitemap, share button | Free organic traffic | SPA with id URLs | storefront (SSR/prerender), product-service | M | Partial: slugs, breadcrumbs, meta/OG/JSON-LD (client-side), share button, sitemap built; SSR/prerender not built ([TD-P7-16](../techdebt/phase7.md#td-p7-16)) |

## Run notes

- No new services. New endpoint families: product-service `/api/products/suggest`, `/facets`, `/batch`, `/slug/{slug}`, `/{id}/variants` (admin PUT), `/{id}/views`, `/{id}/also-viewed`, and public `/sitemap.xml`; promotion-service `/api/promotions/offers`, `/deals/active` and admin `/api/admin/deals` (plus `/internal/promotions/deals/*`); rating-service questions/answers/upvotes, review media (`/api/ratings/{id}/media`) and review filters (`stars`, `verified`, `withMedia`); cart-service `/api/alerts`; order-service `/api/recommendations/products/{id}/bought-together`; user-service `/api/users/me/recently-viewed`; inventory-service variant stock (`/api/inventory/variants/{id}`, internal `/internal/inventory/variants`).
- Seeded demo data: 3 products with 4-6 variants each, 4 active deals ending in 1-3 days, 3 bank offers (HDFC, ICICI, Axis) and EMI plans (display-only).
- Sitemap: `GET http://localhost:8080/sitemap.xml`; its URLs use `product.storefront-base-url` (default `http://localhost:3000`).
- Review photos are stored on the `rating-media` docker volume (`/data/media`); rating-service runs as root so the fresh volume is writable ([TD-P7-15](../techdebt/phase7.md#td-p7-15)). `INTERNAL_API_TOKEN` is now also set for product-service.
- Not built: SSR/prerender and 360-degree images ([TD-P7-16](../techdebt/phase7.md#td-p7-16), [TD-P7-17](../techdebt/phase7.md#td-p7-17)). Search, facets and relevance run in memory over capped id lists ([TD-P7-03](../techdebt/phase7.md#td-p7-03)); storefront main chunk is 584 kB ([TD-P7-18](../techdebt/phase7.md#td-p7-18)).
- Integration run: 68 of 68 Phase 7 e2e checks and 99 of 99 Phase 6 regression checks passed. The dev DB still holds test data ([TD-P7-20](../techdebt/phase7.md#td-p7-20)).
- Process risk: a `git stash` by an unknown actor swept uncommitted work and was restored by a 3-way merge; `stash@{0}` is kept as a backup ([TD-P7-02](../techdebt/phase7.md#td-p7-02)). Much of the Phase 6-7 work is still uncommitted, so avoid state-changing git commands until it is.

## Admin console completion

Built alongside Phase 7 (2026-11-12). Tech debt: [techdebt/admin.md](../techdebt/admin.md).

### What an operator can do now

| Screen | Route | Who | What |
|---|---|---|---|
| Overview | `/dashboard` | all staff | Revenue today and over 30 days (net of refunds, per currency), AOV, open orders, returns in progress, products, low stock, customers. Also an orders-by-status chart, the 5 most recent orders and a restock list. CATALOG_MANAGER sees only the catalog and stock panels. |
| Products | `/products`, `/products/new` | all staff (writes: CATALOG_MANAGER, ADMIN) | Existing CRUD, plus a **category picker** on create and edit. |
| Categories | `/categories` | all staff (writes: CATALOG_MANAGER, ADMIN) | Two-level tree; create, edit, add subcategory, activate/deactivate, delete. Delete is refused while products or subcategories use the category. |
| Inventory | `/inventory` | all staff | Unchanged. |
| Orders / Returns / Coupons | unchanged | ADMIN | Unchanged. Refunds still run from Returns. |
| Payments | `/payments` | ADMIN | Ledger filtered by status, method and order id; a detail view with refund history. Read-only. |
| Customers | `/customers`, `/customers/:id` | ADMIN | Search by email or name, filter by status and role. The detail page shows profile, roles, enable/disable/unlock and the customer's last 10 orders. |
| Reviews | `/reviews` | ADMIN | Search across all products by text, reviewer, star rating and product id; delete a review (the product rating is recalculated). |
| Settings | `/settings` | all staff | Shows the current user's effective access. |

### Safety rules (server-enforced)

- `/api/admin/**` requires `ROLE_ADMIN` at the **gateway** and again in each owning service.
- An admin cannot change their **own** status or roles (422 `SELF_MODIFICATION`).
- The **last active admin** cannot be disabled or demoted (422 `LAST_ADMIN`). The `ROLE_ADMIN` row is locked for the length of the transaction, so two concurrent demotions cannot both succeed.
- Disabling an account, or removing any of its roles, **revokes all refresh tokens**. Access tokens already issued expire on their own TTL (TD-ADM-02).
- Only ACTIVE and DISABLED can be set. LOCKED belongs to the login-attempt policy and can only be cleared by "Unlock" (which sets ACTIVE).

### New APIs

| Method | Path | Service | Notes |
|---|---|---|---|
| GET | `/api/admin/orders/stats` | order | `{ordersByStatus, returnsByStatus, revenueToday[], revenueLast30Days[]}` |
| GET | `/api/admin/orders?userId=` | order | New filter on the existing list |
| GET | `/api/admin/users?q=&status=&role=&page=&size=` | auth | `PagedModel<AdminUserResponse>`, max 50 per page |
| GET | `/api/admin/users/stats` | auth | `{total, byStatus}` |
| GET | `/api/admin/users/{id}` | auth | |
| PUT | `/api/admin/users/{id}/status` | auth | `{status: ACTIVE\|DISABLED}` |
| PUT | `/api/admin/users/{id}/roles` | auth | `{roles: ["ADMIN"\|"CATALOG_MANAGER"\|"VIEWER", ...]}`, replaces the whole set, at least one role |
| GET | `/api/admin/ratings?productId=&rating=&q=` | rating | Deletes go through the existing `DELETE /api/ratings/{id}` (admins already allowed) |
| GET | `/api/admin/payments?status=&method=&orderId=` | payment | Same JSON shape as `PagedModel` |
| GET | `/api/admin/payments/{id}` | payment | `{payment, refunds[]}` |
| GET | `/api/admin/payments/stats` | payment | Count by status |

### Bugs fixed along the way

1. **Category writes always returned 403.** `CategoryController` checked `hasAnyAuthority('CATALOG_MANAGER','ADMIN')`, but the shared JWT converter issues `ROLE_`-prefixed authorities. It now uses `hasAnyRole`, matching `ProductController`.
2. **Editing a product cleared its category.** Product `PUT` is full-replace for `categoryId`, and the admin form never sent it. Both product forms now send it.
3. **Deleting a category in use returned a raw integrity error.** It now returns a clean 409 that names the reason.

### How to verify by hand

1. Sign in as ADMIN, open Overview, and check the tiles against `/orders` and `/returns`.
2. Customers → open another user → toggle CATALOG_MANAGER → Save. The user's next refresh carries the new role.
3. Try to remove ADMIN from yourself: the controls are disabled, and calling the API directly returns 422.
4. With exactly one admin, call `PUT /api/admin/users/{thatAdmin}/roles {"roles":["VIEWER"]}` from a second admin account that has just been demoted. Expect 422 `LAST_ADMIN`.
5. Sign in as a VIEWER token and call `GET /api/admin/users`. Expect 403 from the gateway.
6. Categories: create a parent, add a sub, assign it to a product, then try to delete it. Expect a 409 message.

### Changes

No database migrations. No new services. No new dependencies.

#### Backend

| Path | Change |
|------|--------|
| `services/order-service/order-service-api/.../dto/AdminOrderStats.java` | New DTO. |
| `services/order-service/order-service-app/.../repository/OrderRepository.java` | `countGroupedByStatus()`, `revenueSince(since, excluded)` (JPQL aggregates). |
| `.../repository/OrderReturnRepository.java` | `countGroupedByStatus()`. |
| `.../service/OrderStore.java` | `adminStats(now)`. `adminList` gains a `userId` overload; the old 3-arg signature delegates to it. |
| `.../web/AdminOrderController.java` | `GET /api/admin/orders/stats`; `userId` request param on the list. |
| `services/auth-service/auth-service-app/.../admin/` | New package: `AdminUserController`, `AdminUserService`, `AdminUserDtos`. |
| `.../domain/UserAccount.java` | `enable()`, `revoke(RoleEntity)`. |
| `.../repository/UserAccountRepository.java` | Now extends `JpaSpecificationExecutor`; `findWithRolesByIdIn`, `countByRoleAndStatus`, `countGroupedByStatus`. |
| `.../repository/RoleRepository.java` | `lockByName` (`PESSIMISTIC_WRITE`). |
| `.../config/SecurityConfig.java` | `/api/admin/**` → `hasAuthority(ROLE_ADMIN)` before `anyRequest().authenticated()`. |
| `services/rating-service/rating-service-app/.../repository/RatingRepository.java` | Extends `JpaSpecificationExecutor`. |
| `.../service/RatingService.java` | `adminSearch(productId, ratingValue, q, pageable)`. |
| `.../web/AdminRatingController.java` | New. `GET /api/admin/ratings`, `@PreAuthorize ROLE_ADMIN`. |
| `services/payment-service/payment-service-app/.../repository/AdminPaymentQueries.java` | New. Read-only JdbcClient queries with allow-listed filters. |
| `.../web/AdminPaymentController.java` | New. List, detail, stats; `@PreAuthorize ROLE_ADMIN`. |
| `services/product-service/product-service-app/.../web/CategoryController.java` | **Bug fix:** `hasAnyAuthority` → `hasAnyRole` on create/update/delete. |
| `.../repository/CategoryRepository.java` | `existsByParentCategoryId`, `countProductsInCategory` (native). |
| `.../service/CategoryService.java` | Delete refuses with 409 when the category has subcategories or products. |
| `services/gateway/src/main/resources/application.yml` | Route predicates: `/api/admin/users/**` → auth-secured, `/api/admin/ratings/**` → rating, `/api/admin/payments/**` → payment. |
| `services/gateway/.../config/SecurityConfig.java` | `/api/admin/**` → `hasAuthority("ROLE_ADMIN")` (defence in depth). |

#### Frontend (`apps/admin-product-catalog`)

| Path | Change |
|------|--------|
| `src/types/commerce.ts` | Types for stats, users, ratings, payments, categories. |
| `src/lib/adminApi.ts` | Wrappers for every new endpoint; `statusBadge` helper; `isCategoryActive`. |
| `src/views/DashboardView.vue` | Rewritten: independent panels, role-aware. |
| `src/views/CategoriesView.vue`, `CustomersView.vue`, `CustomerDetailView.vue`, `ReviewsView.vue`, `PaymentsView.vue` | New. |
| `src/components/ProductFormModal.vue`, `src/views/NewProductView.vue` | Category picker; `categoryId` always sent on update. NewProductView loads categories lazily on focus, so mounting still issues no request (the existing spec asserts this). |
| `src/router/index.ts` | Routes `categories`, `customers`, `customer-detail`, `reviews`, `payments` (the last four ADMIN-only). |
| `src/components/AppShell.vue` | Nav entries and page titles. |
| `src/views/SettingsView.vue` | Shows effective access instead of "coming soon". |

#### Verification performed

- Gradle `compileJava` (offline, `eclipse-temurin:25-jdk` container) passed for order, auth, rating, product and gateway. `compileTestJava` passed for gateway and product; auth-service tests were already uncompilable (TD-P6-05).
- payment-service: the new files compiled with `javac` only (TD-ADM-12).
- SPA: `vue-tsc --noEmit` and `vite build` are clean. Vitest: 61 of 64 pass; the 3 failures are the pre-existing Node ≥ 25 `localStorage` issue (TD-ADM-11).
- Not done: running anything against live services.

### Decisions

#### D1. Each service owns its admin endpoints; no admin BFF
The dashboard fans out from the browser (order stats, user stats, inventory, products) in parallel and renders each panel independently.
**Why:** No new service, no new Feign calls, and one service failing degrades one panel rather than the whole page.
**Rejected:** An aggregator endpoint in order-service. It would add three Feign dependencies to the service that is already the most coupled.

#### D2. User management lives in auth-service
Roles and status are columns of `auth.users` and `auth.user_roles`, and auth-service mints the JWT `roles` claim from them.
**Why:** Any other owner would need a sync channel between two sources of truth for privileges.

#### D3. Last-admin guard uses a row lock on `ROLE_ADMIN`
`RoleRepository.lockByName("ROLE_ADMIN")` with `PESSIMISTIC_WRITE` runs at the start of every status or role change, followed by a count of active admins.
**Why:** A count-then-update without a lock lets two concurrent requests each see "2 admins" and both demote. Serialising on one reference row is cheap, because admin changes are rare.
**Rejected:** A `SERIALIZABLE` transaction, which adds retry handling everywhere, and a DB trigger, which puts business logic in SQL with no error code.

#### D4. Self-modification is refused outright
**Why:** It prevents accidental self-lockout and makes every privilege change involve two people. It also makes "the last admin demotes themselves" impossible without relying on D3.

#### D5. Revoke refresh tokens on disable or role removal, not on grant
**Why:** Removing access should take effect as soon as possible. A grant is harmless to delay, and it shows up on the user's next refresh. Access-token revocation is deferred (TD-ADM-02).

#### D6. Role change replaces the whole set
`PUT /roles {roles: [...]}` rather than grant/revoke endpoints.
**Why:** It matches the checkbox UI, is idempotent, and gives one place to run the last-admin check.

#### D7. URL-level `/api/admin/**` rules at both the gateway and auth-service
**Why:** auth-service has no method security enabled, so a URL rule protects any future controller there by default. The gateway rule is defence in depth for services that forget an annotation. Trade-off: TD-ADM-13.

#### D8. Payments ledger is read-only
**Why:** Refunds must keep order, stock and payment state consistent. The return flow (`ReturnService.refund`) already does this. A second refund path from the ledger would bypass restocking and the order's `refundedAmount`.

#### D9. Review delete reuses `DELETE /api/ratings/{id}`
**Why:** That endpoint already allows ADMIN and publishes the event that recomputes the product's averages. A second delete path would have to duplicate both.

#### D10. Category delete refused while in use (409), with deactivate as the normal path
**Why:** Both references are foreign keys. Cascading would orphan products from navigation, and the previous behaviour was a raw integrity error.

#### D11. Fixed `CategoryController` RBAC as part of this change
Changed `hasAnyAuthority('CATALOG_MANAGER','ADMIN')` to `hasAnyRole(...)`.
**Why:** The Categories screen cannot work without it, and the fix aligns the controller with `ProductController` and the shared `RolesClaimAuthoritiesConverter` (which emits `ROLE_*`). This is a behaviour change only for requests that were always 403.
