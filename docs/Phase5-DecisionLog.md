# Phase 5: Architecture and Design Decisions

**Phase:** 5 (Customer accounts)  
**Decided:** November 2026  
**Status:** Implemented  
Most recent first within each phase is the log convention; Phase 5 decisions are numbered D1-D7 for referencing.

## D7: No tests in this pass

**Decision:** Build end-to-end features first; log test gaps as tech debt.  
**Why:** Explicit user instruction. **Cost:** regressions in money paths are unguarded; see [TD-TEST-01](../techdebt/testing.md#td-test-01).  
**Alternatives:** Tests alongside features (rejected by user, slower to first demo).

## D6: Idempotent checkout via `Idempotency-Key` + unique (user_id, key)

**Decision:** Header required; same key and same address replays the original order (200); different address returns 422 `DOMAIN_RULE_VIOLATION` / `IDEMPOTENCY_KEY_REUSED`. A unique-constraint race returns the winner's order.  
**Why:** Mobile/browser retries on a flaky checkout must not double-order. The key is generated once per checkout attempt in the SPA and survives the 401 replay.  
**Alternatives:** Server-generated dedupe by cart hash (rejected: a legitimate repeat order looks identical); a dedicated 409 code (rejected to reuse the existing domain-rule code).

## D5: Checkout is orchestrated on the request thread, sequentially, without an open transaction

**Decision:** Cart, address and each product are fetched one by one; only the insert is transactional (`OrderStore`). Cart clear after commit is best-effort.  
**Why:** The token-relay interceptor reads the request context, so parallel virtual threads lose the bearer token. Correctness over speed (max 50 lines). Remote calls inside a DB transaction would hold connections.  
**Alternatives:** Parallel fetch on virtual threads (rejected: token loss); saga/outbox (deferred: TD-BE-03). **Cost:** TD-BE-06, TD-BE-03.

## D4: Google sign-in starts directly at auth-service (8081), not through the gateway

**Decision:** `GoogleSignInButton` navigates to `http://localhost:8081/oauth2/authorization/google?redirect_uri=<origin>/auth/callback` (`VITE_AUTH_BASE_URL`).  
**Why:** Spring builds Google's `redirect_uri` from the request host. Via the gateway this became `http://<container-ip>:8081/login/oauth2/code/google`, which Google rejects. Directly it is `http://localhost:8081/login/oauth2/code/google`, the URI registered in the console. Cookies ignore ports, so the refresh cookie set by auth-service is still sent to the SPA's `/api/auth/refresh`.  
**Alternatives:** Forwarded-headers handling in auth-service plus gateway routing (rejected for this pass: needs auth-service changes, and none were wanted); registering the container IP in Google (not viable, it changes).  
**Consequence:** The admin SPA still uses the gateway and is affected: [TD-SEC-02](../techdebt/infra-security.md#td-sec-02).

## D3: Google SSO reuses auth-service's existing OIDC leg; token never in the URL

**Decision:** No auth-service code change. Compose sets `AUTH_ALLOWED_REDIRECT_URIS` (5173 and 3000 callbacks) and `AUTH_DEFAULT_REDIRECT_URI` (5173). After Google, auth-service sets the refresh cookie and redirects to `/auth/callback`; the app's bootstrap exchanges the cookie for an access token. The in-app destination is stored in `sessionStorage` (same-origin paths only via `safeNext`).  
**Why:** Smallest change; keeps tokens out of URLs, history and logs; auth-service only honours exact-match redirect URIs, so the destination cannot ride along.  
**Alternatives:** Token in URL fragment (rejected: leakage); new Google endpoint in auth-service (rejected: duplicate of existing leg).

## D2: Auth state: in-memory access token, HttpOnly refresh cookie, single-flight refresh

**Decision:** No token in `localStorage`. One shared refresh promise; each failed request replays once; `/api/auth/*` never triggers refresh.  
**Why:** Refresh tokens rotate, so two concurrent refreshes would look like token reuse. Memory-only access tokens limit XSS exposure.  
**Alternatives:** `localStorage` token (previous behaviour; rejected for XSS); refresh on a timer (rejected: wastes calls and still needs 401 handling).

## D1: Three services (user, cart+wishlist, order), each with its own database

**Decision:** `user-service` (8085), `cart-service` (8086, includes wishlist), `order-service` (8087); databases `user_db`, `cart_db`, `order_db` with per-service roles. `userId` is taken only from JWT `sub`; foreign resources return 404; no cross-database FKs. Orders snapshot address and prices at placement.  
**Why:** Matches the platform's database-per-service pattern; wishlist shares cart's lifecycle and traffic profile, so a fourth service added overhead without benefit. Templated on inventory/product-service, not rating-service.  
**Alternatives:** Five services as the request literally listed (rejected, approved plan chose three); one monolithic customer service (rejected: breaks pattern).  
**New DBs:** created with a one-off script against the running volume, so existing data is kept ([TD-SEC-05](../techdebt/infra-security.md#td-sec-05)).
