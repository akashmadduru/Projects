# 0014 Per-shipment cancel: a claim under the order lock, refund keys per shipment, the remainder and shipping on the completing event

## Status
Accepted (2026-11-23, CTO conditions folded in; the CEO judged the estimate change from 470 to 490 not material, under the 560 stop). Class L, F-20261119-14. The CTO accepted it with conditions. Conditions 2 (durable wallet credit), 3 (runbook query) and 5 (AC-4 disclosure) are folded in below. Owner approval is standing (R-20261119-01).

## Context
- On a multi-shipment order, only the whole order can be cancelled.
- A seller cannot reject a shipment, and no per-shipment refund exists.
- A whole cancel after a partial cancel would refund `total - wallet` again under `cancel-<orderId>`: a double refund.
- Return shipping (ADR 0008) never fires on an order with cancelled lines, because those lines are never "covered".
- A cancel can race a ship, because `advanceShipment` takes no row lock.
- No new service, datastore or vendor is allowed. The ADR 0008 leg machinery and the durable `WalletCreditBacks` (TD-P8-07) must be reused.

## Decision
**Endpoints.** Both take a reason and return 200 on an idempotent replay.
- `POST /api/seller/shipments/{id}/cancel`: own approved seller only; 404 otherwise, 403 if not approved.
- `POST /api/admin/orders/{id}/shipments/{shipmentId}/cancel`: ADMIN.
- Allowed only before dispatch (PLACED, CONFIRMED, PACKED). Not allowed on PENDING_PAYMENT or on risk hold (409).

**Data.** V14 adds nullable or defaulted columns to `order_shipments`:
- `cancel_refund_amount`, `cancel_shipping_refund`;
- `cancel_wallet_leg`, `cancel_rest_leg`, `cancel_rest_route`;
- `cancel_state` (REQUESTED, FAILED or DONE);
- `cancel_attempt`.

**Claim, then money, then commit.** The lock order is always the order row first (`lockById`, PESSIMISTIC_WRITE), then its shipments. No remote call runs inside a transaction.
- **T1 (claim).** Validate, freeze the amount and legs on the first claim, and set REQUESTED. After a reported FAILED, bump the attempt instead.
- **Card leg.** The only synchronous money call, and strict: FAILED or unreachable gives 503 with nothing changed. A FAILED state therefore means no money moved.
- **T3 (commit).** One outbox transaction holds:
  - CANCELLED and DONE, plus `recordRefund`;
  - the durable wallet credit row (the wallet leg plus any DIRECT_WALLET rest leg) in `WalletCreditBacks`;
  - RELEASE_STOCK keyed by shipment;
  - the events.

  After commit, one inline attempt is made, then the existing JobLock retry takes over. A wallet-only cancel succeeds while payment-service is down.
- **Ship.** A ship takes the same lock. It is refused (409) while REQUESTED; a FAILED claim is cleared when the shipment ships.

**Keys.**
- Card leg: `ship-cancel-<shipmentId>`. After each reported FAILED, `-r<n>` (a FAILED key replays FAILED forever).
- Wallet credit: `ship-cancel-wallet-<shipmentId>` in `orders.pending_wallet_credits`. `ON CONFLICT DO NOTHING` means a replay never queues a second credit.
- None of these can collide with `cancel-<orderId>`, `order-wallet-back-<orderId>` or `return-*`.

**Amount and remainder.**
- A line is covered when it is in a non-rejected return, or in a shipment that is cancelled or has a cancel claim.
- A cancel that does not complete the order refunds `paidShare(shipment gross)`, using the same formula and rounding as returns.
- The cancel or return that leaves every line covered takes the remainder: paid items minus every earlier item refund. The shares therefore sum to the total to the cent.
- Legs use the ADR 0008 split, extracted and shared. Siblings are returns plus cancels.

**Shipping.** The completing event carries `shippingAmount` once: the cancel at claim time, or the return at request time (ADR 0008). Both check under the order lock that no return or cancel already carries it. Shipping is never reversed from the seller: cancel events carry no return fields.

**Whole-order cancel** of a non-PENDING_PAYMENT order = a claim of every live shipment in one T1. The last one frozen takes the remainder and the shipping. The order-level follow-ups (coupon, coins, deals, exchange; no order-level wallet credit-back, since the per-shipment rows return it) run once, on the transition to order CANCELLED. `cancel-<orderId>` stays only for unpaid orders, expiry and payment-failed.

**Zero or COD.** COD_PENDING or a refund of 0.00 makes no synchronous payment call and records no refund. The wallet part of a COD order is returned through the durable credit row.

## Alternatives considered
- **One remainder refund under `cancel-<orderId>`.** It needs a second claim on `orders` and a second leg path. Rejected.
- **A time lease (as for returns) instead of REQUESTED.** Once it expires, a ship goes through while the refund outcome is unknown. Rejected.
- **CANCELLED on the `/status` route.** It puts money on a non-idempotent route. Rejected.
- **A separate `shipment_cancels` table.** It is a one-to-one join. Rejected.
- **Wallet leg first.** A later card FAILED would strand the shipment with money already moved. Rejected.
- **A strict wallet credit before T3.** It would return 503 for wallet cancels when payment-service is down, a regression from TD-P8-07. Rejected by the CTO.

## Consequences
- **PENDING gap.** A PENDING card refund commits the cancel. A later FAILED is not tracked, the same known gap as whole-order cancel (backlog).
- **Stuck REQUESTED.** A shipment whose card outcome is unknown, or that crashed between T1 and T3, cannot ship until the cancel is retried. The retry replays the same key. Runbook query:
  `SELECT id, order_id, cancel_attempt, cancel_rest_route, cancel_rest_leg, cancel_wallet_leg, updated_at FROM orders.order_shipments WHERE cancel_state = 'REQUESTED' AND updated_at < now() - interval '10 minutes' ORDER BY updated_at;`
  Wallet credits not yet delivered are rows with `idem_key LIKE 'ship-cancel-wallet-%' AND completed_at IS NULL` in `orders.pending_wallet_credits`. They alert as "WALLET CREDIT STUCK".
- **Behaviour change.**
  - A paid order's whole cancel sends one card refund per shipment, for the same total.
  - The wallet stays durable as today: a credit row per shipment instead of one per order, and it never gives a 503.
  - Across the deploy, a retry of an old cancel whose outcome was unknown uses a new key. payment-service's refundable cap blocks a card over-refund.
- **AC-4 gap (disclosed, accepted by CEO ruling).**
  - seller-service does not yet treat CANCELLED as terminal in its register.
  - Until the follow-up lands, a forged or stale DELIVERED for a cancelled shipment could book SALE, COMMISSION or FEE.
  - F-13's reconcile would then report a STATE_MISMATCH.
  - In normal flow, a cancelled shipment never reaches DELIVERED.
  - The follow-up is in the backlog, for when F-13 merges. The PR states the gap.
- **Rollback.** Revert the image; old code ignores the V14 columns.
  - First drain REQUESTED claims (query above).
  - While rolled back, the old whole cancel ignores partial cancels. Watch `cancel_state='DONE'` on live orders. The card is capped by payment-service; the wallet exposure is at most the order's wallet amount.
  - Queued `ship-cancel-wallet-*` rows are still delivered by the old image's retry job.
- **Backlog.**
  - promotion `coins.onDelivered` earns on `totalAmount`, including cancelled shipments.
  - inventory `release` is not warehouse-scoped.

## Work id
F-20261119-14 (TD-P9-12, TD-P9-13). Design: `.openforge/work/F-20261119-14/design.md`. Builds on ADR 0008 (legs) and ADR 0012 (F-13 seller ledger).
