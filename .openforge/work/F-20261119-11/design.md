# Design F-20261119-11: checkout integrity (O-06, class L, three stacked PRs)

Paths are relative to `services/<svc>/<svc>-app/src/main/...`, verified at the worktree head (base 820e5a8).

## 1. Current behaviour
- **Fingerprint (TD-BE-15).** V12 adds `cart_fingerprint` with a partial unique index on (user, fingerprint) WHERE not null and status <> CANCELLED (V12__cart_fingerprint.sql:7-11). `OrderService.cartFingerprint` hashes product|variant|offer|qty|addedAt, but returns **null** when any line lacks `addedAt` (OrderService.java:499-504). Then `consumeCart` is skipped (:260-262), the index does not apply, and the race path rethrows the raw `DataIntegrityViolationException` (:275-281). In the DB, cart-service `added_at` is `NOT NULL DEFAULT now()` (cart V1__cart_baseline.sql:23), and `CartRepository.findItems` dereferences it (CartRepository.java:73). So a null only arrives through contract drift (an old or other cart-service build, or a field lost in mapping), and that case is unprotected.
- **Breakers (TD-BE-07).** `DependencyBreakerCapability` keeps one breaker per host. It counts only I/O errors and 5xx, and an open breaker gives an IOException, which becomes a FeignException and then 503 (DependencyBreakerCapability.java:53-77). Tests cover opening, 4xx not counting and isolation per host (DependencyBreakerCapabilityTest.java:38,55,66). No test covers half-open recovery or compensation when a breaker is open.
- **Product lookup (TD-BE-06).** `draft` calls `fetchProduct` once per line, one after another (OrderService.java:556-559, 752-761), against `GET /api/products/{id}`. That endpoint returns `detail()`: variants, offers, buy box and inactive products (ProductCatalogService.java:366-370, 187-197). `GET /api/products/batch` (ProductController.java:313-328) calls `findByIds` (ProductCatalogService.java:166-178), which is lean: it returns **no variants, no offers, no buyBox**, and it **drops inactive** products. So it cannot serve `draft`, which needs variants, offers, buyBox, active, salePrice and name (OrderService.java:568-616).
- **Cart clear (TD-BE-03).** `clearCartBestEffort` calls the user-token `DELETE /api/cart` and only logs a failure (OrderService.java:763-769). It runs after placement (:394-396, :414-416) and on the 409 path (:282). The cart-service clear deletes **every** line (CartService.java:70-73, CartRepository.java:135-139), including lines added during checkout. cart-service has an `/internal/**` X-Internal-Token chain but no cart endpoint behind it (InternalSecurityConfig.java:38).

## 2. Proposed change
### PR-A: fail-closed fingerprint plus regression cover (about 20 production lines)
- In `cartSnapshot`, when `cartFingerprint` returns null, throw `CodedException(DEPENDENCY_UNAVAILABLE, "Your cart could not be read, please try again")` and log a WARN that names the cart-service contract. Checkout of a cart is never placed without a fingerprint. `cartFingerprint` itself does not change, so existing fingerprints stay stable and nothing is backfilled.
- No migration. No change to the breaker code. The rest of PR-A is tests (section 5).

### PR-B: one batched product lookup (about 100 production lines)
- **product-service (additive).** `GET /api/products/batch?ids=..&detail=true`. Without `detail` the response is the same as today. With `detail=true`, the new `ProductCatalogService.findDetailsByIds(ids)` returns `detail(p)` for each distinct known id, in request order, **including inactive products**, which matches what `/{id}` already exposes. The existing caps still apply: at most 50 ids, 400 on a non-UUID, duplicates collapsed. `detail` gets an overload that skips the variant stock call, because order-service never reads variant stock (ProductViewFeignClient.java:23-30). This removes one inventory call per product.
- **order-service.** A new `ProductBatchFeignClient` (contextId `productBatchClient`, read timeout 5 s, connect timeout 1 s) has `List<ProductView> batch(@RequestParam ids, @RequestParam detail)` and reuses the `ProductView` record. It is a separate contextId so that the longer timeout does not apply to single lookups. The breaker stays per host.
- In `draft`, one call `fetchProducts(distinct ids)` replaces the per-line loop and builds a `Map<UUID, ProductView>`. A requested id that is missing from the result throws PRODUCT_UNAVAILABLE with today's message. Feign errors go through `RemoteCalls.unavailable("product-service")`. The pricing loop does not change: it reads `views.get(line.productId())`.
- The bearer token is forwarded by the existing `CorrelationAndAuthForwardingInterceptor` (common-web), as today.

### PR-C: durable, scoped cart clear on the existing outbox (about 230 production lines, under the 600 limit, no split needed)
- **cart-service.** `POST /internal/carts/{userId}/consumed` with body `ConsumedLinesRequest{orderId, lines[{productId, variantId, offerId, quantity, addedAt}]}` returns `{removed:n}`. It calls `CartService.removeConsumed`, which takes `lockOrCreateCart(userId)` and then `CartRepository.deleteConsumed`. That deletes a row only when it is in **that user's cart** and matches product, `variant_key` and the exact `offer_key`, plus `quantity` and `date_trunc('milliseconds', added_at)`. A line the user changed or added after the order is kept. Repeating the call is a no-op. The endpoint is protected by the existing TokenFilter and is never gateway-routed. The DTO goes in cart-service-api.
- **order-service.**
  - Add `Outbox.Kind.CLEAR_CART`. The aggregate is `UUID.nameUUIDFromBytes("cart:"+userId)`, so a user's clears run in order and never block an order's own effects. The idem key is `CLEAR_CART:<orderId>`.
  - `OrderRemote.clearCart(userId, orderId, lines)` enqueues the row and then tries the delivery inline once. On success it calls the new `Outbox.complete(idemKey)`, which sets the row DONE if it is still PENDING. On failure it logs and leaves the row PENDING.
  - `clearCartNow(payload)` is the strict call the relay uses, through a new `CartInternalFeignClient` (contextId `cartInternalClient`, `InternalFeignConfig`, 1 s / 3 s timeouts).
  - `OrderService` replaces `clearCartBestEffort` with `remote.clearCart(saved.userId(), saved.id(), consumedLines)`. The lines come from `CartSnapshot`, which now keeps the raw `CartItemResponse` list with `addedAt`. For WALLET orders the enqueue joins the markPaid/ORDER_PLACED transaction (:388-393). The 409 path enqueues for the existing order id, which is idempotent, and retries inline with the current lines, which equal that order's lines because the fingerprint matched. Buy-now never enqueues.
- **OutboxRelay.** Add a `CLEAR_CART -> remote.clearCartNow(...)` case. Backoff and attempt limits become per kind: CLEAR_CART uses a 240 s backoff cap and at most 400 attempts (about 26 h). Other kinds keep 3600 s and 20 attempts.
- **No migration.** `outbox.kind` is varchar(40) with no CHECK constraint (V10__outbox.sql:11).
- **Config.** Two Feign client blocks in application.yml. `docs/reference/endpoints.md` gets the internal endpoint and the `detail` flag.

## 3. AC to change
| AC | Met by | PR |
|---|---|---|
| 1, 3, 4, 5, 7 | Existing V12 guard plus the replay logic. New tests assert stock, coupon, coins and wallet are each called once; that a same-key race returns `created=false`; that two users with identical carts both succeed | A |
| 2 | A null fingerprint is refused (503) before any order row exists, so there is no unprotected path. The test races two placements: 0 orders, both get 503 | A |
| 6 | The buy-now path sets no fingerprint. Test: two keys give two orders | A |
| 8, 9 | Existing capability. Add a test that an open breaker on product or cart maps to 503 DEPENDENCY_UNAVAILABLE with no host call; 4xx and isolation tests stay | A |
| 9b | New test: after the open wait, half-open probes close the breaker. Integration test: coupon redeem fails with an open-breaker FeignException after save, the order is CANCELLED, and RESTORE_STOCK and RELEASE_COUPON are enqueued | A |
| 10, 13 | One `batch(detail=true)` call over the distinct ids. Test: a 50-line cart makes exactly 1 product call, and the order equals one built per line (same fixtures). Two lines of one product make one id | B |
| 11, 14 | Unchanged per-line checks. An id missing from the batch gives PRODUCT_UNAVAILABLE, thrown in `draft` before `store.save`, so no row exists (test counts rows) | B |
| 12 | Token forwarded by the interceptor. Test: the Feign batch call carries the request's Authorization header. Prices come only from `ProductView` | B |
| 15, 16 | The outbox row commits with or after the order and survives a restart. The relay delivers within the 240 s backoff cap, plus 2 s polling, plus up to 10 s of breaker open time, so under 5 minutes | C |
| 17 | The 409 path re-enqueues and clears inline. The fingerprint guard still holds | C |
| 18 | `deleteConsumed` matches exact lines. Test: a new or changed line survives | C |
| 19 | userId comes from the saved order, and the delete is constrained to that user's cart. Test: user B's identical cart is untouched | C |
| 20 | Buy-now does not enqueue. Test: no CLEAR_CART row and no cart call | C |

## 4. Alternatives considered
- **AC-2 content-only fingerprint fallback (no addedAt).** It would block a deliberate repeat order (AC-5) for as long as the earlier order is not cancelled, which for DELIVERED orders means forever. It fails silently for the customer. Rejected in favour of a loud, fail-closed 503 on a path the cart-service schema never produces.
- **Parallel per-line calls (executor) instead of a batch.** Still 50 requests (AC-10 fails), needs token propagation across threads and uses breaker budget 50 times. Rejected.
- **Make `/batch` return detail by default.** It changes a public lean contract that the storefront uses: payload size, and inactive products would appear. Rejected; the flag is opt-in.
- **A new `pending_cart_clears` table plus a JobLock job.** It needs a migration and duplicates the outbox's retry, backoff, DEAD state and admin retry. Rejected.
- **Outbox row keyed by order id.** A pending clear during a cart outage would block that order's PAID event and cancel effects, because the relay delivers rows in order per aggregate (OutboxRelay.java:94-95). Rejected.
- **Reuse `DELETE /api/cart` from the relay.** The relay has no user token, and the call wipes newer lines (AC-18). Rejected.

## 5. Risks, failure modes, rollback, tests
- **AC-2 refusal.** If cart-service ever ships without `addedAt`, every cart checkout returns 503 (buy-now still works). It is loud, a WARN is logged, and a deploy fixes it. A cart-service test pins `addedAt` as non-null in `CartResponse`.
- **Batch latency.** With 50 products, product-service still makes about one stock-rows call per product. A 5 s budget on that one client covers it, and it is strictly fewer calls than today. Backlog: a batched stock-rows endpoint in inventory.
- **Relay.** It runs under JobLock `order-outbox-relay` (a pg advisory lock), so it is safe across instances. An inline attempt racing the relay is harmless because the delete is idempotent. A crash between the commit of a CARD/COD order and the enqueue (a window of milliseconds) loses only the clear. The fingerprint still blocks a re-order, and the stale cart stays (residual risk, documented).
- **Rollback.** The old relay's `Kind.valueOf` throws on an unknown kind and stalls **the whole outbox pass**. Before rolling back PR-C, the runbook must run `UPDATE orders.outbox SET status='DEAD' WHERE kind='CLEAR_CART' AND status='PENDING'`. PR-B rollback: revert the images; the `detail` flag is ignored. PR-A: revert.
- **Deploy order.** Deploy cart-service (PR-C) and product-service (PR-B) before order-service. Until then, a 404 is retried by the outbox or fails checkout respectively.
- **Tests.**
  - OrderPlacementIntegrationTest (Testcontainers) for AC-1 to 7, 9b, 10 to 15, 17 and 20.
  - DependencyBreakerCapabilityTest for AC-8, 9 and 9b (half-open).
  - OutboxRelayTest for CLEAR_CART: fail, then backoff of 240 s or less, then DONE; restart survival; DEAD at 400 attempts.
  - A cart-service repository and controller integration test for AC-18 and 19, plus a 401 without the token.
  - ProductController/ProductCatalogService tests for `detail=true`: inactive included, order kept, duplicates collapsed, more than 50 ids gives 400, and the lean default is unchanged.

## 6. Files expected to change
- **PR-A.** order-service: service/OrderService.java. Tests: OrderPlacementIntegrationTest, DependencyBreakerCapabilityTest. cart-service: one test.
- **PR-B.** product-service: web/ProductController.java, service/ProductCatalogService.java. order-service: client/ProductBatchFeignClient.java (new), service/OrderService.java, resources/application.yml. docs/reference/endpoints.md, docs/techdebt (TD-BE-06 to remediation-plan). Tests.
- **PR-C.** cart-service-api: dto/ConsumedLinesRequest.java (new). cart-service: web/InternalCartController.java (new), service/CartService.java, repository/CartRepository.java. order-service: client/CartInternalFeignClient.java (new), outbox/Outbox.java, outbox/OutboxRelay.java, service/OrderRemote.java, service/OrderService.java, resources/application.yml. docs/reference/endpoints.md, docs/techdebt (TD-BE-03, -07 and -15 rows). Tests.

## 7. Reuse audit
- **Reused.**
  - Outbox, OutboxRelay and JobLock (libs/common-web) carry the deferred clear.
  - The OutboxAdminController DEAD retry covers clears that exhaust their attempts.
  - InternalFeignConfig and the cart-service InternalSecurityConfig TokenFilter protect the internal endpoint.
  - The `/batch` endpoint and its 50-id cap, `findByIds` and `detail()` are extended, not copied.
  - `CartRepository.key()` and the `variant_key`/`offer_key` columns are used for matching.
  - DependencyBreakerCapability, RemoteCalls and CorrelationAndAuthForwardingInterceptor are unchanged.
- **Not reused, and why.** The ReturnRefundWatch job pattern is not needed, because the outbox already polls.
- **Out of scope, for the backlog.**
  - Make the relay tolerate unknown kinds; this is the rollback fragility above.
  - A batched inventory stock-rows endpoint.

## CTO decision

**Accepted** (forge-cto, 2026-11-21; copied from its reply by the CEO). There is no new service, datastore, vendor or abstraction, every AC has a test, and the contract changes are additive. Class L runs under the standing Owner approval (R-20261119-01). ADR 0010 is Accepted, with the CTO's two edits applied.

Rulings:
- **(a) AC-2 fail-closed 503 on a missing `addedAt`:** correct. The column is NOT NULL, so a null means contract drift. The refusal happens before any order row exists, and buy-now is unaffected.
- **(b) `detail=true`:** not a new exposure, because it is the same view as `GET /{id}`. Keep it opt-in and public, since the user's token is forwarded (AC-12).
- **(c) `/internal/carts/{userId}/consumed`:** adequately protected by the cart-service `/internal/**` X-Internal-Token chain. The gateway does not route `/internal/**` and strips `X-Internal-*`. B-4 is not affected. Scoping to the user's cart is by construction.
- **(d) Rollback hazard:** the runbook alone is not enough, so the relay's tolerance of unknown kinds moves into PR-A.
- **(e)** Keep 3 stacked PRs.

Conditions:
1. **Relay tolerance (PR-A).** `OutboxRelay` handles an unknown `kind` without throwing: it logs ERROR with the row id, skips the row, and leaves it PENDING, not DONE and without stalling the pass. Cover it with `OutboxRelayTest`. PR-A must be deployed and live before PR-C; state this in PR-C and the runbook.
2. **PR-C rollback runbook.**
   - Steps: (i) stop or scale down the new order-service pods; (ii) `UPDATE orders.outbox SET status='DEAD' WHERE kind='CLEAR_CART' AND status='PENDING'`; (iii) start the old image.
   - Step (ii) is only a backstop once PR-A is live.
   - Write it in docs/ and in ADR 0010.
3. **Internal endpoint hardening (PR-C).**
   - Validation:
     - `userId` is a UUID path variable;
     - `@Valid` on the request;
     - `lines` capped at 50;
     - quantities from 1 to 99;
     - `orderId` is never used for scoping.
   - Auth tests: 401 with no token, 401 with a wrong token, and 401 when the token is not configured.
   - Scoping tests: user B's identical lines are untouched, and lines that changed or were added after the order survive.
   - Docs: list `/internal/carts/**` in the endpoints doc as internal-only.
   - Gateway: add a test or assertion that no gateway route matches `/internal/**`, or cite the existing one.
4. **`detail=true` (PR-B).** A parity test shows the response is exactly the `/{id}` `detail()` shape. A test shows the lean default response stays byte-identical. Add a backlog note that `/{id}` and `detail=true` expose inactive products to anonymous callers, for the Owner to decide.
5. **AC-2 (PR-A).** The WARN log names the cart-service contract. A cart-service test pinning `addedAt` as non-null in `CartResponse` is mandatory. A test of two racing placements on the 503 path shows 0 orders are created.
6. **Backlog.** B-4 is unaffected. Add the batched inventory stock-rows endpoint.
7. **Verification.** Per PR: forge-verify `--changed` output recorded, an independent review at the PR's SHA, and Owner approval for the push.

Final split, with deploy order A, then product-service and cart-service (B, C), then order-service (B, C):
- **PR-A**, about 30 production lines: fail-closed fingerprint, relay unknown-kind tolerance, and AC-1..AC-9b regression tests.
- **PR-B**, about 100 lines: AC-10..AC-14.
- **PR-C**, about 230 lines: AC-15..AC-20.
