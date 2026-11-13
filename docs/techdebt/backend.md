# Tech Debt: Backend

Index: [README](README.md)

TD-BE-01, TD-BE-02, TD-BE-04 and TD-BE-05 were resolved by Phase 6; residual risks are in [phase6.md](phase6.md). See the resolved list in the [README](README.md#resolved-by-phase-6-checked-against-the-code).

<a id="td-be-03"></a>
## TD-BE-03 Cart clear after order is best-effort, no outbox
- **Severity / Effort:** Med / M
- **Where:** `OrderService.clearCartBestEffort`.
- **Risk:** If cart-service is down after commit, the order exists and the cart is not cleared (only a log warning); a customer can re-order the same items.
- **Fix:** Outbox row in the order transaction, relayed to cart-service or consumed from `order.events`.

<a id="td-be-06"></a>
## TD-BE-06 Sequential product lookups in checkout
- **Severity / Effort:** Med / M
- **Where:** `OrderService.build` (one `ProductFeignClient.findById` per line, max 50 lines, 3s read timeout each).
- **Risk:** Up to 50 serial calls; slow checkouts and gateway `orderCb` slow-call trips. Kept sequential because the token-relay interceptor reads the request thread context.
- **Fix:** Batch endpoint in product-service (`POST /api/products/batch`), or explicit token propagation to a bounded executor.

<a id="td-be-07"></a>
## TD-BE-07 No Feign circuit breakers in order-service
- **Severity / Effort:** Med / S
- **Where:** `order-service-app/src/main/resources/application.yml` (1s connect, 3s read, no retries; no Resilience4j).
- **Risk:** A degraded dependency ties up request threads for the full timeout. Dependency failures map to 503 `DEPENDENCY_UNAVAILABLE`.
- **Fix:** Resilience4j breakers per client with fast-fail.

<a id="td-be-08"></a>
## TD-BE-08 Gateway 503 for 30-40s after a service restart
- **Severity / Effort:** Med / S
- **Where:** Gateway load-balancer instance cache (`services/gateway/src/main/resources/application.yml`).
- **Risk:** Observed during rollout: after restarting user/cart/order service, requests 503 until the cached instance list refreshes.
- **Fix:** Shorten the LB cache TTL / Eureka fetch interval in dev, or enable gateway retry for idempotent GETs.

<a id="td-be-09"></a>
## TD-BE-09 Cart/wishlist trust client snapshots; productId unchecked
- **Severity / Effort:** Med / S
- **Where:** `services/cart-service/cart-service-app/.../service/CartService.java`, `WishlistService.java`.
- **Risk:** name/price/image are display-only and client-supplied; cart accepts any productId. Money is safe because order-service re-prices from product-service, but the cart can show wrong prices and hold dead products.
- **Fix:** Validate productId against product-service on add (or refresh snapshots on cart read).

<a id="td-be-10"></a>
## TD-BE-10 Jackson `fail-on-null-for-primitives=false` in order-service
- **Severity / Effort:** Med / S
- **Where:** `order-service-app/src/main/resources/application.yml` (line ~29).
- **Risk:** Needed to deserialize product-service responses with missing primitives, but it also lets malformed JSON become 0/false silently across all order-service inputs.
- **Fix:** Use a client-specific ObjectMapper for the Feign decoder; restore the default for the server.

<a id="td-be-11"></a>
## TD-BE-11 No abandoned-cart cleanup / account-deletion propagation
- **Severity / Effort:** Low / M
- **Where:** all three services.
- **Risk:** Stale carts, wishlists, addresses and orders persist after an auth-service account is deleted (no events consumed).
- **Fix:** Consume user-deleted events; scheduled purge of carts idle for N days.

<a id="td-be-12"></a>
## TD-BE-12 SecurityConfig copy-pasted across 6 services
- **Severity / Effort:** Low / M
- **Where:** `services/*/*-app/.../config/SecurityConfig.java`, `ProblemResponseWriter`, `*Properties`.
- **Risk:** Fixes must be applied six times; drift.
- **Fix:** Shared resource-server autoconfiguration in `libs/common-security`.

<a id="td-be-13"></a>
## TD-BE-13 No cap on distinct cart lines / wishlist size — RESOLVED (cart max 50 lines, wishlist max 500)
- **Severity / Effort:** Low / S
- **Where:** `CartService`, `WishlistService`. Merge limited to 100 cart / 200 wishlist items per call; checkout rejects more than 50 lines (a cart can exceed what checkout accepts).
- **Fix:** Enforce a per-cart line cap (50) and wishlist cap on add, with a clear error code.

<a id="td-be-14"></a>
## TD-BE-14 `user_profiles.email` never refreshed
- **Severity / Effort:** Low / S
- **Where:** `services/user-service/user-service-app/.../service/UserService.java`.
- **Risk:** Email is a snapshot of the JWT claim at first call.
- **Fix:** Refresh on each `GET /me` when the claim differs.

<a id="td-be-15"></a>
## TD-BE-15 Concurrent checkouts with different keys can double-order one cart
- **Severity / Effort:** Med / M
- **Where:** `services/order-service/order-service-app/src/main/java/com/acme/ecommerce/order/service/OrderService.java` (place flow; best-effort `clearCart` after commit).
- **Risk:** Two tabs placing orders with different `Idempotency-Key`s both read the same cart before either clears it, so two orders are created. A failed best-effort clear followed by a new-key retry does the same. Only the user's own cart is affected.
- **Fix:** Per-user advisory lock (or cart version check) around placement, and a reliable cart clear (outbox or "consumed by order" marker; see TD-BE-03).

<a id="td-be-16"></a>
## TD-BE-16 Build tooling quirks on Windows
- **Severity / Effort:** Low / S
- **Where:** Gradle runs in an `eclipse-temurin:25-jdk` container (no host JDK). Spotless fails on the Windows bind mount ("Could not read build/spotless-clean"), so the build was copied into the container's `/tmp` and formatted sources copied back. Those Java files now have LF endings while siblings are CRLF. `docker compose up --build` needed `BUILDX_BAKE_ENTITLEMENTS_FS=0`.
- **Fix:** Add `.gitattributes` (`*.java text eol=lf` or `crlf`, pick one) and renormalize; install a host JDK 25 or document the container build; document the buildx variable.
