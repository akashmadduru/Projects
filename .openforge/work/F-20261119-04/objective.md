# F-20261119-04 Razorpay: refund states from webhooks, single order per payment, capture-mode alert (portfolio O-04)

## Objective (verbatim, run R-20261119-01 portfolio)
> O-04 | M | TD-P6-30, P6-31, P6-32 | payment-service | Razorpay idempotency, refund confirmation via webhook state, capture mode alert. Money path.

## Goal
A Razorpay refund is recorded as pending until Razorpay confirms it; one payment never creates two Razorpay
orders; and an operator is told when payments authorise but are not captured.

## Register items (full text: docs/techdebt/phase6.md#td-p6-30, -31, -32)
- TD-P6-30 Razorpay order creation has no idempotency key (`RazorpayGateway.createOrder`, `PaymentService.provision`).
- TD-P6-31 Refunds recorded as done once accepted; `refund.processed` / `refund.failed` webhooks not consumed.
- TD-P6-32 INR only; manual capture mode is silent. Scope here: document + alert on manual capture only
  (multi-currency / seller-side / recurring need the business to enable them in Razorpay: out of scope, keep the item open with a narrowed text).

## Affected areas (proved by grep; excerpts read)
- `services/payment-service/payment-service-app/src/main/java/com/acme/ecommerce/payment/service/PaymentService.java`:
  `provision` :178 (Razorpay order created :221 `razorpay.createOrder(subject, minor, currency)`),
  refund path :590 → `refundRazorpay` :629, webhook `handleRazorpayWebhook` :508 — verifies signature, then
  returns early for any event other than `payment.captured` / `order.paid` (:522).
- `web/PaymentController.java:87` webhook endpoint with `X-Razorpay-Signature`.
- Schema: `db/migration/V1__payment_baseline.sql:29` `payment.refunds`; `V3__razorpay.sql:18` alters refunds
  (provider ids). Next migration number must follow the highest existing V*.
- `service/OrderReporter.java`, `repository/PaymentRepository.java` (report/attempt patterns, JobLock usage).

## Runtime facts, with proof
1. Razorpay refund webhook events are `refund.created`, `refund.processed`, `refund.failed`,
   `refund.speed_changed`; event name at top-level `event`; refund object at `payload.refund.entity` with
   `id`, `payment_id`, `status` ("processed" / "failed"), `amount` (minor units), `currency`.
   Proof: https://razorpay.com/docs/webhooks/payloads/refunds/ (fetched 2026-11-19 by the CEO).
2. The existing webhook handler already verifies the signature for every event before the type switch
   (PaymentService.java:512-514), so new event types inherit verification.
3. Razorpay Orders API takes no idempotency key (register text, TD-P6-30). Not re-verified against the API
   docs; the design must not depend on any Razorpay-side idempotency.

## Assumptions (need proof in design)
- A1: Refund rows today get a terminal status at API acceptance; the design must cite the exact column/status values.
- A2: Downstream consumers of a refund (order-service refund status, wallet credit, notifications) read the
  refund status — the design must list every reader and what a PENDING state means for each.

## Blocking questions
None (Owner delegation, run R-20261119-01).
4. (added after design, CEO) `payment.authorized` exists; entity at `payload.payment.entity` (`id`, `order_id`,
   `status` "authorized", `captured` boolean, `amount`). The payment "can be in the captured state when the
   payment.authorized webhook is fired" and "the webhook sequence is not fixed". So: never alert on
   payment.authorized alone; alert only when a payment is still not captured after a delay, and tolerate
   payment.captured arriving before payment.authorized.
   Proof: https://razorpay.com/docs/webhooks/payloads/payments/ (fetched 2026-11-19 by the CEO).
