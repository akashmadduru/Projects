# Evidence F-20261119-04

## Verify (recorded by the CEO)
- Slice 1 @ 4e5fb6d0: forge-verify --changed → forge-verify: PASS (1 checks); JUnit XML: tests 74 skipped 0 failures 0 errors 0; dirty files after build: 0
- Slice 2 @ d17de779 (CEO-run, 2026-11-19): `forge-verify --changed --base fcf6f2a2 --dir <worktree>`: env reference --check PASS; gradle payment-service-app test PASS (gradle_rc=0). JUnit XML: 13 suites, 91 tests, 0 skipped, 0 failures, 0 errors. Dirty files after build: 0. Production lines since base 672 (slice 1 ~282, slice 2 ~390). Delivered as two stacked PRs, each under 600 (constitution 4).
- Fix round @ bc216685 (CEO-run, 2026-11-19): forge-verify --changed --base fcf6f2a2: env reference --check PASS; gradle payment-service-app test PASS (gradle_rc=0). JUnit: 14 suites, 95 tests, 0 skipped, 0 failures, 0 errors. Dirty after build: 0. Slice-2 prod lines (4e5fb6d0..HEAD) 501.
- Fix round 3 @ 218998b1 (CEO-run, 2026-11-19): forge-verify --changed --base fcf6f2a2: env reference --check PASS; gradle payment-service-app test PASS (gradle_rc=0; 17 s, so test outputs probably came from the Gradle cache for this same SHA). JUnit: 14 suites, 97 tests, 0 skipped, 0 failures, 0 errors. Dirty after build: 0. Slice-2 prod lines 520.

## QA (step 7)
Head SHA 218998b1. Static review of tests against the ACs; no test was added and none was run by QA (the CEO's uncached payment-service run at this SHA is the execution evidence; last recorded: 97 tests, 0 failures, 0 errors). Paths are under `services/payment-service/payment-service-app/src/test/java/com/acme/ecommerce/payment/service/`. R = RazorpayRefundStatesIntegrationTest, N = RefundNonRazorpayRegressionTest, C = RazorpayOrderClaimIntegrationTest, W = RazorpayCaptureWatchIntegrationTest, M = RefundMigrationIntegrationTest.

| AC | Test(s) | What it asserts | Verdict |
|---|---|---|---|
| AC-1 | R.acceptedRefundIsPending, R.twoPendingAndReplay | Row PENDING, payment PARTIALLY_REFUNDED 200.00, second refund over the remainder throws DOMAIN_RULE_VIOLATION, admin view shows PENDING; two pending sum to 250.00; replayed key makes no extra provider call | pass (service level; the HTTP 200 itself is not asserted, but RefundResponse is unchanged) |
| AC-2 | R.processed | processed -> SUCCEEDED, payment stays REFUNDED 500.00, no alert | pass |
| AC-3 | R.failed, R.onlyOwnAmountReleased | FAILED, payment back to SUCCEEDED 0.00, wallet balance 0, single REFUND FAILED log containing order id, refund id and amount; a second pending refund is untouched | pass |
| AC-4 | R.retryAfterFailed | New key after FAILED creates a PENDING refund; the old key returns the stored outcome; provider called exactly 2 times | pass |
| AC-5 | R.duplicates, R.concurrentDuplicates | Same event id, same refund and state with other event ids, and 6 concurrent deliveries: one alert, one release | pass |
| AC-6 | R.terminalNeverOverwritten, R.failedAfterSucceeded | failed-then-processed and processed-then-failed keep the terminal state; warn "conflicting"; ERROR alert for failed after SUCCEEDED | pass |
| AC-7 | R.mismatchesAreIgnored, R.refundOfAnotherPayment, R.parkedMismatchDropped, R.duplicateProviderRefundId | Unknown payment, amount, currency, missing entity, created and speed_changed: no state change and no exception; refund id owned by another payment is not applied | pass |
| AC-8 | R.badSignature, R.badSignatureLeavesNoTrace | Bad and null signature throw VALIDATION_FAILED; no state change, no processed event, nothing parked | pass |
| AC-9 | C.concurrentCreatesMakeOneOrder, C.repeatedCreateIsIdempotent, C.failureThenLeaseReclaim, C.lateWinnerCannotPublish, C.publishIsGuarded, C.staleReleaseLeavesTakeoverLeaseIntact | 8 threads: createOrder called once, one payment row, all callers get the same order id; failure and lease takeover; a late winner's publish returns 0 | pass |
| AC-10 | W.alertsOnce, eventAloneDoesNotAlert, capturedBeforeAuthorized, authorizedAlreadyCaptured, capturedWithinGrace, stampSkippedAfterCapture, otherModesNeverAlerted, stuckRefund | Two ticks give one alert and no status change; the three no-alert cases; non-Razorpay modes never alerted; stuck refund alerts once | pass |
| AC-11 | Docs check (no test): runbook "Razorpay setup and alerts" in docs/guides/runbooks/payment-problems.md; TD-P6-30/31 marked fixed and TD-P6-32 narrowed in docs/techdebt/README.md and remediation-plan.md | Content present | pass |
| AC-12 | N.codRefund, N.walletRefund, N.stripeRefund, N.stripeRefundWithIntent, M.defaultIsSucceeded, C.codUnchanged, PaymentServiceRazorpayTest (existing) | COD, Stripe, wallet refunds SUCCEEDED with the same replies; wallet credited at once; old rows default to SUCCEEDED; COD create unchanged | pass (see gap 2) |

Tests added: none. Commit: none; head stays 218998b1.

Gaps (not blocking):
1. AC-1 and AC-4 are asserted at service level, not through the controller, so the HTTP 200 is not directly checked.
2. AC-12 "webhooked": `handleWebhook` (Stripe) has no test in the module, before or after this change. Its body is untouched in the diff (the changed hunks are the new Razorpay methods), so the risk is low. A characterisation test is a backlog item.
3. Review nit: the method='CARD' publish predicate is untested (non-blocking).
4. Open from security: add a PR note about the V8 checksum change (flyway repair if a shared DB ran the d17de779 V8).

Defects found: none.
Verdict: PASS. Every AC maps to at least one test that fails if the behaviour breaks.
- Uncached rerun @ 218998b1 (CEO-run, 2026-11-19): gradle.sh :services:payment-service:payment-service-app:test --rerun-tasks: PASS (gradle_rc=0, 3m 23s, no cache). JUnit: 14 suites, 97 tests, 0 skipped, 0 failures, 0 errors. The new timing tests ran and passed: 'a holder that overran its lease cannot release the lease of the one that took over' and 'publish is guarded: a payment switched to another mode gets no order id'.
