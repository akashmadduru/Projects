
## PR-C fix guard demos

### Item 2: CartInternalFeignClient must not carry Authorization (stripper disabled)
`CartInternalFeignClientHeadersTest.carriesInternalTokenAndNoBearer`, with the Authorization removal commented out in `NoUserTokenFeignConfig`:

```
java.lang.AssertionError:
Expecting actual:
  {"Authorization"=["Bearer user-jwt"], "Content-Length"=["2"], "X-Correlation-Id"=["..."], "X-Internal-Token"=["secret-token"]}
not to contain key:
  "Authorization"
```

### Item 1: enqueue failure must not fail a placed order (run on 71bba97 production code)
`CartClearIntegrationTest.enqueueFailureDoesNotFailACodOrder` (COD, Outbox.enqueue(CLEAR_CART) throws):
```
java.lang.IllegalStateException: outbox insert failed
	at com.acme.ecommerce.order.outbox.Outbox.enqueue(Outbox.java:48)
	at com.acme.ecommerce.order.service.OrderRemote.enqueueClearCart(OrderRemote.java:181)
	at com.acme.ecommerce.order.service.OrderRemote.clearCart(OrderRemote.java:210)
	at com.acme.ecommerce.order.service.OrderService.place(OrderService.java:422)
```
`enqueueFailureKeepsTheConflict` (409 path):
```
Expecting actual throwable to be an instance of: CodedException
but was: java.lang.IllegalStateException: outbox insert failed
```

### Item 3: removeConsumed must not create a cart (run on 71bba97 production code)
`CartConsumedLinesIntegrationTest.noCartCreatesNothing`:
```
expected: 0
 but was: 1      (cart.carts rows for the user, at CartConsumedLinesIntegrationTest.java:89)
```

### Item 4: CLEAR_CART 4xx goes DEAD at once (run on 71bba97 production code)
`OutboxRelayTest.permanentClientErrorGoesDeadImmediately`:
```
expected: "DEAD"
 but was: "PENDING"
```

### Item 5: AC-18 / AC-15 tests
AC-18 now fails the old inline-read shape by construction: the cart mock holds the later line before the relay delivers, and the test checks the delivered payload names only the consumed line. AC-15 asserts the row is due immediately (<= 0 s) instead of the always-true `<= 240`.

## CEO verify at b7f5b97462288d77ce44ea7384dc301b1d227ef9 (final head, 2026-11-23)
```
forge-verify: 41 changed file(s) in C:/Users/Akash/Desktop/Projects/.openforge/worktrees/F-20261119-11 (base 820e5a8d1387)
  PASS env reference --check (exit 0)
  PASS gradle :services:cart-service:cart-service-app:test :services:gateway:test :services:order-service:order-service-app:test :services:product-service:product-service-app:test (exit 0)
      gradle.sh: waiting for another build (pid 357) to finish
      BUILD SUCCESSFUL in 1m 36s
      gradle_rc=0
forge-verify: PASS (2 checks)
rc=0
```
- Production lines: A 67, B 104, C 316 (CEO recount).

## QA (b7f5b97)
XML timestamps (UTC): order-service 2026-11-23T05:44Z to 05:53Z (rerun at this head, 0 failures, 0 errors, 0 skipped in every file cited). cart-service 2026-11-21T13:52Z to 13:55Z, product-service 2026-11-21T13:01Z to 13:05Z, gateway 2026-11-21T12:00Z to 12:02Z: gradle reported those test tasks up to date at b7f5b97, so those XMLs are from the last run of unchanged inputs; every cited testcase is present with no failure element.
AC-1: pass — "AC-1: two different-key placements race: one order, one 409, each charge once" and "two checkouts of one cart at the same moment create one order" (XML: TEST-com.acme.ecommerce.order.service.OrderPlacementIntegrationTest.xml)
AC-2: pass — "a cart line without addedAt gives 503 and no order row, before any other call" and "two different-key placements of a cart without addedAt both get 503; no order row"; the guard is fail-closed (503, no order) rather than a second protected path; cart-side producer contract "every line of a read cart has a non-null addedAt (added, merged, updated)" (XML: TEST-com.acme.ecommerce.order.service.OrderPlacementIntegrationTest.xml; TEST-com.acme.ecommerce.cart.contract.CartResponseContractTest.xml)
AC-3: pass — "AC-3: the same key and address, repeated, returns the original order" and "AC-3: the same key and address, raced, gives one order and one created=false" (XML: TEST-com.acme.ecommerce.order.service.OrderPlacementIntegrationTest.xml)
AC-4: pass — "AC-4: after the first order is CANCELLED the same cart can be ordered again" (XML: TEST-com.acme.ecommerce.order.service.OrderPlacementIntegrationTest.xml)
AC-5: pass — "AC-5: the cart cleared and the same items re-added is a new cart, not blocked" (XML: TEST-com.acme.ecommerce.order.service.OrderPlacementIntegrationTest.xml)
AC-6: pass — "AC-6: buy-now with two different keys makes two orders and never reads the cart" (XML: TEST-com.acme.ecommerce.order.service.OrderPlacementIntegrationTest.xml)
AC-7: pass — "AC-7: two users with identical carts both place; one user's order never blocks another" (XML: TEST-com.acme.ecommerce.order.service.OrderPlacementIntegrationTest.xml)
AC-8: pass — "AC-8: an open product-service breaker gives 503 at once, with no host call", cart, user, promotion variants likewise, "AC-8: an open payment-service breaker gives 503 at once; the order is CANCELLED", "AC-8: an open inventory-service breaker gives 503 at once; the order is CANCELLED"; unit level "ten 5xx answers open the breaker, then calls fail fast without reaching the host" and "each service has its own breaker; I/O errors count as failures" (XML: TEST-com.acme.ecommerce.order.service.OrderPlacementIntegrationTest.xml; TEST-com.acme.ecommerce.order.client.DependencyBreakerCapabilityTest.xml)
AC-9: pass — "AC-9: a 404 from product-service keeps its domain error and never opens the breaker", "AC-9: a 404 from user-service keeps ADDRESS_NOT_FOUND and never opens the breaker", "4xx answers are the dependency working: they never open the breaker", "AC-9: a tripped product-service breaker leaves the cart-service breaker closed" (XML: TEST-com.acme.ecommerce.order.service.OrderPlacementIntegrationTest.xml; TEST-com.acme.ecommerce.order.client.DependencyBreakerCapabilityTest.xml)
AC-9b: pass — "AC-9b: after the open wait, half-open probes succeed, the breaker closes, calls resume"; compensation: "coupon redeem hits an open breaker after the order is saved: cancelled, stock and coupon released", "coin redeem hits an open breaker after the coupon was taken: stock, coupon and coins released", "wallet debit refused by an open payment breaker: cancelled, stock restored, wallet reversal recorded", "payment create refused after a partial wallet debit: cancelled, wallet credited back" (XML: TEST-com.acme.ecommerce.order.client.DependencyBreakerCapabilityTest.xml; TEST-com.acme.ecommerce.order.service.OrderBreakerCompensationIntegrationTest.xml)
AC-10: pass — "AC-10: a 50-line cart makes exactly one product-service request, priced per line"; parity with the per-line shape: "detail=true: each element is exactly the GET /{id} detail shape" and "detail=true parity on a product with a variant and an offer; the one difference is variants[].availableQuantity, which the batch omits and /{id} carries" (XML: TEST-com.acme.ecommerce.order.service.OrderPlacementIntegrationTest.xml; TEST-com.acme.ecommerce.product.web.ProductBatchDetailIntegrationTest.xml)
AC-11: pass — "AC-11: an inactive product keeps PRODUCT_UNAVAILABLE and the message names it", "AC-11: an inactive or unknown variant keeps PRODUCT_UNAVAILABLE naming the product", "AC-11: a product with variants but no variant on the line is VALIDATION_FAILED"; fetch level "a Feign 5xx is DEPENDENCY_UNAVAILABLE (503); a 404 is PRODUCT_UNAVAILABLE" (XML: TEST-com.acme.ecommerce.order.service.OrderPlacementIntegrationTest.xml; TEST-com.acme.ecommerce.order.service.OrderServiceFetchProductsTest.xml)
AC-12: pass — "AC-12: the batch call carries the request's Authorization bearer, never another" and "AC-12: client-supplied cart price, name and image are ignored; the catalog wins" (XML: TEST-com.acme.ecommerce.order.service.OrderPlacementIntegrationTest.xml)
AC-13: pass — "AC-13: the same product on two lines sends one id and prices both lines" (XML: TEST-com.acme.ecommerce.order.service.OrderPlacementIntegrationTest.xml)
AC-14: pass — "AC-14: a batch that omits a requested id is PRODUCT_UNAVAILABLE with no order row"; fetch level "an id missing from the result is PRODUCT_UNAVAILABLE" (XML: TEST-com.acme.ecommerce.order.service.OrderPlacementIntegrationTest.xml; TEST-com.acme.ecommerce.order.service.OrderServiceFetchProductsTest.xml)
AC-15: pass — "AC-15: cart-service down at clear time: 201 and a durable PENDING CLEAR_CART row"; restart survival "a CLEAR_CART row survives a restart: a new relay instance delivers it" (XML: TEST-com.acme.ecommerce.order.outbox.CartClearIntegrationTest.xml; TEST-com.acme.ecommerce.order.outbox.OutboxRelayTest.xml)
AC-16: pass — "AC-16: cart-service recovers: the relay clears the cart with no user action", "AC-15/16: a failed clear stays PENDING, retries within 240 s, then goes DONE", cart side repeat "AC-16: a repeated call is a no-op and returns 0" (XML: TEST-com.acme.ecommerce.order.outbox.CartClearIntegrationTest.xml; TEST-com.acme.ecommerce.order.outbox.OutboxRelayTest.xml; TEST-com.acme.ecommerce.cart.service.CartConsumedLinesIntegrationTest.xml)
AC-17: pass — "AC-17: a pending clear, then checkout of the stale cart: no second order, cleared"; also "the same cart placed again with a new key is refused, and the clear is retried" (XML: TEST-com.acme.ecommerce.order.outbox.CartClearIntegrationTest.xml; TEST-com.acme.ecommerce.order.service.OrderPlacementIntegrationTest.xml)
AC-18: pass — "AC-18: the clear names only the lines the order consumed, never a later item"; cart side "AC-18: a line added after the order survives", "AC-18: a line whose quantity changed after the order survives", "AC-18: a different offer or variant of the same product is a different line", endpoint "AC-18 through the endpoint: a changed and a later line survive" (XML: TEST-com.acme.ecommerce.order.outbox.CartClearIntegrationTest.xml; TEST-com.acme.ecommerce.cart.service.CartConsumedLinesIntegrationTest.xml; TEST-com.acme.ecommerce.cart.web.InternalCartControllerTest.xml)
AC-19: pass — "AC-19: each user's clear is sent for that user and that order, never crossed"; cart side "AC-19: another user's identical lines are untouched" and "AC-19 through the endpoint: another user's cart is untouched" (XML: TEST-com.acme.ecommerce.order.outbox.CartClearIntegrationTest.xml; TEST-com.acme.ecommerce.cart.service.CartConsumedLinesIntegrationTest.xml; TEST-com.acme.ecommerce.cart.web.InternalCartControllerTest.xml)
AC-20: pass — "AC-20: buy-now records no CLEAR_CART row and never calls cart-service" and "AC-20: buy-now never clears the cart and enqueues nothing" (XML: TEST-com.acme.ecommerce.order.outbox.CartClearIntegrationTest.xml; TEST-com.acme.ecommerce.order.service.OrderPlacementIntegrationTest.xml)
Gaps: none; all 21 ACs have at least one passing testcase. Note on AC-2: the implemented behaviour is 503 and no order row for a cart line lacking addedAt (fail closed), not "one order exists"; the tests prove no unprotected path, which satisfies the AC intent, but the literal "only one order exists" outcome is zero orders.
