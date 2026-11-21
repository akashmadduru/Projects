# F-20261119-10 Tasks: coins on return refunds (AC-14..AC-18)
Owner of every task: forge-backend-engineer. Work in the worktree `.openforge/worktrees/F-20261119-10` (stacked on F-09 b3cda340). One Gradle build at a time.
Paths: `OS` = services/order-service/order-service-app/src/main/java/com/acme/ecommerce/order, `PS` = services/promotion-service/promotion-service-app/src/main/java/com/acme/ecommerce/promotion. Tests live under the matching `src/test/java` tree.
Condition numbers C1..C6 are the CTO conditions in design.md "## CTO decision".

## Rule for every condition test (C1, C3, C4 and the AC tests)
Show that each condition test FAILS against the pre-change code, then passes. Method: write the test first, run it with the production change absent (stash or revert the change, or only the test present) and record the failing output in the work folder (`evidence.md` entry, not tasks.md), then restore the change and record the passing run. A test that was never seen failing does not count as covering its condition. Where a test cannot compile before the change (new method or field), add the minimal stub or temporarily disable the change so it compiles and fails on behaviour, and say so.

## promotion-service first (it deploys first, C2)

### T1. Migration V5 coin_return_reversals
- Goal: add the per-return reversal table.
- Files: `services/promotion-service/promotion-service-app/src/main/resources/db/migration/V5__coin_return_reversals.sql` (new). Table `coin_return_reversals` (`return_id` PK, `order_id`, `user_id`, `redeem_due`, `earn_due`, `earn_taken`, `completes`, `created_at`) plus an index on `order_id`. Additive only, no backfill, no CHECK change. Confirm V5 is the next free number.
- Tests: covered by the Flyway startup of the Testcontainers base in T2/T3 (the migration must apply cleanly). Run T2 tests to prove it.
- ACs and conditions: AC-17 (PK guard). Supports C4.
- Verify: `bash .openforge/scripts/gradle.sh :services:promotion-service:promotion-service-app:test --tests '*CoinReturnReversalIntegrationTest'` (run after T2).

### T2. CoinService.onReturnRefunded: partial returns, replay, shortfall
- Goal: implement `onReturnRefunded(orderId, userId, returnId, value, orderValue, completes)` per design section 2: `lock(userId)`, return if returnId already in `coin_return_reversals`, read N and E, sum prior dues (including legacy `redeem-`/`earn-` rows), compute floor shares (BigDecimal `RoundingMode.DOWN`, share 0 when orderValue is 0), write `return-redeem-<returnId>` (+) then `return-earn-<returnId>` (-, clamped to `max(0, balance)`), insert the reversal row always (even when taken is 0), log WARN `COIN REVERSAL SHORTFALL` when taken < earnDue (C5). Implement the full rule including `completes` (T3 tests it).
- Files: `PS/service/CoinService.java` (+~95); new `CoinReturnReversalIntegrationTest` (Testcontainers) in the promotion-service-app test tree.
- Tests in this task: partial return floors both redeem and earn (AC-14, AC-15); reference_id links to returnId (AC-15); replay of the same return writes no new ledger or reversal row (AC-17); spent coins: balance goes to 0, never below, reversal row records earn_due and earn_taken, WARN logged (AC-18, C5); replay AFTER a clamp to 0 writes nothing new (C4, AC-17); refund when N=0 or E=0 writes no zero rows (ledger CHECK delta <> 0).
- ACs and conditions: AC-14, AC-15, AC-17, AC-18, C4, C5.
- Verify: `bash .openforge/scripts/gradle.sh :services:promotion-service:promotion-service-app:test --tests '*CoinReturnReversalIntegrationTest'`

### T3. Completing-return and ordering tests for onReturnRefunded
- Goal: prove exact totals and order independence. Fix CoinService if a test exposes a gap.
- Files: `CoinReturnReversalIntegrationTest` (extend); `PS/service/CoinService.java` only if a fix is needed.
- Tests: three returns (partial, partial, completing) sum exactly to N and E with no remainder (AC-14, AC-16); a full single return gives back all N and reverses all E (AC-14, AC-16); C3: the completing return processed BEFORE an earlier return's event still totals exactly N and E and never exceeds them (caps `N - prior`); legacy `redeem-<orderId>` / `earn-<orderId>` rows count as prior so nothing is double-given-back or double-reversed (AC-14, AC-16); redeemed give-back is applied before the clamp (net balance equals one combined move).
- ACs and conditions: AC-14, AC-16, C3.
- Verify: `bash .openforge/scripts/gradle.sh :services:promotion-service:promotion-service-app:test --tests '*CoinReturnReversalIntegrationTest'`

### T4. promotion event fields, listener routing, scoped age-gate exemption
- Goal: `PS/event/OrderEvent` gains `returnId`, `returnItemsValue`, `orderItemsValue`. In `OrderEventListener`, route `RETURN_REFUNDED` events carrying `returnId` and `returnItemsValue` to `coins.onReturnRefunded(..., completes = paymentStatus == REFUNDED)`. Exempt ONLY those events from the 2-day age gate (C1). Legacy RETURN_REFUNDED events without the new fields keep the age gate and go to `onFullyRefunded`. All other event types keep the gate.
- Files: `PS/event/OrderEvent.java` (+4), `PS/event/OrderEventListener.java` (+12); listener unit test (new or extend the existing OrderEventListener test).
- Tests: C1 both ways: (a) RETURN_REFUNDED with returnId and an `occurredAt` older than `maxEventAgeDays` is still processed (calls `onReturnRefunded`); (b) a legacy RETURN_REFUNDED without returnId and an old `occurredAt` is dropped (the 2-day filter still applies); (c) a fresh legacy event without the new fields goes to `onFullyRefunded`; (d) a non-return event with an old `occurredAt` is still dropped; (e) a new-style event deserialises with `ignoreUnknown` and with the new fields. Also one end-to-end case through the real listener and CoinService for the late-replay case (AC-17 redelivery).
- ACs and conditions: AC-14..AC-17 (wiring), C1.
- Verify: `bash .openforge/scripts/gradle.sh :services:promotion-service:promotion-service-app:test`

## order-service second

### T5. order-service event fields and publisher
- Goal: `OS/event/OrderEvent` gains `returnItemsValue` and `orderItemsValue`. `OrderEventPublisher.build` fills them for RETURN_* events only: `returnItemsValue` = sum of `unitPrice x quantity` over `ret.items()`, `orderItemsValue` = `o.subtotalAmount()`; null for other events. No change to `ReturnService` or `completeRefund`, and no new remote call.
- Files: `OS/event/OrderEvent.java` (+4), `OS/event/OrderEventPublisher.java` (+14); a publisher unit test (new or extend); `ReturnRefundIntegrationTest` (edit).
- Tests: publisher unit test checks both values for RETURN_REFUNDED and null for non-return events; the completing return's event carries `paymentStatus=REFUNDED`, an earlier return's does not (supports AC-16); `ReturnRefundIntegrationTest` asserts the outbox payload carries `returnItemsValue`, `orderItemsValue` and `returnId`, and that `PromotionFeignClient` is never called on refund (design: coins off the money path).
- ACs and conditions: AC-14, AC-15, AC-16 (data feed).
- Verify: `bash .openforge/scripts/gradle.sh :services:order-service:order-service-app:test`

## Docs

### T6. events.md and runbook
- Goal: document the contract and operations.
- Files: `docs/reference/events.md` (+6: the two new order-service fields, the three promotion fields, the promotion consumer row near line 39, the age-gate exemption for return events); `docs/guides/operations.md` (new runbook section "Return coin reversals").
- Runbook must state: (C2) deploy promotion-service BEFORE order-service; rollback caveat (an old promotion-service runs `onFullyRefunded` on a completing return even when per-return reversals exist, an over-reversal clamped to the balance, so roll back only before any completing return is refunded, or accept the clamped over-reversal); how to read `COIN REVERSAL SHORTFALL` WARN lines and `coin_return_reversals` (earn_due vs earn_taken); how to replay a DLT safely (idempotent per return); (C5) privacy-export backlog note: `coin_return_reversals` is retained on USER_DELETED and is not in `PrivacyExportController`; add this as a backlog item in `docs/techdebt/README.md` only if the tech-debt process allows a new entry, otherwise record it in the runbook and in the review notes.
- Env reference: NO new env var, config or endpoint is added (design: "Config: none. Endpoints: none"), so `docs/reference/environment-variables.md` and `endpoints.md` are NOT changed. If the engineer adds any env var after all, update `docs/reference/environment-variables.md` in this task and report it.
- Tests: none (docs). Check events.md field names against the code.
- ACs and conditions: AC-18 (documented shortfall), C2, C5.
- Verify: `node .claude/hooks/forge-verify.mjs --changed` (from the worktree)

## Final gate

### T7. Size check (C6)
- Goal: confirm production changes stay under 600 lines (expected ~145).
- Files: none changed.
- Command: `git -C .openforge/worktrees/F-20261119-10 diff --shortstat b3cda340c42cb466b55a09c709cd76da6054900a -- ':!**/src/test/**' ':!docs'`, recorded in the work folder. Count production lines only (exclude tests and docs).
- ACs and conditions: C6.
- Verify: the command above; result under 600.

### T8. Final forge-verify from the main checkout
- Goal: the "done" gate. Run from the MAIN checkout (not the worktree), after T1..T7, with the worktree pointed at by `--dir`. Record the full output in the work folder.
- Files: none changed. If it fails, fix in the relevant task and rerun.
- ACs and conditions: all.
- Verify: `node .claude/hooks/forge-verify.mjs --changed --base b3cda340c42cb466b55a09c709cd76da6054900a --dir .openforge/worktrees/F-20261119-10`

## Coverage table
| Item | Tasks |
|---|---|
| AC-14 | T2, T3, T4, T5, T8 |
| AC-15 | T2, T4, T5, T8 |
| AC-16 | T3, T4, T5, T8 |
| AC-17 | T1, T2, T4, T8 |
| AC-18 | T2, T6, T8 |
| C1 age-gate both ways | T4, T8 |
| C2 deploy promotion first, in runbook | T6 (ordering of T1..T5 puts promotion first), T8 |
| C3 completing return before earlier event | T3, T8 |
| C4 replay after clamp to 0 | T1, T2, T8 |
| C5 WARN log and privacy-export backlog note | T2 (WARN), T6 (backlog note), T8 |
| C6 under 600 production lines | T7, T8 |
