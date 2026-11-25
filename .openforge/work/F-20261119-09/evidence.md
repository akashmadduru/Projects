# Evidence F-20261119-09
- @ c6f1e36c (CEO-run, 2026-11-21): forge-verify --changed --base 218998b1 --dir <worktree>: env reference PASS; gradle order-service-app and payment-service-app PASS. JUnit: 27 suites, 197 tests, 0 skipped, 0 failures, 0 errors. During the build Spotless reformatted 2 base files (OrderService cartFingerprint, OrderPlacementIntegrationTest), formatting only; the CEO restored them after the run. Dirty after restore: 0. Prod lines 576.
- @ 1860914e (CEO-run, 2026-11-21): forge-verify --changed --base 218998b1: env reference PASS; gradle order + payment PASS. JUnit: 27 suites, 200 tests, 0 skipped, 0 failures, 0 errors. Spotless reformat noise restored after the run. Prod lines 590 (CEO-counted the gate's way).
- @ b3cda340 (CEO-run, 2026-11-21): forge-verify --changed --base 218998b1: env reference PASS; gradle order + payment PASS. JUnit: 27 suites, 201 tests, 0 skipped, 0 failures, 0 errors. Engineer's old-logic proof: completingReturnRefundedFirst and refundedOrderStillRoutes FAIL against the reverted logic (reported, not CEO-reproduced). Prod lines 594.

## QA (step 7)
Head b3cda340c42cb466b55a09c709cd76da6054900a. No code or tests changed by QA. Mapping read from the test sources; suites confirmed in worktree build/test-results/test XML (order-service-app and payment-service-app): ReturnShippingRuleTest 6, FreezeLegsTest 14, OrderRemoteRefundTest 6, ReturnServiceRefundTest 19, ReturnRefundIntegrationTest 16, ReturnRefundWatchTest 2, RefundStatusReportIntegrationTest 6. Each has failures=0, errors=0, skipped=0. QA did not re-run gradle; results are the CEO's run (201 tests).
Abbreviations: RSR = ReturnShippingRuleTest, FL = FreezeLegsTest, ORR = OrderRemoteRefundTest, RSRf = ReturnServiceRefundTest, RRI = ReturnRefundIntegrationTest, RRW = ReturnRefundWatchTest, RSRI = RefundStatusReportIntegrationTest.

| AC | Tests (class::method) | Asserts | Verdict |
|---|---|---|---|
| AC-1 | RSR::singleFullReturn | full return refund includes shipping | PASS |
| AC-2 | RSR::partialReturn, RSR::threePartials, RSR::freeShipping | partial returns carry no shipping | PASS |
| AC-3 | RSR::twoShipmentReturns, RSR::rejectedCarrierMovesShipping, RSR::threePartials | shipping once, on the completing return | PASS |
| AC-4 | RSRf::codDestinationWallet, RSRI::codToWallet, FL::single (COD route) | destination WALLET sent once, no direct credit; refunded_amount raised, one wallet row | PASS |
| AC-5 | RSRf::codRejected, RSRf::codUnavailable, ORR::unavailable, ORR::notFound | 409/404: no credit, PICKED_UP; 503 propagates, nothing finalized | PASS |
| AC-6 | RSRf::walletDoneBeforeRefund, RRI::finalizesOnce | wallet-done write precedes refund call (InOrder) | PASS |
| AC-7 | RSRf::cardThrows, RRI::walletOkCardFailsThenRetry, RRI::migrationAndNullLegs | wallet done, card FAILED, PICKED_UP | PASS |
| AC-8 | RSRf::retryAfterCardFailure, RRI::walletOkCardFailsThenRetry, RRI::failedThenAdminRetry | one wallet credit, same key, REFUNDED | PASS |
| AC-9 | RSRf::alreadyRefunded, RRI::refundedIsNoOp, RRI::secondFinalizeConflicts, RRI::concurrentRefunds, RRI::preMigrationRowRetries | no calls, ledger or outbox rows | PASS |
| AC-10 | FL::single (parameterized), FL::sequentialPartials, FL::legacySibling, FL::completingReturnRefundedFirst, FL::refundedOrderStillRoutes, RRI::concurrentSiblings | w + rest = R to the cent | PASS |
| AC-11 | RSRf::pendingLeg, ORR::passesThrough, RSRI::pending, RRI::watchSettlesPending | no finalize, stock release or event; state PENDING | PASS |
| AC-12 | RSRf::resumeSucceeded, RRW::resumesAll, RSRf::resumeFailed, RSRf::failedRowStartsNewKey, RRI::watchSettlesPending, RRI::failedThenAdminRetry | SUCCEEDED finalizes once; FAILED stays PICKED_UP, retry skips wallet | PASS |
| AC-13 | RSRf::retryWhilePending, RSRI::replayNeverCallsGateway, RSRI::replayAfterFailed | same key, no gateway call | PASS |

Verdict counts: 13 PASS, 0 NOT RUN.

Gaps (non-blocking):
- No test re-run by QA; verdicts rely on the CEO's JUnit XML at this SHA.
- ORR::nullStatus (null read as SUCCEEDED) is a documented unsafe default against an old payment-service.
- The engineer's old-logic proof for completingReturnRefundedFirst and refundedOrderStillRoutes was not reproduced.
- AC-10 "every order mix" rests on the parameterized FL::single rows; QA did not enumerate its rows.
- Coin ACs are out of scope (F-20261119-10).
