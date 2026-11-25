# F-20261119-14 Design: per-shipment cancel and refund (order-service only)

Base: F-11 snapshot 71bba97 (`.openforge/worktrees/F-20261119-11R`). Paths are relative to `services/order-service/order-service-app/src/main/java/com/acme/ecommerce/order/`. Scope: AC-1..3 and AC-5..11. AC-4 is deferred by CEO ruling. COD placement and payment-service are not touched (F-15 owns them). ADR: `.openforge/knowledge/decisions/0014-per-shipment-cancel.md`.

## 1. Current behaviour
- Whole-order cancel only. `OrderLifecycleService.cancel` (:70-94) reads a `CancelPlan` (OrderStore:339-354, read-only, no lock), refunds `total - wallet` under `cancel-<orderId>` (:79) and ignores the returned refund status. Then `OrderStore.cancel` (:356-365) records `refund(total)`. `afterCancel` (:96-114) queues RESTORE_STOCK/DEALS/EXCHANGE, coupon, coins, a full wallet credit-back and ORDER_CANCELLED.
- `Order.cancel` (:438-454) cancels every shipment. `Order.isCancellable` (:520-522) treats CANCELLED shipments as cancellable, so a whole cancel after a partial cancel would refund the full total again.
- Shipments move forward only. `OrderShipment.advance` (:113-135) rejects CANCELLED with 409 INVALID_STATUS_TRANSITION. `OrderStore.advanceShipment` (:408-437) loads the shipment without a row lock. `Order.syncFromShipments` (:863-894) returns early when no shipment is live, so the order never becomes CANCELLED that way.
- Returns: `requestReturn` (Order:587-686) uses the pro-rata formula `gross*(subtotal-discount-coins)/subtotal`, HALF_UP to 2 decimals (:653-660). Shipping goes to the return where `completesOrder` (:689-692) is true and `shippingAlreadyRefunded` (:694-698) is false. Lines of cancelled shipments stay "remaining", so shipping is never refunded on such orders. `OrderStore.requestReturn` (:486-513) takes no order lock.
- ADR 0008 legs: `freezeLegs` (OrderStore:623-662), `walletShare` (:664-669), `route` (:672-685). Siblings are returns only.
- `WalletCreditBacks` (TD-P8-07) is the durable wallet credit:
  - `creditBack` (:70-101) inserts a row into `orders.pending_wallet_credits`, keyed `order-wallet-back-<orderId>` with `ON CONFLICT (idem_key) DO NOTHING`, then attempts it inline.
  - `retryDue` (:103-117) retries under JobLock with backoff.
- Consumers of SHIPMENT_STATUS_CHANGED (checked):
  - seller-service `OrderEventConsumer` (:113-126) books only DELIVERED parts. CANCELLED parts are ignored, and the re-booking of other parts is idempotent.
  - notification `OrderEventListener` (:154, :199-253) is silent on CANCELLED (`default -> null`). It mails on ORDER_CANCELLED.
  - promotion `OrderEventListener` (:51-63) ignores the type.

## 2. Proposed change
**Endpoints.** Both reuse `AdminRequests.CancelOrder(@NotBlank reason)`.
- `POST /api/seller/shipments/{id}/cancel` on `SellerShipmentController`. Class-level SELLER authority. `approvedSellerId` returns null → 403. Not the seller's shipment → 404 `ResourceNotFoundException("Shipment")`, the same as GET. Returns `SellerShipmentResponse`.
- `POST /api/admin/orders/{id}/shipments/{shipmentId}/cancel` on `AdminOrderController`, `hasRole('ADMIN')`. A shipment that is not on `{id}` → 404. Returns `OrderResponse`.
- Unauthenticated → 401 (SecurityConfig). The customer keeps `POST /api/orders/{id}/cancel`.
- `/status` with CANCELLED stays a 409, so no money ever moves on the status route.

**Migration V14__shipment_cancel_refund.sql** (additive, `SET LOCAL lock_timeout='5s'`), on `orders.order_shipments`:
- `cancel_refund_amount numeric(19,4)`, `cancel_shipping_refund numeric(19,4) NOT NULL DEFAULT 0`
- `cancel_wallet_leg numeric(19,4)`, `cancel_rest_leg numeric(19,4)`, `cancel_rest_route varchar(16)`
- `cancel_state varchar(12)`: null, REQUESTED, FAILED or DONE
- `cancel_attempt int NOT NULL DEFAULT 0`

No index and no backfill. F-14 keeps V14, because F-15 has no migration. Whichever branch is rebased second renumbers if needed (CTO condition 4).

**Keys.**
- Card leg: `ship-cancel-<shipmentId>` for attempt 0. Only a FAILED reported by payment-service moves to `ship-cancel-<shipmentId>-r<n>` (ADR 0008: a FAILED key replays FAILED forever).
- Wallet credit (CTO condition 2): the wallet leg plus any DIRECT_WALLET rest leg, as one row in `WalletCreditBacks`, key `ship-cancel-wallet-<sid>`.
  - No attempt suffix: a wallet credit never replays FAILED.
  - `ON CONFLICT DO NOTHING` makes a replay a no-op.
- Shipment UUIDs are unique, and no key can equal `cancel-<orderId>`, `order-wallet-back-<orderId>` or `return-*`.

**Amount** (`Order.cancelShare(s)`, frozen once per claim):
- `covered(line)` = quantity in non-rejected returns, plus quantity of shipments that are CANCELLED or hold a cancel claim (REQUESTED/FAILED/DONE).
- A shipment cancel *completes* the order when every line is covered once this shipment is counted.
- Not completing: `items = paidShare(gross_s)`, using the same formula as returns. It is extracted from Order:653-660 and shared by both paths. `shipping = 0`.
- Completing (remainder rule): `items = max(0, paidItemsValue() - Σ other cancel item parts - Σ non-rejected return item parts)`, where an item part is refund minus its shipping. `shipping = shippingAmount` if no return or cancel already carries shipping, else 0.
- `R = items + shipping`. The shares therefore sum to `total` to the cent.

**Legs.** Extract `Legs split(R, W, T, sibWallet, sibRest)` and `route(order, refundTo, rest)` from `freezeLegs`/`route`. They are used by returns and cancels. Siblings are the frozen returns (as today) plus frozen cancels. The math is unchanged: `wallet = min(round2(R*W/T), W - sibWallet, R)`, then rest = R - wallet, and any excess over the card room moves to the wallet. Cancels use refundTo=SOURCE. COD_PENDING, PENDING and FAILED route NONE.

**Flow** (`OrderLifecycleService.cancelShipments(actor, orderId, sellerOrNull, shipmentIds)`). The lock order is always the order row first (`orders.lockById`, PESSIMISTIC_WRITE), then its shipments. Remote calls never run inside a transaction.
1. **T1 claim** (`OrderStore.claimCancel`). Lock the order and check ownership (404). Then validate:
   - order PENDING_PAYMENT → 409 INVALID_STATUS_TRANSITION;
   - risk hold → 409 INVALID_STATUS_TRANSITION (shipment route only);
   - a shipment past PACKED → 409 ORDER_NOT_CANCELLABLE.

   Then per shipment:
   - DONE: skip (replay);
   - REQUESTED: reuse the frozen claim;
   - FAILED: `attempt++`, then REQUESTED;
   - null: freeze `cancelShare` and the legs, then REQUESTED.

   Every claim sets `updated_at` (used by the runbook query). Shipments are frozen in a stable order (createdAt, id), so the last one sees the others as covered and takes the remainder. Commit.
2. **Card leg, strict, per claimed shipment** (the only remote money call).
   - `remote.refund(orderId, rest, reason, key, dest)`, for routes PAYMENT_SOURCE or PAYMENT_WALLET with rest > 0.
   - FAILED → **T2f** (lock; if REQUESTED and the attempt matches, set FAILED) → 503 UPSTREAM_UNAVAILABLE.
   - An exception leaves REQUESTED → 503.
   - SUCCEEDED or PENDING means accepted.
   - Skipped for NONE, DIRECT_WALLET or 0. A wallet-only cancel therefore makes no synchronous payment call.
   - Nothing is credited before T3, so FAILED always means no money moved.
3. **T3 commit** (`OrderStore.commitCancel`). Lock the order. If the shipment is not REQUESTED at this attempt, return without an event and queue nothing (someone else committed). Otherwise, in one transaction:
   - shipment CANCELLED and DONE, plus a history row with shipmentId;
   - `recordRefund(R, fullyRefunded())`, if R > 0 and money moves (card route, or wallet credit > 0);
   - `walletCreditBacks.enqueue(userId, wallet + directRest, currency, orderId, "ship-cancel-wallet-<sid>")`, if > 0. This is a new keyed, insert-only overload; the JdbcClient joins the transaction, and a DB error rolls T3 back;
   - outbox `RELEASE_STOCK` through `remote.releaseStock(orderId, shipmentId, lines)`, keyed `RELEASE_STOCK:<order>:<shipment>`;
   - `syncFromShipments`.

   After commit, `walletCreditBacks.tryNow(key)` makes one attempt (it exposes the existing private `attempt`). It never throws, and the existing JobLock `retryDue` does the rest.

   If live shipments remain: SHIPMENT_STATUS_CHANGED, plus ORDER_STATUS_CHANGED and COD collected if the derived status moved (reuses `afterAdvance`). If none remain: the order becomes CANCELLED, then `afterCancel(order, walletCreditBack=false)` runs (RESTORE_STOCK is a no-op backstop, plus deals, exchange, coupon and coins), followed by SHIPMENT_STATUS_CHANGED and ORDER_CANCELLED.

**Whole-order cancel** (customer or admin, order not PENDING_PAYMENT, with shipments): `cancelShipments` with every live shipment, risk hold allowed (as today). `CancelPlan`, `cancel-<orderId>` and the old path remain only for PENDING_PAYMENT orders, expiry and payment-failed, where shipment cancels are impossible (AC-5). `cancel-<orderId>` is never sent once a shipment has a claim.

**Ship guard.** `OrderStore.advanceShipment` locks the order row first. `Order.advanceShipment` then rejects REQUESTED with 409 RESOURCE_CONFLICT "cancel in progress". A FAILED claim is cleared (amounts, state), which releases its shipping and sibling legs; the attempt is kept so keys stay unique.

**Returns (Part B).**
- `requestReturn` locks the order.
- `completesOrder` and `fullyRefunded` use `covered` (cancelled lines count).
- `shippingAlreadyRefunded` also counts a cancel claim with `cancel_shipping_refund > 0`.

No DTO or event schema change: `status=CANCELLED` on SHIPMENT_STATUS_CHANGED is already a valid value. Docs: endpoints.md, api.md, events.md, payment runbook (with the stuck-REQUESTED query in section 5).

## 3. AC → change
| AC | Met by |
|---|---|
| AC-1 | Seller/admin endpoint, T1-T3, `cancelShare` pro-rata, shared legs, RELEASE_STOCK keyed by shipment, one SHIPMENT_STATUS_CHANGED, PARTIALLY_REFUNDED via `recordRefund` |
| AC-2 | Key from shipmentId; DONE replay → 200, no remote call, no event, no second wallet row (insert keyed per shipment); frozen amounts |
| AC-3 | Strict card leg before T3; FAILED/exception → 503, status/history/stock/refunded unchanged, no wallet credit queued; retry uses the same frozen amounts and key (`-r<n>` only after a reported FAILED). A wallet-only cancel never fails on payment-service: the credit is durable in T3 |
| AC-5 | T1 validation before any remote call; 409 codes as listed |
| AC-6 | Seller ownership check in T1 → 404; admin order pin → 404; 403/401 from role and approval |
| AC-7 | `covered` and the completing rule on both cancels (A) and returns (B); one shipping claim checked under the order lock |
| AC-8 | Cancel events carry no `returnId`/`refundAmount`, so the seller consumer reverses nothing; RETURN_REFUNDED is unchanged |
| AC-9 | Whole cancel = claim of all live shipments; remainder rule; follow-ups only on the transition to order CANCELLED (once) |
| AC-10 | Order row lock in claim, commit and ship; REQUESTED blocks ship; state/attempt checks make T3 idempotent |
| AC-11 | Route NONE (COD_PENDING) or R=0 → no synchronous payment call, no refund row; the wallet part of a COD order is returned through the durable `WalletCreditBacks` row |

**Worked example (AC-9).**
- Order: S1=1000, S2=600, S3=400 (subtotal 2000); coupon 200, coins 100, shipping 50, so total 1750 and paid items 1700. Wallet W=350, card 1400.
- Seller cancels S1: R1 = 1000×0.85 = 850.00, wallet 170.00, card 680.00.
- Today the customer's whole cancel then refunds card 1400 and wallet 350 again: 2600 paid out on a 1750 order.
- New, S2: R2 = 510.00 (wallet 102, card 408).
- New, S3 (completing): items 1700-850-510 = 340 plus shipping 50, so R3 = 390.00 (wallet min(78, 350-272) = 78, card 312 = card room).
- Totals: 1750 = total, wallet 350 = W, card 1400. Coupon and coins are released once, at S3's commit.

## 4. Alternatives considered
- **A single remainder refund for whole cancel under `cancel-<orderId>`.** It needs a second claim marker on `orders` to stop a racing seller cancel before money moves, and a second leg path. Rejected: two money paths for one fact.
- **A time lease (like `claimRefundLease`) instead of REQUESTED.** Once the lease expires a ship is allowed while the refund outcome is still unknown. Rejected.
- **Allow `CANCELLED` on `/status`.** It mixes money into a non-idempotent status route. Rejected.
- **A separate `shipment_cancels` table.** It needs a join for a fixed one-to-one record. Rejected, as in ADR 0008.
- **Wallet leg first (ADR 0008 order).** A later card FAILED would leave money moved and the shipment unshippable. Rejected for cancels.
- **A strict wallet credit before T3 (first draft).** It would 503 a wallet cancel whenever payment-service is down, a regression from TD-P8-07. Rejected by the CTO (condition 2).

## 5. Risks, failure modes, rollback, tests
- **Stuck REQUESTED** (card leg with an ambiguous outcome, or a crash between T1 and T3): the shipment cannot ship until the cancel is retried. This is intended, because money may have moved. Runbook: retry the cancel. The retry replays the same key, and the wallet credit is queued only at the commit. To find stuck rows:
  `SELECT s.id AS shipment_id, s.order_id, s.cancel_attempt, s.cancel_rest_route, s.cancel_rest_leg, s.cancel_wallet_leg, s.updated_at FROM orders.order_shipments s WHERE s.cancel_state = 'REQUESTED' AND s.updated_at < now() - interval '10 minutes' ORDER BY s.updated_at;`
  Credits not yet delivered: `SELECT idem_key, order_id, amount, attempts, last_error FROM orders.pending_wallet_credits WHERE idem_key LIKE 'ship-cancel-wallet-%' AND completed_at IS NULL;`. These alert as "WALLET CREDIT STUCK" after 10 attempts.
- **PENDING then FAILED** is the known gap (as for whole cancel); backlog.
- **Behaviour change.**
  - A whole-order cancel of a PAID order now sends one card refund per shipment, and the total is the same.
  - The wallet stays durable as today: a credit-back row per shipment instead of one per order, and it never gives a 503.
  - Deploy boundary: an old `cancel-<orderId>` call whose outcome was unknown and is retried after deploy gets a new key. payment-service's refundable cap (PaymentService:702-730) blocks a card over-refund; the case is rare and goes to the runbook.
- **AC-4 gap (disclosed).**
  - seller-service does not yet treat CANCELLED as terminal.
  - A forged or stale DELIVERED for a cancelled shipment could book SALE, COMMISSION or FEE until the follow-up lands.
  - F-13's reconcile would report a STATE_MISMATCH.
  - The follow-up is in the backlog. The consumer check is kept in evidence.
- **Rollback:** revert the image; the V14 columns are ignored by old code.
  - First drain `cancel_state='REQUESTED'` using the query above.
  - The old whole-order cancel ignores partial cancels, so list `cancel_state='DONE' AND order status<>'CANCELLED'` for finance watch while rolled back.
  - The card is capped by payment-service; the wallet exposure is at most the order's wallet amount.
  - Queued `ship-cancel-wallet-*` rows are still delivered by the old image's `retryDue`, because it reads the same table.
- **PII:** logs carry ids and amounts only.

**Tests** (order-service Testcontainers base, payment Feign mocked as in `ReturnRefundIntegrationTest`):
- `ShipmentCancelIT`:
  - AC-1: `sellerCancelsS1_refundsShare_onlyS1Stock_oneEvent`
  - AC-2: `replayAfterSuccess_noSecondRefund_noEvent`, `twoShipments_distinctKeys`
  - AC-3: `paymentDown_503_unchanged_retrySameKey`, `paymentFailed_503_retryUsesR1`
  - AC-5: `notCancellable_409_noPaymentCall` (parametrized: SHIPPED/OFD/DELIVERED/PENDING_PAYMENT/hold)
  - AC-11: `codUncollected_noPaymentCall`, `zeroAmount_noRefundRow`
- `ShipmentCancelWalletIT` (CTO condition 2):
  - `walletOnlyCancel_paymentDown_commitsAndQueuesCredit`
  - `cardFails_onCardPlusWallet_503_noCreditQueued`
  - `replay_queuesNoDuplicateCredit`
- `ShipmentCancelAuthzIT` (AC-6): the 401/403/404 matrix (other seller, wrong order, customer, anonymous, unapproved seller).
- `ShipmentCancelRaceIT` (AC-10): `cancelVsShip_exactlyOneWins`, `doubleCancel_oneRefund`, `shipWhileRequested_409`, `cancelCancelled_200`.
- `WholeOrderCancelIT` (AC-9): `afterPartialCancel_refundsRemainder_workedExample`, `followUpsOnce`, `rounding3Shipments_sumsToTotal`.
- `ShippingOnceIT` (AC-7): `cancelThenReturn_returnCarriesShipping`, `returnThenCancel_cancelCarriesShipping`, `singleShipment_unchanged`.
- `CancelEventShapeTest` (AC-8): no return fields on cancel events.
- Existing whole-cancel tests are updated for the new keys, not deleted.

## 6. Files and estimate
- **Part A** (AC-1,2,3,5,6,9,10,11 and the cancel side of AC-7), about 490 production lines, ceiling 560:
  - V14 SQL
  - `domain/OrderShipment`, `domain/Order`
  - `service/OrderStore`, `service/OrderLifecycleService`
  - `service/WalletCreditBacks`: keyed `enqueue` and `tryNow`, about +25
  - `web/SellerShipmentController`, `web/AdminOrderController`
- **Part B** (return side of AC-7, AC-8), about 70: `domain/Order` (`completesOrder`, `shippingAlreadyRefunded`, `fullyRefunded`), `service/OrderStore.requestReturn` lock.
- **Note for the CTO:** AC-9 moved into A because the whole-order claim *is* the double-refund guard, and splitting it would regress shipping on whole cancels.
- **Reuse:**
  - `remote.refund` with refundStatus, `WalletCreditBacks` (extended with a caller key, not copied), `releaseStock` plus inventory `release` keyed per release id (ReservationService:240-283), `orders.lockById`, the ADR 0008 split and route (extracted, not copied), the `afterAdvance`/`afterCancel` follow-ups, `CancelOrder` DTO, `approvedSellerId`.
  - No new JobLock job is added.
  - Nothing new for a shared lib.
- **Backlog (out of scope):**
  - (1) AC-4 seller-service guard after F-13.
  - (2) promotion `coins.onDelivered` earns on `totalAmount`, including cancelled shipments.
  - (3) inventory `release` lines carry no warehouse, so a line split across warehouses may restock the wrong one.
  - (4) settlement watch for PENDING cancel refunds.

## CTO decision

**ACCEPTED WITH CONDITIONS** (forge-cto, 2026-11-23; copied from its reply by the CEO). Class L, standing Owner approval. Evidence for (b): TD-P8-07 was fixed on 2026-11-17, so the wallet credit-back is durable and retried (`WalletCreditBacks`), and today's whole-order cancel never fails on the wallet.

Conditions:
1. **Split.** Part A is about 470 production lines (ceiling 560) and covers AC-1, 2, 3, 5, 6, 9, 10, 11 and the cancel side of AC-7. Part B is about 70 lines and covers the return side of AC-7 and AC-8. If A passes 560, stop and report. Never exceed 600 per PR. A must not merge without B, so stack them and merge them together.
2. **The strict wallet credit is rejected.**
   - The per-shipment path for paid whole-order cancels, with `ship-cancel-*` keys and the same total, is accepted.
   - The wallet leg, and a DIRECT_WALLET rest leg, must use the durable, retried `WalletCreditBacks` with a shipment-scoped key (`ship-cancel-wallet-<sid>`), enqueued inside T3 together with CANCELLED and recordRefund.
   - The card leg stays first and strict: FAILED or unreachable gives 503 with nothing changed (AC-3).
   - A wallet-only cancel succeeds even when payment-service is down.
   - New tests:
     - a wallet-only cancel with payment-service down commits and queues the credit;
     - a card failure on a card-plus-wallet order gives 503 and queues no credit;
     - a replay queues no duplicate credit.
   - Sections 2, 3 and 5 of this design and ADR 0014 must be amended to match.
3. **Ordering accepted:** advanceShipment locks the order row and refuses REQUESTED with 409; claim, then money, then commit; no remote call runs inside a transaction; T3 is idempotent. The runbook covers clearing a stuck REQUESTED by retrying the cancel, plus a documented SQL query on `cancel_state='REQUESTED'`. No new job.
4. **Migration:** F-14 keeps V14. F-15 has no migration (per its design). Whichever branch is rebased second renumbers if needed, and the final number is recorded in evidence.md. A merged migration is never edited.
5. **The AC-4 gap is disclosed** in the PR and in ADR 0014:
   - seller-service does not yet treat CANCELLED as terminal;
   - a forged or stale DELIVERED could book SALE, COMMISSION or FEE until the follow-up lands;
   - F-13 reconcile would report a STATE_MISMATCH.

   The follow-up is in the backlog. Keep the SHIPMENT_STATUS_CHANGED consumer check in evidence.
6. **Tests:** every AC gets its named test (section 5), plus rounding across 3 or more shipments and the 401/403/404/409 matrix. The existing whole-cancel tests are updated for the new keys, not deleted.

**ADR 0014** stays Proposed until the architect folds in condition 2 and the AC-4 disclosure. After that, no further review round is needed unless the line estimate changes materially.

**CEO note on stacking:** F-14 stacks on F-20261119-15's final head, which is itself on F-11. The files overlap, and F-15 has no migration, so V14 is unaffected.
