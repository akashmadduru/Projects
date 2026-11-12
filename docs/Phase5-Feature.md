# Phase 5: Customer Accounts (Login, Google SSO, User/Cart/Order Services)

**Phase:** 5  
**Date:** November 2026  
**Status:** Implemented (no automated tests by explicit decision; see [techdebt/](../techdebt/README.md))  
**Related:** [Changes](Phase5-Changes.md), [Decision Log](Phase5-DecisionLog.md), [Backend-API](Backend-API.md#customer-services-api-phase-5)

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

- [13: Login, refresh and Google sequence](diagrams/13-auth-login-refresh-google-sequence.md)
- [14: Checkout sequence](diagrams/14-checkout-sequence.md)
- [15: ER for user_db / cart_db / order_db](diagrams/15-customer-data-er.md)
- [16: Component diagram](diagrams/16-customer-components.md)

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
