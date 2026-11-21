# F-20261119-09 Design: return refund legs, shipping, COD, PENDING card leg (O-05a, class L)
Base: F-04 head 218998b1. Paths: `OS` = services/order-service/order-service-app/src/main/java/com/acme/ecommerce/order, `PS` = services/payment-service/payment-service-app/src/main/java/com/acme/ecommerce/payment. ADR: `.openforge/knowledge/decisions/0008-return-refund-legs.md`.

## 1. Current behaviour (proved)
- `ReturnService.refund` (OS/service/ReturnService.java:74-81) is not transactional: `moveMoney`, then `store.completeRefund` (own tx, OrderStore.java:588-597), then `releaseStock` and `publishReturn` as separate outbox inserts (OrderRemote.java:148-158, OrderEventPublisher.java:21-24 "in the caller's transaction when there is one"). A crash after completeRefund loses stock release and event.
- No leg progress is stored. `order_returns` has no refund-progress columns (V2__order_phase6.sql:57-72, OrderReturn.java:35-83). Wallet leg key `return-wallet-<id>` (ReturnService.java:100), wallet-method rest `return-rest-<id>` (:105), card/COD leg `return-<id>` (:109).
- Keys are honoured: wallet credit replays without a second row (WalletService.java:60-82, `txnExists` under wallet lock; `wallet_txns.idempotency_key` UNIQUE, V2__wallet_giftcards.sql:30). Refund replays before any provider call (PaymentService.java:693-695) and re-checks under the payment lock (:724-726); `refunds.idempotency_key` UNIQUE varchar(100) (V1__payment_baseline.sql:36). So a retry after "wallet ok, card failed" does not double-credit, but the wallet leg is re-called blindly and nothing records which leg ran.
- **PENDING is invisible to order-service.** `RefundResponse(status, refundedAmount)` carries the *payment* status REFUNDED/PARTIALLY_REFUNDED (payment-service-api .../RefundResponse.java:5-6; PaymentService.java:751-758), never the refund-row status. A Razorpay refund row is inserted PENDING (:732-741), but `OrderRemote.refund` returns void (OrderRemote.java:298-305), so `ReturnService` treats any non-exception as done (assumption 1 confirmed).
- **Final outcome: nothing reaches order-service.** F-04 settles via signed webhook only (PaymentService.java:768-814, :872-922). Polling was rejected in ADR 0003. `OrderReporter` reports only CARD *payment* outcomes (OrderReporter.java:43-56); there is no refund report.
- A replay of a FAILED key returns the replay view and creates no new refund (:693), so the fixed key `return-<id>` can never be retried after a Razorpay FAILED.
- **COD in payment-service:** a row with method COD, status COD_PENDING is created at placement (PaymentService.java:135), best effort: on failure the order is placed without a row (OrderService.java:399-412). On delivery the outbox sends `cod-collected` (OrderLifecycleService.java:170-171, Outbox COD_COLLECTED) and the status becomes SUCCEEDED (PaymentRepository.java:184-189). Refund rejects COD_PENDING with 409 (PaymentService.java:696-700). With destination WALLET it inserts a SUCCEEDED row, raises `refunded_amount` and credits the wallet with key `refund-<key>` in one tx (:711, :742-753). Today any non-503 error falls back to a direct wallet credit (ReturnService.java:120-128), which is the TD-P8-08 bug.
- **Shipping:** stored (`shipping_amount`, Order.java:125-126). The refund is fixed at request time as discount- and coin-pro-rated item value only (Order.java:626-657); shipping is never added. `refund_amount` is `updatable=false` (OrderReturn.java:57). `remainingReturnable(null)` (Order.java:546-575) already nets out every non-rejected return across the whole order.

## 2. Proposed change
**Shipping: completing-return rule (AC-1..3; CTO condition 2, Owner answer 2026-11-21).** In `Order.requestReturn`, after validating the lines, add `shippingAmount` to `refund_amount` and store it in a new `shipping_refund` column when both hold:
- **(i)** with this return's lines subtracted, `remainingReturnable(null)` is zero for every order line. Prior non-rejected returns plus this one then cover the whole order, whichever shipments they came from.
- **(ii)** no other non-rejected return of the order has `shipping_refund > 0`.
Otherwise `shipping_refund` is 0.
- AC-1: a single full return completes the order.
- AC-2: a partial return that does not complete the order gets no shipping.
- AC-3: on two returns that together cover the order (for example one per shipment), only the second gets shipping, exactly once.
- If the shipping-carrying return is later REJECTED, it drops out of (ii) and its lines return to the remainder, so the next completing return gets shipping instead. Still exactly once among live returns.
- `requestReturn` already runs on the loaded `Order` aggregate inside one tx, so two concurrent completing requests cannot both see (i) and (ii).
- Shipping is set at request time, so the amount the customer sees is the amount paid. Pre-migration returns keep their items-only amounts (`shipping_refund` defaults to 0), so frozen legs stay consistent.

**Migration `V13__return_refund_legs.sql` (additive, all nullable or defaulted, no backfill)** on `orders.order_returns`:
`shipping_refund numeric(19,4) NOT NULL DEFAULT 0`, `wallet_leg_amount numeric(19,4)`, `rest_leg_amount numeric(19,4)`, `rest_route varchar(16)` (NONE|PAYMENT_SOURCE|PAYMENT_WALLET|DIRECT_WALLET), `wallet_leg_done_at timestamptz`, `rest_leg_state varchar(12)` (null=not started|PENDING|SUCCEEDED|FAILED), `rest_leg_attempt int NOT NULL DEFAULT 0`, `refund_lease_until timestamptz`; partial index `ix_order_returns_rest_pending ON (updated_at) WHERE rest_leg_state='PENDING'`.

**Freeze legs (AC-10).** On the first attempt (`wallet_leg_amount IS NULL`), `OrderStore.freezeLegs` computes and stores, in one tx:
- `w = min(round2(R*W/T), W - Σw_other, R)`, `rest = R - w`;
- if `rest > (T-W) - Σrest_other`, the excess moves to `w`.
Here R is the refund, W the order's `walletAmount`, T `totalAmount`; "other" means sibling returns that are REFUNDED or already frozen. Unfrozen REFUNDED siblings use the legacy formula `round2(R*W/T)`.
Because the legs are frozen, every retry sends the same amounts under the same keys, and `w + rest = R` holds to the cent by construction. Route: `R=0` gives NONE; method WALLET gives DIRECT_WALLET; CARD with paymentId and `refundTo!=WALLET` gives PAYMENT_SOURCE; otherwise (COD, or `refundTo=WALLET`) PAYMENT_WALLET. Unpaid orders keep today's rule (no rest leg).

**Refund flow** (`ReturnService.refund`, admin only, unchanged route `POST /api/admin/returns/{id}/status`):
1. If the return is REFUNDED, return its current response with no calls (AC-9).
2. Claim the lease with `UPDATE ... SET refund_lease_until=now()+2m WHERE id=? AND status='PICKED_UP' AND (lease IS NULL OR lease<now())`. If 0 rows, return 409 RESOURCE_CONFLICT "refund in progress". The lease is released in `finally`.
3. Freeze legs if not frozen.
4. Wallet leg: if `w>0` and `wallet_leg_done_at IS NULL`, call `creditWallet(..., "return-wallet-<id>")` (key unchanged), then commit `wallet_leg_done_at=now()` before step 5 (AC-6). A legacy row that already credited replays harmlessly on the same key.
5. Rest leg, skipped if SUCCEEDED:
   - DIRECT_WALLET: `creditWallet("return-rest-<id>")`, which counts as SUCCEEDED.
   - PAYMENT_*: key = `return-<id>` for attempt 0, else `return-<id>-r<n>`. Call `remote.refund`, which now returns the refund-row status.
     - SUCCEEDED: record it, go to 6.
     - PENDING: record it, return the response (status PICKED_UP, `refundState=PENDING`); no finalize (AC-11).
     - FAILED (reported by payment-service): `UPDATE ... rest_leg_attempt=n+1 WHERE rest_leg_attempt=n` (the loser of a race gets 409), then call once more with the new key (AC-12 retry after FAILED). Only a FAILED row may start a `-r<n>` key.
     - Exception: record FAILED, never bump the attempt (the same key is safe), rethrow. 503 stays 503 (AC-5, AC-7).
6. `finalizeRefund`, in one `TransactionTemplate` tx: `markRefunded` (requires PICKED_UP; `@Version`), `order.recordRefund(R)`, outbox `RELEASE_STOCK`, outbox `RETURN_REFUNDED`. This is exactly once (AC-12) and is the single hook where F-10 adds its coin outbox rows.

**PENDING resolution (AC-12, AC-13): poll by replay.** New `ReturnRefundWatch` (`@Scheduled` `${order.returns.refund-poll-interval-ms:300000}` plus `JobLock "order-return-refund-watch"`, the WalletCreditBacks.java:103-117 pattern). It takes up to 50 PICKED_UP returns with `rest_leg_state='PENDING'` and calls `ReturnService.resumePending(id)`: claim the lease, re-send the *same* key, which hits the payment-service replay (a DB lookup before any gateway call, PaymentService.java:693; no new refund).
- SUCCEEDED: finalize.
- FAILED: record FAILED, log `RETURN REFUND FAILED`, and stay PICKED_UP. The admin retries; the wallet is skipped because `wallet_leg_done_at` is set.
- PENDING: nothing.
An admin retry while PENDING also replays the same key (AC-13).

**payment-service (additive contract).** `RefundResponse` gains `refundStatus` (PENDING|SUCCEEDED|FAILED), the status of the row with this idempotency key:
- new insert: the inserted status, re-read after parked events are applied (PaymentService.java:754-757);
- replay (:693, :724): new `PaymentRepository.refundStatusByKey(key)`.
F-04's state machine is untouched. order-service `RefundResult` gains the same field. Null (an older payment-service) is read as SUCCEEDED, so **payment-service must deploy before order-service**; the runbook states this order explicitly (CTO condition 4).

**COD (AC-4, AC-5).** Route PAYMENT_WALLET only. The fallback to a direct wallet credit is deleted, and no payment row is created on demand. Any error leaves the return PICKED_UP with the leg FAILED: 409 when the payment is still COD_PENDING, 404 when there is no payment row. 503 propagates. The runbook notes that a 404 on a COD refund leaves the return PICKED_UP (backlog B-10, CTO condition 1).

**API/events.** `ReturnResponse` gains `shippingRefund` and `refundState` (nullable). No new endpoint and no new event type; `RETURN_REFUNDED` is now published only after money settles. Docs: api.md (admin return status response), data-model.md, environment-variables.md (`ORDER_RETURNS_REFUND_POLL_INTERVAL_MS`), events.md note, runbook (deploy order, COD 404).

## 3. AC map
- AC-1, AC-2, AC-3: completing-return check (i) and (ii) in `requestReturn`, plus `shipping_refund`.
- AC-4, AC-5: PAYMENT_WALLET route; fallback removed; 503 propagates.
- AC-6, AC-7: `wallet_leg_done_at` committed before the rest leg; `rest_leg_state=FAILED`; finalize not reached.
- AC-8: wallet skipped once done (and key-idempotent anyway); refund key replay or one new attempt key; finalize.
- AC-9: early return on REFUNDED.
- AC-10: frozen legs, `w + rest = R`, caps.
- AC-11: PENDING state, no finalize.
- AC-12: watch plus conditional finalize; FAILED leaves the return retryable.
- AC-13: same key, so payment-service replays and makes no provider call.

## 4. Reuse
Reused:
- the wallet and refund idempotency keys;
- the payment-service replay path;
- the F-04 refund-row status;
- Outbox `RELEASE_STOCK` and event publishing;
- `JobLock` plus the `@Scheduled` pattern from WalletCreditBacks;
- `RemoteCalls.translate`;
- `Order.remainingReturnable` for the completing-return check.

Not reused:
- `WalletCreditBacks` table: its key is per order, `order-wallet-back-<orderId>` (WalletCreditBacks.java:48, :71), and it is fire-and-forget; return legs need synchronous, per-return progress.
- `OrderReporter`: it handles only CARD payment outcomes.

## 5. Alternatives
- (a) **Push: payment-service reports refund settlement to a new order-service internal endpoint.** It needs a `reported` column on `payment.refunds` (a second migration), a reporter job, a new endpoint plus docs, and it couples F-04's webhook tx to order-service. Rejected in favour of a replay poll, which uses an existing endpoint and is cheap because few returns are PENDING and Razorpay refunds take days.
- (b) **A new `return_refund_legs` table:** a cleaner audit trail, but a join and more code for a fixed set of two legs. Rejected.
- (c) **Add shipping at refund time:** the displayed `refundAmount` would differ from what is paid, and legacy rows whose wallet leg already ran would mis-split. Rejected.
- (d) **Single-full-return-only shipping rule (the first draft):** multi-shipment orders, which are returned per shipment, would never get shipping back. Superseded by the Owner's completing-return answer.

## 6. Risks, failure modes, rollback
- Lease expiry during a slow call lets a second caller in. Both use the same keys, so this is safe; the attempt bump is a conditional update.
- An order-service crash between the wallet credit and `wallet_leg_done_at`: the next retry replays the key with no second credit.
- A COD order without a payment row cannot be refunded (it now fails loudly instead of using the hidden fallback). Backlog B-10 "ensure a COD payment row at refund"; the runbook covers it.
- payment-service deploy order (null `refundStatus` means SUCCEEDED); the runbook makes payment-service deploy first.
- PENDING latency of up to one poll interval.
- **Multi-shipment orders.** Returns are per shipment (Order.java:586-596), so no single return covers the order.
  - The completing-return rule puts shipping on the last return that completes coverage. Its `refund_amount` then exceeds its items value by `shippingAmount`.
  - If a shipping-carrying return is rejected after a later return was requested, shipping can move to a different return. This is covered by rule (ii) and a test.
  - Shipping is not split per shipment; per-shipment rework is TD-P9-13 (out of scope).
- Rollback: revert the images. The V13 columns are ignored by old code. Returns left PENDING would then complete under the old logic (the old behaviour).

## 7. Files expected to change (estimated production lines, about 460 of the 600 budget)
- order-service:
  - `ReturnService` (~150)
  - new `ReturnRefundWatch` (~50)
  - `OrderStore` (freeze, record, finalize, plan: ~90)
  - `OrderReturn` (~45)
  - `OrderReturnRepository` (lease and bump: ~20)
  - `Order.requestReturn` (completing-return rule: ~25)
  - `OrderRemote` and `PaymentFeignClient` (~10)
  - `OrderMapper` and `ReturnResponse` (~8)
  - `V13__return_refund_legs.sql` (~15)
  - `application.yml` (~2)
- payment-service: `RefundResponse`, `PaymentService.refund`, `PaymentRepository.refundStatusByKey` (~25).
- Docs and runbook as listed in section 2.

## 8. Test plan
- **Order unit:**
  - a single full return gets shipping (AC-1);
  - a partial return does not (AC-2);
  - two shipment returns that sum to the whole order: only the second gets shipping (AC-3);
  - three partial returns: only the completing one gets shipping;
  - the shipping return is REJECTED, then a new completing return gets shipping once;
  - a membership free-shipping order (S=0).
- **ReturnService unit (Mockito InOrder):**
  - the wallet-done write happens before the refund call;
  - card throws: FAILED, attempt unchanged, PICKED_UP;
  - retry: no second `creditWallet`, same key, then REFUNDED (AC-6..8);
  - REFUNDED: zero remote calls (AC-9);
  - PENDING: no `releaseStock` or publish (AC-11);
  - FAILED replay: key `-r1`; an exception never yields `-r1` (AC-12);
  - COD: destination WALLET; 409 or 404 means no `creditWallet`; 503 propagates (AC-4, AC-5);
  - R=0: no calls.
- **Allocation parametrized test:** wallet, card and coin mixes, rounding thirds, sequential partials ending with the last cent, and the completing return carrying shipping (AC-10).
- **order Testcontainers:** V13 applies; a pre-migration row with null legs retries; two concurrent refunds produce one REFUNDED, one `RELEASE_STOCK` and one event outbox row; the watch takes PENDING to SUCCEEDED and finalizes once.
- **payment Testcontainers:**
  - Razorpay mock gives `refundStatus=PENDING`;
  - a replay never calls `RazorpayGateway` (verify zero interactions) (AC-13);
  - after a FAILED webhook, the replay reports FAILED;
  - COD to WALLET: `refunded_amount` rises and there is one `wallet_txns` row (AC-4).

## CTO decision
forge-cto gave this decision on 2026-11-21 in its reply. forge-ceo transcribed it here verbatim. Class L; under charter R-20261119-01, CTO acceptance stands in for Owner approval.

ACCEPT, with conditions. Class L stands; the design is minimal (no new service, datastore or vendor; V13 is additive).

1. (a) Acceptable. Failing loudly is money-safe. The backlog item "ensure COD payment row at refund" must be filed with an ID, and the runbook must say that a 404 on a COD refund leaves the return PICKED_UP. No fallback and no on-demand row creation in this objective.
2. (b) Not honoured for multi-shipment orders. Minimal fix: in `requestReturn`, add shipping to the return that completes the whole order, when the prior non-rejected returns together with this one cover every line fully and no earlier return carries `shipping_refund`. Add a test for two shipment returns that sum to the whole order. If AC-3 forbids this, tell the Owner and record their answer.
3. (c) Confirmed. A replay is a DB lookup of the idempotency key before any gateway call, so a second Razorpay refund cannot be created whatever Razorpay does with the header. Add a test that a replay never calls `RazorpayGateway`. Only a FAILED row may start a `-r<n>` key, and an exception must never bump the attempt.
4. (d) Payment-service must deploy first, recorded in the runbook. A null `refundStatus` read as SUCCEEDED is unsafe against an old payment-service, so the runbook must make that order explicit.
5. (e) Acceptable. Finalize stays guarded by `@Version` plus the PICKED_UP check.

### Owner answer on condition 2 (AskUserQuestion, 2026-11-21)
"Yes, the completing return gets it." Whichever return completes the whole order refunds shipping, exactly once. AC-3 is amended to match (see requirements.md).
