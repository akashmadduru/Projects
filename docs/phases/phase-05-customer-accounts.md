# Phase 5: Customer accounts

**Status:** Built 2026-11-12. No automated tests, by decision (D7).
**Services added:** user-service (:8085), cart-service (:8086), order-service (:8087).
**Tech debt:** [backend](../techdebt/backend.md), [frontend](../techdebt/frontend.md), [testing](../techdebt/testing.md), [infra-security](../techdebt/infra-security.md).

> Later phases changed parts of this: payment, stock reservation, shipping/tax and `order.events`
> (listed below as "not included") all arrived in [Phase 6](phase-06-sell-for-real.md).

## What was built

Shoppers can register, sign in (email/password or Google), keep a server-side cart and wishlist, save addresses, and place and cancel orders from the storefront (`apps/product-catalog`).

| Area | Capability |
|------|-----------|
| Login | Email/password login and registration (password min 12), silent token refresh, logout |
| Google SSO | "Continue with Google" on login and register pages |
| user-service (8085) | Profile, up to 20 addresses with one default |
| cart-service (8086) | Cart, wishlist, merge of anonymous items at login |
| order-service (8087) | Idempotent checkout, order history, cancel |
| Storefront | Login, register, checkout, order detail, account (profile, addresses, orders) |

Not included: payment, inventory reservation, shipping/tax, order events. See [techdebt/backend.md](../techdebt/backend.md).

## How it works

- **Session:** access token lives in memory only; the refresh token is an HttpOnly cookie. On a 401 the client makes one single-flight `POST /api/auth/refresh` and replays the request once. If refresh fails the user is sent to `/login?next=...`. On page load the app restores the session through the same refresh call.
- **Identity:** every customer endpoint takes the user from the JWT `sub`. Resources owned by someone else return 404.
- **Anonymous shopping:** cart and wishlist are local until login, then merged into the server copy and the local copy is cleared. Logout clears them.
- **Checkout:** the storefront sends `POST /api/orders {addressId}` with an `Idempotency-Key` it keeps for retries. The server re-prices every line from product-service (sale price if above 0, else list price); cart prices are display-only.
- **Google SSO:** reuses auth-service's existing OIDC leg; no auth-service code changed. The button navigates to `http://localhost:8081/oauth2/authorization/google?redirect_uri=<origin>/auth/callback`. auth-service sets the refresh cookie and redirects to `/auth/callback`, where the normal bootstrap obtains an access token. The post-login destination is kept in `sessionStorage`.

## Diagrams

- [13: Login, refresh and Google sequence](../architecture/diagrams/13-auth-login-refresh-google-sequence.md)
- [14: Checkout sequence](../architecture/diagrams/14-checkout-sequence.md)
- [15: ER for user_db / cart_db / order_db](../architecture/diagrams/15-customer-data-er.md)
- [16: Component diagram](../architecture/diagrams/16-customer-components.md)

## Run it locally

1. **Create databases (existing postgres volume only).** Init scripts run only on an empty volume. Do not `down -v`; run:
   ```
   docker exec -i ecommerce-postgres psql -U postgres -v ON_ERROR_STOP=0 < infra/scripts/create-customer-dbs.sql
   ```
   The script is re-runnable. Fresh volumes get the same databases from `infra/postgres/init/01-schemas.sql`.
2. **Env.** In `infra/.env` set `USER_DB_PASSWORD`, `CART_DB_PASSWORD`, `ORDER_DB_PASSWORD` (examples in `infra/.env.example`). For Google set `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET`.
3. **Start services.**
   ```
   docker compose -f infra/docker-compose.yml up -d --build auth-service user-service cart-service order-service
   ```
   Allow ~30-40 seconds after a restart before the gateway stops answering 503 (TD-BE-08).
4. **Start the storefront.** `npm run dev` inside `apps/product-catalog` serves http://localhost:3000 (pinned with `strictPort`; 3000 is the allow-listed Google callback origin).
5. **Google console.** The authorized redirect URI must be `http://localhost:8081/login/oauth2/code/google`. auth-service allows storefront redirects to `http://localhost:5173/auth/callback` and `http://localhost:3000/auth/callback` (`AUTH_ALLOWED_REDIRECT_URIS`); the default is the 5173 one (`AUTH_DEFAULT_REDIRECT_URI`).

Config knobs: `VITE_AUTH_BASE_URL` (default `http://localhost:8081`), `VITE_GOOGLE_SSO_ENABLED=false` to hide the Google button, `AUTH_REGISTRATION_ENABLED` (default true).

## Known gaps

See [techdebt/README.md](../techdebt/README.md). Highest: oversell (TD-BE-01), no payment (TD-BE-02), no tests (TD-TEST-01/02), secret rotation (TD-SEC-01), admin SPA Google login broken (TD-SEC-02).

## Changes

### Backend

| Path | Change |
|------|--------|
| `services/user-service/{user-service-api,user-service-app}` | New. Profile (lazy upsert from JWT), addresses (cap 20, one default via partial unique index). API module exposes `UserAddressClient`. Flyway `V1__user_baseline.sql`. |
| `services/cart-service/...` | New. Cart (qty 1..99, one currency), wishlist, merge endpoints; upserts via `INSERT ... ON CONFLICT`. API module exposes `CartClient`. `V1__cart_baseline.sql`. |
| `services/order-service/...` | New. Idempotent placement, history, cancel. Feign clients `CartFeignClient`, `UserFeignClient`, `ProductFeignClient`; 1s connect / 3s read, no retries. `V1__order_baseline.sql`. Sets `spring.jackson.deserialization.fail-on-null-for-primitives=false`. |
| `libs/common-core` `ErrorCode.java`, `CodedException.java` | New codes `CART_EMPTY`, `PRODUCT_UNAVAILABLE`, `CURRENCY_MISMATCH`, `ADDRESS_NOT_FOUND`, `ADDRESS_LIMIT`, `DEPENDENCY_UNAVAILABLE`; `CodedException` carries a code. |
| `services/gateway/src/main/resources/application.yml` | Routes `user`, `cart` (cart + wishlist), `order`; breakers `userCb`, `cartCb`, `orderCb`; CORS allows `Idempotency-Key`. `SecurityConfig` unchanged (deny-by-default). |
| `settings.gradle` | Six new module includes. |

### Infra

| Path | Change |
|------|--------|
| `infra/postgres/init/01-schemas.sql` | Added `user_service`/`cart_service`/`order_service` roles and `user_db`/`cart_db`/`order_db`. Removed the broken admin seed (it inserted into `auth.users` before Flyway ran). |
| `infra/scripts/create-customer-dbs.sql` | New. Re-runnable bootstrap for existing volumes. |
| `infra/docker-compose.yml` | Three service blocks bound to `127.0.0.1:8085/8086/8087`, `*_DB_PASSWORD` required. auth-service: `AUTH_REGISTRATION_ENABLED` default true, `AUTH_ALLOWED_REDIRECT_URIS` (5173 + 3000 callbacks), `AUTH_DEFAULT_REDIRECT_URI` (5173). No auth-service code change. |
| `infra/.env.example` | New password variables. |

### Frontend (`apps/product-catalog`)

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

### Contract deviations from the approved plan

| Plan | Shipped |
|------|---------|
| Idempotency body mismatch: 422 | 422 with code `DOMAIN_RULE_VIOLATION` and rule `IDEMPOTENCY_KEY_REUSED` |
| Missing Idempotency-Key | 400 `VALIDATION_FAILED` |
| 5xx problem detail | Generic text, details in logs only |
| `itemCount` | Total quantity across lines |
| Google start via gateway (8080) | Directly via auth-service (8081); see [DecisionLog D4](phase-05-customer-accounts.md#decisions) |

### Breaking / behaviour notes

- Any code importing `setAuthToken`/`getAuthToken` from `lib/http` breaks. `http.test.ts` still does, so `npm run type-check` fails ([TD-TEST-03](../techdebt/testing.md#td-test-03)).
- The old `localStorage` `auth_token` is no longer read or written.
- Tests were intentionally not written or updated. Backend builds use `-x test`.

## Decisions

### D7: No tests in this pass

**Decision:** Build end-to-end features first; log test gaps as tech debt.  
**Why:** Explicit user instruction. **Cost:** regressions in money paths are unguarded; see [TD-TEST-01](../techdebt/testing.md#td-test-01).  
**Alternatives:** Tests alongside features (rejected by user, slower to first demo).

### D6: Idempotent checkout via `Idempotency-Key` + unique (user_id, key)

**Decision:** Header required; same key and same address replays the original order (200); different address returns 422 `DOMAIN_RULE_VIOLATION` / `IDEMPOTENCY_KEY_REUSED`. A unique-constraint race returns the winner's order.  
**Why:** Mobile/browser retries on a flaky checkout must not double-order. The key is generated once per checkout attempt in the SPA and survives the 401 replay.  
**Alternatives:** Server-generated dedupe by cart hash (rejected: a legitimate repeat order looks identical); a dedicated 409 code (rejected to reuse the existing domain-rule code).

### D5: Checkout is orchestrated on the request thread, sequentially, without an open transaction

**Decision:** Cart, address and each product are fetched one by one; only the insert is transactional (`OrderStore`). Cart clear after commit is best-effort.  
**Why:** The token-relay interceptor reads the request context, so parallel virtual threads lose the bearer token. Correctness over speed (max 50 lines). Remote calls inside a DB transaction would hold connections.  
**Alternatives:** Parallel fetch on virtual threads (rejected: token loss); saga/outbox (deferred: TD-BE-03). **Cost:** TD-BE-06, TD-BE-03.

### D4: Google sign-in starts directly at auth-service (8081), not through the gateway

**Decision:** `GoogleSignInButton` navigates to `http://localhost:8081/oauth2/authorization/google?redirect_uri=<origin>/auth/callback` (`VITE_AUTH_BASE_URL`).  
**Why:** Spring builds Google's `redirect_uri` from the request host. Via the gateway this became `http://<container-ip>:8081/login/oauth2/code/google`, which Google rejects. Directly it is `http://localhost:8081/login/oauth2/code/google`, the URI registered in the console. Cookies ignore ports, so the refresh cookie set by auth-service is still sent to the SPA's `/api/auth/refresh`.  
**Alternatives:** Forwarded-headers handling in auth-service plus gateway routing (rejected for this pass: needs auth-service changes, and none were wanted); registering the container IP in Google (not viable, it changes).  
**Consequence:** The admin SPA still uses the gateway and is affected: [TD-SEC-02](../techdebt/infra-security.md#td-sec-02).

### D3: Google SSO reuses auth-service's existing OIDC leg; token never in the URL

**Decision:** No auth-service code change. Compose sets `AUTH_ALLOWED_REDIRECT_URIS` (5173 and 3000 callbacks) and `AUTH_DEFAULT_REDIRECT_URI` (5173). After Google, auth-service sets the refresh cookie and redirects to `/auth/callback`; the app's bootstrap exchanges the cookie for an access token. The in-app destination is stored in `sessionStorage` (same-origin paths only via `safeNext`).  
**Why:** Smallest change; keeps tokens out of URLs, history and logs; auth-service only honours exact-match redirect URIs, so the destination cannot ride along.  
**Alternatives:** Token in URL fragment (rejected: leakage); new Google endpoint in auth-service (rejected: duplicate of existing leg).

### D2: Auth state: in-memory access token, HttpOnly refresh cookie, single-flight refresh

**Decision:** No token in `localStorage`. One shared refresh promise; each failed request replays once; `/api/auth/*` never triggers refresh.  
**Why:** Refresh tokens rotate, so two concurrent refreshes would look like token reuse. Memory-only access tokens limit XSS exposure.  
**Alternatives:** `localStorage` token (previous behaviour; rejected for XSS); refresh on a timer (rejected: wastes calls and still needs 401 handling).

### D1: Three services (user, cart+wishlist, order), each with its own database

**Decision:** `user-service` (8085), `cart-service` (8086, includes wishlist), `order-service` (8087); databases `user_db`, `cart_db`, `order_db` with per-service roles. `userId` is taken only from JWT `sub`; foreign resources return 404; no cross-database FKs. Orders snapshot address and prices at placement.  
**Why:** Matches the platform's database-per-service pattern; wishlist shares cart's lifecycle and traffic profile, so a fourth service added overhead without benefit. Templated on inventory/product-service, not rating-service.  
**Alternatives:** Five services as the request literally listed (rejected, approved plan chose three); one monolithic customer service (rejected: breaks pattern).  
**New DBs:** created with a one-off script against the running volume, so existing data is kept ([TD-SEC-05](../techdebt/infra-security.md#td-sec-05)).
