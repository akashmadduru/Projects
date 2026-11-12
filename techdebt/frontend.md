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

<a id="td-fe-05"></a>
## TD-FE-05 Auth hardening gaps
- **Severity / Effort:** Med / L
- **Where:** `pages/LoginPage.tsx`, `RegisterPage.tsx`, `lib/errors.ts`.
- **Risk:** No forgot-password, email verification or strength meter (only min length 12). Lockout/429 messages depend on hard-coded `AUTH_*` problem codes.
- **Fix:** Add flows once auth-service supports them; share the code catalogue.

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
- **Risk:** Country only length-2 checked; no state/postal format checks; delete/cancel use `window.confirm`.
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
