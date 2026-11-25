# F-20261119-04 Design: Razorpay refund states, one order per payment, capture-mode alert

Paths are relative to `services/payment-service/payment-service-app/src/main/`. PS = `java/.../service/PaymentService.java`, Repo = `.../repository/PaymentRepository.java`.

## 1. Current behaviour
- **Create.** `create` (PS:114) reads the row (:123) and calls `provision` → `razorpay.createOrder` (:139, :221) outside any tx. Only after that does it insert `ON CONFLICT (order_id) DO NOTHING` (PS:143, Repo:116). Two concurrent callers each create a Razorpay order. The loser's insert is a no-op and it returns the winner's row (:154), so the second order is orphaned. `switchProvider` (PS:304) has the same pattern.
- **Refund.** `refund` (PS:565) calls Razorpay (`refundRazorpay` :629, header `X-Refund-Idempotency` RazorpayGateway:142). Then, in one tx under `lockById` FOR UPDATE (:597), it inserts the refund (:606) and sets `payments.refunded_amount` and status REFUNDED/PARTIALLY_REFUNDED (:622-624). `payment.refunds` has no status column (V1:29-38, V3:18-19). Replays of the same idempotency key return the stored outcome (:567, :598).
- **Webhook.** `handleRazorpayWebhook` (PS:508) checks the signature first (:512-514). It returns early for anything except `payment.captured`/`order.paid` (:522). It dedupes through `processed_events` with the key `rzp:<X-Razorpay-Event-Id>` (:531-535, :561; header PaymentController:88).
- **Readers of refund state (A2).** AdminPaymentQueries:101 (admin detail), PrivacyExportController:20 (`r.*`), order-service `OrderRemote.refund`, which reads `RefundResponse` = payment status + refunded amount (requirements §2). Nothing reads a per-refund status today.
- **Jobs.** OrderReporter:85-96 runs `@Scheduled` + `jobLock.runExclusive(name, ...)`. Alerts are `log.error` with an upper-case prefix ("PAYMENT REPORT STUCK" OrderReporter:65, "DOUBLE CHARGE" PS:417).

## 2. Proposed change
**Data: forward-only. V7 in slice 1, V8 in slice 2.**
```sql
-- V7__razorpay_refund_states.sql
ALTER TABLE payment.refunds
  ADD COLUMN status varchar(10) NOT NULL DEFAULT 'SUCCEEDED'
      CONSTRAINT ck_refunds_status CHECK (status IN ('PENDING','SUCCEEDED','FAILED')),
  ADD COLUMN settled_at timestamptz,
  ADD COLUMN pending_alerted_at timestamptz;
CREATE INDEX ix_refunds_provider_refund ON payment.refunds (provider_refund_id) WHERE provider_refund_id IS NOT NULL;
CREATE INDEX ix_refunds_pending ON payment.refunds (created_at) WHERE status = 'PENDING';
CREATE INDEX ix_payments_provider_payment ON payment.payments (provider_payment_id) WHERE provider_payment_id IS NOT NULL;
CREATE TABLE payment.razorpay_refund_parked (            -- refund events that arrived before their row
  provider_refund_id varchar(100) NOT NULL, status varchar(10) NOT NULL,
  provider_payment_id varchar(100) NOT NULL, amount_minor bigint NOT NULL, currency char(3) NOT NULL,
  received_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY (provider_refund_id, status));
-- V8__razorpay_order_claim_capture.sql
ALTER TABLE payment.payments ADD COLUMN provision_claimed_at timestamptz,
  ADD COLUMN authorized_at timestamptz, ADD COLUMN capture_alerted_at timestamptz;
```
**Backfill.** Every existing refund gets `SUCCEEDED` from the constant default. On PG 11+ this is a metadata-only change, with no table rewrite.

Why SUCCEEDED:
- Each historic row is already counted in `refunded_amount`, and order-service already shows it as refunded.
- PENDING would release nothing, but it would fire the stuck-pending alert for every historic refund.
- FAILED would be false.

The status quo is kept: a historic refund that really failed stays SUCCEEDED and needs a Razorpay-dashboard reconcile (runbook). The default stays after migration, so old pods inserting during a rolling deploy get today's semantics. I chose a non-unique index on `provider_refund_id` so the migration cannot fail on unexpected duplicates; the handler warns if more than one row matches.

**Refund (slice 1).** `insertRefund` takes a `status` argument. In the existing `refund` tx, a Razorpay provider refund inserts `PENDING`; Stripe, COD and WALLET insert `SUCCEEDED` (AC-12). `refunded_amount`/status are reserved exactly as today, and `RefundResponse` is unchanged. After the insert, the same tx checks `razorpay_refund_parked` for the new `provider_refund_id`, applies any parked event through `settleRefund`, and deletes the parked rows.

**`settleRefund(lockedPayment, refundRow, target)`** runs in a tx, holding the payment row lock first and then the refund row (the same lock order as `refund`, so no deadlock):
`UPDATE payment.refunds SET status=:t, settled_at=now() WHERE id=:id AND status='PENDING'` (returns rows).
- rows=1 and t=FAILED: new refunded amount = `locked.refundedAmount - r.amount`. Status = `SUCCEEDED` if that is 0, else `PARTIALLY_REFUNDED`. Write it with the existing `applyRefund`. The wallet is not credited. Then `log.error("REFUND FAILED: Razorpay refund {} of {} {} for order {} (refund row {}) failed; balance released, customer NOT refunded")`.
- rows=1 and t=SUCCEEDED: the payment is unchanged.
- rows=0: re-read. If the status equals the target, it is a duplicate (info). Otherwise it conflicts with a terminal state (warn), and nothing changes.

**Webhook refund branch.** After signature and parse (PS:515-521), any `refund.*` goes to `handleRazorpayRefund`:
- `refund.created` and `refund.speed_changed` return 200 as a no-op.
- `processed`/`failed`: read `payload.refund.entity` {id, payment_id, amount, currency}. If the entity is missing, warn and return 200.
- In one tx: `recordEvent("rzp:"+eventId or "rzp:"+type+":"+refundId)`. If that returns false, it is a duplicate: return. This INSERT ... ON CONFLICT runs inside the tx, so concurrent duplicates serialise on the PK.
- Then `SELECT ... FROM payments WHERE provider_payment_id=:pid FOR UPDATE`. If none, warn "unknown" and return 200 (AC-7).
- Then `SELECT ... FROM refunds WHERE provider_refund_id=:rid FOR UPDATE`.
  - Found: check `r.payment_id = p.id`, `amount_minor = toMinor(r.amount)` and currency = `p.currency`. On a mismatch, warn and return 200. Otherwise call `settleRefund`.
  - Not found: INSERT parked `ON CONFLICT DO NOTHING`, warn, return 200.
- Race proof: the webhook and `refund()` both lock the same `payments` row. Under READ COMMITTED, the second one to get the lock sees the first one's committed rows. So either the webhook finds the refund row, or `refund()` finds the parked event. Neither can miss both.

**One Razorpay order per payment (slice 2, AC-9). Razorpay mode only; Stripe and COD keep today's path.**
1. Insert first: `INSERT ... (provider_order_id, provision_claimed_at) VALUES (NULL, now()) ON CONFLICT (order_id) DO NOTHING`. Exactly one caller gets rows=1, because the unique index serialises racers.
2. The winner calls `createOrder` (no tx), then `UPDATE payments SET provider_order_id=:po, updated_at=now() WHERE id=:id AND provider_order_id IS NULL`. If that returns 0, a reclaimer won: return the stored id. Our order was never handed out.
3. Callers that find a row with `provider_order_id IS NULL` try to reclaim: `UPDATE payments SET provision_claimed_at=now() WHERE id=:id AND provider_order_id IS NULL AND status='REQUIRES_PAYMENT' AND (provision_claimed_at IS NULL OR provision_claimed_at < now() - make_interval(secs => :leaseSec))`. On rows=1 they provision as in step 2. Otherwise they poll `findByOrderId` every 100 ms until the id appears or the lease ends; at the lease end they return 503 UPSTREAM_UNAVAILABLE.
4. Lease defaults to connect + read timeout + 5 s = 18 s (`payment.razorpay.provision-lease`).
5. If `createOrder` throws, the row stays REQUIRES_PAYMENT with no order and the caller gets 503 as today. A retry reclaims after the lease. Until then, `view()` returns `providerOrderId=null`, and reconcile skips it (PS:358).

**Capture alert (slice 2, AC-10).**
- The webhook branch for `payment.authorized` runs `UPDATE payments SET authorized_at=COALESCE(authorized_at, now()) WHERE provider_order_id=:o AND status IN ('REQUIRES_PAYMENT','PROCESSING')`. This is idempotent, so no dedupe is needed.
- New `RazorpayWatch` @Component, `@Scheduled(fixedDelayString="${payment.razorpay.watch-interval-ms:300000}")`, `jobLock.runExclusive("payment-razorpay-watch", ...)`. It runs this claim-and-alert statement:
  `UPDATE payments SET capture_alerted_at=now() WHERE id IN (SELECT id FROM payments WHERE mode='RAZORPAY' AND authorized_at < now()-:grace AND capture_alerted_at IS NULL AND status IN ('REQUIRES_PAYMENT','PROCESSING') ORDER BY authorized_at LIMIT 50) RETURNING id, order_id, provider_order_id, amount, currency`.
  Each returned row gets `log.error("RAZORPAY NOT CAPTURED: ... enable auto-capture")`. Payment status is never changed.
- Stuck-PENDING refunds: the same tick runs the same shape on `refunds.pending_alerted_at` with `status='PENDING' AND created_at < now()-:refundAlertAfter` and logs "REFUND PENDING TOO LONG". No AC requires this. It is the safety net if refund webhooks are not subscribed (~35 lines; the CTO may cut it).

**Config** (`PaymentProperties.Razorpay`):
- `provisionLease` (18s)
- `watchIntervalMs` (300000)
- `captureAlertAfter` (PT15M)
- `refundPendingAlertAfter` (P10D)

All are added to `docs/reference/environment-variables.md`.

**Interfaces.**
- No new endpoints and no DTO changes, so order-service is untouched.
- Admin `RefundRow` gains an additive `status` (AdminPaymentQueries:101).
- The webhook endpoint now acts on `refund.processed`, `refund.failed` and `payment.authorized`.

## 3. AC → change
- AC-1: PENDING insert with the balance reserved; the existing 422 check (:579, :602) still applies.
- AC-2, AC-3: `settleRefund` SUCCEEDED/FAILED, with the release and the REFUND FAILED alert.
- AC-4: the release frees the balance. An old key replay hits `refundExists` (:567) with no provider call.
- AC-5: event-id record in the tx, plus the conditional `WHERE status='PENDING'`. rows=0 means no release and no alert.
- AC-6: the rows=0 conflict path warns and never overwrites.
- AC-7: unknown payment, or a payment/amount/currency mismatch, warns and returns 200. Early events are parked, not lost.
- AC-8: unchanged signature gate (PS:512-514). A test covers refund events.
- AC-9: insert-first claim, lease reclaim and conditional publish.
- AC-10: `authorized_at` plus the claim-and-alert `UPDATE ... RETURNING`.
- AC-11: runbook section in `docs/guides/runbooks/payment-problems.md` (auto-capture, subscribing to refund webhook events, alert meanings, reconcile of historic refunds); close TD-P6-30/31, narrow TD-P6-32, add rows to `remediation-plan.md`.
- AC-12: Stripe/COD/WALLET insert SUCCEEDED; the create path for non-Razorpay is unchanged; a regression test covers it.

## 4. Reuse and alternatives
**Reused:**
- JobLock (`libs/common-web/.../job/JobLock.java:49`)
- `processed_events` + `recordEvent` (Repo:268)
- `lockById` (Repo:93)
- `applyRefund` (Repo:224)
- the signature path
- the alert log style
- the lease-by-timestamp idea (cf. user-service `referral_claims.claimed_at`)

No shared lib fits: the lease is one SQL statement on a payment column.

**Rejected:**
- (a) `pg_advisory_xact_lock(hashtextextended(subject,0))` (as in cart WishlistRepository:105) held across `createOrder`. It holds a DB connection and tx open across a provider HTTP call for up to 13 s, which breaks the PS:33 invariant and can exhaust the pool under load.
- (b) Razorpay-side dedupe (fetch orders by `receipt`). Fact 3 says there is no idempotency, and the receipt filter is unproven.
- (c) Polling Razorpay for refund status instead of webhooks. It needs an unproven API and per-refund calls. Webhooks are proven (fact 1).
- (d) Releasing the balance only at SUCCEEDED (not reserving). That allows over-refund while PENDING, so it fails AC-1.
- (e) Parking early events in `processed_events` with an encoded key. That is a hack, and it cannot be queried by refund id.

## 5. Risks, rollback, tests
- **Risks.**
  - order-service keeps showing REFUNDED for a FAILED refund (non-goal). The REFUND FAILED alert is the only signal; this is a follow-up objective.
  - A refund created by an old pod during rollout is SUCCEEDED, so a later `refund.failed` only warns (AC-6). Keep the window short and grep the logs after deploy.
  - Parked rows from dashboard refunds accumulate (tiny; backlog: retention).
  - A loser waits up to the lease (18 s worst case).
  - `switchProvider` can still create two Razorpay orders concurrently (PS:304; out of AC scope, backlog: add `AND mode <> :m` to Repo:140).
- **Rollback.** Revert the image; V7/V8 are additive and old code ignores the new columns and table. PENDING rows written meanwhile stay PENDING (their amount is still reserved, which is old behaviour) and can be set to SUCCEEDED by hand.
- **Tests** (Testcontainers, `AbstractPaymentIntegrationTest`; Razorpay via the RazorpayGateway test seam):
  - AC-1..7: refund lifecycle with signed payloads (`hmacHex`), including duplicates, out-of-order events, unknown ids, mismatches, an early event that gets parked then applied, and two pending refunds with one failing.
  - AC-8: bad signature.
  - AC-9: 8 threads on a latch with a counting fake `createOrder`: count == 1 and every caller gets the same id. Also a lease-expiry reclaim.
  - AC-10: two ticks, one alert, status unchanged.
  - AC-12: Stripe/COD/wallet regression.
  - Migration test: an existing row reads as SUCCEEDED.

## 6. Files and slices
- **Slice 1** (AC-1..8, AC-12; about 330 production lines):
  - `V7__razorpay_refund_states.sql`
  - PaymentRepository
  - PaymentService (refund, webhook branch, settleRefund)
  - AdminPaymentQueries
  - AdminPaymentController record
- **Slice 2** (AC-9..11; about 260 lines):
  - `V8__razorpay_order_claim_capture.sql`
  - PaymentRepository
  - PaymentService (create, authorized branch)
  - new `service/RazorpayWatch.java`
  - PaymentProperties
  - application.yml
  - docs (runbook, environment-variables, techdebt, remediation-plan, services/payment-service.md)
- Test files are listed in tasks.md.

**Open questions (unproven, must be cited before slice 2):**
- Q1: the `payment.authorized` event name and `payload.payment.entity.order_id` shape (https://razorpay.com/docs/webhooks/payloads/payments/).
- Q2: refund.processed timing, which decides the `refundPendingAlertAfter` default.
- Q3: whether Razorpay honours `X-Refund-Idempotency` (existing code, not re-proven).
- Q4: the dashboard webhook must subscribe to refund.* and payment.authorized (operational; runbook).

## CTO decision
ACCEPT with conditions (spot-checks passed: createOrder PS:221, recordEvent Repo:268, lockById Repo:93, timeouts 3s+10s+5s=18s).
- Parked table: justified. The simpler option, returning 5xx so Razorpay retries, loses events if retries are exhausted and misses AC-7's 200. Keep it.
- Lease takeover is safe. A late original winner fails the conditional publish, so its order is never reachable. It leaves an orphan Razorpay order, never a second payable order or a double charge.
- Backfill SUCCEEDED: correct.
- Capture alert respects fact 4. `authorized_at` is only a candidate marker. The alert needs status still unpaid after the grace period, and the status guard applies at both stamp and alert time.
- Keep the stuck-PENDING alert. It is small and it is the only signal if refund webhooks are not subscribed.
- Slices of about 330 and 260 production lines are within 600.

Conditions:
1. Before coding slice 2, cite Q1 and record fact 4 in the tests. Required cases: captured-before-authorized and authorized-with-captured=true must produce no alert. Authorized-then-captured within the grace period must also produce no alert.
2. Do not alert on the event alone. Only the watch job alerts.
3. Tests must cover the late-winner publish returning 0, plus a refund/webhook race (parked then applied).
4. Runbook states that a historic SUCCEEDED refund that actually failed needs a manual dashboard reconcile, and that webhook subscriptions are required.
5. The new `razorpay_refund_parked` table needs a retention note in the backlog, and no other new tables.
6. Any change to the migrations or lease semantics after this point returns to the CTO.

## CTO ruling (slice 2 fix round)
Ruled by forge-cto on 2026-11-19. The CTO's write was denied, so forge-ceo transcribed the ruling verbatim.
(a) Approve. Release the lease only on a definitive createOrder failure, with provider_order_id IS NULL kept in the guard. The worst case is an orphan order, as already accepted.
(b) Approve. It returns 0 on the same path as a late winner.
(c) Approve. Startup validation only, no change to semantics.
(d) Approve. Prefer SET LOCAL lock_timeout so it does not leak into the session.

## CTO ruling (slice 2 fix round 3)
Ruled by forge-cto on 2026-11-19 in its reply; transcribed by forge-ceo. All four items approved, and condition 6 is satisfied:
1. insertClaimed and reclaimProvision return provision_claimed_at, which serves as the token for the compare-and-release. No schema change.
2. The release is guarded by AND provision_claimed_at = :mine. This closes the gap where an overrunning holder could wipe a takeover's lease.
3. The release is wrapped in try/catch with addSuppressed, so the original Razorpay error is kept.
4. A comment states that a timeout counts as a failure and that an orphan order is the accepted worst case.
