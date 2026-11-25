# Evidence: F-20261119-18

## Engineer report (forge-frontend-engineer)
The CEO recorded this from the engineer's hand-back, because the role rules do not let the engineer write to the work folder. It is the engineer's own account; QA and the CEO verify it independently below.

**Head:** b048adc on `forge/F-20261119-18-storefront-checkout-tests` (base e89649c). There are 8 commits, one per task (Task 9 is evidence only):
- 120eee0 wishlistStore anonymous;
- c68398c wishlistStore server sync;
- 8aa7ada Google button and divider;
- 87bba1e AuthCallbackPage;
- e21af5b checkout summary and totals;
- 59c3a10 checkout payment and COD;
- 2e0e767 checkout place flow;
- b048adc TD-TEST-02 progress.

| File (apps/product-catalog) | Tests |
|---|---|
| src/stores/wishlistStore.test.ts | 10 |
| src/stores/wishlistStore.server.test.ts | 23 |
| src/components/__tests__/GoogleSignInButton.test.tsx (also covers OrDivider) | 12 |
| src/pages/__tests__/AuthCallbackPage.test.tsx | 13 |
| src/pages/__tests__/CheckoutPage.summary.test.tsx | 16 |
| src/pages/__tests__/CheckoutPage.payment.test.tsx | 24 |
| src/pages/__tests__/CheckoutPage.place.test.tsx | 31 |

The test-only helpers are `src/pages/__tests__/checkoutMocks.ts` and `src/pages/__tests__/checkoutTestUtils.tsx`.

**Results:**
- **Determinism:** the 7 new files passed 3 runs in a row (129 tests each run). A reverse-order run with `--sequence.shuffle` also passed (129).
- **Full suite:** `npx vitest run` gives 33 files and 439 tests passed. `npx tsc --noEmit` is clean.
- **Verify:** engineer-run `forge-verify --changed --base e89649c` printed `PASS apps/product-catalog: vitest (exit 0)`, `PASS apps/product-catalog: tsc (exit 0)` and `forge-verify: PASS (2 checks)`.
- **Scope:** the diff is only test files, the helpers and one dated progress line in `docs/techdebt/testing.md`. package.json and the lockfile are unchanged (`npm ci` was used).
- **Bugs:** none found. Nothing is skipped or todo'd.

**Notes:**
- Network calls are mocked at the service modules, as the existing store tests do; no msw handlers were added. PaymentPanel and ExchangeOffer are stubbed.
- AC-12 "a success clears the key" is not observable in the UI, because the page navigates away after a success. It is covered indirectly instead:
  - a retry after a 4xx gets a new key;
  - a retry after a 5xx or network failure reuses the key;
  - a changed request shape gets a new key;
  - a fresh mount gets a different key.
- AC-10 "stale after unmount" asserts that nothing throws and no `console.error` is logged, because React 18 does not warn. The visible stale cases (a late estimate that resolves or rejects after a pincode change) are asserted strictly.
- GoogleSignInButton reads its env flag at module load. The tests use `vi.stubEnv` plus `vi.resetModules` and a dynamic import, and restore `window.location` from its original descriptor.

## QA (forge-qa)
Run in the worktree, apps/product-catalog, head b048adc: `npx vitest run src/stores/wishlistStore src/components/__tests__/GoogleSignInButton src/pages/__tests__ --reporter=verbose`. Result: `Test Files 8 passed (8)`, `Tests 133 passed (133)`. That is the 7 new files (129 tests) plus the existing WishlistPage.test.ts (4). No test was added or edited by QA. Full forge-verify is run by the CEO separately.

Abbreviations: SUM = CheckoutPage.summary.test.tsx, PAY = CheckoutPage.payment.test.tsx, PLC = CheckoutPage.place.test.tsx, CB = AuthCallbackPage.test.tsx, GB = GoogleSignInButton.test.tsx, WS = wishlistStore.test.ts, WSS = wishlistStore.server.test.ts.

| AC | Test file and test name(s) | Result |
|---|---|---|
| AC-1 | SUM: "preselects the first address and lists each line with Qty and line total"; "Subtotal is the sum of price x quantity and the estimate is requested for it" | passed |
| AC-2 | SUM: "shows an empty cart with a Continue shopping link and no address list"; "shows 'That product is not available' when the buyNow product fails to load" | passed |
| AC-3 | PLC Buy Now: "sends an items array and leaves the cart alone"; "includes variantId and offerId from the URL"; "falls back to the product price and omits the variant for an unknown variant id"; clamps qty 0/abc/""/-4/500/99/7/3.9; "defaults the quantity to 1 when qty is missing" | passed |
| AC-4 | PLC cart orders: "places a COD order, replaces history with /orders/<id> and re-attaches the cart"; "sends the selected address id" | passed |
| AC-5 | PLC: "hands a PENDING_PAYMENT card order to the payment panel without navigating" (heading, short id, server total, panel, Pay later link, then onSubmitted goes to ?payment=return); "goes straight to the order when a card order has no payment to collect" | passed |
| AC-6 | PAY wallet: "lets the Wallet method be chosen when the balance covers the whole total"; "disables the Wallet radio for a partial balance and offers the use-wallet checkbox"; "labels the remainder 'Due on delivery' when the method is COD". PLC: "sends paymentMethod WALLET and useWallet..."; "sends useWallet for a partial wallet payment..." | passed |
| AC-7 | SUM: "shows the server discount, coins and shipping, and Total = ..."; "never lets the total go below zero..."; "caps redeemable coins at 20%..."; "shows Shipping Free for a Plus member..."; "shows an 'Includes GST (18%)' line for an INR order"; "shows 'Tax included'... non-INR". PLC: "sends the validated coupon code and the redeemed coins" | passed |
| AC-8 | PAY Cash on Delivery rules: available / estimate says no / no estimate / above 50000 INR / right at the limit / above 1000 other currencies / "reverts to CARD when COD was selected and becomes unavailable" | passed |
| AC-9 | PAY disabled place button and error states: "no saved addresses"; "add-address form is open, and enables it again on cancel"; "address load fails"; "mixed currencies" | passed |
| AC-10 | PAY: "shows the estimate error, Shipping '—' and a disabled COD when the estimate fails"; "optional services that fail hide only their own controls" (wallet, coins, membership, payment config, available coupons); "stale async results" (late failed estimate ignored, unmount) | passed, see Weak 1 |
| AC-11 | PLC: "disables the button while placing and ignores a second click" | passed |
| AC-12 | PLC in flight and idempotency: 4xx NEW key; 5xx SAME key; network SAME key; "changed request shape" NEW key; "distinct key for each separate checkout page" | passed, see Weak 2 |
| AC-13 | PLC problems from the order service: "lists each OUT_OF_STOCK line with the product name, falling back to 'Item'"; "switches to CARD and disables COD with a risk reason on PAYMENT_METHOD_UNAVAILABLE / RISK"; plus the negative cases (non-RISK reason, no items array, clearing) | passed |
| AC-14 | CB: "sends ?error=<code> to the login page with the stored redirect, and consumes it"; "encodes an error value..."; "error wins over an authenticated session"; 4 hostile stored values; "ignores a ?next= query parameter" | passed |
| AC-15 | CB: "goes to the stored post-login path when authenticated, replacing the callback entry"; "defaults to '/'..."; "sends an mfaToken to the login second-factor step, ahead of authenticated" | passed |
| AC-16 | CB: "sends an anonymous visitor without an error to login with AUTH_OIDC_LOGIN_FAILED"; "does not navigate while the session status is loading and shows a status message" | passed |
| AC-17 | GB: "remembers the next path and sends the browser to the Google sign-in URL" (called once with the prop value, assign with the URL, call order); "renders a custom label instead of the default"; default label found via the role name "Continue with Google" | passed |
| AC-18 | GB: flag "false" renders nothing (button and OrDivider); undefined, "", "FALSE", "0", "true", "anything" render | passed |
| AC-19 | WS: all 8 "anonymous wishlist" tests (add, duplicate ignored, other variant separate, remove, toggle, isInWishlist, count and clear); no API call asserted in the first test | passed |
| AC-20 | WSS server-backed mutations: "adds optimistically and saves the full snapshot"; "sends null for missing optional snapshot fields"; "removes optimistically and calls removeWishlistItem(productId, variantId)"; "makes no call and no state change for an add without a snapshot" | passed |
| AC-21 | WSS failure and reconcile: "notifies and reconciles from getWishlist when a save fails"; "rolls back ... when the reload also fails"; "rolls back a failed remove"; "discards a reconcile that a later mutation superseded"; "discards a failed reload that a later mutation superseded" | passed |
| AC-22 | WSS attachServer: "merges anonymous items with snapshots and adopts the server rows"; "sends 250 items as two merge calls of 200 and 50"; "does not send items without a snapshot..."; "loads via getWishlist when there are no local items" | passed |
| AC-23 | WSS: "keeps local items, stays anonymous and notifies when the merge fails"; "becomes server-backed with the retried list..."; "...with an empty list when the retry fails too"; "detachServer clears items and leaves server-backed mode" | passed |
| AC-24 | WS: "writes the anonymous list to localStorage under wishlist-store"; "updates the persisted list on remove and clear". WSS: "persists a server-backed list as an empty items array and never persists serverBacked"; "persists the anonymous list again after detach" | passed |
| AC-25 | No test. Checked by diff: `git diff e89649c --stat -- docs/techdebt` shows only testing.md, +1 line. It is a dated "Progress 2026-11-25" line that names the new tests and says "Still open: the Playwright e2e". README is untouched. | verified by diff |
| AC-26 | Not QA's check. CEO's forge-verify result goes with this one. Determinism: GB uses `vi.stubEnv` with `vi.unstubAllEnvs()`; CB and the stores reset sessionStorage, localStorage and the store state in beforeEach and afterEach. QA saw no real network. | see forge-verify output |

All ACs evidenced: yes (AC-26 is pending only on the CEO's forge-verify output).

### Weak or indirect coverage
1. AC-10, "stale after unmount": `does not throw or log when responses arrive after the page unmounted` can pass vacuously. React 18 no longer warns about setState on an unmounted component, so the console.error and "no throw" assertions would still pass if the effects lacked cancellation guards. The final `queryByRole` check only proves that the page is unmounted. The test cannot prove that the guards work. The strict stale case, "ignores a failed estimate for a pincode that is no longer selected", and SUM's "ignores the stale first answer" do cover the changed-pincode guard. Real coverage for unmount: none. This is acceptable as the engineer stated, but it should be counted as not covered.
2. AC-12, "a success clears the key": there is no direct test. After a success the page navigates away, so the key cannot be observed. The "distinct key for each separate checkout page" test checks a fresh mount, not clearing of the key. If `attempt.current = null` on success were deleted, no test would fail. This is a small gap. It could be closed by a test that stubs `crypto.randomUUID` and re-renders without remounting, but the page unmounts on success. I judge the gap acceptable, but it is a true gap.
3. AC-10, "wallet, coins, membership, payment-config or available-coupons failing": the shared `placeableAndQuiet` helper checks that the button is enabled, no alert shows, the Pay online radio is enabled and Subtotal is correct. Whether each test also asserts that its own control is hidden depends on the test. I did not read each of the 5 bodies line by line. This may be weak.
4. AC-17: the default label is covered only through the role name lookup in the first test. Nothing separate asserts the default label.
5. Negative assertions in CB's loading test ("does not navigate", `queryByTestId('where')` absent) run synchronously straight after render. This is sound here because the effect has run inside RTL's act and the page would have navigated already. They are not vacuous.

### Vacuity review
I found no assertion on a mock that the component never calls, no expect inside a callback that may not run, and no un-awaited `findBy` or `waitFor`. Navigation tests use `await screen.findByTestId(...)` on a router probe, so they wait for the navigation. PLC uses `await landing()` and `findByRole`. The only vacuous-capable assertion is weak 1.

### Spot checks (read production code and reasoned about a broken version)
1. PLC "shows the problem message after a 4xx ... NEW key": production `CheckoutPage.tsx` has `if (isApiError(err) && err.status < 500) attempt.current = null` in the catch. If that line were removed, the retry would have the same JSON shape and reuse the key. `expect(keyOf(1)).not.toBe(keyOf(0))` would fail. The test fails on that break. The 5xx test (`toBe`) would fail if the condition were widened to all errors. The two tests together pin the `< 500` boundary.
2. CB "sends an mfaToken ... ahead of authenticated": state is `status: 'authenticated', mfaToken: 'mfa-123'`, and the test expects `/login?next=%2Faccount%2Fprofile`. If `AuthCallbackPage` checked `status === 'authenticated'` before `mfaToken`, it would navigate to `/account/profile` and the test would fail. The `not.toContain('error=')` assertion and the consumed storage key give two more breakpoints.

### Gaps summary
- No missing tests that require new files. The gaps are weak 1 (AC-10 unmount) and weak 2 (AC-12 success clears key). Neither blocks. Weak 3 I did not check in detail.
- No defects found in production code.

## CEO verify (worktree at b048adc, clean tree)
```
forge-verify: 10 changed file(s) in c:/Users/Akash/Desktop/Projects/.openforge/worktrees/F-20261119-18 (base e89649c94749)
  PASS apps/product-catalog: vitest (exit 0)
  PASS apps/product-catalog: tsc (exit 0)
forge-verify: PASS (2 checks)
rc=0
```
- AC-26 passed: forge-verify PASS (vitest + tsc, exit 0), run by the CEO at b048adc; determinism shown by the engineer's 3 consecutive runs plus a shuffled run.
- AC-25 passed: verified by diff (one dated progress line in docs/techdebt/testing.md; item kept open for the Playwright e2e).

## Round 2 (engineer report, recorded by the CEO) and CEO checks
- Head 305d540e3aade9a2e06b69c2021e305910056c6e. Commits: 44c278f (exact redirect assertions + redirect.test.ts), 1dce9c6 (review fixes), 305d540 (safeNext control-character rejection, CEO-approved production fix).
- The engineer reports all 7 round-1 findings fixed. Exact toBe on every callback navigation, with the stored key consumed. The backslash case now uses String.raw. Both stale-estimate tests settle inside act(). Guard-removal proof: with the `active` guards in CheckoutPage.tsx deleted, exactly "re-requests the estimate for another address and ignores the stale first answer" (summary) and "ignores a failed estimate for a pincode that is no longer selected" (payment) failed; the guards were then reverted. Wallet and membership cases now show the control first. The key test is renamed, and the AC-12 "success clears the key" gap stays disclosed. Nits are fixed.
- safeNext hostile cases (engineer): //x, //evil.example/x, /\x, https://x, javascript:x, '', null, undefined, 'a' and %2F%2Fevil.example are rejected; '/a?b=1' is accepted. '/\t/evil.example' was ACCEPTED before the fix (production bug, CEO ruling in log.md).
- CEO fail-before/pass-after check: with redirect.ts from 1dce9c6, redirect.test.ts + AuthCallbackPage.test.tsx gave 6 failed (including "rejects slash TAB slash and returns the fallback"). At 305d540 both files passed, 41/41. The worktree was restored (git status clean).
- CEO verify at 305d540: PASS apps/product-catalog: vitest (exit 0); PASS apps/product-catalog: tsc (exit 0); forge-verify: PASS (2 checks); rc=0.
- AC-26 passed at 305d540 (CEO forge-verify above).

## Round 3 (CEO record, 49f94cf)
- Lint fix: the safeNext control-character regex failed eslint no-control-regex (CI ci.yml:152 runs `npm run lint`). Replaced by a charCode loop (+4/-1, same rejection set).
- CEO: `npm run lint` 0 errors (2 base warnings in src/test/setup.ts); `forge-verify --changed --base e89649c` PASS (2 checks); redirect and callback tests 41/41 passed with the test files unchanged.
