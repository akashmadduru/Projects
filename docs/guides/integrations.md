# Setup guide: environment variables, payments, email and other services

Everything the platform reads from the environment, and how to connect each external service. All values go in **`infra/.env`** (gitignored; copy from `infra/.env.example`), which `infra/docker-compose.yml` passes to the containers.

```bash
cp infra/.env.example infra/.env      # then edit
./infra/scripts/generate-keystore.sh -p "<your-keystore-password>"   # JWT signing key (once)
docker compose -f infra/docker-compose.yml --env-file infra/.env up -d --build
```

**Status of each integration today**

| Integration | Works out of the box | Real provider |
|---|---|---|
| Payments | Yes: **SIMULATED** mode (no real money) and Cash on Delivery | **Stripe** only. Live Stripe was never exercised end to end ([TD-P6-02](../techdebt/phase6.md#td-p6-02)); run the test-mode checklist below before trusting it. Razorpay and others are not implemented. |
| Email | Yes: caught by **Mailpit** at http://127.0.0.1:8025 | Any SMTP provider, configured below |
| SMS / phone OTP | Dev stub only: the text is logged and emailed to Mailpit | **None.** Needs code ([TD-P6-08](../techdebt/phase6.md#td-p6-08)) |
| Browser push | Yes: keys are generated on first start | Same keys, or your own |
| Google sign-in | Off until you add credentials | Google Cloud OAuth client |

---

## 1. Required variables (compose refuses to start without them)

| Variable | What it is | How to set it |
|---|---|---|
| `AUTH_DB_PASSWORD`, `PRODUCT_DB_PASSWORD`, `INVENTORY_DB_PASSWORD`, `USER_DB_PASSWORD`, `CART_DB_PASSWORD`, `ORDER_DB_PASSWORD`, `RATING_DB_PASSWORD`, `PAYMENT_DB_PASSWORD`, `PROMOTION_DB_PASSWORD`, `NOTIFICATION_DB_PASSWORD` | One Postgres role per service | The example values match `infra/postgres/init/01-schemas.sql`, which creates the roles **on first volume creation only**. Changing a password later means `ALTER ROLE ... PASSWORD` in Postgres as well as the `.env` value. |
| `JWT_KEYSTORE_PASSWORD` | Password of `infra/secrets/jwt-keystore.p12` | The same `-p` value you passed to `generate-keystore.sh`. |
| `INTERNAL_API_TOKEN` | Shared secret for service-to-service `/internal/**` calls (`X-Internal-Token`) | Long random string: `openssl rand -base64 48`. Every service gets the same value. |

## 2. Optional variables, by area

### Identity and tokens
| Variable | Default | Notes |
|---|---|---|
| `JWT_KEYSTORE_ALIAS` | `jwt-signing` | Alias inside the keystore. |
| `AUTH_JWT_ISSUER` | `https://auth.acme-ecommerce.internal` | Must be identical in auth-service, gateway and every resource server. Compose already passes one value to all of them. A mismatch gives `invalid_token` everywhere with no clear log line. |
| `AUTH_JWT_AUDIENCES` | `ecommerce-platform` | Same rule. |
| `AUTH_REGISTRATION_ENABLED` | `true` | Set `false` to close public sign-up. |
| `AUTH_COOKIE_SECURE` | `false` | **Set `true` in production** (HTTPS), or the refresh cookie is sent over plain HTTP. |
| `AUTH_ALLOWED_REDIRECT_URIS` | `http://localhost:5173/auth/callback,http://localhost:3000/auth/callback` | Exact-match allow-list for where Google login may return. |
| `AUTH_DEFAULT_REDIRECT_URI` | `http://localhost:5173/auth/callback` | Must be in the list above. |
| `GATEWAY_CORS_ALLOWED_ORIGINS` | `http://localhost:5173,5174,3000,3001` | Exact origins of the storefront and admin SPA. Never `*`. |
| `STOREFRONT_BASE_URL` | `http://localhost:3000` | Origin used in password-reset and verify-email links. **Wrong value = broken links in every email.** |

### Payments (Stripe), see section 3
`STRIPE_SECRET_KEY`, `STRIPE_PUBLISHABLE_KEY`, `STRIPE_WEBHOOK_SECRET`. All blank by default, which means SIMULATED mode.

### Email, see section 4
`MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_SMTP_AUTH`, `MAIL_SMTP_STARTTLS`, `MAIL_SMTP_SSL`, `NOTIFICATION_SENDER`.

### Browser push, see section 6
`VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`, `VAPID_SUBJECT`.

### Google sign-in, see section 7
`GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`.

### Ports (change if something local collides)
`GATEWAY_PORT` 8080, `AUTH_PORT` 8081, `PRODUCT_PORT` 8082, `INVENTORY_PORT` 8083, `RATING_PORT` 8084, `USER_PORT` 8085, `CART_PORT` 8086, `ORDER_PORT` 8087, `PAYMENT_PORT` 8088, `PROMOTION_PORT` 8089, `NOTIFICATION_PORT` 8090, `SELLER_PORT` 8091, `SUPPORT_PORT` 8092, `EUREKA_PORT` 8761, `POSTGRES_PORT` 5432, `REDIS_PORT` 6379, `MAILPIT_UI_PORT` 8025, `PRODUCT_CATALOG_PORT` 3000, `ADMIN_CATALOG_PORT` 3001.

### Frontend build/dev variables (Vite, set in each app's own `.env`, not `infra/.env`)
| App | Variable | Default | Notes |
|---|---|---|---|
| Admin (`apps/admin-product-catalog`) | `VITE_API_BASE_URL` | `http://localhost:8080` | The gateway origin. |
| Admin | `VITE_APP_ORIGIN` | `http://localhost:5173` | Must also be in `GATEWAY_CORS_ALLOWED_ORIGINS` and, with `/auth/callback`, in `AUTH_ALLOWED_REDIRECT_URIS`. |
| Storefront (`apps/product-catalog`) | `VITE_AUTH_BASE_URL` | `http://localhost:8081` | Where the Google sign-in redirect starts (auth-service). |
| Storefront | `VITE_GOOGLE_SSO_ENABLED` | shown | Set `false` to hide the Google button when Google is not configured. |

Vite bakes these in at **build** time. Changing them means rebuilding the app.

### Order rules (not in compose yet)
Shipping fees, GST, COD limits and the return window are properties of order-service, not named env vars. To change one, add a Spring relaxed-binding variable to the `order-service` block in `docker-compose.yml`:

| Setting | Default | Env name |
|---|---|---|
| Free shipping above (INR / other) | 499 / 25 | `ORDER_SHIPPING_INR_FREE_THRESHOLD` / `ORDER_SHIPPING_OTHER_FREE_THRESHOLD` |
| Shipping fee (INR / other) | 40 / 4.99 | `ORDER_SHIPPING_INR_FEE` / `ORDER_SHIPPING_OTHER_FEE` |
| GST % | 18 | `ORDER_TAX_GST_PERCENT` |
| COD limit (INR / other) | 50000 / 1000 | `ORDER_DELIVERY_COD_LIMIT_INR` / `ORDER_DELIVERY_COD_LIMIT_OTHER` |
| Return window (days) | 7 | `ORDER_RETURNS_WINDOW_DAYS` |
| Unpaid order expiry | 30m | `ORDER_PENDING_EXPIRY` |

---

## 3. Payment gateway (Stripe)

### How the modes work
- **`STRIPE_SECRET_KEY` blank → SIMULATED.** No real charges. The checkout shows a test payment panel and payment-service records the outcome itself. Safe for development and demos.
- **`STRIPE_SECRET_KEY` set → STRIPE mode.** payment-service creates a PaymentIntent per order; the storefront loads the publishable key from `GET /api/payments/config` and renders Stripe's card element. Outcomes arrive by **webhook** and are reported to order-service (retried every 30 s until delivered).
- **Cash on Delivery** needs no gateway and works in both modes (subject to the COD limits above).

### Setup (test mode first)
1. Create a Stripe account and stay in **Test mode** (dashboard toggle).
2. Developers → **API keys**: copy the **Publishable key** (`pk_test_...`) and **Secret key** (`sk_test_...`).
3. Webhook, local development, with the [Stripe CLI](https://stripe.com/docs/stripe-cli):
   ```bash
   stripe login
   stripe listen --forward-to localhost:8080/api/payments/webhook
   ```
   It prints a signing secret `whsec_...`.
4. Put all three in `infra/.env`:
   ```env
   STRIPE_SECRET_KEY=sk_test_...
   STRIPE_PUBLISHABLE_KEY=pk_test_...
   STRIPE_WEBHOOK_SECRET=whsec_...
   ```
5. Recreate the service: `docker compose -f infra/docker-compose.yml --env-file infra/.env up -d --force-recreate payment-service`.
6. Check `curl http://localhost:8080/api/payments/config` reports mode `STRIPE`.

The gateway exposes `POST /api/payments/webhook` without a JWT on purpose; it is authenticated by Stripe's signature. **Without `STRIPE_WEBHOOK_SECRET` the endpoint returns 404**, and card orders stay unpaid.

### Test-mode checklist (not yet run for this codebase)
1. Pay with `4242 4242 4242 4242` (any future expiry, any CVC). Order becomes PLACED and the payment SUCCEEDED.
2. Pay with `4000 0000 0000 9995` (declined). Order stays unpaid and payment is FAILED.
3. Cancel a paid order, or approve a return and mark it REFUNDED, then confirm in the Stripe dashboard that the refund exists.
4. Stop `payment-service` for a minute after paying; confirm the order still becomes PLACED once it is back (the reporter retries).
5. Known gaps to read first: [TD-P6-02](../techdebt/phase6.md#td-p6-02) (live mode unverified, webhook parsing), [TD-P6-03](../techdebt/phase6.md#td-p6-03) (cancelling an unpaid order does not cancel the PaymentIntent, so a late payment is refunded inline).

### Going live
- Create live keys (`sk_live_...`, `pk_live_...`) and a **real webhook endpoint** in Stripe (Developers → Webhooks) pointing at `https://<your-api-domain>/api/payments/webhook`, subscribed to `payment_intent.succeeded` and `payment_intent.payment_failed`. Use that endpoint's `whsec_...`, not the CLI's.
- Do not put live keys in a file that is committed or shared. Use your platform's secret manager.
- Currencies: the platform charges in the order's currency (INR and USD are seeded). Check your Stripe account supports the ones you sell in.

---

## 4. Email

### What sends email
notification-service sends: order confirmation and status emails (from the `order.events` Kafka topic), and password-reset and verify-email messages (requested by auth-service). It uses plain SMTP.

### Development (default)
Nothing to configure. All mail goes to **Mailpit**: open http://127.0.0.1:8025. Reset links point at `STOREFRONT_BASE_URL`.

### Real provider
Set these in `infra/.env`, then `docker compose ... up -d --force-recreate notification-service`.

| Provider | `MAIL_HOST` | `MAIL_PORT` | `MAIL_USERNAME` / `MAIL_PASSWORD` | Flags |
|---|---|---|---|---|
| Amazon SES | `email-smtp.<region>.amazonaws.com` | 587 | SMTP credentials created in SES (not your AWS keys) | `MAIL_SMTP_AUTH=true`, `MAIL_SMTP_STARTTLS=true` |
| SendGrid | `smtp.sendgrid.net` | 587 | username literally `apikey`, password is the API key | same |
| Mailgun | `smtp.mailgun.org` | 587 | the domain's SMTP login and password | same |
| Gmail / Workspace | `smtp.gmail.com` | 587 | your address and an **app password** (2-step verification required) | same; fine for testing only |
| Implicit TLS | any | 465 | as the provider says | `MAIL_SMTP_AUTH=true`, `MAIL_SMTP_SSL=true` |

Also set:
```env
NOTIFICATION_SENDER=no-reply@yourdomain.com   # the From address
STOREFRONT_BASE_URL=https://shop.yourdomain.com
```

**Deliverability:** the From domain must be verified with the provider and have **SPF and DKIM** records, or mail lands in spam. SES starts in a sandbox that only sends to verified addresses; request production access.

**Verify:** place a test order or request a password reset and watch `docker logs ecommerce-notification`. Delivery failures are logged, and are not retried today ([TD-P6-07](../techdebt/phase6.md#td-p6-07)), so a provider outage loses those emails.

---

## 5. SMS and phone OTP

There is **no SMS provider**. In `notification-service`, `POST /internal/notifications/sms` logs the message and emails it to `sms+<digits>@acme.local` so you can read it in Mailpit. **It also writes OTP codes to the log**, so do not run it with real users ([TD-P6-08](../techdebt/phase6.md#td-p6-08)).

To send real SMS you need to add code: implement a provider call (Twilio, AWS SNS, MSG91, ...) in `InternalNotificationController#sms` and add the provider's credentials as new variables (for example `SMS_PROVIDER`, `SMS_API_KEY`, `SMS_SENDER_ID`). Until then, keep phone-OTP login off or treat it as a demo feature. If you serve India, you will also need DLT registration of your sender ID and templates with the provider.

---

## 6. Browser push notifications (optional)

| Variable | Notes |
|---|---|
| `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY` | Leave **blank**: a keypair is generated on first start and stored in the `notification_db.settings` table, so it survives restarts. Set both only if you need to supply your own. |
| `VAPID_SUBJECT` | A `mailto:` address you control, for example `mailto:ops@yourdomain.com`. Push services use it to contact you. |

Browsers only allow push on **HTTPS** (and `localhost`). If you replace the database, subscribers' keys change and existing subscriptions stop working.

---

## 7. Google sign-in (optional)

1. Google Cloud Console → APIs & Services → **Credentials** → Create credentials → **OAuth client ID**, type **Web application**.
2. **Authorised redirect URI** (this is auth-service's callback, not the SPA's):
   - local: `http://localhost:8081/login/oauth2/code/google`
   - production: `https://<your-auth-domain>/login/oauth2/code/google`
3. Put the client id and secret in `infra/.env`:
   ```env
   GOOGLE_CLIENT_ID=...apps.googleusercontent.com
   GOOGLE_CLIENT_SECRET=...
   ```
4. Make sure the SPA callback URLs are in `AUTH_ALLOWED_REDIRECT_URIS` (for example `https://shop.yourdomain.com/auth/callback`).
5. Recreate auth-service. Without these two variables the Google leg simply is not installed and password login works as normal.

Known issue: the **admin** SPA's Google button can start through the gateway and be rejected ([TD-SEC-02](../techdebt/infra-security.md#td-sec-02)). Use password login for the admin console until it is fixed.

> **Security note:** an earlier `infra/.env` on a dev machine held a real `GOOGLE_CLIENT_SECRET` ([TD-SEC-01](../techdebt/infra-security.md#td-sec-01)). If that file was ever shared, **rotate the secret** in Google Cloud.

---

## 8. Going to production: checklist

- [ ] **HTTPS everywhere.** Terminate TLS in front of the gateway and both SPAs.
- [ ] `AUTH_COOKIE_SECURE=true`.
- [ ] `GATEWAY_CORS_ALLOWED_ORIGINS`, `AUTH_ALLOWED_REDIRECT_URIS`, `AUTH_DEFAULT_REDIRECT_URI` and `STOREFRONT_BASE_URL` set to your real HTTPS origins (exact match, no wildcards).
- [ ] New random values for every `*_DB_PASSWORD` **and** change the matching Postgres roles, `INTERNAL_API_TOKEN`, and `JWT_KEYSTORE_PASSWORD`. The shipped example values are public.
- [ ] A production JWT keystore from a secret manager. `generate-keystore.sh` is for local development only; the key must be identical across all auth-service replicas.
- [ ] Stripe live keys and a live webhook (section 3), after passing the test-mode checklist.
- [ ] Real SMTP with SPF and DKIM (section 4), and a real SMS provider if you use phone OTP (section 5).
- [ ] Do not expose Postgres, Redis, Kafka, Eureka, Mailpit or the `/internal/**` ports publicly. Compose binds the service ports to `127.0.0.1` for this reason.
- [ ] Rebuild both SPAs with production `VITE_*` values.
- [ ] Review the open High items in [techdebt/README.md](../techdebt/README.md), notably TD-P6-01 (no outbox), TD-P6-03 and TD-ADM-01.

## 9. Troubleshooting

| Symptom | Likely cause |
|---|---|
| Compose stops with `set X in infra/.env` | A required variable (section 1) is missing. |
| Every request returns 401 `invalid_token` | Issuer or audience differs between services, or the keystore password is wrong. |
| Checkout shows the simulated panel when you expect Stripe | `STRIPE_SECRET_KEY` empty, or `payment-service` was not recreated after editing `.env`. |
| Card paid in Stripe but the order stays unpaid | `STRIPE_WEBHOOK_SECRET` missing or wrong, or `stripe listen` is not running. |
| No emails | In dev, open Mailpit, not your inbox. In production, check `docker logs ecommerce-notification` for SMTP auth or TLS errors and the flags in section 4. |
| Reset and verify links open the wrong site | `STOREFRONT_BASE_URL`. |
| Google login says redirect not allowed | The SPA callback is missing from `AUTH_ALLOWED_REDIRECT_URIS`, or the Google console redirect URI does not match auth-service's. |
| Browser blocks calls with a CORS error | The SPA origin is missing from `GATEWAY_CORS_ALLOWED_ORIGINS`. |
