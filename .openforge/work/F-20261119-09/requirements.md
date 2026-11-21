# F-20261119-09 Requirements: return refund correctness, part 1: shipping, COD, split legs, PENDING card leg (O-05a)
> Split by the CEO on 2026-11-21 following the CTO's recommendation (complexity.md: two objectives, each under 600 lines). The AC text is the CPO's, copied unchanged. The sibling objective is F-20261119-10 (coins).


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
- AC-1: Given an order with shipping fee S>0 and a return covering every item at full quantity, when the refund runs, then the refund amount includes S in addition to the (discount pro-rated) items value.
- AC-2: Given the same order and a return of only some items or a reduced quantity, when the refund runs, then the refund amount excludes shipping and equals the pro-rated items value only.
- AC-3: Given returns that together cover every item of the order in full (for example one per shipment), when the return that completes the order is refunded, then shipping is refunded exactly once, on that completing return. Earlier partial returns refund no shipping. (Amended by the CEO on 2026-11-21 per the Owner's answer recorded in design.md, CTO condition 2. The original text said only a single full return refunds shipping.)

COD
- AC-4: Given a paid COD order, when its return is refunded, then payment-service records a refund for the amount with destination WALLET, `payments.refunded_amount` increases by that amount, and the user's wallet is credited exactly once.
- AC-5: Given a COD refund where payment-service rejects the call with a non-availability error, when the refund runs, then no direct wallet credit is made outside payment-service and the return is not marked refunded. When payment-service is unavailable, the response is 503 and the return stays PICKED_UP.

Split refund and retry
- AC-6: Given a split refund, when the wallet leg succeeds, then the wallet-leg completion is persisted in the DB before the card leg is attempted, separately from the card-leg state.
- AC-7: Given the wallet leg succeeded and the card leg failed, when the failure is returned, then the wallet-leg state is recorded as done, the card-leg state as failed or not done, and the return stays PICKED_UP.
- AC-8: Given AC-7, when the refund is retried and the card leg succeeds, then the wallet ledger shows exactly one credit for that return (same total as one wallet leg), the card refund is recorded once, and the return becomes REFUNDED.
- AC-9: Given a return already REFUNDED, when the refund is called again, then no wallet, card or coin movement happens and the response is unchanged (no duplicate ledger rows).
- AC-10: wallet leg plus card leg equals the refund amount to the cent, for every order mix of wallet, card and coins.

PENDING card leg (F-04)
- AC-11: Given the card leg returns PENDING, when the refund runs, then the return is not marked REFUNDED, stock is not released, and RETURN_REFUNDED is not published. The card-leg state is recorded as pending.
- AC-12: Given a pending card leg, when F-04 confirms it SUCCEEDED (webhook or poll), then the return reaches REFUNDED with stock release, event and coin handling done exactly once. When it is confirmed FAILED, the card leg is marked failed and the return stays PICKED_UP and retryable without re-crediting the wallet. (Which component drives completion is for the architect; the outcome is the requirement.)
- AC-13: Given a retry while the card leg is PENDING, then no second card refund is created.

Coins

## Edge cases and abuse cases
- Concurrent refund clicks or retries on the same return: one set of ledger rows, one card refund.
- Refund amount 0 (fully coin or discount covered): no payment calls, return still completes.
- Wallet-only order: no card call. Card-only order: no wallet credit. `refundTo=WALLET` on a card order sends the whole remainder to the wallet.
- Promotion-service down during the coin step: the return must not be lost. Refund completion and coin catch-up must be retryable.
- Payment-service down: 503 and no partial state change beyond recorded leg progress.
- Rows created before the migration (no leg progress): a retry must still work and must not double-credit.
- A customer cannot trigger or retry a refund (admin only). Idempotency keys are not guessable or reusable across returns.

## Open questions that block work
- None. Coin questions moved to F-20261119-10.
## Assumptions to verify
1. The order-service card-refund call today treats a non-exception response as success, so PENDING would be misread as done.
2. Idempotency keys exist per leg (`return-wallet-<id>`, `return-rest-<id>`, `return-<id>`) and are honoured by wallet and payment-service. Confirm they protect against retry after a partial failure.
3. promotion-service has an idempotent redeemed-coin reversal (CoinService.java:119-136) that is reusable for returns. A pro-rata earned-coin reversal may or may not exist.
4. Shipping fee and coins redeemed or earned are stored on the order, so a "whole order returned" check is possible, including across earlier partial returns.
5. COD payments have a payment row that payment-service can refund to WALLET. `refunded_amount` is updated by that path.
6. Event and stock release run only after money movement, so deferring them for PENDING is safe.
