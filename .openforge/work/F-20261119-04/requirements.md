# F-20261119-04 Requirements: Razorpay refund states, single order per payment, capture-mode alert

## 1. Problem and users
A Razorpay refund is recorded as done the moment Razorpay accepts it, so a refund that later fails leaves the
customer unpaid while the system shows REFUNDED. Concurrent provisioning can create two Razorpay orders for one
payment, and manual-capture accounts authorise payments that are never captured, silently. Affected: customers
paying/refunded via Razorpay (INR), finance/ops operators, order-service and its event consumers.

## 2. Scope
- payment-service Razorpay refund lifecycle (PENDING, SUCCEEDED, FAILED) driven by signed webhooks.
- One Razorpay order per payment under concurrent create (TD-P6-30).
- Detection, alert and documentation of manual capture mode (TD-P6-32, narrowed).

Verified current behaviour (A1/A2):
- A1: `payment.refunds` has NO status column (V1__payment_baseline.sql:29-38, V3__razorpay.sql:18-19 only adds
  provider_refund_id). `refund()` inserts the row (PaymentService.java:606) and in the same transaction sets
  `payments.refunded_amount` and `payments.status` to REFUNDED / PARTIALLY_REFUNDED (:622-624), right after the
  provider call returns an id (:590). Wallet-destination refunds credit the wallet in that transaction (:613).
- A2: Readers: order-service `OrderRemote.refund` (:298) treats a non-error return as done and then records
  `Order.paymentStatus` REFUNDED/PARTIALLY_REFUNDED (OrderStore.java:360, :595; Order.java:497) and publishes
  events consumed by seller-service ledger, promotion coins, notification. `RefundResponse.status` and
  `AdminPaymentQueries` read payment status. Wallet credit is NOT a downstream reader: it happens only for
  destination WALLET, which never calls Razorpay.

## 3. Non-goals
Multi-currency, seller-side payouts, recurring/subscriptions on Razorpay (TD-P6-32 stays open, text narrowed);
Stripe and COD changes; order-service changes; auto-retry of failed refunds; auto-crediting wallet on failure;
Razorpay-side idempotency (none exists); `refund.speed_changed` handling beyond a 200 no-op.

## 4. Acceptance criteria
- AC-1: Given a card payment on Razorpay, when a refund is accepted by Razorpay, then the refund row is stored
  with status PENDING (not final), the call returns 200, and the amount counts against the refundable balance
  (a second refund exceeding the remainder still fails 422 DOMAIN_RULE_VIOLATION).
- AC-2: Given a PENDING refund, when a validly signed `refund.processed` arrives for its provider refund id,
  then the refund becomes SUCCEEDED and payment status/refunded amount are unchanged from AC-1; response 200.
- AC-3: Given a PENDING refund, when a validly signed `refund.failed` arrives, then the refund becomes FAILED,
  the reserved amount is released (payment refunded_amount and status return to their pre-refund values), the
  wallet is NOT credited, and an operator alert is raised containing order id, refund id and amount.
- AC-4: Given a FAILED refund, when the caller retries with a new idempotency key, then a new refund can be
  created for the released balance; retrying the old key returns the stored outcome without a second provider call.
- AC-5: Given a processed or failed refund event delivered twice (same event id, or same refund id and state),
  then the second delivery returns 200 and changes nothing (no second release, no second alert).
- AC-6: Given `refund.failed` arrives before `refund.processed` or after SUCCEEDED (or the reverse), then a
  terminal state is never overwritten; the conflicting event is logged at warn and returns 200.
- AC-7: Given a refund event for an unknown refund id, or an event whose payment/amount/currency does not match
  our record, then no state changes, a warning is logged, and the response is 200 (so Razorpay stops retrying).
- AC-8: Given an invalid or missing `X-Razorpay-Signature` on a refund event, then 400 VALIDATION_FAILED and no
  state change (same as today for other events).
- AC-9: Given N>=2 concurrent create-payment calls for the same order on Razorpay, then exactly one Razorpay order
  is created, one payment row exists, and all callers get the same provider order id and 200.
- AC-10: Given Razorpay payments that are authorised but not captured (manual capture), then the service detects
  it (payment.authorized seen with no capture, or capture-mode check) and raises one operator alert per
  affected payment, with no change to payment status; a repeat check does not re-alert.
- AC-11: Given the repo docs, then a runbook states how to enable auto-capture in the Razorpay dashboard and what
  the alert means; TD-P6-30/31 are closed and TD-P6-32 is narrowed in docs/techdebt.
- AC-12: Given Stripe and COD payments, when created, refunded or webhooked, then behaviour, status codes and
  stored states are identical to before; wallet-destination refunds stay immediately final.

## 5. Edge cases and abuse
- Replayed or forged webhooks (signature, body tampering, old event ids); event with missing `payload.refund`.
- Refund for a payment partially refunded already; two refunds pending at once; failure of one releases only its amount.
- Webhook arrives before the refund row is committed (race with `refund()` transaction): must not be lost.
- Provider call succeeds but local commit fails: retry with same key must not double-refund.
- Razorpay create-order times out after creating the order: retry must not leave an orphan reachable by a second payment.
- Late-payment refund by order-service (cancel path) obeys the same PENDING/FAILED rules.

## 6. Open questions
None blocking. Noted for design: order-service already shows REFUNDED at acceptance; this change keeps that
(non-goal) and relies on the alert for FAILED, so the Owner may want a follow-up objective for order-service
reconciliation. Existing refund rows (no status) must be treated as SUCCEEDED (backfill).
