# F-20261119-19 Keep the checkout Idempotency-Key on ambiguous replies (408/409/429)

## Objective (CEO-originated from the F-18 review backlog; in the Owner's "security and money paths" scope)
The storefront checkout (apps/product-catalog/src/pages/CheckoutPage.tsx, placeOrder catch block, ~line 359) throws away the Idempotency-Key on any ApiError with status < 500. For 408, 429 and some 409s, the first request may already have been processed or still be in flight, so a retry with a new key can create a second order or charge. Rotate the key only on definitive rejections; keep it on ambiguous replies. Add tests for each status class.

## Context
- Server (order-service OrderStore.findReplay): a repeated key for the same user returns the existing order (replay); a key reused with a different addressId gives 409 IDEMPOTENCY_KEY_REUSED. So keeping the key on an ambiguous reply is safe: the retry either replays the order or is processed once.
- Known definitive 409s include OUT_OF_STOCK (with items) and PAYMENT_METHOD_UNAVAILABLE; IDEMPOTENCY_KEY_REUSED must rotate, or every retry fails. Other 409 codes in order-service must be classified (CPO/architect: grep ErrorCode / problem codes).
- The client already uses a new key when the request shape changes (attempt.current.shape), and keeps it on 5xx/network errors.
- F-18 (305d540) added CheckoutPage.place.test.tsx with the existing key tests; extend them. Stacked on F-18 for that reason.
- Non-goals: server changes; the checkout login guard (separate backlog item); the Playwright e2e.
- Branch `forge/F-20261119-19-checkout-idempotency-retry`, worktree `.openforge/worktrees/F-20261119-19`, base F-18 head 305d540.

## Blocking questions
None. Default: if the 409 code set cannot be read from the code, treat an unknown 409 as ambiguous (keep the key). A wrong keep is safe because the server replays or rejects; a wrong rotate is what causes duplicate orders.
