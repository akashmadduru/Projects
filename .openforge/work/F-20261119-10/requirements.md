# F-20261119-10 Requirements: return refund correctness, part 2: coins (O-05b)
> Split by the CEO on 2026-11-21 following the CTO's recommendation (complexity.md: two objectives, each under 600 lines). The AC text is the CPO's, copied unchanged. The sibling objective is F-20261119-09 (shipping, COD, legs).


## Problem
Return refunds are wrong or unrecoverable today. Shipping is never refunded. A COD return is only marked refunded, or falls back to a direct wallet credit, so `payments.refunded_amount` stays 0. A split refund (wallet leg then card leg) leaves the return PICKED_UP if the card leg fails, and a retry can credit the wallet twice. Redeemed coins are not returned on a return, and earned coins are reversed only on a full refund. With F-04, a card refund can also come back PENDING.

## Users affected
Customers returning items (money and coins). Admins running return refunds and retrying failures. Finance, who read `refunded_amount` and the ledger.

## Scope
- `ReturnService.refund` and its refund plan: shipping rule, COD wallet credit, per-leg progress and retry, handling of a PENDING card leg.
- Coin handling on returns: give back redeemed coins, reverse earned coins pro rata.
- Additive DB migrations only.

## Non-goals
- No bank/UPI payout flow.
- No change to the F-04 Razorpay refund state machine. It is only consumed.
- No order-status display work (B-3), unless needed for correctness.
- No new service, datastore or vendor. No per-shipment return rework (TD-P9-13). No currency-on-return or admin INR display fix (TD-P6-22).

## Acceptance criteria
Return refund = the admin refund action on a PICKED_UP return (`ReturnService.refund`).

Shipping

COD

Split refund and retry

PENDING card leg (F-04)

Coins
- AC-14: Given an order that used N redeemed coins, when its return is refunded, then the coins are given back in the coin ledger, pro rata to the refunded items value share. A full return gives back all N, and the total across returns never exceeds N.
- AC-15: Given a partial return, when it is refunded, then earned coins are reversed pro rata to the refunded items value share (rounded down, never below zero), as a ledger row linked to the return.
- AC-16: Given a full return (or the final return that completes the order), then total reversed earned coins across returns equals the coins earned on the order, with no rounding remainder left.
- AC-17: Given the coin steps are retried or the event is redelivered, then there is no duplicate coin ledger row for the same return.
- AC-18: Given coins already spent, when earned coins are reversed, then the balance behaviour is whatever the Owner or architect decides (see open question); it is never silently skipped without a record.

## Edge cases and abuse cases
- Concurrent refund clicks or retries on the same return: one set of ledger rows, one card refund.
- Refund amount 0 (fully coin or discount covered): no payment calls, return still completes.
- Wallet-only order: no card call. Card-only order: no wallet credit. `refundTo=WALLET` on a card order sends the whole remainder to the wallet.
- Promotion-service down during the coin step: the return must not be lost. Refund completion and coin catch-up must be retryable.
- Payment-service down: 503 and no partial state change beyond recorded leg progress.
- Rows created before the migration (no leg progress): a retry must still work and must not double-credit.
- A customer cannot trigger or retry a refund (admin only). Idempotency keys are not guessable or reusable across returns.

## Open questions that block work
- None. AC-18 is settled from existing policy: the reversal is clamped to the current balance (CoinService.java:159-175). See F-20261119-09/objective.md for the CEO ruling.
## Assumptions to verify
1. The order-service card-refund call today treats a non-exception response as success, so PENDING would be misread as done.
2. Idempotency keys exist per leg (`return-wallet-<id>`, `return-rest-<id>`, `return-<id>`) and are honoured by wallet and payment-service. Confirm they protect against retry after a partial failure.
3. promotion-service has an idempotent redeemed-coin reversal (CoinService.java:119-136) that is reusable for returns. A pro-rata earned-coin reversal may or may not exist.
4. Shipping fee and coins redeemed or earned are stored on the order, so a "whole order returned" check is possible, including across earlier partial returns.
5. COD payments have a payment row that payment-service can refund to WALLET. `refunded_amount` is updated by that path.
6. Event and stock release run only after money movement, so deferring them for PENDING is safe.
