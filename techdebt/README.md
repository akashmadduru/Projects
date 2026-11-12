# Tech Debt Register

Shortcuts taken while building customer accounts (login, Google SSO, user/cart/order services) under the instruction "no test cases for now, focus on e2e features". Related docs: [Phase5-Feature](../docs/Phase5-Feature.md), [Phase5-DecisionLog](../docs/Phase5-DecisionLog.md).

## How to use this folder

- One file per area. Each item has a stable ID (`TD-<AREA>-NN`); reference it in commits and PRs.
- Severity: High = blocks real customers/money or leaks secrets; Med = wrong or fragile under load/failure; Low = polish.
- Effort: S = under a day, M = a few days, L = a week or more.
- When fixing an item, delete it from its file and from the table below in the same PR. Do not renumber.
- Each item was checked against the code on 2026-11-12. Items found to be wrong were dropped or corrected.

## Index (highest severity first)

| ID | Title | Area | Severity | Effort | File |
|----|-------|------|----------|--------|------|
| TD-BE-01 | No inventory reservation at checkout (oversell) | backend | High | L | [backend.md](backend.md#td-be-01) |
| TD-BE-02 | No payment, shipping cost or tax | backend | High | L | [backend.md](backend.md#td-be-02) |
| TD-TEST-01 | No tests for user/cart/order services | testing | High | L | [testing.md](testing.md#td-test-01) |
| TD-TEST-02 | No frontend tests for auth/cart/checkout | testing | High | M | [testing.md](testing.md#td-test-02) |
| TD-SEC-01 | Real GOOGLE_CLIENT_SECRET in local infra/.env (rotate) | infra-security | High | S | [infra-security.md](infra-security.md#td-sec-01) |
| TD-SEC-02 | Admin SPA Google login starts via gateway (redirect_uri rejected) | infra-security | High | S | [infra-security.md](infra-security.md#td-sec-02) |
| TD-TEST-03 | Stale `http.test.ts`; `npm run type-check` fails | testing | Med | S | [testing.md](testing.md#td-test-03) |
| TD-TEST-04 | Gateway route/authorization contract untested | testing | Med | M | [testing.md](testing.md#td-test-04) |
| TD-BE-03 | Cart clear after order is best-effort, no outbox | backend | Med | M | [backend.md](backend.md#td-be-03) |
| TD-BE-04 | No `order.events` Kafka publisher | backend | Med | M | [backend.md](backend.md#td-be-04) |
| TD-BE-05 | Cancel does not release stock or refund | backend | Med | M | [backend.md](backend.md#td-be-05) |
| TD-BE-06 | Sequential product lookups in checkout | backend | Med | M | [backend.md](backend.md#td-be-06) |
| TD-BE-07 | No Feign circuit breakers in order-service | backend | Med | S | [backend.md](backend.md#td-be-07) |
| TD-BE-08 | Gateway 503 for 30-40s after a service restart | backend | Med | S | [backend.md](backend.md#td-be-08) |
| TD-BE-09 | Cart/wishlist trust client snapshots; productId unchecked | backend | Med | S | [backend.md](backend.md#td-be-09) |
| TD-BE-10 | Jackson `fail-on-null-for-primitives=false` in order-service | backend | Med | S | [backend.md](backend.md#td-be-10) |
| TD-SEC-03 | Dev DB passwords committed | infra-security | Med | S | [infra-security.md](infra-security.md#td-sec-03) |
| TD-SEC-04 | Open registration by default, no email verification | infra-security | Med | M | [infra-security.md](infra-security.md#td-sec-04) |
| TD-SEC-05 | Manual DB script needed for existing volumes | infra-security | Med | M | [infra-security.md](infra-security.md#td-sec-05) |
| TD-SEC-06 | Dev admin seed removed, no replacement | infra-security | Med | S | [infra-security.md](infra-security.md#td-sec-06) |
| TD-FE-02 | Checkout totals are client-side; price drift seen only after order | frontend | Med | M | [frontend.md](frontend.md#td-fe-02) |
| TD-FE-03 | Optimistic cart/wishlist edits are last-write-wins | frontend | Med | M | [frontend.md](frontend.md#td-fe-03) |
| TD-FE-04 | Failed login merge has no in-session retry | frontend | Low | S | [frontend.md](frontend.md#td-fe-04) |
| TD-FE-05 | Auth hardening gaps (forgot password, verification) | frontend | Med | L | [frontend.md](frontend.md#td-fe-05) |
| TD-BE-11 | No abandoned-cart cleanup / account-deletion propagation | backend | Low | M | [backend.md](backend.md#td-be-11) |
| TD-BE-12 | SecurityConfig copy-pasted across 6 services | backend | Low | M | [backend.md](backend.md#td-be-12) |
| TD-BE-14 | `user_profiles.email` never refreshed | backend | Low | S | [backend.md](backend.md#td-be-14) |
| TD-TEST-05 | `tsconfig.build.json` excludes tests | testing | Low | S | [testing.md](testing.md#td-test-05) |
| TD-BE-15 | Concurrent checkouts with different keys can double-order one cart | backend | Med | M | [backend.md](backend.md#td-be-15) |
| TD-SEC-09 | Prod cookie/redirect defaults (AUTH_COOKIE_SECURE, localhost allow-list) must be overridden | infra-security | Med | S | [infra-security.md](infra-security.md#td-sec-09) |
| TD-SEC-10 | Feign token relay goes to any `services.*.url` override | infra-security | Low | S | [infra-security.md](infra-security.md#td-sec-10) |
| TD-SEC-11 | `http.ts` attaches bearer token to absolute URLs | infra-security | Low | S | [infra-security.md](infra-security.md#td-sec-11) |
| TD-SEC-12 | Non-UUID JWT `sub` returns 500 instead of 401 | infra-security | Low | S | [infra-security.md](infra-security.md#td-sec-12) |
| TD-SEC-13 | DB `CONNECT` not revoked from PUBLIC | infra-security | Low | S | [infra-security.md](infra-security.md#td-sec-13) |
| TD-BE-16 | Build tooling quirks on Windows (spotless bind mount, buildx entitlement, LF endings) | backend | Low | S | [backend.md](backend.md#td-be-16) |
| TD-TEST-06 | Seed data cannot exercise the sale-price rule | testing | Low | S | [testing.md](testing.md#td-test-06) |
| TD-RT-01 | Demo product averages disagree with rating-service | ratings | Med | S | [ratings.md](ratings.md#td-rt-01) |
| TD-RT-02 | No scheduled reconcile of product rating fields | ratings | Med | M | [ratings.md](ratings.md#td-rt-02) |
| TD-RT-03 | Rating updates bump `Product.version` | ratings | Low | S | [ratings.md](ratings.md#td-rt-03) |
| TD-RT-04 | Reviewer display name is a snapshot | ratings | Low | S | [ratings.md](ratings.md#td-rt-04) |
| TD-RT-05 | Seed reviews have synthetic authors | ratings | Low | S | [ratings.md](ratings.md#td-rt-05) |
| TD-RT-06 | Duplicated rating event/summary shapes, no contract tests | ratings | Low | M | [ratings.md](ratings.md#td-rt-06) |
| TD-RT-07 | Ratings frontend cleanups (dead code, refetch, lag, no tests) | ratings | Low | S | [ratings.md](ratings.md#td-rt-07) |
| TD-SEC-08 | No account-linking UX for Google vs password accounts | infra-security | Low | M | [infra-security.md](infra-security.md#td-sec-08) |
| TD-FE-06 | Wishlist: snapshot-less items dropped; N product fetches | frontend | Low | M | [frontend.md](frontend.md#td-fe-06) |
| TD-FE-07 | Session-expiry full reload; no Retry-After on 429 | frontend | Low | S | [frontend.md](frontend.md#td-fe-07) |
| TD-FE-08 | Address form validation minimal; `window.confirm` dialogs | frontend | Low | S | [frontend.md](frontend.md#td-fe-08) |
| TD-FE-09 | No cross-tab session sync | frontend | Low | M | [frontend.md](frontend.md#td-fe-09) |
| TD-FE-10 | Google button shown without checking server config | frontend | Low | S | [frontend.md](frontend.md#td-fe-10) |
| TD-FE-11 | Post-login destination in sessionStorage can swap across tabs | frontend | Low | S | [frontend.md](frontend.md#td-fe-11) |

## Dropped after verification

- "Address `isDefault` serialization unverified": `AddressResponse` is a record with component `isDefault`, and the frontend type uses the same name. Not an issue.
- TD-FE-01 "`npm run dev` forces port 3000; Google callback needs 5174": resolved by keeping the storefront on 3000 (`vite.config.ts` strictPort 3000) and allow-listing `http://localhost:3000/auth/callback` in compose.
- TD-BE-13 "No cap on distinct cart lines / wishlist size": resolved in review fixes (cart max 50 lines, wishlist max 500, 409 `CART_LIMIT` / `WISHLIST_LIMIT`).
- TD-FE-04 data loss: resolved (quantities clamped to 99, merge chunked, local items kept when merge fails). Remaining: no retry until next sign-in.
- TD-SEC-07 "rating-service not runnable in compose/gateway": resolved by the ratings feature (rating_db, Dockerfile, compose on 8084, gateway route, `roles`-based admin check). Related product-service listener bug (rating events failed to deserialize; denormalization stub) also fixed.
- "RouteAuthorizationContractTest not updated": that test does not exist in `services/gateway/src/test`; it is only referenced in a `SecurityConfig` javadoc. Reframed as TD-TEST-04.
- "Update platform error catalogue": done in [Backend-API.md](../docs/Backend-API.md).
