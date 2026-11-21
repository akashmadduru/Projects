# 0009 Return coins are settled per return by promotion-service from the RETURN_REFUNDED event

## Status
Accepted (2026-11-21, CTO, class L, standing Owner approval R-20261119-01). Accepted with six conditions, recorded in `.openforge/work/F-20261119-10/design.md` under "CTO decision".

## Context
Today, return refunds handle coins badly:
- Redeemed coins are given back only on cancel or payment failure, and only in full (`CoinService.releaseRedeem`, CoinService.java:119-137).
- Earned coins are reversed only when a `RETURN_REFUNDED` event reports the order fully refunded (`onFullyRefunded`, :159-179).

So a partial return gives back nothing and reverses nothing.

Only promotion-service knows how many coins an order earned: `EARN_ORDER` is computed at DELIVERED (:143-157). The money path in order-service was hardened in F-09 (ADR 0008), with a single finalize that writes `RETURN_REFUNDED` to the outbox in the same transaction as REFUNDED. The Owner requires that coins never block the money refund.

The CEO ruling for AC-18: the reversal is clamped to the current balance, and the balance never goes negative.

## Decision
- **Event-driven, off the money path.** order-service adds two fields to `RETURN_REFUNDED`:
  - `returnItemsValue`: the return's gross item value;
  - `orderItemsValue`: the order subtotal.

  It makes no new remote call, and `ReturnService` and `completeRefund` are unchanged. Delivery is at least once, through the existing outbox and Kafka, keyed by orderId.
- **promotion-service `onReturnRefunded`** runs under the existing per-user advisory lock and works out two dues for the return:
  - `share = returnItemsValue / orderItemsValue`;
  - redeemed `N` and earned `E` are read from the order's ledger rows;
  - each due is `floor(X × share)`, capped at `X − prior dues`;
  - the return that completes the order (`paymentStatus=REFUNDED`) takes `X − prior dues`, so the totals are exact and do not depend on event order.

  Legacy whole-order rows (`redeem-<orderId>`, `earn-<orderId>`) count as prior dues.
- **Ledger rows.** Both reuse reason `REVERSE_ORDER`:
  - `+redeemDue` under `return-redeem-<returnId>`;
  - then `-min(earnDue, max(0, balance))` under `return-earn-<returnId>`.
- **Record and idempotency.** A new table, `promotion.coin_return_reversals` (V5, additive), has `return_id` as its PK and stores `redeem_due`, `earn_due` and `earn_taken`.
  - It makes a replay a no-op, including after a clamp to 0. The ledger unique key is a second guard.
  - It records a clamped or zero reversal, which a ledger row cannot do because of `CHECK delta <> 0`.
  - A shortfall is also logged as a WARN, `COIN REVERSAL SHORTFALL`.
- **Age-gate exemption, only for `RETURN_REFUNDED` events that carry `returnId`.** Today the gate drops any event older than `coins.maxEventAgeDays` (2 days) before the type dispatch (OrderEventListener.java:39-44). Without the exemption, a late DLT replay or a long promotion-service outage would lose the coin step without a trace. Legacy `RETURN_REFUNDED` events without `returnId`, and all other event types, keep the 2-day filter.
- **Legacy events** without the new fields keep using `onFullyRefunded`.
- **promotion-service deploys first**, before order-service. The order is recorded in the runbook.

## Alternatives considered
- **Synchronous Feign call in `finalizeRefund`.** This puts promotion-service on the money path and needs its own progress column, retry job and lease. Rejected.
- **order-service computes the coin counts and calls a new internal endpoint.** order-service does not know the earned coins, so the earn rules would be duplicated. Rejected.
- **Ledger reference keys only, with no table.** A clamp to zero leaves no record, and "prior due" cannot be derived once a take was clamped. Rejected.
- **Recover the clamped shortfall on later returns.** This is a new policy beyond the CEO ruling. Rejected; the shortfall is recorded and logged as a WARN.

## Consequences
- Coin settlement lags the money refund by the outbox and Kafka latency.
- promotion-service outages delay coins but never lose them. The DLT replay is idempotent.
- An earned-coin shortfall when the customer has already spent the coins is accepted and recorded; it is not recovered.
- **Deploy order:** promotion-service first, then order-service, as the runbook states.
  - Until order-service is deployed, events lack the new fields and promotion-service takes the legacy path.
  - An old promotion-service would ignore the new fields (`ignoreUnknown`).
- **Rollback caveat (runbook).** Suppose promotion-service is rolled back after per-return reversals exist. The old `onFullyRefunded` would then run on a completing return and reverse all earned coins again on top of the per-return reversals. This over-reversal is still clamped to the balance. Roll back promotion-service only before any completing return is refunded, or accept and correct the clamped over-reversal.
- The new table is retained on user deletion, like the ledger. Adding it to `PrivacyExportController` is a backlog note.
- The PR must stay under 600 production lines; the estimate is about 145.

## Work id
F-20261119-10 (O-05b, AC-14..AC-18). Design: `.openforge/work/F-20261119-10/design.md`. Builds on ADR 0008 (F-20261119-09).
