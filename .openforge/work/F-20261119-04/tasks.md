# F-20261119-04 Tasks

Conventions
- Owner of every task: forge-backend-engineer.
- M = `services/payment-service/payment-service-app` (verified Gradle module `:services:payment-service:payment-service-app`).
  Main = `M/src/main/java/com/acme/ecommerce/payment`, Res = `M/src/main/resources`, Test = `M/src/test/java/com/acme/ecommerce/payment`.
- Worktree W = C:/Users/Akash/Desktop/Projects/.openforge/worktrees/F-20261119-04.
- Verify prefix V = `bash C:/Users/Akash/Desktop/Projects/.openforge/scripts/gradle.sh -C W`.
  Every command below is `V <task>`, with W written out in full. Run one build at a time.
- Existing migrations are V1..V6, so V7 and V8 are the next numbers. Existing tests live in Test/service (e.g. PaymentServiceRazorpayTest) and extend Test/support/AbstractPaymentIntegrationTest.
- Each task includes its tests. Production budget: slice 1 about 330 lines, slice 2 about 260 lines (each under 600).
- CTO condition 6: any change to the migrations or lease semantics after design returns to the CTO.

## Slice 1 (AC-1..8, AC-12)

### T1 Migration V7 and repository plumbing
- Goal: add the refund status columns, indexes and parked table. Repository gets `insertRefund(..., status)`, `findRefundByProviderRefundId` (FOR UPDATE), `findByProviderPaymentIdForUpdate`, `settleRefundRow` (conditional UPDATE ... WHERE status='PENDING'), and park/take/delete parked-event methods.
- Files: Res/db/migration/V7__razorpay_refund_states.sql (new), Main/repository/PaymentRepository.java, Test/service/RefundMigrationIntegrationTest.java (new).
- Tests: a pre-existing refund row (inserted without status) reads as SUCCEEDED; the CHECK constraint rejects other values; the conditional settle returns 1 then 0; parked insert is idempotent.
- ACs: AC-12 (default SUCCEEDED), groundwork for AC-1..7. CTO: 5 (only the one new table).
- Verify: `bash C:/Users/Akash/Desktop/Projects/.openforge/scripts/gradle.sh -C C:/Users/Akash/Desktop/Projects/.openforge/worktrees/F-20261119-04 :services:payment-service:payment-service-app:test --tests "*RefundMigrationIntegrationTest"`

### T2 Refund inserts PENDING for Razorpay
- Goal: in `refund()`, a Razorpay provider refund inserts PENDING; Stripe, COD and WALLET insert SUCCEEDED. The reservation of refunded_amount/status is unchanged, and so is `RefundResponse`. After the insert, apply and delete any parked event for the new provider_refund_id through `settleRefund` (a stub from T3 is acceptable only if T3 lands before the T2 verify; otherwise implement `settleRefund` here and let T3 add the webhook branch).
- Files: Main/service/PaymentService.java, Main/repository/AdminPaymentQueries.java and Main/web/AdminPaymentController.java (additive `status` on RefundRow), Test/service/RazorpayRefundStatesIntegrationTest.java (new, refund part), Test/service/RefundNonRazorpayRegressionTest.java (new).
- Tests: Razorpay refund stored PENDING and returns 200. A second refund exceeding the remainder returns 422 DOMAIN_RULE_VIOLATION. Two pending refunds at once. Stripe, COD and wallet-destination refunds are SUCCEEDED, with codes and states identical to before, and the wallet is still credited immediately. Admin detail shows status. Replay of the same idempotency key makes no second provider call.
- ACs: AC-1, AC-12.
- Verify: `bash C:/Users/Akash/Desktop/Projects/.openforge/scripts/gradle.sh -C C:/Users/Akash/Desktop/Projects/.openforge/worktrees/F-20261119-04 :services:payment-service:payment-service-app:test --tests "*RazorpayRefundStatesIntegrationTest" --tests "*RefundNonRazorpayRegressionTest"`

### T3 settleRefund and refund webhook branch
- Goal: add `settleRefund` (SUCCEEDED leaves the payment unchanged; FAILED releases the amount, restores payment status and refunded_amount, does not credit the wallet, and logs `REFUND FAILED` with order id, refund id and amount; rows=0 means duplicate (info) or terminal conflict (warn)). Add `handleRazorpayRefund` in `handleRazorpayWebhook`:
  - refund.created and refund.speed_changed return 200 as a no-op.
  - Missing `payload.refund.entity` warns and returns 200.
  - The event id is recorded in the same tx (`rzp:<eventId>`, or `rzp:<type>:<refundId>` when the header is absent).
  - Unknown payment or refund is parked or warned, and returns 200.
  - A payment/amount/currency mismatch warns and returns 200.
  - The signature gate stays first.
- Files: Main/service/PaymentService.java, Main/web/PaymentController.java (only if the event-id header handling needs it), Test/service/RazorpayRefundStatesIntegrationTest.java (webhook part).
- Tests (signed payloads via `hmacHex`):
  - processed leaves payment status and amount unchanged.
  - failed releases the amount, restores the pre-refund status, does not credit the wallet, and logs the alert.
  - Retry after FAILED with a new key creates a new refund, while the old key returns the stored outcome with no second provider call.
  - Duplicate delivery (same event id, and same refund id and state with a different event id) causes no second release or alert, shown with a log appender.
  - failed then processed, and processed then failed, never overwrite the terminal state (warn, 200).
  - Unknown refund id, payment mismatch, amount mismatch, currency mismatch and missing entity all return 200 with no change.
  - Bad or missing signature returns 400 VALIDATION_FAILED with no change.
  - Two pending refunds with one failing releases only its own amount.
  - CTO 3 (race): an event that arrives before the refund row is parked, then applied by `refund()` (parked then applied).
- ACs: AC-2, AC-3, AC-4, AC-5, AC-6, AC-7, AC-8. CTO: 3 (parked-then-applied race).
- Verify: `bash C:/Users/Akash/Desktop/Projects/.openforge/scripts/gradle.sh -C C:/Users/Akash/Desktop/Projects/.openforge/worktrees/F-20261119-04 :services:payment-service:payment-service-app:test --tests "*RazorpayRefundStatesIntegrationTest"`

### T4 Slice 1 gate
- Goal: run the full payment-service-app test suite to confirm there are no regressions (existing PaymentServiceRazorpayTest, Cancel tests and others), and confirm that the production diff for slice 1 is under 600 lines. Fix any failures found in the files from T1..T3. This task adds no new feature code.
- Files: none planned (fixes only in T1..T3 files).
- ACs: AC-12 (regression), all slice 1 ACs.
- Verify: `bash C:/Users/Akash/Desktop/Projects/.openforge/scripts/gradle.sh -C C:/Users/Akash/Desktop/Projects/.openforge/worktrees/F-20261119-04 :services:payment-service:payment-service-app:test`

## Slice 2 (AC-9..11), starts after slice 1 is merged or stacked

### T5 Migration V8 and one Razorpay order per payment
- Goal: add `V8__razorpay_order_claim_capture.sql` (provision_claimed_at, authorized_at, capture_alerted_at). Razorpay create path does insert-first with a NULL provider_order_id, then `createOrder` with no tx, then conditional publish (`WHERE provider_order_id IS NULL`). When the publish returns 0, return the stored id. Losers try a lease reclaim, otherwise poll every 100 ms until the id appears or the lease ends, then return 503 UPSTREAM_UNAVAILABLE. Add `provisionLease` (18s) to PaymentProperties and application.yml. Stripe and COD keep their current path. Leave `switchProvider` as is.
- Files: Res/db/migration/V8__razorpay_order_claim_capture.sql (new), Main/repository/PaymentRepository.java, Main/service/PaymentService.java, Main/config/PaymentProperties.java, Res/application.yml, Test/service/RazorpayOrderClaimIntegrationTest.java (new).
- Tests:
  - 8 threads on a latch with a counting fake `createOrder`: count == 1, one payment row, and every caller gets the same provider order id with 200.
  - Lease-expiry reclaim after a failed `createOrder`.
  - CTO 3: the late original winner's conditional publish returns 0 and its order is never reachable.
  - `createOrder` throwing returns 503 and leaves the row with no order; `view()` returns a null providerOrderId and reconcile skips it.
  - Stripe and COD create are unchanged.
- ACs: AC-9, AC-12. CTO: 3 (late-winner publish returns 0), 6.
- Verify: `bash C:/Users/Akash/Desktop/Projects/.openforge/scripts/gradle.sh -C C:/Users/Akash/Desktop/Projects/.openforge/worktrees/F-20261119-04 :services:payment-service:payment-service-app:test --tests "*RazorpayOrderClaimIntegrationTest"`

### T6 Capture-mode watch and stuck-pending refund alert
- Goal:
  - Before coding, cite Q1 (https://razorpay.com/docs/webhooks/payloads/payments/: the `payment.authorized` event name and `payload.payment.entity.order_id`, `captured` flag) in a code comment and in the test class Javadoc, and record fact 4 in the tests (CTO 1).
  - The `payment.authorized` webhook branch only stamps `authorized_at` (idempotent COALESCE, `status IN ('REQUIRES_PAYMENT','PROCESSING')`, and no stamp when `captured=true`). It never alerts (CTO 2).
  - New `service/RazorpayWatch` (@Scheduled, `jobLock.runExclusive("payment-razorpay-watch", ...)`) runs the claim-and-alert UPDATE ... RETURNING, with the status guard applied at alert time too, and logs `RAZORPAY NOT CAPTURED`. It never changes payment status.
  - The same tick alerts `REFUND PENDING TOO LONG` via `pending_alerted_at`.
  - Add `watchIntervalMs`, `captureAlertAfter` (PT15M) and `refundPendingAlertAfter` (P10D) to PaymentProperties and application.yml.
- Files: Main/service/PaymentService.java, Main/service/RazorpayWatch.java (new), Main/repository/PaymentRepository.java, Main/config/PaymentProperties.java, Res/application.yml, Test/service/RazorpayCaptureWatchIntegrationTest.java (new).
- Tests:
  - Authorized and still unpaid after the grace period: two ticks give exactly one alert and status is unchanged.
  - Required cases: captured before authorized gives no alert. authorized with captured=true gives no alert. Authorized then captured within the grace period gives no alert.
  - The event alone (before the grace period) gives no alert.
  - Stuck PENDING refund gives one alert, and a second tick does not re-alert.
  - Non-Razorpay modes are never alerted.
- ACs: AC-10. CTO: 1, 2.
- Verify: `bash C:/Users/Akash/Desktop/Projects/.openforge/scripts/gradle.sh -C C:/Users/Akash/Desktop/Projects/.openforge/worktrees/F-20261119-04 :services:payment-service:payment-service-app:test --tests "*RazorpayCaptureWatchIntegrationTest"`

### T7 Docs, runbook and techdebt
- Goal:
  - Runbook section in docs/guides/runbooks/payment-problems.md covers: enabling auto-capture in the Razorpay dashboard, the meaning of `RAZORPAY NOT CAPTURED`, `REFUND FAILED` and `REFUND PENDING TOO LONG`, and the required webhook subscriptions (refund.processed, refund.failed, payment.authorized). It states that a historic SUCCEEDED refund that actually failed needs a manual dashboard reconcile (CTO 4).
  - Update docs/reference/environment-variables.md with the four new properties, and services/payment-service.md with the refund states and order claim.
  - Close TD-P6-30 and TD-P6-31, and narrow TD-P6-32, in docs/techdebt (phase6.md and README.md). Add rows to docs/techdebt/remediation-plan.md.
  - Backlog notes: parked-table retention (CTO 5) and the `switchProvider` race (`AND mode <> :m`).
- Files: docs/guides/runbooks/payment-problems.md, docs/reference/environment-variables.md, docs/services/payment-service.md (confirm the real path; the design names `services/payment-service.md`), docs/techdebt/phase6.md, docs/techdebt/README.md, docs/techdebt/remediation-plan.md.
- Tests: none for docs. Verify by grep that the runbook headings and the three TD ids are updated, then run the final suite.
- ACs: AC-11. CTO: 4, 5.
- Verify: `bash C:/Users/Akash/Desktop/Projects/.openforge/scripts/gradle.sh -C C:/Users/Akash/Desktop/Projects/.openforge/worktrees/F-20261119-04 :services:payment-service:payment-service-app:test`

## Coverage: AC to tasks

| AC | Tasks |
|---|---|
| AC-1 | T1, T2, T4 |
| AC-2 | T3, T4 |
| AC-3 | T3, T4 |
| AC-4 | T3, T4 |
| AC-5 | T3, T4 |
| AC-6 | T3, T4 |
| AC-7 | T3, T4 |
| AC-8 | T3, T4 |
| AC-9 | T5 |
| AC-10 | T6 |
| AC-11 | T7 |
| AC-12 | T1, T2, T4, T5 |

## Coverage: CTO conditions to tasks

| Condition | Tasks |
|---|---|
| 1 Cite Q1, record fact 4, three no-alert cases | T6 |
| 2 Event alone never alerts, only the watch job | T6 |
| 3 Late-winner publish returns 0; parked-then-applied race | T5, T3 |
| 4 Runbook: historic refund reconcile and webhook subscriptions | T7 |
| 5 Parked table retention note, no other new tables | T7, T1 |
| 6 Migration or lease changes return to the CTO | T1, T5 (noted) |
