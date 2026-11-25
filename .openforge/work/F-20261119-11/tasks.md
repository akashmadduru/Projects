# Tasks F-20261119-11: checkout integrity (O-06, class L, 3 stacked PRs)

Source: requirements.md, design.md (CTO decision conditions C1..C7 and final split are binding), ADR 0010.
Stack order: PR-A, then PR-B (on A), then PR-C (on B). Each part ends with a gate task. The gate commit is that PR's head and is reviewed separately by the CEO at its SHA.
Deploy order: PR-A live first; then product-service and cart-service (B, C); then order-service (B, C).

Shorthand. OS = `services/order-service/order-service-app`, CS = `services/cart-service/cart-service-app`, PS = `services/product-service/product-service-app`. Java sources under `src/main/java/com/acme/ecommerce/<svc>/...`.

Verification commands (from project.md):
- V-ORDER = `bash .openforge/scripts/gradle.sh :services:order-service:order-service-app:test`
- V-CART = `bash .openforge/scripts/gradle.sh :services:cart-service:cart-service-app:test`
- V-PROD = `bash .openforge/scripts/gradle.sh :services:product-service:product-service-app:test`
- V-GW = `bash .openforge/scripts/gradle.sh :services:gateway:test`
- V-GATE = `node .claude/hooks/forge-verify.mjs --changed` (output recorded in the work folder)
One Gradle build at a time. Each implementation task includes its own tests; the verification command runs them.

---
## PART PR-A: fail-closed fingerprint, relay tolerance, AC-1..9b regression cover

### A1. Relay tolerates unknown outbox kinds (backend-engineer)
- Goal: in `OutboxRelay`, an unknown `kind` no longer throws from `Kind.valueOf`. Log ERROR with the row id, skip the row, leave it PENDING (never DONE), and continue the rest of the pass. Tests added in `OutboxRelayTest`: a PENDING row with kind `BOGUS` plus a valid row behind it; the valid row is delivered and the bogus row stays PENDING.
- Files: `OS/.../outbox/OutboxRelay.java`; `OS/src/test/.../outbox/OutboxRelayTest.java`.
- ACs: none (enabler for PR-C rollback). CTO: C1 (d).
- Verify: V-ORDER

### A2. Fail-closed cart fingerprint (backend-engineer)
- Goal: in `OrderService.cartSnapshot`, when `cartFingerprint` returns null, throw `CodedException(DEPENDENCY_UNAVAILABLE, "Your cart could not be read, please try again")` and log WARN naming the cart-service contract (`addedAt` missing in CartResponse). Thrown before any order row exists. `cartFingerprint` itself unchanged. Buy-now untouched. Tests in `OrderPlacementIntegrationTest`: a cart with a line lacking `addedAt` gives 503; two racing different-key placements both give 503 and the orders table has 0 rows for the user (and no stock/coupon/coins/wallet call).
- Files: `OS/.../service/OrderService.java`; `OS/src/test/.../OrderPlacementIntegrationTest.java`.
- ACs: AC-2. CTO: C5, (a).
- Verify: V-ORDER

### A3. Pin `addedAt` non-null in cart-service CartResponse (backend-engineer)
- Goal: one cart-service test that fails if `CartResponse` / its item DTO can serialise a null `addedAt` (mapping from a repository row with `added_at` set always yields non-null; DTO test on the contract).
- Files: one new test under `CS/src/test/...` (for example `CartResponseContractTest`).
- ACs: AC-2 (support). CTO: C5 (mandatory test).
- Verify: V-CART

### A4. Regression tests for concurrency and duplicates (qa)
- Goal: extend `OrderPlacementIntegrationTest` (Testcontainers), no production change.
  - AC-1: two different-key concurrent placements: exactly one order, the other 409 RESOURCE_CONFLICT; stock, coupon, coins, wallet each called once.
  - AC-3: same key and address, repeated and raced: original order returned, `created=false`, one order.
  - AC-4: first order CANCELLED, place again from the same cart: 201.
  - AC-5: cart cleared, same items re-added, place: 201.
  - AC-6: buy-now with two different keys: two orders.
  - AC-7: users A and B with identical carts: both 201.
- Files: `OS/src/test/.../OrderPlacementIntegrationTest.java` (plus fixtures if needed).
- ACs: AC-1, AC-3, AC-4, AC-5, AC-6, AC-7. CTO: none directly.
- Verify: V-ORDER

### A5. Breaker regression tests (qa)
- Goal: no production change.
  - `DependencyBreakerCapabilityTest`: after the open wait, half-open probes succeed and the breaker closes; calls resume (AC-9b). Keep the existing 4xx-not-counted and per-host isolation tests; add or confirm an explicit assertion that a tripped product breaker leaves the cart breaker closed (AC-9).
  - `OrderPlacementIntegrationTest`: for product and cart dependencies (and one of user/payment/promotion/inventory) an open breaker maps to 503 DEPENDENCY_UNAVAILABLE with no host call and no 3 s wait (AC-8); a 404/4xx keeps its mapped domain error (AC-9).
  - AC-9b compensation: coupon redeem fails with an open-breaker FeignException after the order is saved; the order ends CANCELLED and RESTORE_STOCK and RELEASE_COUPON (plus coins/wallet release where applicable) are enqueued.
- Files: `OS/src/test/.../DependencyBreakerCapabilityTest.java` (location per repo), `OS/src/test/.../OrderPlacementIntegrationTest.java`.
- ACs: AC-8, AC-9, AC-9b.
- Verify: V-ORDER

### A6. PR-A gate and commit boundary (qa)
- Goal: run V-ORDER and V-CART green, then V-GATE and record its output in `.openforge/work/F-20261119-11/verify-A.md`. Commit on the forge branch with a message naming PR-A. That commit is the PR-A head; it is handed to the CEO for an independent review at the SHA (the author never reviews). The Owner approves the push. State in the PR description that PR-A must be live before PR-C.
- Files: `.openforge/work/F-20261119-11/verify-A.md` (and the commit itself).
- ACs: AC-1..AC-9b (gate). CTO: C7, C1 (deploy ordering note).
- Verify: V-GATE

---
## PART PR-B: one batched product lookup (AC-10..AC-14)

### B1. product-service `detail=true` batch (backend-engineer)
- Goal: `GET /api/products/batch?ids=..&detail=true` returns `detail(p)` for each distinct known id, in request order, including inactive products, via new `ProductCatalogService.findDetailsByIds(ids)`. Add a `detail` overload that skips the variant stock call. Caps unchanged: at most 50 ids, 400 on non-UUID or more than 50, duplicates collapsed. Without the flag the response is unchanged. Tests:
  - parity: each element is exactly the `GET /{id}` `detail()` shape;
  - the lean default response is byte-identical to before (golden comparison);
  - inactive included, request order kept, duplicates collapsed, 51 ids gives 400, empty list and non-UUID behave as today.
- Files: `PS/.../web/ProductController.java`; `PS/.../service/ProductCatalogService.java`; tests under `PS/src/test/...`.
- ACs: supports AC-10, AC-11, AC-14. CTO: C4, (b).
- Verify: V-PROD

### B2. order-service batched product client and `draft` rewrite (backend-engineer)
- Goal:
  - New `ProductBatchFeignClient` (contextId `productBatchClient`, read timeout 5 s, connect 1 s, `List<ProductView> batch(@RequestParam ids, @RequestParam detail)`), reusing `ProductView`.
  - `draft` makes one `fetchProducts(distinct ids)` call and builds `Map<UUID, ProductView>`. A requested id missing from the result throws PRODUCT_UNAVAILABLE with today's message naming the product, before `store.save`.
  - Feign errors go through `RemoteCalls.unavailable("product-service")`.
  - The pricing loop is unchanged and reads `views.get(line.productId())`.
  - Config: client block in `application.yml`.
- Tests (in this task): unit/slice tests for `fetchProducts` (distinct ids, missing id, Feign error mapping to 503).
- Files: `OS/.../client/ProductBatchFeignClient.java` (new); `OS/.../service/OrderService.java`; `OS/src/main/resources/application.yml`; unit tests under `OS/src/test/...`.
- ACs: AC-10, AC-11, AC-13, AC-14 (impl). CTO: none directly.
- Verify: V-ORDER

### B3. Integration tests for batched lookup (qa)
- Goal: extend `OrderPlacementIntegrationTest`. No production change.
  - AC-10: a 50-line cart makes exactly 1 product-service request and the order equals one built from per-line fixtures (names, prices, variants, offers, currency).
  - AC-11: missing, inactive or unavailable product or variant gives the same codes as today and the message names the product.
  - AC-12: the batch call carries the request's Authorization bearer (assert the header at the stub; never anonymous or another user's); client-supplied price, name or image is ignored.
  - AC-13: the same product on two lines (different variants/offers) gives one id in the call and both lines priced correctly.
  - AC-14: the batch omits a requested id: PRODUCT_UNAVAILABLE and zero order rows (count rows).
- Files: `OS/src/test/.../OrderPlacementIntegrationTest.java`.
- ACs: AC-10, AC-11, AC-12, AC-13, AC-14.
- Verify: V-ORDER

### B4. Docs and backlog for PR-B (backend-engineer)
- Goal:
  - `docs/reference/endpoints.md`: document the `detail` flag.
  - `docs/techdebt`: remove TD-BE-06 and add a row to `remediation-plan.md`.
  - Backlog notes (C4): `/{id}` and `detail=true` expose inactive products to anonymous callers (Owner decision). (C6): add the batched inventory stock-rows endpoint; state that B-4 is unaffected.
- Files: `docs/reference/endpoints.md`; `docs/techdebt/README.md`; `docs/techdebt/remediation-plan.md`.
- ACs: none (documentation). CTO: C4 (backlog note), C6.
- Verify: V-GATE (docs-only; part gate re-runs it)

### B5. PR-B gate and commit boundary (qa)
- Goal: run V-PROD and V-ORDER green, then V-GATE with output recorded in `.openforge/work/F-20261119-11/verify-B.md`. Commit as the PR-B head (stacked on the PR-A head). The CEO reviews separately at this SHA; the Owner approves the push. Deploy note: product-service before order-service.
- Files: `.openforge/work/F-20261119-11/verify-B.md` (and the commit).
- ACs: AC-10..AC-14 (gate). CTO: C7.
- Verify: V-GATE

---
## PART PR-C: durable, scoped cart clear (AC-15..AC-20)

### C1. cart-service consumed-lines removal (backend-engineer)
- Goal:
  - `ConsumedLinesRequest{orderId, lines[{productId, variantId, offerId, quantity, addedAt}]}` in cart-service-api `dto/`.
  - `CartService.removeConsumed(userId, request)` takes `lockOrCreateCart(userId)`, then `CartRepository.deleteConsumed`, which deletes a row only in that user's cart matching product, `variant_key`, exact `offer_key` (via `CartRepository.key()`), `quantity` and `date_trunc('milliseconds', added_at)`. It returns the removed count. A repeat call is a no-op.
  - `orderId` is never used for scoping.
- Tests (repository, Testcontainers): a changed or newly added line survives (AC-18); user B's identical lines are untouched (AC-19); idempotent repeat returns 0; a matching line is removed.
- Files: `services/cart-service/cart-service-api/.../dto/ConsumedLinesRequest.java` (new); `CS/.../service/CartService.java`; `CS/.../repository/CartRepository.java`; tests under `CS/src/test/...`.
- ACs: AC-16 (idempotent), AC-18, AC-19. CTO: C3 (scoping tests).
- Verify: V-CART

### C2. Internal endpoint with hardening and auth tests (backend-engineer)
- Goal: `POST /internal/carts/{userId}/consumed` in new `InternalCartController` returns `{removed:n}`.
  - Validation: `userId` is a UUID path variable; `@Valid` on the body; `lines` capped at 50 (`@Size(max=50)`); quantity 1..99; the `orderId` field is not used for scoping.
  - Protected by the existing `/internal/**` TokenFilter in `InternalSecurityConfig`.
  - Controller tests (MockMvc or Testcontainers): 401 with no token, 401 with a wrong token, 401 when the token is not configured; 400 for a non-UUID userId, 51 lines, quantity 0 or 100; 200 with a valid token. Scoping through the endpoint: user B untouched, changed or added lines survive.
- Files: `CS/.../web/InternalCartController.java` (new); `CS/.../config/InternalSecurityConfig.java` (only if the matcher needs it); tests under `CS/src/test/...`.
- ACs: AC-18, AC-19 (via the endpoint). CTO: C3, (c).
- Verify: V-CART

### C3. Gateway assertion that `/internal/**` is not routed (qa)
- Goal: cite or add a test in `GatewayEdgeIntegrationTest` (existing, mentions "internal") asserting no gateway route matches `/internal/**` (and `/internal/carts/{id}/consumed` specifically returns 404/denied through the gateway, with `X-Internal-*` stripped). Add only a test if the existing one does not cover it; if it does, cite the test name in the verify file. No production change.
- Files: `services/gateway/src/test/java/com/acme/ecommerce/gateway/GatewayEdgeIntegrationTest.java` (only if needed).
- ACs: none. CTO: C3 (gateway), (c).
- Verify: V-GW

### C4. order-service outbox kind CLEAR_CART, relay per-kind limits, internal Feign client (backend-engineer)
- Goal:
  - `Outbox.Kind.CLEAR_CART`; aggregate = `UUID.nameUUIDFromBytes(("cart:"+userId).getBytes())`; idem key `CLEAR_CART:<orderId>`; new `Outbox.complete(idemKey)` sets the row DONE only if it is still PENDING.
  - `OutboxRelay` gets a `CLEAR_CART -> remote.clearCartNow(...)` case, with per-kind backoff and attempt limits: CLEAR_CART 240 s cap and 400 attempts then DEAD (logs "OUTBOX DEAD"); other kinds keep 3600 s and 20 attempts. The unknown-kind tolerance from A1 stays.
  - New `CartInternalFeignClient` (contextId `cartInternalClient`, `InternalFeignConfig`, timeouts 1 s / 3 s) calling `POST /internal/carts/{userId}/consumed`; `application.yml` client blocks.
- Tests: `OutboxRelayTest`: CLEAR_CART fail, then backoff of 240 s or less, then DONE; restart survival (row persists and is picked up by a new relay instance); DEAD at 400 attempts; other kinds keep their limits; `Outbox.complete` is a no-op when the row is not PENDING.
- Files: `OS/.../outbox/Outbox.java`; `OS/.../outbox/OutboxRelay.java`; `OS/.../client/CartInternalFeignClient.java` (new); `OS/src/main/resources/application.yml`; `OS/src/test/.../outbox/OutboxRelayTest.java`.
- ACs: AC-15, AC-16 (mechanism). CTO: C1 (relay still tolerant), design backoff bound.
- Verify: V-ORDER

### C5. OrderRemote.clearCart and OrderService wiring (backend-engineer)
- Goal:
  - `OrderRemote.clearCart(userId, orderId, lines)` enqueues the row, tries inline once, calls `Outbox.complete(idemKey)` on success, and logs and leaves PENDING on failure. `clearCartNow(payload)` is the strict call used by the relay.
  - `CartSnapshot` keeps the raw `CartItemResponse` list with `addedAt`. `OrderService` replaces `clearCartBestEffort` with `remote.clearCart(saved.userId(), saved.id(), consumedLines)`; `userId` comes from the saved order.
  - WALLET orders: the enqueue joins the markPaid/ORDER_PLACED transaction.
  - The 409 replay path enqueues (idempotent) for the existing order id and retries inline.
  - Buy-now never enqueues and never calls cart-service.
- Tests: unit tests of `OrderRemote.clearCart` (inline success marks DONE; failure leaves PENDING; the repeat enqueue is idempotent; `userId` taken from the order); buy-now enqueues nothing.
- Files: `OS/.../service/OrderRemote.java`; `OS/.../service/OrderService.java`; unit tests under `OS/src/test/...`.
- ACs: AC-15, AC-17, AC-18, AC-19, AC-20 (impl). CTO: none directly.
- Verify: V-ORDER

### C6. Integration tests for reliable cart clear (qa)
- Goal: extend `OrderPlacementIntegrationTest` (and an end-to-end relay test where practical). No production change.
  - AC-15: cart-service down at clear time: 201 and a PENDING CLEAR_CART row exists; it survives a simulated restart.
  - AC-16: cart-service recovers; the relay empties the cart with no user action, the row goes DONE, within the documented bound (240 s cap + 2 s poll + 10 s breaker open, under 5 min); a repeated clear is harmless.
  - AC-17: pending clear, user re-checks out the stale cart: no second order (409, fingerprint guard), the cart ends up empty.
  - AC-18: an item added after the order but before the deferred clear survives.
  - AC-19: the clear for user A's order does not touch user B's identical cart.
  - AC-20: buy-now: no CLEAR_CART row, no cart-service call.
- Files: `OS/src/test/.../OrderPlacementIntegrationTest.java` (plus fixtures).
- ACs: AC-15, AC-16, AC-17, AC-18, AC-19, AC-20.
- Verify: V-ORDER

### C7. Docs, runbook and ADR update for PR-C (backend-engineer)
- Goal:
  - `docs/reference/endpoints.md`: list `/internal/carts/**` as internal-only (X-Internal-Token, not gateway-routed).
  - A rollback runbook in `docs/` (for example `docs/runbooks/checkout-integrity-rollback.md`, location per existing docs layout) with: (i) stop or scale down the new order-service pods; (ii) `UPDATE orders.outbox SET status='DEAD' WHERE kind='CLEAR_CART' AND status='PENDING'`; (iii) start the old image. Note that (ii) is only a backstop once PR-A is live, and that PR-A must be live before PR-C. Also record the deploy order and the DEAD retry through `OutboxAdminController`.
  - ADR 0010: confirm the same runbook and the PR-A-before-PR-C statement are present; edit only if they differ.
  - `docs/techdebt`: remove TD-BE-03, TD-BE-07 and TD-BE-15 and add rows to `remediation-plan.md`.
- Files: `docs/reference/endpoints.md`; `docs/runbooks/...` (new); `.openforge/knowledge/decisions/0010-checkout-integrity.md` (if needed); `docs/techdebt/README.md`; `docs/techdebt/remediation-plan.md`.
- ACs: none (documentation). CTO: C2, C3 (docs), C1 (PR-A before PR-C statement).
- Verify: V-GATE (docs-only; part gate re-runs it)

### C8. PR-C gate and commit boundary (qa)
- Goal: run V-CART, V-ORDER, V-GW green (one at a time), then V-GATE with output recorded in `.openforge/work/F-20261119-11/verify-C.md`. Commit as the PR-C head (stacked on the PR-B head). The CEO reviews separately at this SHA; the Owner approves the push. PR description states: PR-A live first; deploy cart-service then order-service; rollback runbook link.
- Files: `.openforge/work/F-20261119-11/verify-C.md` (and the commit).
- ACs: AC-15..AC-20 (gate). CTO: C7.
- Verify: V-GATE

---
## Coverage: AC to tasks
| AC | Tasks |
|---|---|
| AC-1 | A4 |
| AC-2 | A2, A3 |
| AC-3 | A4 |
| AC-4 | A4 |
| AC-5 | A4 |
| AC-6 | A4 |
| AC-7 | A4 |
| AC-8 | A5 |
| AC-9 | A5 |
| AC-9b | A5 |
| AC-10 | B1, B2, B3 |
| AC-11 | B1, B2, B3 |
| AC-12 | B3 (token forwarded by the unchanged common-web interceptor; B2 does not alter it) |
| AC-13 | B2, B3 |
| AC-14 | B1, B2, B3 |
| AC-15 | C4, C5, C6 |
| AC-16 | C1, C4, C6 |
| AC-17 | C5, C6 |
| AC-18 | C1, C2, C5, C6 |
| AC-19 | C1, C2, C5, C6 |
| AC-20 | C5, C6 |

Gate tasks A6, B5, C8 re-confirm their part's ACs.

## Coverage: CTO conditions to tasks
| Condition | Tasks |
|---|---|
| C1 relay tolerance in PR-A (OutboxRelayTest); PR-A live before PR-C stated in PR-C and runbook | A1, A6, C4 (tolerance retained), C7, C8 |
| C2 PR-C rollback runbook (docs and ADR 0010) | C7 |
| C3 internal endpoint hardening: validation, 3 auth tests, scoping tests, docs, gateway assertion | C1, C2, C3, C7 |
| C4 `detail=true` parity and lean-default byte-identical tests; backlog note on inactive exposure | B1, B4 |
| C5 AC-2 WARN naming the contract; cart-service `addedAt` test; two-racer 503 test with 0 orders | A2, A3 |
| C6 backlog: B-4 unaffected; batched inventory stock-rows endpoint | B4 |
| C7 per PR: forge-verify output recorded, independent review at the SHA, Owner approval for the push | A6, B5, C8 |
| Rulings (a) AC-2 fail-closed; (b) detail opt-in with forwarded token; (c) internal endpoint protection; (d) relay tolerance moved to PR-A; (e) 3 stacked PRs | A2; B1, B3; C2, C3; A1; this part structure |

## Task count
19 tasks: PR-A 6 (A1..A6), PR-B 5 (B1..B5), PR-C 8 (C1..C8).
