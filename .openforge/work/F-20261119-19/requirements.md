# F-20261119-19 requirements: keep the checkout Idempotency-Key on ambiguous replies

## 1. Problem and users affected
Storefront checkout (`apps/product-catalog/src/pages/CheckoutPage.tsx`, `placeOrder` catch) discards the
Idempotency-Key on every ApiError with status < 500. On 408, 429 and some 409s the first request may have
been processed or still be running, so retrying with a new key can create a second order or charge.
Affected: shoppers placing orders (COD, card, wallet, Buy Now) on slow or rate-limited connections.

## 2. Scope and non-goals
In scope: the key-retention decision in `placeOrder` for a failed `orderApi.place`, and tests in
`apps/product-catalog/src/pages/__tests__/CheckoutPage.place.test.tsx`. Class S: one client file plus tests.
Non-goals: any server or gateway change; the checkout login guard; the Playwright e2e; new UI copy
(the existing `errorMessage(err)` text is shown unchanged); automatic retries (the user presses the button again).

## 3. Classification of place-call errors (ROTATE = new key on next attempt, KEEP = same key)
Server replay (same user + key returns the existing order) is in `OrderService.java` `place` (first step)
and `OrderStore.findReplay`, so KEEP is safe: the retry is replayed or processed once.

| Status / code | Class | Source |
|---|---|---|
| 400 VALIDATION_FAILED | ROTATE | libs/common-core/.../error/ErrorCode.java |
| 401, 403 (auth, before the handler) | ROTATE | gateway security (brief) |
| 404 ADDRESS_NOT_FOUND | ROTATE | ErrorCode.java; OrderService.java |
| 422 IDEMPOTENCY_KEY_REUSED (is 422, not 409; DomainRuleViolation) | ROTATE | order-service OrderStore.java:118-120 |
| 422 PAYMENT_METHOD_UNAVAILABLE (RISK and non-RISK) | ROTATE | ErrorCode.java; OrderService.java:237-249; RiskBlockedException.java |
| 422 DOMAIN_RULE_VIOLATION (e.g. coin cap), COUPON_INVALID/EXPIRED/NOT_APPLICABLE | ROTATE | ErrorCode.java; OrderService.java:195; order RemoteCalls.java |
| 409 OUT_OF_STOCK (with or without `items`) | ROTATE | order OutOfStockException.java; RemoteCalls.java:36 |
| 409 PRODUCT_UNAVAILABLE, CART_EMPTY, CURRENCY_MISMATCH | ROTATE | ErrorCode.java; OrderService.java:177,480,583-757 |
| 409 INSUFFICIENT_BALANCE, COUPON_USAGE_LIMIT | ROTATE | ErrorCode.java; OrderService.java:222; RemoteCalls.java |
| Other 4xx not listed (e.g. 405, 413, 415) | ROTATE | existing behaviour (status < 500) |
| 408 (any code) | KEEP | brief; not emitted by order-service, may come from a proxy |
| 429 RATE_LIMITED | KEEP | gateway RateLimitWebFilter.java:116; ErrorCode.java:120 |
| 409 RESOURCE_CONFLICT ("cart already ordered") | KEEP | OrderService.java:283-285 |
| 409 with an unknown, missing or in-progress code | KEEP | brief default |
| 5xx incl. 503 DEPENDENCY_UNAVAILABLE | KEEP | existing; ErrorCode.java:126 |
| Network failure / non-ApiError | KEEP | existing |

## 4. Acceptance criteria
Common setup: checkout page with a valid cart and address; `orderApi.place` mocked; user presses Place Order, then presses it again with nothing changed.
- AC-1: Given place rejects with 408, when the user retries unchanged, then the second call's key equals the first.
- AC-2: Given 429 RATE_LIMITED, when retried unchanged, then the same key is sent.
- AC-3: Given 409 RESOURCE_CONFLICT, when retried unchanged, then the same key is sent.
- AC-4: Given 409 with an unknown code (e.g. `SOMETHING_NEW`) or no code, when retried, then the same key is sent.
- AC-5: Given 400 VALIDATION_FAILED, 401, 403 or 404, when retried unchanged, then a different key is sent (one test per status).
- AC-6: Given 422 IDEMPOTENCY_KEY_REUSED, when retried, then a different key is sent.
- AC-7: Given 409 OUT_OF_STOCK, 409 PAYMENT_METHOD_UNAVAILABLE (non-RISK reason), 422 PAYMENT_METHOD_UNAVAILABLE, 409 INSUFFICIENT_BALANCE, CART_EMPTY, PRODUCT_UNAVAILABLE, CURRENCY_MISMATCH, COUPON_USAGE_LIMIT, then each yields a different key on retry (table-driven).
- AC-8: Given 422 DOMAIN_RULE_VIOLATION or a COUPON_* 422, when retried, then a different key is sent.
- AC-9: Given 500, 502, 503 and a network error, when retried, then the same key is sent (existing tests stay green).
- AC-10: For every KEEP and ROTATE row, the user sees the same message as today (`errorMessage(err)` in the alert) and the Place button is re-enabled; stock-shortfall list behaviour for OUT_OF_STOCK is unchanged.
- AC-11: Each status class in the table (408, 429, 409 RESOURCE_CONFLICT, 409 unknown, 4xx rotate, 409 rotate codes, 422 rotate codes, 5xx, network) has a test that fails if the rule is reverted to `status < 500` rotates-all or to keep-all (verified by mutating the rule and seeing the tests fail).
- AC-12: Existing behaviour unchanged: changing the request shape after a KEEP failure sends a new key; a successful place clears the key so a later place sends a new key; PAYMENT_METHOD_UNAVAILABLE with reason RISK still switches to CARD, sets the COD-risk state, shows the existing message and rotates the key.
- AC-13: No file outside `CheckoutPage.tsx` and its tests changes (no server, gateway or shared-lib diff).
- AC-14: `node .claude/hooks/forge-verify.mjs --changed` passes and the output is recorded.

## 5. Edge cases and abuse cases
- Error body has a `code` that differs in case or is absent on a 409: treated as unknown, KEEP.
- 409 OUT_OF_STOCK whose `items` is missing: still ROTATE (code decides, not `items`).
- 408/429 repeated many times: key stays the same; server returns at most one order.
- KEEP followed by the user editing coupon, coins, address, payment method or Buy Now qty: shape changes, new key, which is intended.
- A KEEP reply then a definitive ROTATE reply: key is cleared at that point; the next attempt gets a new key.
- Double-click while placing remains blocked (existing); rotation must not happen while a request is in flight.
- Spoofed or odd status (e.g. 409 from a proxy HTML page, non-JSON body): no problem code, so KEEP for 409, existing rule for others.
- Replayed order returned after a KEEP retry: user is navigated normally and the key is cleared.

## 6. Open questions
None blocking. Note for the CEO: the brief says IDEMPOTENCY_KEY_REUSED is 409; the code returns 422 (OrderStore.java:118). Classification is by code, so the rule must match the code regardless of status. RESOURCE_CONFLICT is classed KEEP (conservative; safe either way because the server refuses a second order for the same cart).
