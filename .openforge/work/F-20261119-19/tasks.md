# F-20261119-19 tasks

Worktree: c:/Users/Akash/Desktop/Projects/.openforge/worktrees/F-20261119-19. Commands run from `apps/product-catalog` unless stated.

## Task 1 - shouldRotateKey rule + unit table (forge-frontend-engineer)
- Goal: add exported pure `shouldRotateKey(err)` and `ROTATE_CODES` to CheckoutPage.tsx (design section 2) and replace the `status < 500` check in the `placeOrder` catch with `if (shouldRotateKey(err)) attempt.current = null`. Leave the RISK branch untouched. Add the table-driven `it.each` unit test over every requirements row (incl. 409 lower-case code, 409 no code, 409 OUT_OF_STOCK without items, 422 IDEMPOTENCY_KEY_REUSED, TypeError, 408 with a code, 502).
- Files: apps/product-catalog/src/pages/CheckoutPage.tsx; apps/product-catalog/src/pages/__tests__/CheckoutPage.place.test.tsx
- ACs: AC-1..AC-9 (rule level), AC-13
- Verify: `npx vitest run src/pages/__tests__/CheckoutPage.place.test.tsx` then `npx tsc --noEmit`

## Task 2 - UI retry tests (forge-frontend-engineer)
- Goal: extend the place test file (reuse `problem()`): place, place again unchanged, compare `api.place.mock.calls[0][1]` vs `[1][1]`.
  AC-1 408; AC-2 429; AC-3 409 RESOURCE_CONFLICT; AC-4 409 SOMETHING_NEW and 409 no code (same key);
  AC-5 400 VALIDATION_FAILED, 401, 403, 404 one test each (new key); AC-6 422 IDEMPOTENCY_KEY_REUSED;
  AC-7 `it.each` of the 409/422 rotate codes; AC-8 422 DOMAIN_RULE_VIOLATION and 422 COUPON_EXPIRED; AC-9 add 502 (500/503/network exist).
  Each asserts alert text equals `errorMessage(err)` and Place button enabled (AC-10). Add AC-12 cases: 429 then change coupon/payment gives new key; KEEP then ROTATE then retry gives new key. Existing shape-change, success-clear, RISK and OUT_OF_STOCK shortfall tests must stay green.
- Files: apps/product-catalog/src/pages/__tests__/CheckoutPage.place.test.tsx
- ACs: AC-1..AC-10, AC-12
- Verify: `npx vitest run src/pages/__tests__/CheckoutPage.place.test.tsx`; then `npx tsc --noEmit`; then `npm run lint`

## Task 3 - Mutation evidence and forge-verify (qa)
- Goal: temporarily replace the body of `shouldRotateKey` with (a) `return isApiError(err) && err.status < 500` and (b) `return false`; run the place test file each time; record in evidence.md that (a) fails the 408/429/409-keep tests and (b) fails the 4xx/409/422 rotate tests; revert and confirm `git diff` shows only the two allowed files (AC-13). Then run forge-verify and record its output.
- Files: .openforge/work/F-20261119-19/evidence.md (source file mutation is reverted, no net change)
- ACs: AC-11, AC-13, AC-14
- Verify: `npx vitest run src/pages/__tests__/CheckoutPage.place.test.tsx` (expect failures under each mutation, pass after revert); from the worktree root `node .claude/hooks/forge-verify.mjs --changed`; `git diff --name-only` lists only the two files.

## Coverage table
| AC | Tasks |
|---|---|
| AC-1 | 1, 2 |
| AC-2 | 1, 2 |
| AC-3 | 1, 2 |
| AC-4 | 1, 2 |
| AC-5 | 1, 2 |
| AC-6 | 1, 2 |
| AC-7 | 1, 2 |
| AC-8 | 1, 2 |
| AC-9 | 1, 2 |
| AC-10 | 2 |
| AC-11 | 3 |
| AC-12 | 2 |
| AC-13 | 1, 3 |
| AC-14 | 3 |
