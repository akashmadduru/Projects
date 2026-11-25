# F-20261119-14 Tasks: per-shipment cancel and refund (order-service only)

Owner of every task: forge-backend-engineer. Class L, two stacked parts. A must not merge without B (CTO condition 1).
Base: the final head of F-20261119-15 (itself on F-11). AC-4 is deferred by CEO ruling and is not planned here.
Paths: `OS` = `services/order-service/order-service-app/src/main/java/com/acme/ecommerce/order/`, `OT` = the matching `src/test/java/...` tree.
Branch `forge/F-20261119-14-<slug>` in a worktree. Commits are local only. No push without the Owner.

Verification commands (from project.md):
- V-T(x): `bash .openforge/scripts/gradle.sh :services:order-service:order-service-app:test --tests '<x>'`. `gradle.sh` passes its arguments to Gradle (`TASKS="$*"`). Run one build at a time. Read `gradle_rc=`.
- V-ALL: `bash .openforge/scripts/gradle.sh :services:order-service:order-service-app:test`
- V-FORGE: `node .claude/hooks/forge-verify.mjs --changed`. The output is recorded in evidence.md.

Commit rule: each task ends with one commit (the boundary). The Part A and Part B boundaries (A10, B4) also require V-ALL and V-FORGE.

Tolerating F-15 (it adds the ENSURE_COD_PAYMENT outbox kind and `CodPaymentRows`, and changes `OrderService` placement, `ReturnService.runLegs` and `OutboxRelay`):
- F-14 adds no outbox kind and does not touch placement. Stock release reuses the existing RELEASE_STOCK kind, and the wallet credit reuses `WalletCreditBacks`.
- Every code reference in design.md (line numbers, `freezeLegs`, `route`) is read from the F-15 head, not from 71bba97. Locate by symbol name. If `runLegs` or `freezeLegs` has moved or changed shape, adapt the extraction (A3) to it. Do not re-implement it.
- `OutboxRelay` is read only. If its switch must change, add nothing beyond a case that already exists.
- The F-15 COD behaviour (COD_PENDING payment row, route NONE, `collect` on delivery) must stay green after every F-14 task.

---

## Part A: cancel side (about 490 production lines, ceiling 560; if it passes 560, stop and report)

### A1. Rebase on F-15 head and re-run F-15's tests (baseline)
- Goal: put the branch on F-15's final head and prove that F-15 is green before any F-14 change. Record the F-15 head SHA, whether `V14` is still free (CTO condition 4), and which F-15 symbols (`CodPaymentRows`, `runLegs`, `OutboxRelay`) the later tasks touch.
- Files: none changed. Write the baseline output to `.openforge/work/F-20261119-14/evidence.md`. If `V14__*.sql` is taken on the F-15 head, record the next free number and use it in A2.
- ACs / conditions: none (baseline); condition 4.
- Verify, in this order:
  - `bash .openforge/scripts/gradle.sh :services:order-service:order-service-app:test --tests '*CodPaymentRowsIntegrationTest' --tests '*OutboxIntegrationTest' --tests '*OrderPlacementIntegrationTest' --tests '*ReturnRefundLegacyCodIntegrationTest' --tests '*ReturnRefundIntegrationTest'`
  - then V-ALL
  - if F-15 changed payment-service, also `bash .openforge/scripts/gradle.sh :services:payment-service:payment-service-app:test` (one build at a time).
- Commit boundary: no code commit. A baseline note is committed only if evidence.md is tracked. Any F-15 failure is reported to the CEO and nothing else starts.

### A2. Migration V14 and shipment cancel state
- Goal: additive columns and the `OrderShipment` fields: `cancelRefundAmount`, `cancelShippingRefund`, `cancelWalletLeg`, `cancelRestLeg`, `cancelRestRoute`, `cancelState` (null, REQUESTED, FAILED, DONE), `cancelAttempt`. Add small accessors: `holdsCancelClaim()`, `isCancelled()`.
- Files: `services/order-service/order-service-app/src/main/resources/db/migration/V14__shipment_cancel_refund.sql` (`SET LOCAL lock_timeout='5s'`, no index, no backfill), `OS domain/OrderShipment.java`; test `OT domain/OrderShipmentCancelStateTest` plus a migration-applies check in the existing Testcontainers base.
- ACs / conditions: groundwork for AC-1, AC-2, AC-10; condition 4.
- Verify: V-T(`*OrderShipmentCancelStateTest`), then V-T(`*ShipmentCancelMigrationIT`).
- Commit boundary: yes.

### A3. Pro-rata share, `covered` and `cancelShare` in `Order`
- Goal: extract `paidShare(gross)` from the return formula (same rounding) and share it between returns and cancels. Add `covered(line)` (non-rejected returns plus cancelled or claimed shipments) and `cancelShare(s)` with the remainder rule. `cancelShare` returns items and shipping separately. It does not change return behaviour yet. The Part B changes to `completesOrder` and `fullyRefunded` are NOT made here.
- Files: `OS domain/Order.java`; test `OT domain/OrderCancelShareTest`: the worked example 850/510/390, 3 or more shipments summing to total to the cent, a return plus a cancel mix, and no shipping when a claim already carries it.
- ACs / conditions: AC-1, AC-7 (cancel side), AC-9; condition 6 (rounding across 3 or more).
- Verify: V-T(`*OrderCancelShareTest`), then V-T(`*ReturnRefundIntegrationTest`) as a regression check on the extraction.
- Commit boundary: yes.

### A4. Extract `Legs split` and `route` (pure refactor on F-15's code)
- Goal: lift `Legs split(R, W, T, sibWallet, sibRest)` and `route(order, refundTo, rest)` out of `freezeLegs`/`route`, keeping the maths identical. Returns call the extracted code, including F-15's `runLegs` and legacy COD paths. Siblings gain frozen cancels, but the sibling list is empty until A6 supplies them.
- Files: `OS service/OrderStore.java` (and `ReturnService.java` only if `runLegs` calls the old signature); test `OT service/LegsSplitTest` (the maths table, COD_PENDING/PENDING/FAILED route NONE, excess to wallet).
- ACs / conditions: groundwork for AC-1, AC-11.
- Verify: V-T(`*LegsSplitTest`), then V-T(`*ReturnRefundIntegrationTest`) and V-T(`*ReturnRefundLegacyCodIntegrationTest`).
- Commit boundary: yes.

### A5. `WalletCreditBacks`: keyed enqueue and `tryNow`
- Goal: add an insert-only `enqueue(userId, amount, currency, orderId, key)` that joins the caller's transaction (`ON CONFLICT (idem_key) DO NOTHING`), and expose a never-throwing `tryNow(key)` over the existing private `attempt`. No new job.
- Files: `OS service/WalletCreditBacks.java`; test `OT service/WalletCreditBacksKeyedIT`: a replay inserts one row, a rollback of the outer transaction leaves no row, `tryNow` swallows errors, and `retryDue` still delivers.
- ACs / conditions: AC-2 (no duplicate credit); condition 2.
- Verify: V-T(`*WalletCreditBacksKeyedIT`), then V-T(`*WalletCreditBacks*`) for the existing TD-P8-07 tests.
- Commit boundary: yes.

### A6. `OrderStore` claim, fail and commit, plus ship guard
- Goal: `claimCancel` (T1: lock order, 404 ownership, 409 validation, per-shipment DONE skip, REQUESTED reuse, FAILED attempt++, null freeze share and legs, stable order by createdAt and id, set `updated_at`), `failCancel` (T2f, attempt-matched) and `commitCancel` (T3: idempotent, CANCELLED and DONE, history row with shipmentId, `recordRefund` if money moves, keyed wallet enqueue `ship-cancel-wallet-<sid>` for wallet leg plus DIRECT_WALLET rest, outbox RELEASE_STOCK keyed `RELEASE_STOCK:<order>:<shipment>`, `syncFromShipments`). `advanceShipment` locks the order row first. `Order.advanceShipment` rejects REQUESTED with 409 RESOURCE_CONFLICT "cancel in progress". A FAILED claim is cleared on ship but the attempt is kept. Add `Order.isCancellable` handling for CANCELLED shipments (AC-9 guard). Keys: `ship-cancel-<sid>` and `ship-cancel-<sid>-r<n>`.
- Files: `OS domain/Order.java`, `OS domain/OrderShipment.java`, `OS service/OrderStore.java`; test `OT service/OrderStoreCancelIT` (claim and commit paths, idempotent T3, wrong-attempt T3 is a no-op, no remote calls inside the transactions, a FAILED claim cleared by ship).
- ACs / conditions: AC-1, AC-2, AC-3, AC-5, AC-6 (ownership 404), AC-10, AC-11; conditions 2 and 3.
- Verify: V-T(`*OrderStoreCancelIT`), then V-T(`*OrderStore*`).
- Commit boundary: yes.

### A7. `OrderLifecycleService.cancelShipments` (flow and ITs)
- Goal: orchestrate T1, then the strict card leg (`remote.refund`, FAILED goes to T2f and 503, exception leaves REQUESTED and gives 503, SUCCEEDED or PENDING accepted, skipped for NONE, DIRECT_WALLET or 0), then T3, then `walletCreditBacks.tryNow`. Events: SHIPMENT_STATUS_CHANGED (plus `afterAdvance` when the order status moved); when no live shipment remains, the order becomes CANCELLED, `afterCancel(order, walletCreditBack=false)` runs, and ORDER_CANCELLED follows. No remote call runs inside a transaction. Logs carry ids and amounts only.
- Files: `OS service/OrderLifecycleService.java`; tests `OT service/ShipmentCancelIT` (`sellerCancelsS1_refundsShare_onlyS1Stock_oneEvent`, `replayAfterSuccess_noSecondRefund_noEvent`, `twoShipments_distinctKeys`, `paymentDown_503_unchanged_retrySameKey`, `paymentFailed_503_retryUsesR1`, `notCancellable_409_noPaymentCall` parametrised SHIPPED/OFD/DELIVERED/PENDING_PAYMENT/hold, `codUncollected_noPaymentCall`, `zeroAmount_noRefundRow`) and `OT service/ShipmentCancelWalletIT` (`walletOnlyCancel_paymentDown_commitsAndQueuesCredit`, `cardFails_onCardPlusWallet_503_noCreditQueued`, `replay_queuesNoDuplicateCredit`). Payment Feign mocked as in `ReturnRefundIntegrationTest`. The COD cases run against F-15's COD_PENDING payment row.
- ACs / conditions: AC-1, AC-2, AC-3, AC-5, AC-11 (and the cancel side of AC-7); conditions 2 and 6.
- Verify: V-T(`*ShipmentCancelIT`), then V-T(`*ShipmentCancelWalletIT`).
- Commit boundary: yes.

### A8. Seller and admin endpoints, authz matrix, endpoint docs
- Goal: `POST /api/seller/shipments/{id}/cancel` on `SellerShipmentController` (SELLER, `approvedSellerId` null gives 403, not owned gives 404 `ResourceNotFoundException("Shipment")`, returns `SellerShipmentResponse`) and `POST /api/admin/orders/{id}/shipments/{shipmentId}/cancel` on `AdminOrderController` (`hasRole('ADMIN')`, shipment not on `{id}` gives 404, returns `OrderResponse`). Both take `AdminRequests.CancelOrder`. `/status` with CANCELLED stays 409. Update `docs/reference/endpoints.md` and `api.md`.
- Files: `OS web/SellerShipmentController.java`, `OS web/AdminOrderController.java`, `docs/reference/endpoints.md`, `docs/reference/api.md`; test `OT web/ShipmentCancelAuthzIT` (other seller 404, wrong order 404, customer 403, anonymous 401, unapproved seller 403, `/status` CANCELLED 409).
- ACs / conditions: AC-6, AC-1 (endpoint entry); condition 6 (401/403/404/409 matrix).
- Verify: V-T(`*ShipmentCancelAuthzIT`).
- Commit boundary: yes.

### A9. Whole-order cancel via `cancelShipments`
- Goal: a customer or admin whole-order cancel of a non-PENDING_PAYMENT order with shipments claims every live shipment (risk hold allowed, as today). The old `CancelPlan` and `cancel-<orderId>` path remains only for PENDING_PAYMENT, expiry and payment-failed. `cancel-<orderId>` is never sent once any shipment has a claim. Order follow-ups (coupon, coins, wallet) run once, at the transition to CANCELLED. Update the existing whole-cancel tests for the new keys. Do not delete them.
- Files: `OS service/OrderLifecycleService.java`, `OS domain/Order.java` (isCancellable); tests `OT service/WholeOrderCancelIT` (`afterPartialCancel_refundsRemainder_workedExample`, `followUpsOnce`, `rounding3Shipments_sumsToTotal`) and the updated existing cancel ITs.
- ACs / conditions: AC-9; condition 6.
- Verify: V-T(`*WholeOrderCancelIT`), then V-T(`*OrderCancel*`) for the updated existing tests.
- Commit boundary: yes.

### A10. Race tests and Part A gate
- Goal: prove AC-10 and close Part A. Check the Part A production line count (target about 490, stop at 560). Record the evidence: V14 final number, the F-15 head SHA, the AC-4 gap and the consumer check on SHIPMENT_STATUS_CHANGED (seller-service books DELIVERED only).
- Files: test `OT service/ShipmentCancelRaceIT` (`cancelVsShip_exactlyOneWins`, `doubleCancel_oneRefund`, `shipWhileRequested_409`, `cancelCancelled_200`); `.openforge/work/F-20261119-14/evidence.md`.
- ACs / conditions: AC-10; conditions 1, 3, 5, 6.
- Verify: V-T(`*ShipmentCancelRaceIT`), then V-ALL, then V-FORGE. Record all three outputs.
- Commit boundary: **Part A boundary.** Part A is not merged alone.

---

## Part B: return side (about 70 production lines), stacked on Part A

### B1. Covered-aware return completion and shipping-once
- Goal: `completesOrder` and `fullyRefunded` use `covered` (lines of cancelled shipments count). `shippingAlreadyRefunded` also counts a cancel claim with `cancel_shipping_refund > 0`. `OrderStore.requestReturn` locks the order row first. A single-shipment order behaves as before. F-15's `runLegs` and legacy COD return paths must be unaffected.
- Files: `OS domain/Order.java`, `OS service/OrderStore.java`; test `OT service/ShippingOnceIT` (`cancelThenReturn_returnCarriesShipping`, `returnThenCancel_cancelCarriesShipping`, `singleShipment_unchanged`, plus a refund total that equals the order's shipping exactly once over a mixed run).
- ACs / conditions: AC-7; conditions 1 and 6.
- Verify: V-T(`*ShippingOnceIT`), then V-T(`*ReturnRefundIntegrationTest`) and V-T(`*ReturnRefundLegacyCodIntegrationTest`).
- Commit boundary: yes.

### B2. Cancel event shape and seller reversal check
- Goal: prove that cancel events (SHIPMENT_STATUS_CHANGED CANCELLED, ORDER_CANCELLED) carry no `returnId` or `refundAmount`, so the seller consumer reverses nothing, and RETURN_REFUNDED is unchanged. No production change is expected. If a production change is needed, stop and report.
- Files: test `OT service/CancelEventShapeTest`. Record the consumer check (seller-service `OrderEventConsumer` ignores CANCELLED, RETURN_REFUNDED is items only) in evidence.md.
- ACs / conditions: AC-8; conditions 5 and 6.
- Verify: V-T(`*CancelEventShapeTest`).
- Commit boundary: yes.

### B3. Docs, runbook and backlog
- Goal: `docs/reference/events.md` (SHIPMENT_STATUS_CHANGED may carry CANCELLED from a per-shipment cancel; no schema change), and the payment runbook (stuck-REQUESTED query, the undelivered `ship-cancel-wallet-%` query, "retry the cancel clears a stuck REQUESTED", the rollback drain). Remove TD-P9-12 and TD-P9-13 from `docs/techdebt/` and add rows to `docs/techdebt/remediation-plan.md` (the project.md convention). Backlog entries: AC-4 seller-service guard after F-13, promotion coins on `totalAmount`, inventory release warehouse split, settlement watch for PENDING cancel refunds. Do not edit ADR 0014 (the architect owns it). Report if it still says Proposed.
- Files: `docs/reference/events.md`, the payment runbook file (locate under `docs/`), `docs/techdebt/README.md`, `docs/techdebt/remediation-plan.md`, `.openforge/knowledge/backlog.md`.
- ACs / conditions: AC-3 and AC-8 (docs side); conditions 3 and 5.
- Verify: V-FORGE (docs-only change, no Java build needed).
- Commit boundary: yes.

### B4. Final gate
- Goal: full verification on the stacked head. Confirm that the Part B lines are about 70 and the PR total is under 600. Record V14's final number. Confirm that `git diff` contains no change to the files the Owner must approve.
- Files: `.openforge/work/F-20261119-14/evidence.md` only.
- ACs / conditions: all in scope; conditions 1, 4, 5 and 6.
- Verify: V-ALL, then V-FORGE. Record both outputs.
- Commit boundary: **Part B boundary.** The review is requested against this SHA. A and B merge together.

---

## Coverage: AC to tasks
| AC | Tasks |
|---|---|
| AC-1 | A2, A3, A4, A6, A7, A8 |
| AC-2 | A2, A5, A6, A7 |
| AC-3 | A6, A7, B3 |
| AC-4 | deferred by CEO ruling (backlog in B3; gap disclosed in A10 evidence) |
| AC-5 | A6, A7 |
| AC-6 | A6, A8 |
| AC-7 | A3 (cancel side), A7, B1 |
| AC-8 | B2, B3 |
| AC-9 | A3, A6, A9 |
| AC-10 | A2, A6, A10 |
| AC-11 | A4, A6, A7 |

## Coverage: CTO conditions to tasks
| Condition | Tasks |
|---|---|
| 1 Split and size limits | A10, B1, B4 |
| 2 Durable wallet credit, strict card leg | A5, A6, A7 |
| 3 Ordering, runbook, no new job | A6, A7, A10, B3 |
| 4 Migration number | A1, A2, B4 |
| 5 AC-4 gap disclosure | A10, B2, B3 |
| 6 Named tests, matrix, existing tests updated | A3, A7, A8, A9, A10, B1, B2 |
