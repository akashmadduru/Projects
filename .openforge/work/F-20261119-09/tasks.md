# F-20261119-09 Tasks (O-05a return refunds), class L, 12 tasks

Owner of every task: forge-backend-engineer. Each task includes its own tests. Paths: `OS` = services/order-service/order-service-app/src/main/java/com/acme/ecommerce/order, `PS` = services/payment-service/payment-service-app/src/main/java/com/acme/ecommerce/payment. Tests live in the matching `src/test` tree.
Worktree: `.openforge/worktrees/F-20261119-09`. Gradle command form (one build at a time):
`bash .openforge/scripts/gradle.sh -C .openforge/worktrees/F-20261119-09 <gradle task>`
Payment-service tasks come first because it deploys first (CTO condition 4). Base: F-04 head 218998b1.
CTO conditions: C1 = COD fails loudly, backlog ID filed, runbook says 404 leaves return PICKED_UP. C2 = completing-return shipping rule plus two-shipment test. C3 = replay never calls RazorpayGateway, only FAILED starts `-r<n>`, exception never bumps attempt. C4 = payment-service deploys first, in the runbook. C5 = finalize guarded by `@Version` plus PICKED_UP.

## Payment-service

### T1. RefundResponse.refundStatus and replay lookup
- Goal: `RefundResponse` gains `refundStatus` (PENDING|SUCCEEDED|FAILED) = status of the refund row for this idempotency key. New insert: inserted status, re-read after parked events are applied. Replay (both replay points): `PaymentRepository.refundStatusByKey(key)`. F-04 state machine untouched.
- Files: payment-service-api `RefundResponse.java`; `PS/service/PaymentService.java` (refund); `PS/.../PaymentRepository.java`; tests in payment-service-app (Testcontainers base).
- Tests: Razorpay mock yields `refundStatus=PENDING`; replay never calls `RazorpayGateway` (verify zero interactions); after a FAILED webhook the replay reports FAILED; COD payment with destination WALLET raises `refunded_amount` and writes exactly one `wallet_txns` row; COD_PENDING gives 409; missing payment gives 404.
- ACs: AC-4, AC-13. CTO: C3 (replay test), C1 (409/404 behaviour).
- Verify: `bash .openforge/scripts/gradle.sh -C .openforge/worktrees/F-20261119-09 :services:payment-service:payment-service-app:test`

## Order-service

### T2. Migration V13 and OrderReturn leg columns
- Goal: `V13__return_refund_legs.sql` exactly as in design section 2 (additive, no backfill, partial index `ix_order_returns_rest_pending`). Map the new columns on `OrderReturn`. Add `OrderReturnRepository` queries: lease claim (conditional update), lease release, attempt bump (`WHERE rest_leg_attempt=n`), find PENDING (limit 50).
- Files: `OS/.../db/migration/V13__return_refund_legs.sql` (under order-service-app resources), `OS/domain/OrderReturn.java`, `OS/repository/OrderReturnRepository.java`.
- Tests: Testcontainers: V13 applies on top of V12; a pre-migration row (null legs) loads; lease claim wins once and fails for a second caller; attempt bump is conditional.
- ACs: AC-7, AC-9 (foundation), edge case "rows before migration". CTO: C5 (PICKED_UP-guarded claim).
- Verify: `bash .openforge/scripts/gradle.sh -C .openforge/worktrees/F-20261119-09 :services:order-service:order-service-app:test`

### T3. Shipping rule in Order.requestReturn
- Goal: add `shippingAmount` to `refund_amount` and store `shipping_refund` when (i) this return's lines complete every order line via `remainingReturnable(null)` and (ii) no other non-rejected return has `shipping_refund>0`. Expose `shippingRefund` and `refundState` (nullable) on `ReturnResponse` and `OrderMapper`.
- Files: `OS/domain/Order.java`, `OS/dto/ReturnResponse.java` (or its api module), `OS/mapper/OrderMapper.java`.
- Tests (unit): single full return gets shipping; partial return none; two shipment returns summing to the whole order, only the second gets shipping; three partials, only the completing one; shipping-carrying return REJECTED then a new completing return gets shipping once; free-shipping order (S=0).
- ACs: AC-1, AC-2, AC-3. CTO: C2.
- Verify: `bash .openforge/scripts/gradle.sh -C .openforge/worktrees/F-20261119-09 :services:order-service:order-service-app:test`

### T4. Remote refund returns the refund-row status
- Goal: order-service `RefundResult` gains `refundStatus`; `PaymentFeignClient` DTO updated; `OrderRemote.refund` returns it (null read as SUCCEEDED, documented as unsafe against an old payment-service). Errors still pass through `RemoteCalls.translate` (503 stays 503).
- Files: `OS/client/PaymentFeignClient.java`, `RefundResult`, `OS/service/OrderRemote.java`.
- Tests: unit: PENDING, SUCCEEDED, FAILED, null mapping; 503 and 404 translation.
- ACs: AC-5, AC-11 (foundation). CTO: C4 (null semantics).
- Verify: `bash .openforge/scripts/gradle.sh -C .openforge/worktrees/F-20261119-09 :services:order-service:order-service-app:test`

### T5. Leg freezing (allocation)
- Goal: `OrderStore.freezeLegs` per design section 2: `w = min(round2(R*W/T), W - Σw_other, R)`, `rest = R - w`, excess to `w` when rest exceeds the card remainder; route NONE|PAYMENT_SOURCE|PAYMENT_WALLET|DIRECT_WALLET; unfrozen REFUNDED siblings use the legacy formula; stores in one tx; no-op if already frozen; unpaid orders keep the current rule.
- Files: `OS/service/OrderStore.java`.
- Tests: parametrized allocation test: wallet-only, card-only, mixed, coins, thirds rounding, sequential partials ending on the last cent, completing return carrying shipping; assert `w + rest = R` to the cent. COD and `refundTo=WALLET` route PAYMENT_WALLET; R=0 gives NONE.
- ACs: AC-10, AC-4 (route), edge cases (wallet-only, card-only, R=0).
- Verify: `bash .openforge/scripts/gradle.sh -C .openforge/worktrees/F-20261119-09 :services:order-service:order-service-app:test`

### T6. Leg progress writes and finalizeRefund
- Goal: `OrderStore` methods to commit `wallet_leg_done_at`, set `rest_leg_state`, bump attempt (conditional), and `finalizeRefund` in one `TransactionTemplate` tx: `markRefunded` (requires PICKED_UP, `@Version`), `order.recordRefund(R)`, outbox `RELEASE_STOCK`, outbox `RETURN_REFUNDED`. Single hook for the F-10 coin rows (comment only).
- Files: `OS/service/OrderStore.java`, `OS/domain/OrderReturn.java` (state helpers).
- Tests: Testcontainers: finalize writes one `RELEASE_STOCK` and one event outbox row; a second finalize does nothing or conflicts; concurrent finalize yields one REFUNDED; a rollback leaves no partial outbox rows.
- ACs: AC-6, AC-9, AC-12 (exactly once). CTO: C5.
- Verify: `bash .openforge/scripts/gradle.sh -C .openforge/worktrees/F-20261119-09 :services:order-service:order-service-app:test`

### T7. ReturnService.refund flow (wallet leg, rest leg, COD, retry)
- Goal: rewrite `ReturnService.refund` per design steps 1 to 6: early return on REFUNDED with zero calls; lease claim (409 "refund in progress") released in `finally`; freeze; wallet leg then commit `wallet_leg_done_at` before the rest leg; rest leg by route with key `return-<id>` (attempt 0) or `return-<id>-r<n>`; SUCCEEDED finalizes; PENDING records and returns (no finalize, `refundState=PENDING`); FAILED reported by payment-service bumps the attempt once and retries with the new key; exception records FAILED and never bumps; 503 propagates. Delete the COD direct-wallet fallback (no on-demand payment row).
- Files: `OS/service/ReturnService.java`.
- Tests (Mockito InOrder): wallet-done write precedes the refund call; card throws, so FAILED, attempt unchanged, PICKED_UP; retry makes no second `creditWallet`, same key, then REFUNDED; REFUNDED makes zero remote calls; PENDING makes no `releaseStock` or publish; retry while PENDING reuses the same key; FAILED replay uses `-r1`, an exception never yields `-r1`; COD sends destination WALLET once, 409 or 404 means no `creditWallet` and return stays PICKED_UP, 503 propagates; R=0 makes no calls; refund-in-progress 409.
- ACs: AC-4, AC-5, AC-6, AC-7, AC-8, AC-9, AC-11, AC-12 (FAILED branch), AC-13, AC-10. CTO: C1, C3, C5.
- Verify: `bash .openforge/scripts/gradle.sh -C .openforge/worktrees/F-20261119-09 :services:order-service:order-service-app:test`

### T8. ReturnRefundWatch and resumePending
- Goal: `ReturnService.resumePending(id)` (claim lease, replay the same key; SUCCEEDED finalizes, FAILED records FAILED and logs `RETURN REFUND FAILED`, PENDING no-op). New `ReturnRefundWatch` `@Scheduled(fixedDelayString="${order.returns.refund-poll-interval-ms:300000}")` under `JobLock "order-return-refund-watch"`, up to 50 returns. Add the property to `application.yml`.
- Files: `OS/service/ReturnService.java`, new `OS/job/ReturnRefundWatch.java`, `order-service-app/src/main/resources/application.yml`.
- Tests: unit for the three outcomes; Testcontainers: watch moves PENDING to SUCCEEDED and finalizes once (second run does nothing); FAILED leaves PICKED_UP and an admin retry skips the wallet leg; JobLock held means no processing.
- ACs: AC-12, AC-13, AC-8 (retry after FAILED).
- Verify: `bash .openforge/scripts/gradle.sh -C .openforge/worktrees/F-20261119-09 :services:order-service:order-service-app:test`

### T9. Order-service end-to-end Testcontainers (QA)
- Goal: integration tests across the real DB: pre-migration row with null legs retries once and does not double-credit; two concurrent refund calls give one REFUNDED, one `RELEASE_STOCK`, one event row, one card refund call; wallet ledger shows exactly one credit after wallet-ok, card-fail, retry; REFUNDED re-call adds no ledger or outbox rows; admin-only access (customer gets 403).
- Files: order-service-app Testcontainers tests only (no production code).
- ACs: AC-6, AC-7, AC-8, AC-9, AC-10, edge cases (concurrency, pre-migration, admin only). CTO: C5.
- Verify: `bash .openforge/scripts/gradle.sh -C .openforge/worktrees/F-20261119-09 :services:order-service:order-service-app:test`

## Config, docs, verification

### T10. Env reference for ORDER_RETURNS_REFUND_POLL_INTERVAL_MS
- Goal: new env var from T8. Describe it in `docs/reference/env-descriptions.json`, add to config examples if the other order vars are there, regenerate `docs/reference/environment-variables.md`, then run the check.
- Files: `docs/reference/env-descriptions.json`, `docs/reference/environment-variables.md`, (if applicable) `deploy/local/config.env`-style example files.
- Regenerate and check: `node deploy/local/scripts/env-reference.mjs` then `node deploy/local/scripts/env-reference.mjs --check` (run with cwd = the worktree).
- ACs: supports AC-12 (poll interval configuration).

### T11. Docs and runbook
- Goal: update `docs/reference/api.md` (admin return status response: `shippingRefund`, `refundState`), `docs/reference/data-model.md` (V13 columns), `docs/reference/events.md` (RETURN_REFUNDED now published only after money settles), and the runbook: (a) deploy order, payment-service before order-service, null `refundStatus` is unsafe against an old payment-service; (b) a 404 on a COD refund leaves the return PICKED_UP and why; (c) PENDING leg and poll interval; (d) rollback. File backlog item "ensure COD payment row at refund" as B-10 in `.openforge/knowledge/backlog.md` (confirm the next free ID there; adjust the runbook reference if B-10 is taken). No endpoint added, so endpoints.md is unchanged.
- Files: `docs/reference/api.md`, `docs/reference/data-model.md`, `docs/reference/events.md`, the runbook under `docs/` (locate the existing runbook; create `docs/runbooks/return-refunds.md` if none), `.openforge/knowledge/backlog.md`.
- ACs: AC-5, AC-11, AC-12 (documented behaviour). CTO: C1, C4.
- Verify: `node .claude/hooks/forge-verify.mjs --changed --dir .openforge/worktrees/F-20261119-09` (docs only, no gradle), then T12.

### T12. Final forge-verify (from the main checkout)
- Goal: record the output of the full verification run for the branch.
- Command, run from c:/Users/Akash/Desktop/Projects (main checkout):
  `node .claude/hooks/forge-verify.mjs --changed --dir .openforge/worktrees/F-20261119-09 --base 218998b1b0ec3bb70e2e207eec0fa342b8bc6414`
- Pass criteria: env reference check passes, gradle test for payment-service-app and order-service-app passes. Record the output in the work folder (history).
- ACs: all (gate).

## Coverage table

| AC | Tasks |
|---|---|
| AC-1 | T3 |
| AC-2 | T3 |
| AC-3 | T3 |
| AC-4 | T1, T5, T7 |
| AC-5 | T4, T7, T11 |
| AC-6 | T6, T7, T9 |
| AC-7 | T2, T7, T9 |
| AC-8 | T7, T8, T9 |
| AC-9 | T2, T6, T7, T9 |
| AC-10 | T5, T7, T9 |
| AC-11 | T4, T7, T11 |
| AC-12 | T6, T7, T8, T10, T11 |
| AC-13 | T1, T7, T8 |

| CTO condition | Tasks |
|---|---|
| C1 (COD fails loudly, B-10 filed, runbook 404) | T1, T7, T11 |
| C2 (completing-return shipping, two-shipment test) | T3 |
| C3 (replay never calls gateway, only FAILED starts `-r<n>`, exception never bumps) | T1, T7 |
| C4 (payment-service deploys first, in runbook) | T4, T11 |
| C5 (finalize guarded by `@Version` plus PICKED_UP) | T2, T6, T7, T9 |

All of T1 to T12 feed the T12 gate.
