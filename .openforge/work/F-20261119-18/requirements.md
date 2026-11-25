# Requirements F-20261119-18: storefront checkout, Google callback and wishlist tests (TD-TEST-02)

## 1. Problem and users affected
CheckoutPage, AuthCallbackPage, GoogleSignInButton and wishlistStore in apps/product-catalog have no tests. Order placement, Google sign-in fallbacks and wishlist sync can regress unnoticed. Affected: shoppers (orders, sign-in, wishlist) and developers changing these files.

## 2. Scope and non-goals
Scope: vitest component and store tests in apps/product-catalog, following the style of stores/*.test.ts and components/__tests__/CheckoutCoupons.test.tsx (network mocked via msw or module mocks). Plus a register update.
Non-goals: no Playwright e2e (stays open in TD-TEST-02); no production change. A test that exposes a real bug is escalated to the CEO and not fixed silently. No new dependency. Out of scope: ExchangeOffer, PaymentPanel and CheckoutCoupons internals (mocked or covered elsewhere), and any route-guard test (the page itself has no auth gate).

## 3. Acceptance criteria
Checkout (CheckoutPage)
- AC-1: Given a cart with a snapshot line and one address, when the page loads, then the first address is preselected, the order summary shows the line name, Qty and formatted line total, and Subtotal equals the sum of price x quantity.
- AC-2: Given an empty cart (no buyNow), when the page renders, then it shows "Your cart is empty" with a "Continue shopping" link to "/", and the address list is not shown. Given ?buyNow=<id> for a product that fails to load, it shows "That product is not available".
- AC-3: Given ?buyNow=<id>&qty=<n>, when placing, then orderApi.place receives an `items` array (productId, quantity, plus variantId/offerId when in the URL), and the cart store is not re-attached. qty is clamped to 1..99 and non-numeric falls back to 1.
- AC-4: Given a cart order paid by COD (delivery estimate has codAvailable true, total within the limit), when "Place order" is clicked, then place is called with paymentMethod "COD" and the selected addressId, then the page navigates to /orders/<id> with replace and state {placed:true}, and the cart is re-attached from the server.
- AC-5: Given CARD selected and the server returns an order with `payment` and status PENDING_PAYMENT, when placed, then the page shows "Complete your payment" with the short order id and the server totalAmount, renders PaymentPanel, and offers "Pay later from the order page". It does not navigate until PaymentPanel calls onSubmitted, which goes to /orders/<id>?payment=return.
- AC-6: Given a wallet balance covering the whole total, when the Wallet option is chosen, then place is called with paymentMethod "WALLET" and useWallet true. Given a partial balance, the Wallet radio is disabled and the "use wallet" checkbox shows "Paid from wallet" and "To pay" (or "Due on delivery" for COD) with place sending useWallet true.
- AC-7: Given a validated coupon, coins redeemed and a delivery estimate, then the summary shows the Discount, Coins and Shipping rows, and Total = subtotal - discount - coins + shipping (never negative). The discount comes from the server validation response. Place sends couponCode and coinsToRedeem. A Plus member shows Shipping "Free". An INR order shows an "Includes GST (18%)" line, and a non-INR order shows "Tax included".
- AC-8: Given COD is unavailable (estimate.codAvailable false, no estimate, or total above 50000 INR / 1000 other), then the COD radio is disabled with the matching reason text. If COD was selected and becomes unavailable, the method reverts to CARD.
- AC-9: Given a user with no saved addresses, then the address form is shown and the place button is disabled. Given a pending form or no selected address, the button is also disabled. Given the address load fails, an error alert is shown and no place button is rendered. Given mixed currencies in the cart, an error alert is shown and the button is disabled.
- AC-10: Given the delivery estimate fails, then the error message is shown, the Shipping row shows "—", and COD is disabled. A failing wallet, coins, membership, payment-config or available-coupons call hides only its own controls, and checkout still works.
- AC-11: Given place is in flight, then the button is disabled and reads "Placing order...", so a second click makes no second request.
- AC-12: Given place fails with a 4xx ApiError, then the problem message is shown, the button is re-enabled, and the retry sends a NEW Idempotency-Key. Given a 5xx or network failure, the retry reuses the SAME key. A changed request shape (for example a different payment method) gets a new key. A success clears the key.
- AC-13: Given an OUT_OF_STOCK problem with items, then an alert lists "<name>: you asked for N, only M available", with the product name resolved from the lines (fallback "Item"). Given PAYMENT_METHOD_UNAVAILABLE with reason RISK, then the method switches to CARD, COD stays disabled with the risk reason, and the message "Cash on Delivery is not available for this order. Please choose online payment." is shown.

Google callback and button
- AC-14: Given ?error=<code>, when AuthCallbackPage mounts, then it navigates with replace to /login?error=<encoded code>&next=<encoded stored redirect>, and the stored redirect is consumed.
- AC-15: Given no error and status "authenticated", then it navigates with replace to the stored post-login path (default per takePostLoginRedirect). Given mfaToken set, it navigates to /login?next=... for the second-factor step, and mfaToken takes precedence over authenticated.
- AC-16: Given status "anonymous" with no error (session could not be restored), then it navigates to /login?error=AUTH_OIDC_LOGIN_FAILED&next=.... Given a loading status, no navigation happens and "Signing you in..." is shown as role=status.
- AC-17: Given the Google button is enabled, when clicked, then rememberPostLoginRedirect(next) is called with the prop value and window.location.assign is called with googleSignInUrl(). The custom label is rendered, and the default is "Continue with Google".
- AC-18: Given VITE_GOOGLE_SSO_ENABLED is "false", then GoogleSignInButton and OrDivider render nothing. Given it is unset or any other value, both render.

Wishlist store
- AC-19: Given an anonymous user (serverBacked false), then addItem, removeItem, toggleItem, isInWishlist, getItemCount and clearWishlist work locally with no API call. A duplicate add of the same productId+variantId is ignored, and the same product with a different variantId is a separate entry.
- AC-20: Given a signed-in user (serverBacked true), then add and remove update state immediately (optimistic) and call saveWishlistItem (with name/price/currency/imageUrl/variantId/variantLabel) or removeWishlistItem(productId, variantId). An add without a snapshot makes no call and no state change.
- AC-21: Given a server mutation fails, then a notification with the error message is raised and state is reconciled from getWishlist. If that reload also fails, it rolls back to the pre-mutation items. A reconcile that is superseded by a later mutation is discarded.
- AC-22: Given anonymous items with snapshots, when attachServer runs, then they are sent to mergeWishlist in chunks of 200, state becomes the server rows (price as a number, null fields as undefined) and serverBacked is true. Items without a snapshot are not sent. With no local items it loads via getWishlist.
- AC-23: Given merge fails with local items, then a notification is raised, the local items are kept and serverBacked stays false. Given a failed merge or load with no local items, serverBacked becomes true with the server list if the retry succeeds, else an empty list. detachServer clears items and sets serverBacked false.
- AC-24: Given persistence, then an anonymous list is written to localStorage key "wishlist-store", and a serverBacked list persists as an empty items array. serverBacked itself is never persisted.

Process
- AC-25: docs/techdebt/testing.md TD-TEST-02 gets a dated progress line naming the new tests, and the item stays open for the Playwright e2e only. The README index/counts are unchanged.
- AC-26: `node .claude/hooks/forge-verify.mjs --changed` passes (vitest and type check), with its output recorded in the work folder. Tests are deterministic (no real network, no real timers beyond fake ones) and leave global state (env, window.location, localStorage, stores) restored between tests.

## 4. Edge cases and abuse cases
- Double click and re-click during placing (AC-11), network retry (AC-12), changed shape after failure.
- buyNow with qty "0", "abc", "500", a missing product, an unknown variant id (falls back to the product price).
- `?error=` containing characters that need URL encoding, or a hostile `next` value (the stored redirect comes from lib/redirect, and the callback must not build a URL from raw input).
- Wallet balance in a different currency from the order (the wallet is not offered).
- Stale async results after unmount or a changed pincode (no state update from an inactive effect).
- Wishlist: overlapping mutations, more than 200 local items (two merge calls), a local item with no snapshot.

## 5. Open questions
None blocking. Note: CheckoutPage has no auth gate of its own, so "auth-required" is tested only as the 401 ApiError path in AC-12. If the CEO wants route-guard coverage, that is a separate item.
