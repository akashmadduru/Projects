# Phase 7: Technical Changes

**Changeset:** Admin console completion
**Date:** 2026-11-12
**See also:** [Feature](Phase7-Admin-Feature.md), [Decision Log](Phase7-Admin-DecisionLog.md)

No database migrations. No new services. No new dependencies.

## Backend

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

## Frontend (`apps/admin-product-catalog`)

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

## Verification performed

- Gradle `compileJava` (offline, `eclipse-temurin:25-jdk` container) passed for order, auth, rating, product and gateway. `compileTestJava` passed for gateway and product; auth-service tests were already uncompilable (TD-P6-05).
- payment-service: the new files compiled with `javac` only (TD-ADM-12).
- SPA: `vue-tsc --noEmit` and `vite build` are clean. Vitest: 61 of 64 pass; the 3 failures are the pre-existing Node ≥ 25 `localStorage` issue (TD-ADM-11).
- Not done: running anything against live services.
