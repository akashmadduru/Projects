# F-20261119-18 Storefront checkout, Google callback and wishlist tests (TD-TEST-02)

## Objective (CEO-originated from the tech-debt register)
Add frontend tests for the still-untested parts of TD-TEST-02 in apps/product-catalog: the checkout page flow (CheckoutPage.tsx), the Google sign-in callback fallback paths (AuthCallbackPage.tsx, GoogleSignInButton.tsx) and the wishlist store (stores/wishlistStore.ts). Test-only; production code changes only if a test exposes a real bug, which is escalated to the CEO first.

## Context
- TD-TEST-02 (docs/techdebt/testing.md): Med / M. authStore and cartStore tests already exist (progress 2026-11-17).
- Tooling present: vitest, @testing-library/react + user-event + jest-dom, msw. Follow the existing tests (stores/*.test.ts, components/__tests__/CheckoutCoupons.test.tsx).
- Base: version_v1 head e89649c. Not stacked; independent of all forge branches (only F-07 touches this app, in PhoneSection only).
- Non-goal: the Playwright register/add-to-cart/checkout e2e (needs the full stack); keep it in the register as the remaining part.
- Branch `forge/F-20261119-18-storefront-checkout-tests`, worktree `.openforge/worktrees/F-20261119-18`.

## Blocking questions
None. Default: if a test reveals a real bug, stop and report; do not fix silently.
