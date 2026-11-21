# Requirements F-20261119-11: checkout integrity and resilience (O-06)

## 1. Problem and users affected
Checkout in order-service makes one product call per cart line, clears the cart with a fire-and-forget call, and
(per the register) lacked breakers and double-order protection. Affected: shoppers (stale cart after paying, slow
checkout, errors when a dependency is down) and the business (duplicate orders, money held on duplicates).

## Verified current state (base 820e5a8)
- TD-BE-15: DONE in the stacked chain. V12 adds `cart_fingerprint` plus a partial unique index (user, fingerprint, status<>CANCELLED); a losing racer gets 409 RESOURCE_CONFLICT. Needs regression ACs only, plus one gap: carts whose lines lack `addedAt` have a null fingerprint and get NO protection (AC-2).
- TD-BE-07: DONE. `DependencyBreakerCapability` (resilience4j via `spring-cloud-starter-circuitbreaker-resilience4j`, already in the version catalog) wraps every Feign client, one breaker per host. Needs verification ACs only (AC-8, AC-9); no new dependency.
- TD-BE-06: OPEN. `OrderService.draft` calls `products.findById` once per line, sequentially. A `GET /api/products/batch?ids=` (max 50, request order) exists but returns the lean `ProductResponse`; whether it carries what checkout needs (variants, offers, buy box, active flag, sale price) is unverified.
- TD-BE-03: OPEN. `clearCartBestEffort` only logs on failure; a 409 retry re-attempts the clear, but nothing else does.

## 2. Scope
In: cart-based and buy-now placement in order-service; its calls to product-service and cart-service; the
cart-clear step; the breaker behaviour on all order-service Feign clients.
## Non-goals
No new service, datastore or vendor (outbox reuses the existing order DB and event relay if used). No change to
pricing rules, payment, coupons, coins, wallet. No cart-service product validation (TD-BE-09). No gateway breaker
changes. No UI work. Design choice (batch endpoint vs executor, outbox vs marker) is the architect's.

## 3. Acceptance criteria
Concurrency and duplicates (TD-BE-15)
- AC-1: Given a user with one cart, when two placements with different Idempotency-Keys run concurrently, then exactly one order is created and the other gets 409 RESOURCE_CONFLICT; stock, coupon, coins and wallet are charged once.
- AC-2: Given a cart whose lines have no `addedAt`, when two different-key placements race, then still only one order exists (no unprotected path).
- AC-3: Given the same Idempotency-Key and same address, when placement repeats or races, then the original order is returned (200/replay, `created=false`), never a second order.
- AC-4: Given the first order was CANCELLED (abandoned or failed), when the user places again from the same cart, then it succeeds (201).
- AC-5: Given the cart was cleared and the same items were re-added, when the user places, then it succeeds (201); a deliberate repeat order is not blocked.
- AC-6: Given a buy-now request (explicit items), when two different-key requests are sent, then both succeed (the cart guard applies to cart checkout only).
- AC-7: Given user A and user B with identical carts, when both place, then both succeed; a cart is never blocked by another user's order.

Circuit breakers (TD-BE-07)
- AC-8: Given a dependency (product, cart, user, payment, promotion, inventory) fails with I/O errors or 5xx past the failure threshold, when the next checkout calls it, then the call fails fast (no 3 s read wait) with 503 `DEPENDENCY_UNAVAILABLE`.
- AC-9: Given a dependency answers 4xx (out of stock, expired coupon, 404), then the breaker does not count it and the mapped 4xx domain error is unchanged. One tripped dependency does not open another's breaker.
- AC-9b: Given the breaker was open and the wait elapses, when a probe succeeds, then calls resume; a placement failing on an open breaker after the order row exists still runs the existing compensation (order cancelled, stock/coupon/coins/wallet released).

Batched product lookup (TD-BE-06)
- AC-10: Given a 50-line cart, when placed, then product-service receives a bounded number of product requests (at most 1 for the product data, not 50) and the order is identical (names, prices, variants, offers, currency) to a per-line lookup.
- AC-11: Given a line with a missing, inactive or unavailable product or variant, then the same error codes as today apply (404-style PRODUCT_UNAVAILABLE / VALIDATION_FAILED) and the message names the product.
- AC-12: Given the caller's bearer token, then the product lookup is made with that user's token (never anonymous, never another user's) and prices still come only from product-service; client-supplied cart price/name/image is ignored.
- AC-13: Given a cart with the same product on two lines (different variants or offers), then both lines are priced correctly from one lookup per distinct product.
- AC-14: Given a batch result that omits a requested id, then placement fails with PRODUCT_UNAVAILABLE and no order row is left live.

Reliable cart clear (TD-BE-03)
- AC-15: Given cart-service is down at clear time, when the order commits, then the order is still returned (201) and the clear is durably recorded as pending (survives an order-service restart).
- AC-16: Given a pending clear, when cart-service recovers, then the cart is emptied without any user action, within a documented bound, and the pending record is closed; repeated clear is harmless.
- AC-17: Given a pending clear and the user returns, when they open or re-check-out the stale cart, then no second order is created (AC-1 holds) and the cart ends up empty.
- AC-18: Given a user adds a new item after the order but before the deferred clear runs, then the new item is not lost (the clear removes only the items the order consumed, or is skipped when the cart changed).
- AC-19: Given a clear for order X of user A, then it can only affect user A's cart.
- AC-20: Given buy-now placement, then the cart is not touched.

## 4. Edge cases and abuse
- Rapid double-click or retried POST with a lost response; two browser tabs; client retry after 503.
- Replayed request after cancellation; cart edited mid-checkout (fingerprint changes: treated as a new cart, so a stale-read race cannot be exploited to order a mutated cart twice).
- Cart of 51 lines still rejected (existing MAX_LINES); quantity cap 99 unchanged.
- Batch with duplicate ids, empty list, non-UUID id (400); an attacker cannot enlarge the batch beyond 50.
- Deferred clear must not become a way to wipe another user's or a newer cart.
- Order-service restart between commit and clear; clear job running on several instances at once.

## 5. Open questions
None blocking. Defaults assumed: (a) the deferred clear completes within 5 minutes of cart-service recovery; (b) the batch response may need extending for checkout fields, which is an architect call and stays inside product-service; (c) breaker thresholds stay as shipped (50% / 20 calls / 10 s open).
