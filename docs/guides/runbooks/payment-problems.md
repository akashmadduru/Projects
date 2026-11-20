# Runbook: payment problems (Stripe and Razorpay)

Commands assume the AWS deployment (`kubectl -n <namespace>`); locally use `docker compose logs payment-service`.
Background: [payment-service](../../services/payment-service.md), [integrations §3b](../integrations.md#3b-payment-gateway-razorpay).

## Which provider handled it

```sql
-- payment_db
select id, order_id, mode, status, provider_order_id, provider_payment_id, amount, currency
from payment.payments where order_id = '<order id>';
```

`mode` is `STRIPE`, `RAZORPAY` or `SIMULATED`. Razorpay ids are `order_…` and `pay_…`.

## Symptoms

| Symptom | Likely cause | Action |
|---|---|---|
| payment-service will not start, message "At least one of STRIPE_SECRET_KEY or RAZORPAY_KEY_ID + RAZORPAY_KEY_SECRET is required" | `prod` profile with no provider keys | Set one provider's keys ([rotate-secrets](rotate-secrets.md)), restart |
| `/api/payments/config` shows no `providers` entry for one you set | Keys incomplete (Razorpay needs id **and** secret) | Fix the secret, restart payment-service |
| Razorpay webhook returns **404** | `RAZORPAY_WEBHOOK_SECRET` is blank | Set it to the secret shown in the Razorpay webhook settings |
| Razorpay webhook returns **400 Invalid webhook signature** | Wrong secret, or a proxy altered the body | Compare the secret; confirm the gateway forwards the raw body |
| Customer paid, order still PENDING_PAYMENT | Webhook missing **and** browser callback lost | Ask the customer to open the order page (it re-checks Razorpay). If still unpaid, check Razorpay for the order's payments and that auto-capture is on |
| Payments sit "authorized" in the Razorpay dashboard | Manual capture | Enable automatic capture in the dashboard; capture or refund the stuck ones by hand |
| "Razorpay accepts INR payments only" | Non-INR order, Razorpay chosen by name | Customer pays with Stripe, or enable Stripe |
| Webhook accepted but order not paid, log "amount mismatch" | Amount or currency differs from what we asked | Investigate in the Razorpay dashboard before refunding; never mark it paid by hand |
| Refund failed with a 503 "Payment provider request failed" | Provider outage or bad request | Retry from admin; check the `Razorpay call failed` log line for the cause |

## Razorpay setup and alerts

**Webhook subscriptions.** The Razorpay webhook must subscribe to `payment.captured`, `order.paid`, `payment.authorized`,
`refund.processed` and `refund.failed`. Without the refund events every Razorpay refund stays PENDING.
**Auto-capture.** Dashboard -> Settings -> Configuration -> Payment capture: automatic. With manual capture, payments
authorize and never settle.

| Log line (ERROR) | Meaning | Action |
|---|---|---|
| `RAZORPAY NOT CAPTURED` | A `payment.authorized` event was seen and the payment is still unpaid 15 min later (the event alone never alerts: Razorpay may send it after, or with, the capture). Status is not changed | Enable auto-capture, then capture or refund that payment in the dashboard |
| `REFUND FAILED` | Razorpay reported the refund failed. The balance is released, no wallet credit, the customer was NOT refunded | Refund the customer another way, or retry with a new idempotency key |
| `REFUND FAILED AFTER SUCCEEDED` | `refund.failed` for a refund we hold as SUCCEEDED (older refunds default to SUCCEEDED). State unchanged | Reconcile in the Razorpay dashboard by hand. A historic SUCCEEDED refund that really failed also needs this |
| `REFUND PENDING TOO LONG` | A refund has been PENDING for over 10 days (webhooks probably not subscribed) | Check it in the dashboard, subscribe the events, settle the row by hand |
| `LEDGER MISMATCH` | Releasing a failed refund would make `refunded_amount` negative. Nothing was changed, the webhook returns 500 and Razorpay retries | Fix the data by hand within Razorpay's retry window (about 24 hours), or reconcile that refund by hand; do not ignore |
| `REFUND ID DUPLICATED` | Two refund rows share a Razorpay refund id; nothing applied | Reconcile by hand |
| `PARKED EVENT DROPPED` | An early refund event did not match the refund created later; it will not be redelivered | Reconcile that refund by hand |

A parked `refund.failed` that is applied inside `refund()` still returns 200 to order-service (it shows the refund as
done); the `REFUND FAILED` alert is the only signal (backlog B-3).

**Migration V7 lock profile.** The CHECK on `payment.refunds.status` scans the table under an ACCESS EXCLUSIVE lock,
and `ix_payments_provider_payment` is built without `CONCURRENTLY`, so writes to `payment.payments` wait while it
builds. Both are quick at current sizes (seconds for tens of thousands of rows); for a much larger table deploy in a quiet
window. V8 adds nullable columns: no table rewrite, only a brief exclusive lock (it sets `lock_timeout = 5s`, so it
fails fast rather than queueing behind a long transaction; just redeploy).

## Return refunds (shipping, COD, split legs, PENDING card leg)

Admin refund of a PICKED_UP return (`POST /api/admin/returns/{id}/status`) pays two legs: the wallet share first (key
`return-wallet-<id>`), then the rest to the card (key `return-<id>`, or `return-<id>-r<n>` after payment-service
reported a FAILED refund) or, for cash on delivery and `refundTo=WALLET`, to the wallet through payment-service. Legs are
frozen on the first attempt and progress is stored on the return, so every retry is safe.

**Deploy order: payment-service first, then order-service.** order-service reads `refundStatus` in the refund reply. An
older payment-service does not send it and order-service reads a missing value as SUCCEEDED, which would mark a PENDING
Razorpay refund as done. Never run the new order-service against an old payment-service.

**A COD refund that returns 404 leaves the return PICKED_UP.** A COD order placed while payment-service was down has no
payment row (placement is best effort), and there is no fallback credit any more. The refund fails loudly with 404, the
wallet is not credited and the return stays PICKED_UP. A 409 means the COD payment is still COD_PENDING (the order was
never marked delivered and collected). Fix the cause, then retry. Backlog B-10 makes sure every COD order gets a payment row.

**PENDING card leg.** Razorpay accepts a refund before it is done. The return stays PICKED_UP with `refundState=PENDING`
and no stock release or event. `ReturnRefundWatch` re-checks it every `ORDER_RETURNS_REFUND_POLL_INTERVAL_MS` (default
5 minutes) by replaying the same key, which payment-service answers from its own table without calling Razorpay. On
SUCCEEDED the return completes once. On FAILED the log line `RETURN REFUND FAILED` (ERROR) names the return; the admin
retries and a new `-r<n>` key is used. The wallet is not credited again.

**Refund in progress (409).** Another call holds the 2-minute lease. Wait and retry.

**Rollback.** Revert the images (order-service first, then payment-service). The V13 columns are ignored by old code. A
return left PENDING completes under the old logic, which treats an accepted refund as done.

## Double charge

Log line `DOUBLE CHARGE` at ERROR carries both provider payment ids. It means the customer switched provider, paid
on the abandoned one anyway, and the order is already settled by the other.

1. Confirm both payments are captured in their dashboards and the order is PLACED once.
2. Refund the **duplicate** in the provider dashboard (the platform does not auto-refund it).
3. Note it on the order for support.

Alert on this log line (CloudWatch metric filter or equivalent).

## Replay a missed webhook

Razorpay: Dashboard -> Settings -> Webhooks -> the endpoint -> recent deliveries -> resend. Stripe:
`stripe events resend <evt_id>`. Replays are safe: events are de-duplicated on `X-Razorpay-Event-Id` / the Stripe event
id in `payment.processed_events`.

## Rotate keys

Follow [rotate-secrets](rotate-secrets.md). Razorpay allows regenerating the key secret in the dashboard (the old one
stops working immediately, so update and restart straight after). Rotating the webhook secret needs the same value
in Razorpay and in `RAZORPAY_WEBHOOK_SECRET`; events sent in between are rejected and must be resent.

## Switch off a provider in an emergency

Blank its keys in `config.env` and redeploy (`./deploy/aws/deploy.sh --only infra,platform`). The other provider keeps
working. In-flight payments on the disabled provider still settle only if its webhook secret stays set; keep that
secret until they drain, or reconcile by hand afterwards.
