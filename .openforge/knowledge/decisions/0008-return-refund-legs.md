# 0008 Return refunds run as frozen, tracked legs; PENDING card legs are resolved by an idempotent replay poll

## Status
Accepted (2026-11-21, CTO, class L, standing Owner approval R-20261119-01). Accepted with five conditions, recorded in `.openforge/work/F-20261119-09/design.md` under "CTO decision". The Owner answered condition 2 the same day.

## Context
`ReturnService.refund` moves money in two legs, wallet then card, COD or wallet-rest, and records nothing about which leg ran. This causes four problems:
- If the card leg fails after the wallet leg, the return is stuck PICKED_UP (TD-P6-14).
- COD failures fall back to a direct wallet credit that payment-service never records (TD-P8-08).
- Shipping is never refunded.
- Since F-04 (ADR 0003), a Razorpay refund is PENDING until a webhook settles it. However, `RefundResponse.status` is the *payment* status, and order-service ignores the response. A PENDING refund is therefore read as done, and order-service never learns that it later FAILED.

Owner policy:
- 2026-11-20: COD returns are refunded to the wallet as a payment-service refund.
- 2026-11-21: shipping is refunded once, by whichever return completes the whole order.

## Decision
- **Shipping: the completing return, at request time.** In `requestReturn`, `shippingAmount` is added to `refund_amount` and stored in `order_returns.shipping_refund` when both hold:
  - the prior non-rejected returns plus this one cover every order line fully;
  - no other non-rejected return already carries `shipping_refund`.

  A partial return that does not complete the order gets no shipping. With one return per shipment, the last one gets it, exactly once. Because shipping is set at request time, the amount shown to the customer is the amount paid, and returns created before the migration keep their amounts.
- **Frozen legs.** On the first refund attempt, order-service stores `wallet_leg_amount`, `rest_leg_amount` and `rest_route` on `order_returns` (V13, additive). The wallet leg is pro rata and capped by the wallet and card amounts still unrefunded across sibling returns, so `wallet + rest = refund` to the cent. Every retry sends the same amounts under the same keys.
- **Tracked progress.**
  - `wallet_leg_done_at` is committed before the rest leg starts.
  - The rest leg has a state (PENDING, SUCCEEDED or FAILED) and an attempt counter.
  - The key is `return-<id>` for attempt 0 (the existing key). Only a FAILED reported by payment-service starts `return-<id>-r<n>`, through a conditional bump; an exception never bumps the attempt.
  - A lease column serialises concurrent refund calls on one return.
- **Finalize once.** One transaction marks the return REFUNDED (only from PICKED_UP, with `@Version`), records the order refund, and enqueues RELEASE_STOCK and RETURN_REFUNDED through the outbox. This is the only completion point; F-10 adds coin handling here.
- **PENDING resolution by replay poll.** payment-service's `RefundResponse` gains `refundStatus`, the status of the refund row for the idempotency key, including on replay. A `@Scheduled` + `JobLock` job in order-service re-sends the same key for returns whose leg is PENDING. The replay is a DB lookup before any gateway call, so no second provider refund can be created.
  - SUCCEEDED: the job finalizes the return.
  - FAILED: the return stays PICKED_UP and retryable, and the wallet leg is not repeated.
- **COD only through payment-service** with destination WALLET. The direct-credit fallback is removed and no payment row is created on demand. Any error leaves the return PICKED_UP.
- **Deploy payment-service first.** order-service reads a missing `refundStatus` (an old payment-service) as SUCCEEDED, which is unsafe, so the runbook must state the deploy order explicitly.
- The F-04 refund state machine is consumed, not changed.

## Alternatives considered
- **Push from payment-service on refund settlement** (new order-service internal endpoint, `reported` flag on `payment.refunds`, reporter job). This needs a second migration and couples F-04's webhook transaction to order-service. Rejected; the replay poll reuses an existing endpoint, and PENDING returns are rare and slow to settle anyway.
- **Separate `return_refund_legs` table.** It needs more code and a join for a fixed pair of legs. Rejected.
- **Add shipping at refund time.** The displayed refund would no longer match the amount paid, and pre-migration rows whose wallet leg already ran would split wrongly. Rejected.
- **Shipping only on a single full return** (the first draft). Multi-shipment orders are returned per shipment, so they would never get shipping back. Superseded by the Owner's answer of 2026-11-21.
- **Keep the fixed key and ask the admin to refund by hand after FAILED.** A FAILED key replays forever, so the return could never complete. Rejected.

## Consequences
- payment-service must deploy before order-service (runbook).
- A PENDING return completes up to one poll interval (default 5 min) after Razorpay settles.
- On multi-shipment orders, the completing return refunds more than its items value, by the shipping amount. Shipping is not split per shipment; per-shipment rework is TD-P9-13.
- A COD order whose payment row was never created at placement (OrderService.java:406-411) now fails its refund loudly (404), and the return stays PICKED_UP. The runbook covers this. Backlog B-10: ensure a COD payment row exists at refund.
- Rollback means reverting the images. The V13 columns are ignored by old code.

## Work id
F-20261119-09 (TD-P6-14, TD-P8-08). Design: `.openforge/work/F-20261119-09/design.md`. Sibling: F-20261119-10 (coins).
