# Tech Debt: Testing

Index: [README](README.md)

<a id="td-test-01"></a>
## TD-TEST-01 Ownership is not tested per HTTP endpoint
- **Severity / Effort:** Med / M
- **Where:** order-, cart- and user-service controllers.
- **Risk:** Service-level coverage added 2026-11-17 with a Testcontainers Postgres base in each of the three services: `OrderPlacementIntegrationTest` (12 cases: card order, idempotent replay, currency mix, inactive product, coupon, coins cap, wallet paying the whole order, and the compensation when stock, payment, coupon, coins or wallet fails), `CartServiceIntegrationTest` (merge sums and caps at 99, all-or-nothing merge, 50-line cap, save for later, per-user isolation), `AddressRulesIntegrationTest` (first address is default, exactly one default, promotion on delete, 20-address cap, another user's address is not found). Writing the coupon test found that a failed coupon or coins redemption did not release them; fixed. Still untested: the HTTP layer, i.e. that each endpoint takes the user from the token and answers 404 for another user's order.
- **Fix:** MockMvc tests per endpoint with two users' tokens (reuse product-service's `TestKeys`/`StubJwksServer`).

<a id="td-test-02"></a>
## TD-TEST-02 No frontend tests for checkout and the Google sign-in pages
- **Severity / Effort:** Med / M
- **Where:** `apps/product-catalog/src/pages/CheckoutPage.tsx`, `pages/AuthCallbackPage.tsx`, `components/GoogleSignInButton.tsx`, `stores/wishlistStore.ts`.
- **Risk:** Progress 2026-11-17: `stores/authStore.test.ts` (login, second factor, profile failure drops the token, a failing cart sync does not block sign-in, logout when the server call fails, session restore with a pending Google 2FA challenge) and `stores/cartStore.test.ts` (99 cap, quantity 0 removes, sign-in merge then adopt, failed merge keeps the anonymous cart, a signed-in cart never persisted locally, optimistic add with rollback). Still untested: the checkout page flow, the Google callback fallback paths and the wishlist store.
- **Fix:** Component tests for checkout and the callback page; one Playwright e2e covering register, add to cart, checkout.

<a id="td-test-04"></a>
## TD-TEST-04 Gateway route/authorization contract untested
- **Severity / Effort:** Med / M
- **Where:** `services/gateway/src/main/java/.../config/SecurityConfig.java` (javadoc names `RouteAuthorizationContractTest`, which does not exist); `services/gateway/src/test`.
- **Risk:** The javadoc says route table and security rules must be asserted against each other. New routes `/api/users`, `/api/cart`, `/api/wishlist`, `/api/orders` rely on deny-by-default with no test.
- **Fix:** Add the test it describes, parsing `application.yml` routes and asserting each requires authentication.

<a id="td-test-06"></a>
## TD-TEST-06 Seed data cannot exercise the sale-price rule
- **Severity / Effort:** Low / S
- **Where:** product-service seed (`R__product_demo_seed.sql`): `salePrice == priceAmount` for every product.
- **Risk:** The checkout price rule (`salePrice > 0 ? salePrice : priceAmount`, `OrderService.unitPrice`) has never been observed choosing the sale price.
- **Fix:** Seed at least one discounted product, or cover it in an order-service unit test.

