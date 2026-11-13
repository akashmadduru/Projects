# Tech Debt: Testing

Index: [README](README.md)

<a id="td-test-01"></a>
## TD-TEST-01 No tests for user/cart/order services
- **Severity / Effort:** High / L
- **Where:** `services/user-service`, `services/cart-service`, `services/order-service` (no `src/test`).
- **Risk:** Address default rules, cart merge/caps, idempotent checkout and ownership (404 on foreign resources) are only manually verified. Regressions in money paths ship silently.
- **Fix:** Unit tests for `OrderService.place` (empty cart, currency mix, inactive product, idempotent replay, key reuse), `CartRepository` upserts and caps, `UserService` address limit/default promotion; integration tests with Testcontainers Postgres; ownership tests per endpoint.

<a id="td-test-02"></a>
## TD-TEST-02 No frontend tests for auth, cart sync, checkout, Google SSO
- **Severity / Effort:** High / M
- **Where:** `apps/product-catalog/src/stores/{authStore,cartStore,wishlistStore}.ts`, `pages/CheckoutPage.tsx`, `pages/AuthCallbackPage.tsx`, `components/GoogleSignInButton.tsx`.
- **Risk:** Login-merge-replace, rollback on failed optimistic edits and the Google callback fallback paths are untested.
- **Fix:** MSW-based store tests; component tests for login/register/callback; one Playwright e2e covering register, add to cart, checkout.

<a id="td-test-03"></a>
## TD-TEST-03 Stale `http.test.ts`
- **Severity / Effort:** Med / S
- **Where:** `apps/product-catalog/src/lib/http.test.ts` (8 references to removed `setAuthToken`/`getAuthToken`).
- **Risk:** `npm run type-check` fails. `npm run build` still passes only because of TD-TEST-05.
- **Fix:** Rewrite for the new behaviour: single-flight refresh, replay once, Idempotency-Key preserved on replay, auth paths skip refresh, GET dedupe cleanup.

<a id="td-test-04"></a>
## TD-TEST-04 Gateway route/authorization contract untested
- **Severity / Effort:** Med / M
- **Where:** `services/gateway/src/main/java/.../config/SecurityConfig.java` (javadoc names `RouteAuthorizationContractTest`, which does not exist); `services/gateway/src/test`.
- **Risk:** The javadoc says route table and security rules must be asserted against each other. New routes `/api/users`, `/api/cart`, `/api/wishlist`, `/api/orders` rely on deny-by-default with no test.
- **Fix:** Add the test it describes, parsing `application.yml` routes and asserting each requires authentication.

<a id="td-test-05"></a>
## TD-TEST-05 `tsconfig.build.json` excludes tests
- **Severity / Effort:** Low / S
- **Where:** `apps/product-catalog/tsconfig.build.json`.
- **Risk:** A deliberate workaround; test type errors never block `npm run build`.
- **Fix:** Delete the file and point `build` back to `tsc` once TD-TEST-03 is done.

<a id="td-test-06"></a>
## TD-TEST-06 Seed data cannot exercise the sale-price rule
- **Severity / Effort:** Low / S
- **Where:** product-service seed (`R__product_demo_seed.sql`): `salePrice == priceAmount` for every product.
- **Risk:** The checkout price rule (`salePrice > 0 ? salePrice : priceAmount`, `OrderService.unitPrice`) has never been observed choosing the sale price.
- **Fix:** Seed at least one discounted product, or cover it in an order-service unit test.
