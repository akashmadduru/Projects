# Tech Debt: Backend

Index: [README](README.md)

TD-BE-01, TD-BE-02, TD-BE-04 and TD-BE-05 were resolved by Phase 6; residual risks are in [phase6.md](phase6.md). See the resolved list in the [README](README.md#resolved-by-phase-6-checked-against-the-code).

<a id="td-be-03"></a>
## TD-BE-03 Cart clear after order is best-effort, no outbox
- **Severity / Effort:** Low / M (was Med; narrowed 2026-11-18)
- **Where:** `OrderService.clearCartBestEffort`.
- **Risk:** If cart-service is down after commit, the order exists and the cart is not cleared (only a log warning), so the cart still shows the ordered items. Since 2026-11-18 (TD-BE-15) checking out that same cart again is refused with 409 and retries the clear, so it can no longer become a second order; what remains is the stale cart until the next checkout attempt.
- **Fix:** Outbox row in the order transaction, relayed to cart-service or consumed from `order.events`.

<a id="td-be-07"></a>
## TD-BE-07 No Feign circuit breakers in order-service
- **Severity / Effort:** Med / S
- **Where:** `order-service-app/src/main/resources/application.yml` (1s connect, 3s read, no retries; no Resilience4j).
- **Risk:** A degraded dependency ties up request threads for the full timeout. Dependency failures map to 503 `DEPENDENCY_UNAVAILABLE`.
- **Fix:** Resilience4j breakers per client with fast-fail.

<a id="td-be-09"></a>
## TD-BE-09 Cart/wishlist trust client snapshots; productId unchecked
- **Severity / Effort:** Med / S
- **Where:** `services/cart-service/cart-service-app/.../service/CartService.java`, `WishlistService.java`.
- **Risk:** name/price/image are display-only and client-supplied; cart accepts any productId. Money is safe because order-service re-prices from product-service, but the cart can show wrong prices and hold dead products.
- **Fix:** Validate productId against product-service on add (or refresh snapshots on cart read).

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


<a id="td-be-17"></a>
## TD-BE-17 Inactive products are readable by anonymous callers
- **Severity / Effort:** Low / S
- **Where:** `GET /api/products/{id}` and `GET /api/products/batch?detail=true` (`ProductCatalogService.detail`, `findDetailsByIds`).
- **Risk:** Both return the detail view of inactive products to anyone, because order-service needs to refuse them with a clear error. `detail=true` is not a new exposure (it is the same view `/{id}` already serves) but it adds a bulk path. The Owner decides whether inactive products should be hidden from anonymous callers or only readable with a token.
- **Fix:** If hidden: require a service or admin token for inactive products and let order-service use it. Raised by F-20261119-11 (CTO condition 4).

<a id="td-be-18"></a>
## TD-BE-18 Detail batch still makes one stock-rows call per product
- **Severity / Effort:** Low / M
- **Where:** `OfferService.enrich` (called from `ProductCatalogService.detail`), per product, through `InventoryServiceClient.stockRows`.
- **Risk:** `GET /api/products/batch?detail=true` skips variant stock but still makes about one inventory request per product, so a 50-product checkout costs up to 50 inventory calls inside one 5 s order-service read budget (`ORDER_PRODUCT_BATCH_READ_TIMEOUT_MS`). It is still far fewer calls than before, and checkout traffic stays the same shape. The unaffected backlog item B-4 is not changed by this.
- **Fix:** Add a batched stock-rows endpoint to inventory-service (`/internal/inventory/stock/by-products`) and make `OfferService.enrich` take the rows for all products at once. Raised by F-20261119-11 (CTO condition 6).
