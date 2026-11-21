# Return refunds: shipping on the completing return, COD through payment-service, tracked wallet and card legs, PENDING card refunds finished by a watch (TD-P6-14, TD-P8-08 part 1)

Branch `forge/F-20261119-09-return-refunds`, head `b3cda340`, built on top of F-20261119-04 (218998b1). Merge order: F-01, then F-04 part A, then part B, then this branch.

**Deploy payment-service first.** An old payment-service sends no `refundStatus`. For card refunds the new order-service reads that as PENDING, so a refund is never finalized too early. The runbook covers this.

## Why
- Return refunds never included shipping.
- COD returns credited the wallet directly, so `payments.refunded_amount` stayed at 0.
- A split refund whose wallet leg succeeded and card leg failed left the return stuck, with no safe retry.
- After F-04, a Razorpay card refund can sit in PENDING, and order-service had no way to see when it settled.

## What changed
- **Shipping (Owner policy):** whichever return completes the order refunds shipping, exactly once. That return may be the last of several, for example one per shipment. Partial returns refund only items. The amount is stored in `order_returns.shipping_refund`.
- **Legs:**
  - V13 (additive, `SET LOCAL lock_timeout='5s'`) records the frozen wallet and card amounts, wallet-leg done, card-leg state and attempt, and a refund lease.
  - The legs are frozen once, inside a lock on the order row, with `wallet + card = R` to the cent and capped against sibling returns.
  - Wallet leg done is committed before the card call.
  - A retry replays the same keys. Only a card leg that payment-service reported FAILED moves to `return-<id>-r<n>`. An exception never bumps the attempt.
  - The lease is stamped by the DB clock and released only by its holder.
- **PENDING:**
  - payment-service now reports `refundStatus`.
  - A PENDING card leg does not finalize the return.
  - `ReturnRefundWatch` polls with the same key, which payment-service answers from its database without calling Razorpay, and finalizes once in one transaction.
  - A poll that is still PENDING rotates the watch window.
- **COD:** refunded to the wallet through payment-service as a recorded refund. The direct wallet fallback is removed. A COD order with no payment row now fails loudly and stays PICKED_UP (backlog B-10).
- **Order status:** an order is marked REFUNDED only when every line is returned and every return is REFUNDED or REJECTED. A REFUNDED order still routes any remaining money that is owed.

## Evidence
- `forge-verify --changed` PASS: env reference check, and order-service plus payment-service tests (27 suites, 201 tests, 0 skipped, 0 failures).
- QA mapped AC-1..AC-13 to passing tests in the JUnit XML. `forge-gate` PASS.
- 594 production lines (class L, limit 600). The fix round reached 641 lines and was trimmed on the Owner's decision.

## Review
- Independent code and security review ran for 3 rounds.
- Round 1: the code reviewer found a **major money bug** that security missed. Refunding the shipping-carrying return first marked the order REFUNDED, and a sibling return's card share was then silently never paid. It is fixed.
- Round 2: the regression test for that bug was found to pass even against the buggy code. It was rewritten so it fails against the old logic, as the engineer showed.
- Both reviewers approve b3cda340.
- Design: ADR 0008 (Accepted). The CTO accepted it with 5 conditions, and the Owner answered the shipping question.

## Before deploy (Owner or ops)
Run the reconciliation queries in `docs/guides/runbooks/payment-problems.md` for PICKED_UP returns created before V13:
- A return with a `return-rest-<id>` wallet row has already been paid its remainder. **Do not retry it.** Settle it by hand.
- Before marking such a return REFUNDED by hand, check that the `return-wallet-<id>` leg exists when a wallet share was expected. Then release stock and record the event, because a manual status flip skips both. This is a security review minor.

V13 has not been released, so a local database that ran an earlier V13 needs `flyway repair`.

## Known issues (not blocking)
- B-10: COD orders with no payment row cannot be refunded until a row is ensured.
- The concurrent-siblings test catches a missing lock only under real thread overlap, so it depends on timing.
- The runbook is stricter than it needs to be for WALLET-method retries.
- Coin reversal is the follow-up objective F-20261119-10.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
