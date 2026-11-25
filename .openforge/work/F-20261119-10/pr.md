# Return coin reversal: per-return give-back of redeemed coins and reversal of earned coins (O-05 part 2, ADR 0009)

Branch `forge/F-20261119-10-return-coins`, head `820e5a8d`, built on top of F-20261119-09 (b3cda340). Merge order: F-01, F-04 part A, F-04 part B, F-09, then this branch.

**Deploy promotion-service first.** An old promotion-service ignores the new event fields and keeps the legacy full-refund path. A new promotion-service accepts both old and new events.

## Why
Coins were adjusted only when a whole order was refunded. A partial return kept every earned coin and returned none of the redeemed ones, and several returns on one order could not add up to the correct total.

## What changed
- **order-service:** `RETURN_*` outbox events now carry `returnItemsValue` and `orderItemsValue`. Coins stay off the money path, and promotion-service is never called synchronously.
- **promotion-service:**
  - `onReturnRefunded` runs under the per-user advisory lock. It gives back `floor(N × value / orderValue)` redeemed coins and reverses `floor(E × value / orderValue)` earned coins, capped at what is still due.
  - The completing return (paymentStatus=REFUNDED) takes the exact remainder, so totals are exactly N and E in any order.
  - Spent coins: the balance never goes below 0. The shortfall is recorded in `earn_due`/`earn_taken` and logged at WARN with UUIDs only.
- **V5 `coin_return_reversals`:** primary key `return_id` makes replays a no-op. A CHECK constraint keeps dues non-negative and `earn_taken <= earn_due`.
- **Hardening:**
  - Payload amounts are clamped: negative, zero or oversized values cannot write a negative due or give back more than N or E.
  - The legacy `onFullyRefunded` and `releaseRedeem` subtract per-return dues, so mixed old and new events never double-count.
- **Age gate:** `RETURN_REFUNDED` events that carry both `returnId` and `returnItemsValue` are exempt from the 2-day age gate. Every other event keeps the filter.

## Evidence
- `forge-verify --changed` PASS at 820e5a8 for the order-service and promotion-service tests: 133 tests, 0 failures, 0 skipped. Gradle reused the test results from a run on identical inputs.
- QA mapped AC-14..AC-18 to passing tests in the JUnit XML. `forge-gate` PASS.
- 164 production lines (class L, limit 600).
- Guard tests were shown to fail on the pre-fix code: the negative-value test gave -10 instead of 0, and the legacy-after-per-return test gave -40 instead of -30.

## Review
- Independent code and security review ran for 2 rounds, and both approve 820e5a8.
- Round 1 ended in approval with minors, which were all fixed:
  - The legacy path ignored per-return rows.
  - The rounding mode was not pinned by a test.
  - There was no end-to-end check that the completing return's event carries REFUNDED.
  - The payload amounts were not range-checked (security).
- Design: ADR 0009 (Accepted). Owner policy: spent coins are clamped at 0 and the shortfall is recorded.

## Known issues (not blocking)
- A runbook sentence in `docs/guides/operations.md` is garbled, and the documented SHORTFALL log format leaves out `user=` (doc nits).
- The privacy-export follow-up for `coin_return_reversals` is tracked only in the runbook. The Owner should decide whether to add it to the tech-debt register.
- Redeemed coins given back on a return do not expire, the same as on cancel. This is accepted and documented.
- On rollback, the old promotion-service's full-refund path ignores per-return rows (documented in the runbook).

🤖 Generated with [Claude Code](https://claude.com/claude-code)
