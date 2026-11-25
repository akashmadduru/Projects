# Storefront tests for checkout, Google sign-in callback and wishlist, plus an open-redirect hardening in safeNext (TD-TEST-02)

**Not stacked.** Based on `version_v1` (e89649c), independent of every other forge branch.
- Range: `e89649c..49f94cf`.
- Size: 5 production lines (`apps/product-catalog/src/lib/redirect.ts`); everything else is tests and one register line.

## Why
- TD-TEST-02: the checkout page flow, the Google sign-in callback paths and the wishlist store had no frontend tests. The auth and cart stores were already covered.
- Writing exact open-redirect tests found a real bug: `safeNext` accepted `/<TAB>/evil.example`. The URL parser strips TAB, CR and LF, so the value becomes `//evil.example`, a cross-origin URL.

## What changed
- **`safeNext`** (+5 lines) now rejects any ASCII control character (a charCode loop; a regex form failed eslint no-control-regex). The existing checks are unchanged, so the function only gets stricter.
  - **Impact today was low.** Every consumer navigates through react-router, where a cross-origin path throws: a crafted `/login?next=%2F%09%2Fevil.example` link crashed the page after sign-in rather than redirecting.
  - **Why fix it now:** it was a latent open redirect for any future full-page navigation.
- **New tests in `apps/product-catalog`** (8 files and 2 test-only helpers):
  - **Checkout (summary, payment, place):**
    - server-side totals, discount, coins, shipping and GST;
    - COD, card, wallet and partial-wallet payment;
    - the rules for when COD is allowed, and the fallback to card;
    - disabled states, and optional services that fail and hide only their own control;
    - double-submit protection;
    - Idempotency-Key reuse: a new key after a 4xx, the same key after a 5xx or network failure, a new key when the request shape changes;
    - OUT_OF_STOCK and RISK replies, and Buy Now.
  - **Google callback:** the error, stored-path and two-factor handoff paths, with exact-match open-redirect cases for `//`, a real backslash, `%2F%2F`, TAB, `https:` and `javascript:`.
  - **Google button and divider:** the SSO flag and the stored `next` path.
  - **`redirect.test.ts`:** table cases for `safeNext`, sanitising on write, and re-sanitising and consuming on read.
  - **Wishlist store:** anonymous use, quick updates with rollback, server reconcile, merging in chunks on sign-in, and what gets saved in the browser.
- **Register:** TD-TEST-02 gets a dated progress line and stays open for the Playwright e2e.

## Evidence
- **Tests:** the full storefront suite passes (34 files, 461 tests), and `tsc` is clean.
- **Determinism:** the new files passed 3 runs in a row and a shuffled run.
- **`forge-verify --changed`:** PASS. The engineer ran it, and the CEO re-ran it at 49f94cf. `npm run lint` has 0 errors (2 warnings already in the base code).
- **Fail before, pass after:**
  - with the old `safeNext`, 6 redirect and callback tests fail; at 305d540, 41 of 41 pass;
  - deleting the stale-response guards in `CheckoutPage.tsx` makes exactly the two stale-estimate tests fail.
- **QA:** mapped AC-1..AC-26 to passing tests.
- **`forge-gate`:** PASS.

## Review
- Independent code and security review, 3 rounds (round 3: lint-only swap of the regex for a charCode loop, same behaviour); both approve 49f94cf.
- Round 1 found that both open-redirect checks could pass with the protection removed: a substring match and a mis-escaped backslash. It also found that the stale-response tests settled outside `act()`. All were fixed and proven in round 2.

## Known gaps (on the backlog)
- **PRODUCTION, money path:** checkout uses a new Idempotency-Key after any 4xx, including the ambiguous 408, 409 and 429, where the first order may already exist. A retry could create a second order. A fix needs sign-off.
- **No login guard on checkout:** the page has no auth gate of its own; login-required is covered only through the failed-order path.
- **Disclosed test gaps:**
  - "a successful order clears the key" can't be observed in the UI;
  - the after-unmount test is a no-throw check only;
  - the Playwright register, add-to-cart and checkout e2e is still open.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
