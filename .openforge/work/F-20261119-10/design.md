# F-20261119-10 Design: coins on return refunds (AC-14..AC-18), stacked on F-09 b3cda340
Paths: `OS` = services/order-service/order-service-app/src/main/java/com/acme/ecommerce/order, `PS` = services/promotion-service/promotion-service-app/src/main/java/com/acme/ecommerce/promotion.

## 1. Current behaviour (proven)
- **Where redeemed coins are recorded:** in the promotion ledger as `REDEEM_ORDER`, `-N`, `reference_id = orderId` (PS/service/CoinService.java:105-117). order-service also keeps `orders.coins_redeemed` and `coins_value` (OS/domain/Order.java:162-166). The ledger is unique on `(reason, reference_id, user_id)` and inserts with `ON CONFLICT DO NOTHING` (V3__coins_members_deals.sql:12; CoinService.java:235-247).
- **The only give-back is all-or-nothing.** `releaseRedeem` credits `+N` as `REVERSE_ORDER` / `redeem-<orderId>` (CoinService.java:119-137). It runs on cancel and payment failure (OS/service/OrderLifecycleService.java:106-107, OutboxRelay.java:140) and never on a return.
- **How earned coins are computed:** on `ORDER_STATUS_CHANGED`/`DELIVERED`, `floor(totalAmount / earnUnit)`, doubled for members, stored as `EARN_ORDER` / `orderId` (CoinService.java:143-157; listener PS/event/OrderEventListener.java:47-48). Only promotion-service knows the earned figure; order-service does not store it.
- **Earned reversal happens only on a full refund.** On `RETURN_REFUNDED` with `paymentStatus=REFUNDED`, `onFullyRefunded` takes `min(earned, max(0, balance))` as `REVERSE_ORDER` / `earn-<orderId>` (OrderEventListener.java:49-50; CoinService.java:159-179). A partial return reverses nothing, and redeemed coins are never given back on a return.
- **Idempotency keys:** idempotency comes from the ledger unique key alone. The listener lets failures propagate to DlqErrorHandlers: 3 retries, then `.DLT` (OrderEventListener.java:26-35; OrderKafkaConfig.java:39).
- **Sync or events:** order-service calls promotion synchronously only for redeem and release at placement (OS/client/PromotionFeignClient.java:50-59). Return coins are **event-driven**. `finalizeRefund` writes `RETURN_REFUNDED` to the outbox in the same transaction as REFUNDED (OS/service/ReturnService.java:158-166; OrderEventPublisher.java:53-55, 122-124). The event is keyed by orderId, so one order's events arrive in order (OrderEventPublisher.java:130-132). The relay retries with backoff and marks the row DEAD after 20 attempts (OutboxRelay.java:30, 147-161).
- **What the event carries:** `returnId` and `refundAmount` (OS/event/OrderEvent.java:34-36). It does not carry the items value. The promotion copy drops everything except type, ids, status, paymentStatus, total, currency and occurredAt (PS/event/OrderEvent.java:8-17).
- **Hidden loss path:** the listener silently drops events older than `maxEventAgeDays = 2` (OrderEventListener.java:39-44; PromotionProperties.java:39). `occurredAt` is stamped at enqueue (OrderEventPublisher.java:108). If promotion-service is down for more than 2 days, or a DLT replay comes late, the coin step for a return is skipped without any record.
- **F-09 finalize:** `completeRefund` is the single completion hook (OS/service/OrderStore.java:699-715). After the `markRefunded` call in that method, `o.fullyRefunded()` (Order.java:495-501) is true only for the return that completes the order, so that return's event carries `paymentStatus=REFUNDED`. Siblings serialise on `lockById` (OrderStore.java:709).

## 2. Proposed change
**Coins stay off the money path.** order-service makes no new remote call. `ReturnService` and `completeRefund` are unchanged. order-service only adds data to the `RETURN_REFUNDED` event, which is already outbox-committed with REFUNDED. A failure in promotion-service can therefore never block or undo a money refund.

**Event (additive, order-service).** `OrderEvent` gains two fields:
- `returnItemsValue` = Σ `unitPrice × quantity` over `ret.items()` (ReturnResponse.java:24-25);
- `orderItemsValue` = `o.subtotalAmount()` (OrderResponse.java:22).

`OrderEventPublisher.build` fills both for RETURN_* events and leaves them null otherwise. This is the same gross/subtotal basis that `requestReturn` uses for the money (Order.java:651-659).

**promotion-service.**
- `PS/event/OrderEvent` gains `returnId`, `returnItemsValue` and `orderItemsValue`.
- The listener routes `RETURN_REFUNDED` events that carry `returnId` and `returnItemsValue` to `coins.onReturnRefunded(orderId, userId, returnId, value, orderValue, completes)`, where `completes` = `paymentStatus == REFUNDED`.
- Legacy events without the new fields still go to `onFullyRefunded`.
- `RETURN_REFUNDED` is **exempt from the age gate**. It is idempotent per return, so a late replay is safe.

**`CoinService.onReturnRefunded`** (`@Transactional`) reuses `lock`, `exists`, `insert` and `balance`:
1. `lock(userId)`. If `coin_return_reversals` already has `returnId`, return: this is a no-op on replay.
2. Read the order figures:
   - `N` = `abs(REDEEM_ORDER delta)` for the order, or 0;
   - `E` = the `EARN_ORDER` delta, or 0.
3. Sum the prior amounts due for this order:
   - prior redeemed = Σ `redeem_due` over earlier reversal rows, plus `N` if the legacy `redeem-<orderId>` row exists;
   - prior earned = Σ `earn_due` over earlier reversal rows, plus `E` if the legacy `earn-<orderId>` row exists.
4. Work out the amounts due:
   - share = `value / orderValue`, or 0 when `orderValue` is 0;
   - `redeemDue = completes ? N - priorR : min(floor(N × share), N - priorR)`, never below 0;
   - `earnDue` follows the same rule with `E`;
   - the floor uses BigDecimal `RoundingMode.DOWN`.
5. Write the ledger rows:
   - If `redeemDue > 0`, insert `+redeemDue`, `REVERSE_ORDER`, `return-redeem-<returnId>`. The give-back never expires, like `releaseRedeem`.
   - Then `taken = min(earnDue, max(0, balance))`. If `taken > 0`, insert `-taken`, `REVERSE_ORDER`, `return-earn-<returnId>`.
6. Insert into `coin_return_reversals` (`return_id` PK, `order_id`, `user_id`, `redeem_due`, `earn_due`, `earn_taken`, `completes`, `created_at`). If `taken < earnDue`, log a WARN `COIN REVERSAL SHORTFALL`.

The redeemed give-back is applied before the clamp, so the net balance is what one combined ledger move would give.

**Migration** PS `V5__coin_return_reversals.sql`: one new table plus an index on `order_id`. It is additive, with no backfill and no change to the reason CHECK, because `REVERSE_ORDER` is reused.

**Config:** none. **Endpoints:** none. **Docs:** `docs/reference/events.md` gets the two new fields and the promotion consumer row (events.md:39).

## 3. AC → mechanism
| AC | Mechanism |
|---|---|
| AC-14 | `return-redeem-<returnId>` row of `floor(N × share)`. The completing return gives `N - prior`. The `N - prior` cap holds for every return, so the total stays at or below N. Legacy full give-back counts as prior. |
| AC-15 | `return-earn-<returnId>` row of `floor(E × share)`, clamped to `max(0, balance)`. It is linked to the return by `reference_id` and recorded in `coin_return_reversals`. |
| AC-16 | The completing return (`paymentStatus=REFUNDED`, Order.java:495-501) sets `earnDue = E - Σ prior due`, so the dues sum to exactly E with no remainder. Caps make the result independent of event order. |
| AC-17 | The `coin_return_reversals` PK on return_id is checked under the user advisory lock. The ledger unique key (V3:12) is a second guard. A DLT replay or outbox redelivery writes nothing. |
| AC-18 | Per the CEO ruling, `taken = min(due, max(0, balance))` and the balance never goes negative. `earn_due` and `earn_taken` are always written, even when `taken = 0`, which a ledger row cannot record because of `CHECK delta <> 0`. A WARN is logged. |

## 4. Alternatives considered
- **Synchronous Feign call from `finalizeRefund`.** This puts promotion-service on the money path, inside the transaction or between REFUNDED and the event. It would need a coin-progress column, a retry job and a second lease. Rejected because the outbox already gives at-least-once delivery for free.
- **order-service computes the coin counts and calls a new `/internal/coins/return` endpoint.** order-service does not know E (CoinService.java:143-157), and adding it duplicates the earn rules. Rejected.
- **Idempotency only through ledger reference keys, with no new table.** A clamped-to-0 reversal leaves no trace (AC-18), and "prior due" cannot be summed when earlier takes were clamped. Rejected.
- **Recover the earned shortfall on later returns.** This is a new policy beyond the CEO ruling. Rejected; the shortfall is recorded only.

## 5. Risks, failure modes, rollback, tests
- **promotion-service down:** the money refund is unaffected. Kafka retains the event and the consumer catches up. A failure in the handler goes to DLT after 3 retries, and replaying the DLT (events.md:115) is idempotent. With the age-gate exemption, a late replay is still applied.
- **Kafka down:** the outbox row retries and goes DEAD after 20 attempts (OutboxRelay.java:30). It stays listed for an admin retry, and the coins follow on that retry.
- **Out-of-order or concurrent events:** they are serialised per user by the advisory lock (CoinService.java:217-222), and the caps bound the totals.
- **Earn arriving after the refund:** if DELIVERED earning was skipped or arrives late (age gate), `E = 0` at the refund and nothing is reversed. This matches today's behaviour. Noted, not fixed.
- **Pre-deploy partial returns** got no coin step. The completing return now settles the full remainder, which is correct per AC-14 and AC-16.
- **Deploy order:** either order is safe. Old promotion ignores the new fields (`ignoreUnknown`, PS/event/OrderEvent.java:7); old order-service events take the legacy path. Promotion first is preferred.
- **Rollback:** revert the images; V5 is then ignored. Caveat: an old promotion-service would run `onFullyRefunded` on a completing return even when per-return reversals exist. That is an over-reversal, still clamped to the balance. The runbook should say to roll back only before any completing return is refunded, or to accept the clamped over-reversal.
- **Privacy:** like the ledger, the new table is retained on USER_DELETED. Adding it to `PrivacyExportController` is a backlog note.

**Tests (production behaviour proven):**
- promotion `CoinReturnReversalIntegrationTest` (Testcontainers):
  - partial floors;
  - 3 returns sum exactly to N and E;
  - completing-first ordering stays capped;
  - a replayed return writes no new row;
  - spent coins: balance goes to 0 (never below), with the shortfall row;
  - legacy `earn-`/`redeem-` rows count as prior;
  - an event without the new fields goes to `onFullyRefunded`.
- A listener unit test: an old `occurredAt` on RETURN_REFUNDED is still processed.
- order-service:
  - a publisher unit test checks the RETURN_REFUNDED values;
  - `ReturnRefundIntegrationTest` asserts that the outbox payload carries `returnItemsValue` and that PromotionFeignClient is never called on refund.

## 6. Reuse
- **Reused:**
  - CoinService `lock`, `exists`, `insert` and `balance` (CoinService.java:217-247);
  - the clamp rule from `onFullyRefunded` (:175);
  - the outbox and per-order ordering (OutboxRelay; OrderEventPublisher);
  - DlqErrorHandlers (libs/common-web);
  - the per-return event pattern from seller-service `LedgerService.reverseReturn` (seller LedgerService.java:154-158; OrderEventConsumer.java:91-94).
- **Not reused:** `releaseRedeem`, which is whole-order only, and `onFullyRefunded`, which would double-reverse. Both stay, for cancel and for legacy events.
- **No shared lib needed:** the floor and cap arithmetic is about 10 lines, used only in CoinService.

## 7. Files and size
- order-service: `event/OrderEvent.java` (+4), `event/OrderEventPublisher.java` (+14).
- promotion-service: `event/OrderEvent.java` (+4), `event/OrderEventListener.java` (+12), `service/CoinService.java` (+95), `db/migration/V5__coin_return_reversals.sql` (+14).
- Docs: `docs/reference/events.md` (+6).
- Tests (about 300 lines): the new `CoinReturnReversalIntegrationTest` and a listener test, plus edits to `ReturnRefundIntegrationTest`.
- **Estimated production lines: about 145**, well under 600.

## CTO decision
Decided by forge-cto on 2026-11-21 in its reply; forge-ceo transcribed it here verbatim. Class L. Under charter R-20261119-01, this acceptance counts as standing Owner approval.

ACCEPT (class L, standing Owner approval). The design is minimal and covers AC-14..AC-18. Coins ride the outbox event, so they never block the money refund. The per-return table under the user advisory lock makes redelivery a no-op. Floor plus remainder-to-completing-return gives exact totals. The clamp follows the CEO ruling and records earn_due and earn_taken. V5 is an additive table, and the event fields are backward compatible.

Conditions:
1. Limit the age-gate exemption to RETURN_REFUNDED events that carry returnId. Legacy events without it keep the 2-day filter. Test both cases. (I did not open OrderEventListener.java myself; this rests on the design's description.)
2. Deploy promotion-service first. Record that in the runbook, together with the rollback over-reversal caveat.
3. Test that a completing return processed before an earlier return's event still totals exactly N and E.
4. Test that a replay after a clamp to 0 writes nothing new.
5. Keep the WARN log on shortfall and the privacy-export backlog note.
6. Verify the PR stays under 600 production lines.

### CEO check of condition 1's unverified premise (G-1)
OrderEventListener.java:39-44 (promotion-service, at worktree base b3cda340) drops any event whose `occurredAt` is older than `coins.maxEventAgeDays`, before the type dispatch. RETURN_REFUNDED is currently handled at :49-50 (`onFullyRefunded`) behind that gate. The design's description is correct.
