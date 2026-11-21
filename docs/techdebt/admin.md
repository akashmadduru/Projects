# Tech Debt: Phase 7 "Admin console completion"

Index: [README](README.md). Feature: [Phase7-Admin-Feature](../phases/phase-07-discovery-and-conversion.md#admin-console-completion). Changes: [Phase7-Admin-Changes](../phases/phase-07-discovery-and-conversion.md#changes).

Built on 2026-11-12 under "no test cases, build features fast, record shortcuts here". What was verified: all changed backend modules compile in an `eclipse-temurin:25-jdk` container (Gradle offline). payment-service was the exception; it has since built and passed its tests through Gradle (2026-11-17). On the SPA, `vue-tsc --noEmit` and `vite build` are clean, and the existing Vitest suite passes (the 3 Node 25+ failures were fixed on 2026-11-18). **Nothing was run end-to-end against live services.** `<svc>` means `services/<name>/<name>-app/src/main/java/com/acme/ecommerce/<name>`.

<a id="td-adm-01"></a>
## TD-ADM-01 Admin routes: no 403 tests per route, no SQL tests for admin queries
- **Severity / Effort:** Med / M
- **Where:** gateway `SecurityConfig` `/api/admin/**` rule; each service's admin controllers; `<order>/service/OrderStore.java#adminStats`; `<payment>/repository/AdminPaymentQueries.java`; admin SPA CustomerDetail guards.
- **Risk:** The privilege rules now have tests (2026-11-17, `AdminUserServiceIntegrationTest` on a real database): self-change is `SELF_MODIFICATION`; disabling or demoting the last active admin is `LAST_ADMIN`; two admins demoting each other at the same moment leaves exactly one admin (the ROLE_ADMIN row lock holds); disabling a user and removing a role revoke their sessions, adding a role does not. Still untested: that a VIEWER and a CATALOG_MANAGER get 403 on every `/api/admin/**` route at the gateway and in each service, the admin SQL filters, and the CustomerDetail guards in the SPA.
- **Fix:** WebMvc/WebFlux slice tests per admin route with several roles; SQL tests for `AdminPaymentQueries`; Vitest for the CustomerDetail guards.

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

<a id="td-adm-10"></a>
## TD-ADM-10 Category model limits: hard delete, fixed parent, no slug/order
- **Severity / Effort:** Low / S
- **Where:** product-service `CategoryService`, `UpdateCategoryRequest`.
- **Risk:** Delete is now refused (409) while products or subcategories reference the category, but a successful delete is still a hard delete. A category cannot be moved under another parent. There is no slug or sort order for storefront navigation.
- **Fix:** Soft-delete through `is_active`, an optional `parentCategoryId` on update with the depth re-check, plus `slug` and `sort_order` columns.

