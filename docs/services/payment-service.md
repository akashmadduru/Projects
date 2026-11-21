# payment-service

Takes online payments for orders, wallet top-ups and gift cards; records COD; issues refunds; owns the
wallet and gift-card ledgers. Written 2026-11-15 for the multi-provider change
([ADR 0005](../architecture/adr/0005-multiple-payment-providers.md)).

- Stack: Spring Boot MVC, plain-SQL `JdbcClient`, Flyway (forward-only), Stripe Java SDK, `RestClient` for Razorpay.
- Database: `payment_db`, schema `payment` ([data model](../reference/data-model.md)).
- Callers: the storefront (through the gateway), order-service (internal, `/internal/**`).
- **Status:** unit-tested with mocked HTTP (30 tests across `PaymentProvidersTest`, `RazorpayGatewayTest`,
  `PaymentServiceRazorpayTest`). **Neither Stripe nor Razorpay has been run against a live account**
  ([TD-P6-02](../techdebt/phase6.md#td-p6-02)).

---

## Providers

A provider is **on** when its keys are set. There is no master switch.

| Provider | On when | Takes | Browser gets |
|---|---|---|---|
| Stripe | `STRIPE_SECRET_KEY` set | any currency, cards | publishable key and PaymentIntent client secret |
| Razorpay | `RAZORPAY_KEY_ID` **and** `RAZORPAY_KEY_SECRET` set | **INR only**; UPI, cards, netbanking, wallets | key id and Razorpay order id (never the secret) |
| SIMULATED | neither set, and the `prod` profile is **not** active | nothing real | a test panel |

- With both on, the customer chooses at checkout and may switch until they pay.
  `PAYMENT_DEFAULT_PROVIDER` (`STRIPE` or `RAZORPAY`) only sets which is preselected.
- Under the `prod` profile with neither configured, `PaymentProviders.init()` throws and the service
  **refuses to start**. SIMULATED can never reach production.
- `payments.mode` records the provider (`STRIPE`, `RAZORPAY`, `SIMULATED`). `payments.method` stays `CARD` or `COD`;
  `CARD` means "paid online".

## Endpoints

Full list in [endpoints.md](../reference/endpoints.md#payment-service-19).

| Endpoint | Auth | Purpose |
|---|---|---|
| `GET /api/payments/config` | none needed beyond the gateway | Enabled providers (`id`, `label`, `publicKey`, `methods`, `currencies`) and the default |
| `GET /api/payments/orders/{orderId}` | owner | The order's payment. Re-checks the provider if still unpaid, so a closed tab self-heals |
| `POST /api/payments/orders/{orderId}/provider` | owner | Switch provider before paying (409 once paid) |
| `POST /api/payments/{id}/razorpay/verify` | owner | Browser success callback; checks the checkout signature |
| `POST /internal/payments/orders/{orderId}/cancel` | internal token | order-service: order cancelled or expired before payment. Cancels the Stripe intent and voids the payment; a Razorpay order is left to lapse |
| `POST /api/payments/webhook` | Stripe signature | Stripe events. 404 without `STRIPE_WEBHOOK_SECRET` |
| `POST /api/payments/webhook/razorpay` | Razorpay signature | Razorpay events. 404 without `RAZORPAY_WEBHOOK_SECRET` |

Both webhook paths are public at the gateway on purpose; the signature is the authentication.

## Razorpay flow

See [diagram 18](../architecture/diagrams/18-razorpay-payment-sequence.md).

1. `create` picks a provider, then `provision` calls `POST /v1/orders` (paise, `receipt` = the order id) and stores
   `provider_order_id`.
2. The browser opens Razorpay Checkout and, on success, posts `razorpay_order_id`, `razorpay_payment_id` and
   `razorpay_signature` to `/razorpay/verify`.
3. The server checks `HMAC-SHA256(order_id|payment_id, key_secret)` in constant time and checks the order id is the
   one it created. Only a valid signature marks the payment SUCCEEDED and tells order-service. The signature can only
   be produced with the key secret, which is the standard Razorpay check; no extra API call is made.
4. The webhook handles `payment.captured` and `order.paid`. It verifies `X-Razorpay-Signature` over the **raw
   bytes**, de-duplicates on `X-Razorpay-Event-Id` in `processed_events`, and **rejects any amount or currency
   mismatch**. `payment.failed` is ignored because Razorpay lets the customer retry on the same order.
5. **Re-check on read.** `GET /api/payments/orders/{id}` for an unpaid payment asks Razorpay for the order's captured
   payment (one call) and settles it under the same amount and currency rule as the webhook. Re-checks are throttled
   to one per provider object every 10 s per pod, and skipped for a provider object created in the last 10 s, because
   these are synchronous calls on the customer's page load. Stripe re-checks follow the same throttle.
6. Refunds call `POST /v1/payments/{id}/refund` with an `X-Refund-Idempotency` header.

## Safety rules to preserve

- Never trust the browser: success is confirmed against Razorpay's own API, not the callback alone.
- Money arriving for a payment already settled by the other provider logs `DOUBLE CHARGE` at ERROR with both ids.
  Alert on it ([runbook](../guides/runbooks/payment-problems.md#double-charge)).
- Switching away from Stripe cancels its intent. A Razorpay order cannot be cancelled; an unpaid one just lapses.
- Stripe intent re-creation uses a random idempotency key, so a switch back never reuses a cancelled intent.
- The storefront caches `/api/payments/config` for 5 minutes per tab (failures are not cached) and starts loading
  Razorpay's `checkout.js` when Razorpay becomes the chosen provider, not on click.
- Secrets are never logged. `RestClientException` messages are logged, and credentials travel only in a header.

## Configuration

| Variable | Notes |
|---|---|
| `STRIPE_SECRET_KEY`, `STRIPE_PUBLISHABLE_KEY`, `STRIPE_WEBHOOK_SECRET` | All three or none (deploy preflight enforces it) |
| `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET` | All three or none |
| `RAZORPAY_BASE_URL` | Default `https://api.razorpay.com`; change only to a mock in tests |
| `PAYMENT_DEFAULT_PROVIDER` | Preselected provider; blank = first configured |

Descriptions and defaults: [environment-variables.md](../reference/environment-variables.md). Setup steps and the
test-mode checklists: [integrations §3 and §3b](../guides/integrations.md). Cloud setup:
[cloud-setup.md](../guides/cloud-setup.md).

## Schema

Migrations V1 (baseline), V2, **V3 `razorpay`** (additive: `ck_payments_mode`, `provider_order_id`,
`provider_payment_id`, `refunds.provider_refund_id`). Details in [data-model.md](../reference/data-model.md).
V3 was validated on Postgres 17. Flyway is forward-only: to roll back a bad release, deploy the previous image;
the added columns are nullable and harmless to it.

## Tests

```bash
./gradlew :services:payment-service:payment-service-app:test
```

| Class | Covers |
|---|---|
| `PaymentProvidersTest` (7) | Which providers are on, default choice, `prod` fails closed, SIMULATED never in prod |
| `RazorpayGatewayTest` (7) | Request shapes against a mock server, both signature checks, tampering, blank secret |
| `PaymentServiceRazorpayTest` (16) | Verify, webhook dedupe, amount mismatch, switch rules, double-charge flag, re-check throttle and amount rule |

Storefront: `components/__tests__/PaymentPanel.test.tsx` (5), `services/paymentApi.test.ts` (3, config cache),
`lib/paymentOptions.test.ts` (5). Admin: `views/PaymentsView.spec.ts` (3). Not covered: anything against real Razorpay or Stripe.

## Known gaps

[TD-P6-02](../techdebt/phase6.md#td-p6-02) (never run live), [TD-P6-30](../techdebt/phase6.md#td-p6-30) (no order idempotency key),
[TD-P6-31](../techdebt/phase6.md#td-p6-31) (refund recorded once accepted),
[TD-P6-32](../techdebt/phase6.md#td-p6-32) (INR only, manual capture undetected).
