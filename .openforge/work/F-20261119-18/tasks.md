# Tasks F-20261119-18: storefront checkout, Google callback and wishlist tests (TD-TEST-02)

Owner of every task: frontend-engineer. Worktree: c:/Users/Akash/Desktop/Projects/.openforge/worktrees/F-20261119-18 (app: apps/product-catalog). Branch forge/F-20261119-18-storefront-checkout-tests. One commit per task, message `forge(F-20261119-18): <task title>`.

## Rules (apply to every task)
- No production diff. Only new test files plus docs/techdebt/testing.md. Do not edit any file under src that is not a test.
- If a test exposes a real bug, STOP and escalate to the CEO with the failing test and the cause. Do not fix it, do not weaken the assertion, do not skip or `.todo` it.
- No snapshot tests. Assert on roles, text, store state and mock call arguments.
- Follow the style of src/stores/*.test.ts and src/components/__tests__/CheckoutCoupons.test.tsx. One network mechanism per file (msw handlers or module mocks, not both). No new dependency.
- Restore globals in afterEach: window.location (restore the original descriptor/object), import.meta.env / vi.stubEnv (vi.unstubAllEnvs), fake timers (vi.useRealTimers), localStorage and sessionStorage (clear), zustand stores (reset to initial state), msw handlers (resetHandlers), mocks (vi.restoreAllMocks / clearAllMocks). Use findBy/waitFor, never real sleeps.
- Before writing a test, read the target source file and the existing sibling tests; mirror their mocks.
- Verification command per task: run from apps/product-catalog `npx vitest run <test file>`; it must pass, and `npx tsc --noEmit` style type check is covered by the final task.

## Tasks

### 1. wishlistStore: anonymous behaviour and persistence
- Goal: test local add, remove, toggle, isInWishlist, getItemCount, clearWishlist with no API call; duplicate add of same productId+variantId ignored; same product with different variantId is a separate entry; persistence to localStorage key "wishlist-store" for anonymous lists.
- Files: apps/product-catalog/src/stores/wishlistStore.test.ts (new; part 1, task 2 extends it or adds wishlistStore.server.test.ts)
- ACs: AC-19, AC-24 (anonymous write part)
- Verify: `npx vitest run src/stores/wishlistStore.test.ts`
- Commit: forge(F-20261119-18): wishlistStore anonymous tests

### 2. wishlistStore: server-backed sync, reconcile, attach/detach, persistence
- Goal: serverBacked add/remove optimistic with saveWishlistItem (name/price/currency/imageUrl/variantId/variantLabel) and removeWishlistItem(productId, variantId); add without snapshot makes no call and no state change; failure raises notification and reconciles via getWishlist; failed reload rolls back to pre-mutation items; superseded reconcile discarded; attachServer merge in chunks of 200 (250 items gives two mergeWishlist calls), server rows mapped (price number, null to undefined), items without snapshot not sent, no local items uses getWishlist; merge failure with local items keeps items, serverBacked false, notification; failed merge/load with no local items gives serverBacked true with retry list or empty; detachServer clears; serverBacked list persists as empty items array and serverBacked is never persisted.
- Files: apps/product-catalog/src/stores/wishlistStore.server.test.ts (new)
- ACs: AC-20, AC-21, AC-22, AC-23, AC-24
- Verify: `npx vitest run src/stores/wishlistStore.server.test.ts`
- Commit: forge(F-20261119-18): wishlistStore server sync tests

### 3. GoogleSignInButton and OrDivider tests
- Goal: click calls rememberPostLoginRedirect(next prop) and window.location.assign(googleSignInUrl()); custom label and default "Continue with Google"; VITE_GOOGLE_SSO_ENABLED "false" renders nothing for both components; unset or other value renders both. Stub window.location and env, restore after each test.
- Files: apps/product-catalog/src/components/__tests__/GoogleSignInButton.test.tsx (new; also covers OrDivider, or split OrDivider.test.tsx next to it if they live in separate files)
- ACs: AC-17, AC-18
- Verify: `npx vitest run src/components/__tests__/GoogleSignInButton.test.tsx`
- Commit: forge(F-20261119-18): Google button and divider tests

### 4. AuthCallbackPage tests
- Goal: ?error=<code> navigates with replace to /login?error=<encoded>&next=<encoded stored redirect> and consumes the stored redirect (error with characters needing encoding; hostile next comes only from lib/redirect); authenticated goes to stored post-login path (default from takePostLoginRedirect); mfaToken goes to /login?next=... and wins over authenticated; anonymous without error goes to /login?error=AUTH_OIDC_LOGIN_FAILED&next=...; loading does not navigate and shows "Signing you in..." with role=status. Use MemoryRouter with a location probe; set authStore state directly.
- Files: apps/product-catalog/src/pages/__tests__/AuthCallbackPage.test.tsx (new; use the existing pages test dir if different)
- ACs: AC-14, AC-15, AC-16
- Verify: `npx vitest run src/pages/__tests__/AuthCallbackPage.test.tsx`
- Commit: forge(F-20261119-18): AuthCallbackPage tests

### 5. CheckoutPage tests part 1: render, summary, totals, empty and unavailable states
- Goal: create shared test helper/setup in the first checkout test file (mock ExchangeOffer, PaymentPanel, CheckoutCoupons as needed; helper to render with MemoryRouter and seed cart, address, wallet, coins, membership, estimate responses). Tests: first address preselected, summary line name, Qty, formatted line total, Subtotal = sum price x qty; empty cart shows "Your cart is empty", "Continue shopping" link to "/", address list hidden; ?buyNow for failing product shows "That product is not available"; discount from server validation, Coins, Shipping rows, Total = subtotal - discount - coins + shipping and never negative; Plus member Shipping "Free"; INR shows "Includes GST (18%)", non-INR shows "Tax included".
- Files: apps/product-catalog/src/pages/__tests__/CheckoutPage.summary.test.tsx (new; helper may go in src/pages/__tests__/checkoutTestUtils.tsx, test-only)
- ACs: AC-1, AC-2, AC-7 (display part; place payload part in task 7)
- Verify: `npx vitest run src/pages/__tests__/CheckoutPage.summary.test.tsx`
- Commit: forge(F-20261119-18): CheckoutPage summary and totals tests

### 6. CheckoutPage tests part 2: payment methods, COD rules, disabled states, failing side calls
- Goal: Wallet covering whole total makes Wallet radio choosable (place payload checked in task 7); partial balance disables Wallet radio, "use wallet" checkbox shows "Paid from wallet" and "To pay" (or "Due on delivery" for COD); wallet in a different currency not offered; COD disabled with matching reason when estimate.codAvailable false, no estimate, total above 50000 INR or 1000 other; COD selected then unavailable reverts to CARD; no saved addresses shows address form and place button disabled; pending form or no selected address disables button; address load failure shows error alert and no place button; mixed currencies shows error alert and disabled button; estimate failure shows error, Shipping "—", COD disabled; failing wallet, coins, membership, payment-config or available-coupons call hides only its own controls and checkout still works; ignore stale async result after unmount or pincode change.
- Files: apps/product-catalog/src/pages/__tests__/CheckoutPage.payment.test.tsx (new; reuse helper from task 5)
- ACs: AC-6 (selection and display), AC-8, AC-9, AC-10
- Verify: `npx vitest run src/pages/__tests__/CheckoutPage.payment.test.tsx`
- Commit: forge(F-20261119-18): CheckoutPage payment method and COD tests

### 7. CheckoutPage tests part 3: place flow, idempotency, errors, buyNow
- Goal: COD cart order sends paymentMethod "COD" and addressId, navigates to /orders/<id> with replace and state {placed:true}, re-attaches cart from server; CARD with payment and PENDING_PAYMENT shows "Complete your payment", short order id, server totalAmount, PaymentPanel and "Pay later from the order page", no navigation until onSubmitted then /orders/<id>?payment=return; WALLET full cover sends paymentMethod "WALLET" and useWallet true; partial wallet sends useWallet true; couponCode and coinsToRedeem sent; buyNow sends items array (productId, quantity, variantId/offerId when in URL), cart not re-attached, qty clamped 1..99 and "0"/"abc"/"500" cases, unknown variant falls back to product price; in-flight disables button and reads "Placing order..." and a second click makes no second request; 4xx ApiError shows problem message, re-enables button, retry sends NEW Idempotency-Key; 5xx or network failure retry reuses SAME key; changed shape (different payment method) gets new key; success clears the key; OUT_OF_STOCK alert "<name>: you asked for N, only M available" with name from lines and fallback "Item"; PAYMENT_METHOD_UNAVAILABLE reason RISK switches to CARD, COD disabled with risk reason, message "Cash on Delivery is not available for this order. Please choose online payment."
- Files: apps/product-catalog/src/pages/__tests__/CheckoutPage.place.test.tsx (new; may be split into .place and .errors if over about 400 lines)
- ACs: AC-3, AC-4, AC-5, AC-6 (place payload), AC-7 (place payload), AC-11, AC-12, AC-13
- Verify: `npx vitest run src/pages/__tests__/CheckoutPage.place.test.tsx`
- Commit: forge(F-20261119-18): CheckoutPage place flow tests

### 8. Register progress line
- Goal: add a dated progress line (2026-11-25 or the actual run date) to TD-TEST-02 in docs/techdebt/testing.md naming the new tests (CheckoutPage, AuthCallbackPage, GoogleSignInButton/OrDivider, wishlistStore); keep the item open for the Playwright e2e only. Do not change docs/techdebt/README.md index or counts.
- Files: docs/techdebt/testing.md
- ACs: AC-25
- Verify: `git diff --stat -- docs/techdebt` shows only testing.md changed; README.md untouched.
- Commit: forge(F-20261119-18): TD-TEST-02 progress

### 9. Final verification and evidence
- Goal: run the full gate, confirm no production diff (`git diff --name-only e89649c94749497f63d11ee7c59383f8f80843fc` lists only test files, helper under __tests__, and docs/techdebt/testing.md), confirm no test leaves globals dirty (run the new files together and in reverse order), and record output.
- Files: .openforge/work/F-20261119-18/verify.md (record command output; no source change)
- ACs: AC-26
- Verify (from the worktree, CLAUDE_PROJECT_DIR set to the main checkout c:/Users/Akash/Desktop/Projects): `node .claude/hooks/forge-verify.mjs --changed --base e89649c94749497f63d11ee7c59383f8f80843fc`
- Commit: forge(F-20261119-18): verification evidence (only if the work folder is tracked; otherwise no commit)

## Coverage table

| AC | Task(s) |
|---|---|
| AC-1 | 5 |
| AC-2 | 5 |
| AC-3 | 7 |
| AC-4 | 7 |
| AC-5 | 7 |
| AC-6 | 6, 7 |
| AC-7 | 5, 7 |
| AC-8 | 6 |
| AC-9 | 6 |
| AC-10 | 6 |
| AC-11 | 7 |
| AC-12 | 7 |
| AC-13 | 7 |
| AC-14 | 4 |
| AC-15 | 4 |
| AC-16 | 4 |
| AC-17 | 3 |
| AC-18 | 3 |
| AC-19 | 1 |
| AC-20 | 2 |
| AC-21 | 2 |
| AC-22 | 2 |
| AC-23 | 2 |
| AC-24 | 1, 2 |
| AC-25 | 8 |
| AC-26 | 9 (and the determinism rules in every task) |
