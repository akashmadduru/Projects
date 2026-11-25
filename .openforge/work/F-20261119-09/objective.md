# F-20261119-09 Objective (portfolio O-05, run R-20261119-01)

## Objective (portfolio.md:145)
"Refund correctness: shipping, COD, split, coins returned. Depends on O-04 (refund states)." Items: TD-P6-14, TD-P8-08.

## Goal
Make return refunds correct and recoverable:
- shipping refunded under the Owner's policy;
- COD returns refunded through a recorded payment;
- the wallet and card legs of a split refund tracked so a partial failure can be retried;
- redeemed coins returned, and earned coins reversed pro rata.

## Base
Stacked on F-20261119-04 head 218998b1. F-04 is the Razorpay refund states work; card refunds can now be PENDING, then SUCCEEDED or FAILED. Merge order: F-01, F-04 (PRs A and B), then this branch.

## Owner decisions (AskUserQuestion, 2026-11-20)
1. **Shipping:** refunded only when the whole order is returned. Partial returns refund items only.
2. **COD returns:** refunded by crediting the wallet, recorded as a refund on payment-service, so `payments.refunded_amount` and the ledger are correct. No bank or UPI payout flow.

## Facts (cited)
1. `ReturnService.refund(returnId)` builds a `ReturnRefundPlan` (`store.refundPlan`) and splits the refund into a wallet share and a remainder (`rest`). It refunds the remainder through `remote.refund(...)`. Source: services/order-service/order-service-app/src/main/java/com/acme/ecommerce/order/service/ReturnService.java:74-114.
2. promotion-service has `CoinService.redeem` (:105) and an idempotent reversal of redeemed coins used on cancel and payment failure ("Gives redeemed coins back", CoinService.java:119-136). Its use on returns is unproven; the architect must confirm.
3. The register describes the risks in phase6.md TD-P6-14 and phase8.md TD-P8-08:
   - if the wallet leg succeeds and the card leg fails, the return is left PICKED_UP;
   - COD falls back to a direct wallet credit with `refunded_amount` 0;
   - earned coins are reversed only on a full refund.

## Assumptions to prove (architect; G-1)
- How the order-service card-refund call behaves now that F-04 can return PENDING. It must not treat PENDING as done (related to B-3).
- What the existing idempotency keys on the refund legs are.
- Whether a pro-rata earned-coin reversal exists or needs a new endpoint.

## Constraints
- No new service, datastore or vendor. Migrations must be additive.
- Do not change the Razorpay refund-state machine from F-04; only consume it.

## Blocking questions
None. The Owner answered both policy questions above.

## CEO ruling (2026-11-21) on the CPO's AC-18 question
Existing policy: the full-refund earned-coin reversal is "clamped to the current balance", per `CoinService.java:159` (javadoc) and `:175`, `Math.min(earned, Math.max(0, balance))`. The pro-rata reversal follows the same rule: it is capped at the current balance, and the balance never goes negative. This is not a new policy, so it is not escalated to the Owner.
