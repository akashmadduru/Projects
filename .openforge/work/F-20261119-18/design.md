# F-20261119-18 design (class S stub)

Class S needs no design document or ADR (constitution section 4). This stub exists for the PR gate and records where the decisions live. The CEO wrote it.

## Approach
The CTO decision in complexity.md ("## CTO decision") sets the approach: follow the existing tests, mocking at the service modules as the store tests do; no new dependency; no snapshots; deterministic timers; restore globals after each test.

## Production change (CEO ruling, 2026-11-25)
The tests exposed a real bug: `safeNext` in `apps/product-catalog/src/lib/redirect.ts` accepted `/\t/evil.example`, and the URL parser strips the TAB, giving `//evil.example`. The objective allows a production fix with CEO sign-off.
- **Approved fix:** +2 lines that reject any ASCII control character. The existing checks are unchanged, so the function can only get stricter.
- **Impact today:** low. Every consumer navigates through react-router, so the result was a crash, not a redirect. Without the fix it is a latent open redirect.
- **Review:** both reviewers approved the change at 305d540.
