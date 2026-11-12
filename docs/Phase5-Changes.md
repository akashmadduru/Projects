# Phase 5: Technical Changes

**Changeset:** Customer accounts, user/cart/order services, Google SSO  
**Date:** November 2026  
**See also:** [Feature](Phase5-Feature.md), [Decision Log](Phase5-DecisionLog.md)

## Backend

| Path | Change |
|------|--------|
| `services/user-service/{user-service-api,user-service-app}` | New. Profile (lazy upsert from JWT), addresses (cap 20, one default via partial unique index). API module exposes `UserAddressClient`. Flyway `V1__user_baseline.sql`. |
| `services/cart-service/...` | New. Cart (qty 1..99, one currency), wishlist, merge endpoints; upserts via `INSERT ... ON CONFLICT`. API module exposes `CartClient`. `V1__cart_baseline.sql`. |
| `services/order-service/...` | New. Idempotent placement, history, cancel. Feign clients `CartFeignClient`, `UserFeignClient`, `ProductFeignClient`; 1s connect / 3s read, no retries. `V1__order_baseline.sql`. Sets `spring.jackson.deserialization.fail-on-null-for-primitives=false`. |
| `libs/common-core` `ErrorCode.java`, `CodedException.java` | New codes `CART_EMPTY`, `PRODUCT_UNAVAILABLE`, `CURRENCY_MISMATCH`, `ADDRESS_NOT_FOUND`, `ADDRESS_LIMIT`, `DEPENDENCY_UNAVAILABLE`; `CodedException` carries a code. |
| `services/gateway/src/main/resources/application.yml` | Routes `user`, `cart` (cart + wishlist), `order`; breakers `userCb`, `cartCb`, `orderCb`; CORS allows `Idempotency-Key`. `SecurityConfig` unchanged (deny-by-default). |
| `settings.gradle` | Six new module includes. |

## Infra

| Path | Change |
|------|--------|
| `infra/postgres/init/01-schemas.sql` | Added `user_service`/`cart_service`/`order_service` roles and `user_db`/`cart_db`/`order_db`. Removed the broken admin seed (it inserted into `auth.users` before Flyway ran). |
| `infra/scripts/create-customer-dbs.sql` | New. Re-runnable bootstrap for existing volumes. |
| `infra/docker-compose.yml` | Three service blocks bound to `127.0.0.1:8085/8086/8087`, `*_DB_PASSWORD` required. auth-service: `AUTH_REGISTRATION_ENABLED` default true, `AUTH_ALLOWED_REDIRECT_URIS` (5173 + 3000 callbacks), `AUTH_DEFAULT_REDIRECT_URI` (5173). No auth-service code change. |
| `infra/.env.example` | New password variables. |

## Frontend (`apps/product-catalog`)

| Path | Change |
|------|--------|
| `src/lib/http.ts` | Rewritten: in-memory token, `credentials: 'include'`, single-flight refresh and one replay on 401 (skipped for `/api/auth/*`), in-flight GET dedupe only, Idempotency-Key preserved on replay. `setAuthToken`/`getAuthToken` removed (replaced by `setAccessToken`/`getAccessToken`). |
| `src/stores/{authStore,cartStore,wishlistStore}.ts` | Auth status machine and bootstrap; cart/wishlist local-first with server merge, optimistic updates with rollback. |
| `src/services/{authApi,userApi,cartApi,orderApi}.ts` | New API clients. `authApi.googleSignInUrl()` targets `VITE_AUTH_BASE_URL` (default :8081). |
| `src/pages/` | `LoginPage`, `RegisterPage`, `CheckoutPage`, `OrderPage`, `AuthCallbackPage`, `account/{AccountLayout,ProfilePage,AddressesPage,OrdersPage}`. |
| `src/components/` | `RequireAuth`, `AccountMenu`, `AddressForm`, `GoogleSignInButton`; `CartDrawer` checkout button; `Header`. |
| `src/lib/redirect.ts` | `safeNext` plus sessionStorage-backed post-login destination. |
| `src/router/index.tsx` | Routes `/login`, `/register`, `/auth/callback`, `/checkout`, `/orders/:id`, `/account`, `/account/addresses`, `/account/orders`. |
| `vite.config.ts` | Port pinned to 3000 with `strictPort` (matches `npm run dev` and the Docker image). |
| `tsconfig.build.json` | New; `npm run build` type-checks without test files. |

## Contract deviations from the approved plan

| Plan | Shipped |
|------|---------|
| Idempotency body mismatch: 422 | 422 with code `DOMAIN_RULE_VIOLATION` and rule `IDEMPOTENCY_KEY_REUSED` |
| Missing Idempotency-Key | 400 `VALIDATION_FAILED` |
| 5xx problem detail | Generic text, details in logs only |
| `itemCount` | Total quantity across lines |
| Google start via gateway (8080) | Directly via auth-service (8081); see [DecisionLog D4](Phase5-DecisionLog.md) |

## Breaking / behaviour notes

- Any code importing `setAuthToken`/`getAuthToken` from `lib/http` breaks. `http.test.ts` still does, so `npm run type-check` fails ([TD-TEST-03](../techdebt/testing.md#td-test-03)).
- The old `localStorage` `auth_token` is no longer read or written.
- Tests were intentionally not written or updated. Backend builds use `-x test`.
