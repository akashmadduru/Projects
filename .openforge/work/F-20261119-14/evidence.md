# F-20261119-14 evidence (Part A, in progress)

## A1 baseline
F-15 head 8ff29132. Targeted baseline (CodPaymentRowsIntegrationTest, OutboxIntegrationTest, OrderPlacementIntegrationTest, ReturnRefundLegacyCodIntegrationTest, ReturnRefundIntegrationTest): BUILD SUCCESSFUL, gradle_rc=0 (taken on pure base: OrderShipment.class compiled before any F-14 edit). V-ALL baseline and payment-service run not done separately (V-ALL runs at the Part A gate). V14 is free (V13 latest); F-14 uses V14__shipment_cancel_refund.sql.

## Part A guard demos (mutants applied to the production code, tests unchanged; 12 of 12 target guards fail on their real assertion)
- Whole cancel after partial (mutant: `if (false && plan.viaShipments())`, i.e. the old cancel-<orderId> path): WholeOrderCancelIT.afterPartialCancel_refundsRemainder_workedExample: `expected: 1400.00 but was: 680.00`; followUpsOnce: `expected: 0 but was: 1`.
- Cancel racing ship (mutants: no order lock in claim/fail/commit, no REQUESTED guard in Order.advanceShipment): ShipmentCancelRaceIT.cancelVsShip_exactlyOneWins: `Expecting actual throwable to be an instance of CodedException but was ObjectOptimisticLockingFailureException`; shipWhileRequested_409: `Expecting code to raise a throwable`; doubleCancel_oneRefund: `expected null but was ObjectOptimisticLockingFailureException`.
- Card FAILED (mutant: FAILED ignored): ShipmentCancelIT.paymentFailed_503_retryUsesR1 and ShipmentCancelWalletIT.cardFails_onCardPlusWallet_503_noCreditQueued: `Expecting code to raise a throwable`.
- Wallet-only cancel with payment down (mutant: credit never queued): walletOnlyCancel_paymentDown_commitsAndQueuesCredit: `EmptyResultDataAccessException: Incorrect result size: expected 1, actual 0` (no queued row).
- Replay queues no duplicate credit (same mutant): replay_queuesNoDuplicateCredit: `expected: 1 but was: 0`; ShipmentCancelIT.replayAfterSuccess: `expected: 1 but was: 0`.
- 401/403/404/409 matrix (mutant: seller ownership check removed): ShipmentCancelAuthzIT.sellerMatrix: `[not cancelled by a stranger] expected: null but was: "DONE"` (the HTTP answer alone stayed 404 because the response read is owner-scoped; the test now also asserts nothing was cancelled).
Positive sanity cases: the owner/right-order requests return 200 in the same tests; the unmutated run passes (run4: 46 run, only 2 test-expectation bugs, fixed).

## Open
- Part A production lines vs base: 617 (593 adds + 24 deletes, src/main incl. V14 SQL) > 600 ceiling. Stopped and reported to the CEO. V-ALL and forge-verify (verify-A.md) not run on the final head.
- AC-4 gap: seller-service does not treat CANCELLED as terminal (backlog). SHIPMENT_STATUS_CHANGED consumers: seller-service books DELIVERED only.

## Final verification (Part A 0727afe, Part B 57e20e6)

Gate line counts (git diff --numstat 8ff2913..HEAD, excluding src/test, docs and .md): Part A 586 (8ff2913..0727afe); Part B adds 35 (0727afe..57e20e6); PR total 597.

| Run | Head | Tests | gradle_rc |
|---|---|---|---|
| Full order-service module (detached worktree F-20261119-14V) | 0727afe | 256 run, 0 failed, 0 skipped | 0 |
| forge-verify --changed (verify-A.md) | 0727afe | PASS (1 checks) | 0 |
| Full order-service module (F-14 worktree) | 57e20e6 | 262 run, 0 failed, 0 skipped | 0 |
| forge-verify --changed (verify-B.md) | 57e20e6 | PASS (1 checks) | 0 |

Guard demos (HEAD tests against older src/main; each must fail):
- r3 ITs against 8f214e7: failedClaim_siblingHoldsShipping_shipIsRefused and siblingDoneWithShipping_thenFailed_shipIsRefused, 2 tests completed, 2 failed (no 409 on the ship), gradle_rc=1.
- Round-1 IT failedClaim_siblingCancelNotCompleting_shipWins against a764802: 1 failed, gradle_rc=1 (assertion messages were not captured for these two runs).
- B-fix IT returnWhileCancelRequested_shippingOnce_orderRefunded against b4c7f44: AssertionFailedError, expected 50.00 but was 0.0000, gradle_rc=1.
src/main was restored to HEAD after each demo.

Note: gradle.sh spotlessApply reformats OrderStoreCancelIT.java (test file, +2/-1 lines of formatting only) on every run; it was reverted and not committed.

## QA
Source: JUnit XML in services/order-service/order-service-app/build/test-results/test/ from the engineer's full run at 57e20e6 (262 run, 0 failed, 0 skipped). QA ran no build (CEO verify build in progress). All XML names below are prefixed `TEST-com.acme.ecommerce.order.`.

- AC-1: pass — ShipmentCancelIT."AC-1: a seller cancels S1: its share is refunded, only S1 stock is released, one event" (service.ShipmentCancelIT.xml); share maths: OrderCancelShareTest.workedExample_850_510_390 (domain.OrderCancelShareTest.xml)
- AC-2: pass — ShipmentCancelIT."AC-2: a replay after success makes no second refund, credit or event" and "AC-2: two shipments of one order refund under two distinct keys" (service.ShipmentCancelIT.xml)
- AC-3: pass — ShipmentCancelIT."AC-3: payment-service down gives 503 and nothing changes; the retry reuses the key" and "AC-3: a FAILED card refund gives 503 with no change; the retry uses the -r1 key" (service.ShipmentCancelIT.xml); ShipmentCancelWalletIT."a card failure on a card-plus-wallet order gives 503 and queues no credit" (service.ShipmentCancelWalletIT.xml)
- AC-5: pass — ShipmentCancelIT parameterised [1..5] SHIPPED/OUT_FOR_DELIVERY/DELIVERED/PENDING_PAYMENT/HOLD (service.ShipmentCancelIT.xml); ShipmentCancelAuthzIT."a shipment that already shipped is a 409, and /status CANCELLED stays a 409" (web.ShipmentCancelAuthzIT.xml)
- AC-6: pass — ShipmentCancelAuthzIT."seller: the owner gets 200, another seller 404, an unapproved seller 403" and "admin: right order 200, shipment of another order 404, customer 403, anonymous 401" (web.ShipmentCancelAuthzIT.xml)
- AC-7: pass — Part A (cancel side): ShippingOnceIT."AC-7: S1 returned, then S2 and S3 cancelled: the last cancel carries the shipping" and ShipmentCancelIT."AC-7: a FAILED claim moved no money, so a sibling cancel is not completing" (service.ShippingOnceIT.xml, service.ShipmentCancelIT.xml). Part B (return side): ShippingOnceIT."AC-7: S1 and S2 cancelled, then S3 returned: the return carries the shipping once", "AC-7: a return while a sibling cancel is REQUESTED carries the shipping once", "AC-7: a single-shipment order returns shipping exactly as before" (service.ShippingOnceIT.xml); ReturnShippingRuleTest."AC-3: two shipment returns that sum to the whole order refund shipping once" (domain.ReturnShippingRuleTest.xml)
- AC-8: pass (order-service side only) — CancelEventShapeTest."SHIPMENT_STATUS_CHANGED CANCELLED has no returnId, refundAmount or return values" and "ORDER_CANCELLED has no returnId, refundAmount or return values" (event.CancelEventShapeTest.xml); OrderEventPublisherReturnTest."RETURN_REFUNDED carries the return items value and the order subtotal" (event.OrderEventPublisherReturnTest.xml)
- AC-9: pass — WholeOrderCancelIT."AC-9: a whole cancel after a partial cancel refunds only the remainder (850/510/390)", "AC-9: shares of 3+ shipments with a coupon and shipping sum to the total to the cent", "AC-9: coupon, coins and the order event run once, at the transition to CANCELLED" (service.WholeOrderCancelIT.xml)
- AC-10: pass — ShipmentCancelRaceIT."AC-10: a cancel racing a ship has exactly one winner and the state matches it", "AC-10: two concurrent cancels of one shipment make one refund, one credit, one event", "AC-10: cancelling a cancelled shipment is a 200 with no second effect", "AC-10: shipping a shipment whose cancel is in progress is a 409" (service.ShipmentCancelRaceIT.xml)
- AC-11: pass — ShipmentCancelIT."AC-11: an uncollected COD shipment is cancelled with no payment call and no refund row" and "AC-11: a zero-amount cancel moves no money and records no refund" (service.ShipmentCancelIT.xml)
- AC4: deferred (CEO ruling, backlog)

### Gaps
- AC-8: only the order-service side is proved (events carry no shipping or refund values on cancel; RETURN_REFUNDED carries items value). No seller-service test was run or examined, so "seller-service never reverses shipping" is not proved by a test here; seller-service has no shipping-reversal logic in this diff, and the AC4 seller touch is deferred.
- Guard demos in this file have assertion messages missing for two r3 runs and the round-1 run (noted above); not a test gap.
- Non-blocking: spotlessApply reformats OrderStoreCancelIT.java on every run (noted above).
- No in-scope AC lacks a passing test. No defects found. QA added no tests and edited no production code.

## CEO verify (57e20e6, 2026-11-23T15:00:20Z)
```
forge-verify: 35 changed file(s) in C:/Users/Akash/Desktop/Projects/.openforge/worktrees/F-20261119-14 (base 8ff29132fe43)
  PASS gradle :services:order-service:order-service-app:test (exit 0)
      BUILD SUCCESSFUL in 2m 11s
      gradle_rc=0
forge-verify: PASS (1 checks)
rc=0
```
