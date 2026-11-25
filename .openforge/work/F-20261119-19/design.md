# F-20261119-19 design: keep the checkout Idempotency-Key on ambiguous replies (class S)

## 1. Current behaviour
- `CheckoutPage.tsx:331-332` builds the key per request shape; `:337` sends it; `:338` clears it on success.
- `:349-356` RISK branch (PAYMENT_METHOD_UNAVAILABLE + reason RISK): switches to CARD, sets codRisk, clears key.
- `:359` `if (isApiError(err) && err.status < 500) attempt.current = null` rotates on every 4xx, including 408, 429 and ambiguous 409s.
- Helpers: `isApiError`, `problemCode`, `errorMessage` in `src/lib/errors.ts:7-15,59`; `ApiErrorImpl` in `src/lib/problem.ts:7`.

## 2. Proposed change (CheckoutPage.tsx only; AC-13)
Add a module-level pure function (exported for the unit test) near `stockShortfalls`:
```ts
const ROTATE_CODES = new Set(['VALIDATION_FAILED','ADDRESS_NOT_FOUND','IDEMPOTENCY_KEY_REUSED','PAYMENT_METHOD_UNAVAILABLE',
  'DOMAIN_RULE_VIOLATION','COUPON_INVALID','COUPON_EXPIRED','COUPON_NOT_APPLICABLE','OUT_OF_STOCK','PRODUCT_UNAVAILABLE',
  'CART_EMPTY','CURRENCY_MISMATCH','INSUFFICIENT_BALANCE','COUPON_USAGE_LIMIT'])
export function shouldRotateKey(err: unknown): boolean {
  if (!isApiError(err)) return false                                    // network / unknown: KEEP
  const s = err.status
  if (s >= 500 || s === 408 || s === 429) return false                  // ambiguous by status, any code
  const code = problemCode(err)
  if (code && ROTATE_CODES.has(code)) return true                       // definitive rejection
  if (s === 409) return false                                           // RESOURCE_CONFLICT, unknown, missing, case-mismatch
  return s >= 400                                                       // other 4xx (400/401/403/404/405/413/415/422): ROTATE
}
```
Line 359 becomes `if (shouldRotateKey(err)) attempt.current = null`. Status-based KEEP for 408/429/5xx is checked before
the code so the table's "408 any code" and "5xx" rows win over a stray code. Codes match exactly (case-sensitive), per edge case.
RISK path (`:349-356`) is untouched and still runs first, so it keeps its own unconditional rotation (AC-12).
`setPlaceError(errorMessage(err))`, `setShortfalls`, `setPlacing(false)` are unchanged (AC-10). No config, data or API change.
If `react-refresh/only-export-components` lint complains about the extra export, the CEO may instead accept a test-only
check through the UI; no lint rule was found in the app's eslint config, so the export is expected to pass.

## 3. AC mapping and tests (extend `src/pages/__tests__/CheckoutPage.place.test.tsx`, reuse its `problem()` helper)
- Unit: table-driven `it.each` over `shouldRotateKey` covering every requirements row (incl. 409 lower-case code, 409 no code, 409 OUT_OF_STOCK without items, 422 IDEMPOTENCY_KEY_REUSED, TypeError).
- UI (place, place again unchanged, compare `api.place.mock.calls[0][1]` vs `[1][1]`), at least one per class:
  AC-1 408; AC-2 429 RATE_LIMITED; AC-3 409 RESOURCE_CONFLICT; AC-4 409 SOMETHING_NEW and 409 no code (equal key);
  AC-5 400 VALIDATION_FAILED, 401, 403, 404 one test each; AC-6 422 IDEMPOTENCY_KEY_REUSED; AC-7 `it.each` of 409/422 rotate codes;
  AC-8 422 DOMAIN_RULE_VIOLATION and 422 COUPON_EXPIRED (different key); AC-9 existing 500/503/network tests stay, add 502.
- AC-10: each UI test asserts the alert text equals `errorMessage(err)` and the Place button is enabled; existing OUT_OF_STOCK shortfall tests (lines ~434-448) stay green.
- AC-12: existing tests for shape change, success clearing and RISK (line ~461) stay; add "KEEP (429) then change coupon/payment -> new key" and "KEEP then ROTATE then retry -> new key".
- Note: existing test at line ~318 uses `400 COUPON_EXPIRED`; it still rotates (4xx fallback). Line ~498 `400 BAD` still rotates.

## 4. AC-11 mutation check
Tester temporarily replaces the body of `shouldRotateKey` with (a) `return isApiError(err) && err.status < 500` and (b) `return false`,
runs the place test file, and records in evidence.md that (a) fails the 408/429/409-keep tests and (b) fails the 4xx/409/422 rotate tests; then reverts.

## 5. Alternatives rejected
- Put the function in `src/lib/errors.ts`: more reusable, but AC-13 forbids changes outside CheckoutPage.tsx and tests.
- KEEP list instead of ROTATE list for 409: an unknown future 409 would rotate and risk a duplicate order; requirements say unknown 409 KEEP.

## 6. Reuse
Reuses `isApiError`/`problemCode`/`errorMessage` (`src/lib/errors.ts`) and the test's `problem()` helper. No existing key-retention helper found in the app.

## 7. Risks and rollback
Risk: a ROTATE code missing from the set makes a definitive 409 KEEP; harmless (server replays or rejects again; user edit changes shape).
Rollback: revert the one line and function. Files: `apps/product-catalog/src/pages/CheckoutPage.tsx`, `apps/product-catalog/src/pages/__tests__/CheckoutPage.place.test.tsx`.
