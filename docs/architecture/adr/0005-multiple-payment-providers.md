# ADR 0005: Stripe and Razorpay side by side

Status: accepted, 2026-11-15. Implemented; never run against live Razorpay.

## Context
payment-service was written for Stripe: the schema, the webhook and the storefront all assumed it. The Indian market
needs UPI, netbanking and wallets, which Razorpay provides. Replacing Stripe would delete working code and drop
non-INR cards; the business wanted customers to choose.

## Decision
Both providers can be on at once. A payment records the provider it uses (`payments.mode`: STRIPE, RAZORPAY or
SIMULATED) and the customer may switch while it is unpaid.

| Choice | Why |
|---|---|
| `PaymentProviders` decides what is on, from keys; `PAYMENT_DEFAULT_PROVIDER` only orders the choice | no second switch to keep in step with the keys |
| `RazorpayGateway` on Spring `RestClient`, no vendor SDK | no dependency whose version could not be verified; full control of timeouts and error mapping |
| Browser callback verified with HMAC over `order_id|payment_id`; webhook verified over the raw body | the customer cannot claim a payment that is not theirs |
| Razorpay webhook: only `payment.captured` / `order.paid` act; amount and currency must match | a failed attempt is retryable on the same order, so failing the payment could cancel an order about to be paid |
| Order creation has no idempotency key at Razorpay | at most an orphan Razorpay order after a race; it is never paid |
| Switching cancels the old Stripe intent; a paid duplicate is logged `DOUBLE CHARGE` for manual refund | Stripe can cancel, Razorpay cannot; automatic refunds of surprises were judged riskier |
| `method` stays CARD / COD; CARD means "paid online" | avoids a schema and contract change across order-service and the apps |
| Migration V3 is additive (new columns, widened CHECK) | Stripe rows and not-yet-upgraded pods keep working during a rolling deploy |
| INR only for Razorpay; other currencies fall back to Stripe | Razorpay needs account-level activation for others |

## Consequences
- Two providers double the thing to test and to keep secrets for; the catalogue and `deploy.sh` treat each provider as
  all-or-nothing.
- `PaymentService` still branches on provider. A third provider means extracting an interface; two did not justify it.
- Unverified until run with real accounts: Razorpay request and field names, the refund idempotency header, event names.

## Not done
Razorpay Route / settlements, subscriptions and EMI; capturing payments left in "authorized"; refunds to UPI status
tracking (the platform records the refund as done when Razorpay accepts it).
