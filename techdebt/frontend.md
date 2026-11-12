# Tech Debt: Frontend (`apps/product-catalog`)

Index: [README](README.md)

<a id="td-fe-02"></a>
## TD-FE-02 Checkout totals are client-side
- **Severity / Effort:** Med / M
- **Where:** `src/pages/CheckoutPage.tsx`.
- **Risk:** Totals come from cart snapshots (display only); the server re-prices, so drift is seen only after the order is placed. No shipping/tax lines (see TD-BE-02).
- **Fix:** Server-side cart pricing preview endpoint; show differences before confirming.

<a id="td-fe-03"></a>
## TD-FE-03 Optimistic cart/wishlist edits are last-write-wins
- **Severity / Effort:** Med / M
- **Where:** `src/stores/cartStore.ts`, `wishlistStore.ts`.
- **Risk:** Rapid concurrent edits can briefly show stale state; wishlist rollback does not re-sync from server.
- **Fix:** Per-product mutation queue, or reconcile with the server response after each settle.

<a id="td-fe-04"></a>
## TD-FE-04 Failed login merge has no in-session retry (data-loss part fixed)
- **Severity / Effort:** Med / S
- **Where:** `cartStore.attachServer`, `wishlistStore.attachServer`.
- **Risk:** If merge fails (e.g. `CURRENCY_MISMATCH`), local items are dropped after a notification; no per-item retry.
- **Fix:** Keep local copy until merge succeeds; merge item by item on conflict.

TD-FE-05 (forgot password, email verification) was resolved by Phase 6; remaining gaps (no password strength meter, `AUTH_*` codes hard-coded in `lib/errors.ts`, unverified login allowed) are folded into [TD-P6-10](phase6.md#td-p6-10).

<a id="td-fe-06"></a>
## TD-FE-06 Wishlist snapshot gaps
- **Severity / Effort:** Low / M
- **Where:** `wishlistStore.ts`, wishlist page.
- **Risk:** Anonymous items without a snapshot (older persisted data) are dropped at merge; server wishlist page refetches each product (N requests).
- **Fix:** Batch product endpoint (TD-BE-06).

<a id="td-fe-07"></a>
## TD-FE-07 Session-expiry full reload; no Retry-After
- **Severity / Effort:** Low / S
- **Where:** `stores/authStore.ts` (`window.location.assign('/login?next=...')`), `lib/http.ts`.
- **Risk:** Full page reload to avoid a router import cycle; 429 `Retry-After` is ignored.
- **Fix:** Router navigate via injected handler; surface the wait time.

<a id="td-fe-08"></a>
## TD-FE-08 Address form validation minimal
- **Severity / Effort:** Low / S
- **Where:** `components/AddressForm.tsx`, `pages/account/AddressesPage.tsx`.
- **Risk:** Country only length-2 checked; no state/postal format checks. Address delete now uses an inline confirm (2026-11-12); `window.confirm` remains for order cancel (`pages/OrderPage.tsx`) and review delete (`components/ReviewsSection.tsx`).
- **Fix:** ISO country list, per-country postal patterns, styled confirm dialog.

<a id="td-fe-09"></a>
## TD-FE-09 No cross-tab session sync
- **Severity / Effort:** Low / M
- **Where:** `stores/authStore.ts`.
- **Risk:** Logging out in one tab leaves others "authenticated" until their next 401.
- **Fix:** BroadcastChannel for login/logout events.

<a id="td-fe-10"></a>
## TD-FE-10 Google button shown without checking server config
- **Severity / Effort:** Low / S
- **Where:** `components/GoogleSignInButton.tsx` (hidden only when `VITE_GOOGLE_SSO_ENABLED=false`).
- **Risk:** With blank `GOOGLE_CLIENT_ID` the button leads to a 404 on auth-service.
- **Fix:** Probe a capability endpoint at startup.

<a id="td-fe-11"></a>
## TD-FE-11 Post-login destination can swap across tabs
- **Severity / Effort:** Low / S
- **Where:** `lib/redirect.ts` (`pc.post-login-redirect` in sessionStorage).
- **Risk:** Two simultaneous Google sign-ins can swap destinations. Harmless: only same-origin paths are accepted by `safeNext`.
- **Fix:** Carry a per-attempt nonce.

<a id="td-fe-12"></a>
## TD-FE-12 Storefront test harness does not boot on Node 26
- **Severity / Effort:** Med / S
- **Where:** `apps/product-catalog/src/test/setup.ts:16` (MSW `setupServer`), `vitest@1`, `msw@2`.
- **Risk:** All 15 storefront test files fail at suite setup with `Cannot read properties of undefined (reading 'on')` before any test runs, so the suite gives zero signal. This was found on 2026-11-12 while doing the profile/deals UI pass; it predates that change, which touched no test or setup files. It is separate from the stale-type errors in TD-TEST-03.
- **Fix:** Pin Node 22 LTS for the app (`.nvmrc` + `engines`), or upgrade `vitest`/`msw` to versions that support Node 26's globals, then re-run.

<a id="td-fe-13"></a>
## TD-FE-13 Profile/deals UI pass is untested
- **Severity / Effort:** Low / S
- **Where:** `src/components/DealsStrip.tsx`, `src/pages/account/{OrdersPage,AddressesPage,RecentlyViewedPage}.tsx`, `src/lib/retroLights.ts`.
- **Risk:** There are no tests for the order tracker step mapping, the inline address delete confirm, the history clear/remove, or the lights toggle. The order list summary has no line items, so cards cannot show product thumbnails without an N-call fan-out.
- **Fix:** Once TD-FE-12 is fixed, add RTL tests for these. To get thumbnails, add the first item's image and name to the backend `OrderSummaryResponse`.

## Phase 8 storefront (moved)

Phase 8 storefront debt (no tests, assumed contract shapes, client-side checkout estimates, 60 s badge polling, no polling after payment return, `window.prompt` dialogs, push opt-in limits, 608 kB main chunk, `PersonalisedHome` request fan-out) now lives in [phase8.md, TD-P8-18](phase8.md#td-p8-18) and [TD-P8-01](phase8.md#td-p8-01).
