# Complexity F-20261119-18

| Factor | Score | Reason |
|---|---|---|
| Services or apps touched | 0 | Only apps/product-catalog (tests) plus a register doc |
| Data change | 0 | None |
| Public API / event contract | 0 | Unchanged |
| Security or money path | 1 | Tests cover checkout (money) and Google sign-in (auth) indirectly; no production code changes |
| New dependency or infrastructure | 0 | vitest, Testing Library and msw already present |
| Estimated changed lines | 0 | Expected production lines 0; test and doc lines are excluded from the limit (tests are likely 150-600 lines but do not count) |

Total: 1
Class: S (requirements, tasks, implement, test, review)
Line limit: 300 production lines. Expected 0. A real bug found by a test is escalated to the CEO before any production fix.

## CTO decision
- Follow the existing tests (stores/*.test.ts, components/__tests__/CheckoutCoupons.test.tsx). Use whichever mechanism they use for network: msw handlers for flows that go through HTTP (checkout, callback), or mocking the api module where the existing store tests already do so. Do not mix styles within one file.
- No new dependency. No snapshot tests; assert on roles, text and store state.
- Deterministic timers: vi.useFakeTimers for any delay or redirect, restore in afterEach; no real sleeps; use findBy/waitFor instead of arbitrary timeouts.
- Reset stores, localStorage and msw handlers between tests.
- Cover the Google callback fallback paths (missing or invalid params, failure) and the wishlist store add, remove, toggle and persistence behaviour.
- Update the TD-TEST-02 register entry with progress; keep the Playwright e2e listed as remaining.
