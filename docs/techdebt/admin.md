# Tech Debt: Phase 7 "Admin console completion"

Index: [README](README.md). Feature: [Phase7-Admin-Feature](../phases/phase-07-discovery-and-conversion.md#admin-console-completion). Changes: [Phase7-Admin-Changes](../phases/phase-07-discovery-and-conversion.md#changes).

Built on 2026-11-12 under "no test cases, build features fast, record shortcuts here". What was verified: all changed backend modules compile in an `eclipse-temurin:25-jdk` container (Gradle offline). payment-service is the exception, see TD-ADM-12. On the SPA, `vue-tsc --noEmit` and `vite build` are clean, and the existing Vitest suite passes except the 3 tests in TD-ADM-11. **Nothing was run end-to-end against live services.** `<svc>` means `services/<name>/<name>-app/src/main/java/com/acme/ecommerce/<name>`.

<a id="td-adm-01"></a>
## TD-ADM-01 No tests for the admin APIs or screens
- **Severity / Effort:** High / M
- **Where:** `<auth>/admin/AdminUserService.java`, `AdminUserController.java`; `<order>/service/OrderStore.java#adminStats`; `<rating>/web/AdminRatingController.java`; `<payment>/web/AdminPaymentController.java`, `repository/AdminPaymentQueries.java`; gateway `SecurityConfig` `/api/admin/**` rule; the six new/changed SPA views.
- **Risk:** The privilege rules are the parts that most need tests and have none: no self-modification, never zero active admins, the ROLE_ADMIN row lock, and session revocation on disable or role removal. A regression there is a privilege-escalation or lockout bug.
- **Fix:** `AdminUserService` tests: self-change gives 422 `SELF_MODIFICATION`; demoting or disabling the last active admin gives 422 `LAST_ADMIN`; disable calls `revokeAllSessions`; a role-set replace grants and revokes correctly. Concurrency test: two transactions demote the last two admins and exactly one succeeds. Add WebMvc/WebFlux slice tests that a VIEWER and a CATALOG_MANAGER get 403 on every `/api/admin/**` route, at the gateway and in each service. Add SQL tests for `AdminPaymentQueries` filters, and Vitest tests for the CustomerDetail guards.

<a id="td-adm-02"></a>
## TD-ADM-02 Disable/demote does not invalidate access tokens already issued
- **Severity / Effort:** Med / M
- **Where:** `<auth>/admin/AdminUserService.java` (calls `TokenService.revokeAllSessions`).
- **Risk:** Refresh tokens are revoked at once, but a stateless access token keeps its old roles until it expires (`auth.jwt.access-token-ttl`). A disabled admin can keep acting for up to one TTL.
- **Fix:** Keep the TTL short (≤ 5 min), or add a Redis deny-list keyed by `sub` plus an `iat` cutoff that the gateway checks. `UserSessionCache` already writes per-user state to Redis and could be extended.

<a id="td-adm-03"></a>
## TD-ADM-03 Dashboard stats scan the orders table; "today" is the UTC day
- **Severity / Effort:** Med / S
- **Where:** `<order>/repository/OrderRepository.java` (`countGroupedByStatus`, `revenueSince`), `OrderReturnRepository.countGroupedByStatus`; `OrderStore.adminStats`.
- **Risk:** The stats run three grouped aggregates per dashboard load, with no index on `(placed_at, status)` and no cache. That is fine at today's volume but grows linearly. "Revenue today" truncates to the UTC day, which for an IST store starts at 05:30 local time. Revenue is reported per currency and never converted.
- **Fix:** Add a Flyway index on `orders (placed_at) INCLUDE (status, currency, total_amount, refunded_amount)` and cache the result for about 60 s. Take the store timezone from config (`order.reporting-zone`). Later, move to a daily rollup table fed by `order.events`.

<a id="td-adm-04"></a>
## TD-ADM-04 Low-stock tile looks only at the 50 lowest rows
- **Severity / Effort:** Low / S
- **Where:** `apps/admin-product-catalog/src/views/DashboardView.vue`, `lib/adminApi.ts#lowestStock`.
- **Risk:** inventory-service has no "quantity ≤ threshold" filter. The SPA fetches the 50 lowest-quantity rows and filters them on the client. A product with a high per-row threshold but a quantity above the 50th-lowest is missed.
- **Fix:** Add `GET /api/inventory?lowStock=true` (a `quantity <= low_stock_threshold` predicate) and use its `totalElements`.

<a id="td-adm-05"></a>
## TD-ADM-05 No audit trail for admin actions
- **Severity / Effort:** Med / M
- **Where:** user status and role changes (`AdminUserService`), review deletes (`RatingService.delete`), category deletes, order and return status changes.
- **Risk:** Role and status changes go only to the application log (`log.info`), and review deletes are not logged per actor at all. Nobody can answer "who made X an admin" or "who deleted this review" once the logs rotate.
- **Fix:** Add an `admin_audit` table (actor, action, target, before/after JSON, at) in each owning service, or a shared `admin.events` Kafka topic. Show it on the customer detail page.

<a id="td-adm-06"></a>
## TD-ADM-06 Review moderation is delete-only; reviews show product ids, not names
- **Severity / Effort:** Low / M
- **Where:** `apps/admin-product-catalog/src/views/ReviewsView.vue`; `<rating>/web/AdminRatingController.java`.
- **Risk:** There is no hide/unhide, no flagged-review queue and no reason captured on delete. Moderators see a product UUID and have to look the product up by hand. `RatingResponse` deliberately has no author id, so the review cannot link to the customer.
- **Fix:** Add a `status` (VISIBLE/HIDDEN) column and an admin PATCH, an admin-only DTO carrying `userId`, and a batched product-name lookup (product-service `GET /api/products?ids=`).

<a id="td-adm-07"></a>
## TD-ADM-07 Payments ledger is read-only and not cross-linked
- **Severity / Effort:** Low / M
- **Where:** `apps/admin-product-catalog/src/views/PaymentsView.vue`; `<payment>/web/AdminPaymentController.java`.
- **Risk:** The ledger shows order and customer ids as text with no links to the order and customer screens. There is no manual "mark COD collected" or goodwill refund outside the return flow. Paging uses OFFSET, which slows down on deep pages.
- **Fix:** Link the ids to `/orders?q=` and `/customers/:id`. Add an admin COD-collect action that reuses `PaymentRepository.markCodCollected` and reports it to order-service. Switch to keyset paging on `(created_at, id)`, with an index on `created_at`.

<a id="td-adm-08"></a>
## TD-ADM-08 Customer search is unindexed `LIKE '%q%'`
- **Severity / Effort:** Low / S
- **Where:** `<auth>/admin/AdminUserService.java#list`.
- **Risk:** Every search is a sequential scan of `auth.users`, which is fine up to roughly 100k accounts. The role filter joins `user_roles`; roles for a page are loaded in one extra query.
- **Fix:** Add a `pg_trgm` GIN index on `lower(email)` and `lower(display_name)`.

<a id="td-adm-09"></a>
## TD-ADM-09 Category JSON naming is inconsistent (`isActive` vs `active`)
- **Severity / Effort:** Low / S
- **Where:** product-service `CreateCategoryRequest` (boxed `Boolean isActive`, bound as `isActive`), `UpdateCategoryRequest` and `CategoryResponse` (primitive `boolean isActive`; Lombok makes the JSON property `active`).
- **Risk:** The SPA sends both spellings and reads `active ?? isActive`. Any other client will get it wrong.
- **Fix:** Add `@JsonProperty("active")` on all three, or use records, then drop the dual send in `lib/adminApi.ts`.

<a id="td-adm-10"></a>
## TD-ADM-10 Category model limits: hard delete, fixed parent, no slug/order
- **Severity / Effort:** Low / S
- **Where:** product-service `CategoryService`, `UpdateCategoryRequest`.
- **Risk:** Delete is now refused (409) while products or subcategories reference the category, but a successful delete is still a hard delete. A category cannot be moved under another parent. There is no slug or sort order for storefront navigation.
- **Fix:** Soft-delete through `is_active`, an optional `parentCategoryId` on update with the depth re-check, plus `slug` and `sort_order` columns.

<a id="td-adm-11"></a>
## TD-ADM-11 `auth.storage.spec.ts` fails on Node ≥ 25 (pre-existing)
- **Severity / Effort:** Low / S
- **Where:** `apps/admin-product-catalog/src/stores/auth.storage.spec.ts`, `vitest.config.ts`.
- **Risk:** Node's experimental global `localStorage` (undefined without `--localstorage-file`) shadows jsdom's, so all 3 tests fail in `beforeEach` with "Cannot read properties of undefined (reading 'clear')". This is unrelated to Phase 7 and was already failing on this machine, but it hides real regressions in the token-storage guarantee.
- **Fix:** Pin Node 22 LTS for the SPA (`.nvmrc` / `engines`), or stub `globalThis.localStorage` from jsdom's window in `src/test/support.ts`.

<a id="td-adm-12"></a>
## TD-ADM-12 payment-service not compiled by Gradle for this change
- **Severity / Effort:** Med / S
- **Where:** `<payment>/web/AdminPaymentController.java`, `repository/AdminPaymentQueries.java`.
- **Risk:** The offline Gradle cache has no `stripe-java:34.1.0`, and the sandbox gets 403 from Maven Central. The two new files were compiled with `javac` against `common-core` and the cached Spring jars, which passed, but not through the module's real classpath, Spotless or the annotation processors.
- **Fix:** On a machine with network access, run `./gradlew :services:payment-service:payment-service-app:build` (and `spotlessApply` for the whole changeset; see TD-BE-16).

<a id="td-adm-13"></a>
## TD-ADM-13 `/api/admin/**` is ADMIN-only at the gateway
- **Severity / Effort:** Low / S
- **Where:** `services/gateway/src/main/java/com/acme/ecommerce/gateway/config/SecurityConfig.java`.
- **Risk:** This is intentional defence in depth, but a future admin endpoint meant for CATALOG_MANAGER (for example a catalog report) will be refused at the edge until this rule changes. The rule is documented in the code.
- **Fix:** When that need appears, add a narrower `pathMatchers(...).hasAnyAuthority(ROLE_ADMIN, ROLE_CATALOG_MANAGER)` above the blanket rule.
