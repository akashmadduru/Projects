# Tech Debt: Phase 6 "Sell for real"

Index: [README](README.md). Feature scope: [Product Roadmap, Phase 6](../phases/phase-06-sell-for-real.md).

Phase 6 was built under "no tests, no HLD/LLD, record shortcuts as debt". The integration run passed 98 of 99 end-to-end checks; the one failure was a bug in the check script, not the product. Only **SIMULATED** payment mode was exercised. Paths below are relative to the repo root; `<svc>` means `services/<name>/<name>-app/src/main`.

<a id="td-p6-02"></a>
## TD-P6-02 Stripe and Razorpay live mode never exercised
- **Severity / Effort:** High / S
- **Where:** `services/payment-service/payment-service-app/` (PaymentIntent create/retrieve, refund, webhook parse via `deserializeUnsafe`); `deploy/local/.env`.
- **Risk:** Only SIMULATED mode and unit tests with mocked HTTP were verified. Razorpay (added 2026-11-15, [ADR 0005](../architecture/adr/0005-multiple-payment-providers.md)) was written from public API documentation and never called; its request shapes, the `X-Refund-Idempotency` header and event names are unconfirmed. Stripe: Stripe SDK 34.1.0 was chosen without a live account. The webhook endpoint returns 404 unless the webhook secret is set. In SIMULATED mode refunds are only recorded in the DB.
- **Fix:** Run the Razorpay test-mode checklist in [integrations §3b](../guides/integrations.md#3b-payment-gateway-razorpay) (pay, fail, refund, webhook replay, tampered signature). Stripe: set `STRIPE_SECRET_KEY`, `STRIPE_PUBLISHABLE_KEY`, `STRIPE_WEBHOOK_SECRET` in `deploy/local/.env`; run a test-mode pay, fail, refund and webhook cycle (Stripe CLI `listen`); replace `deserializeUnsafe` with version-checked parsing.

<a id="td-p6-05"></a>
## TD-P6-05 No tests for Phase 6; existing tests stale
- **Severity / Effort:** Med / L
- **Where:** payment-service, promotion-service, notification-service, order lifecycle/returns, cart saved-items, admin Orders/Returns/Coupons views and `adminApi.ts`, storefront checkout/orders/returns. Stale: auth-service tests will not compile (`UserResponse` gained 2 constructor args, `AuthMethod` gained OTP, `SecurityConfig`/`RateLimitFilter` paths changed); storefront tests still use the old Order types and `place(addressId, key)` signature; existing admin vitest suite not re-run.
- **Progress 2026-11-17:** the stale half is done: auth-service tests compile and pass again (5 files repaired), the storefront type-checks fully with tests and its stale tests are fixed, the admin suite was re-run and passes. Added: order placement on a real database (12 cases incl. compensation), cancel/late-payment, wallet credit-back, payment cancel, Razorpay verify/webhook, gift cards. Still untested: coupon rules and OTP, promotion- and notification-service, returns, the order state machine, a checkout e2e.
- **Risk:** Money, stock and auth flows have only the one-off e2e script as a safety net. Extends TD-TEST-01/02/03.
- **Fix:** Fix auth-service and storefront tests first, then add service tests for payment (webhook, refund idempotency), order state machine, coupon rules, OTP; Playwright for checkout.

<a id="td-p6-06"></a>
## TD-P6-06 Repo Dockerfiles for new services untested
- **Severity / Effort:** Med / S
- **Where:** `services/{payment,promotion,notification}-service/*-app/Dockerfile`, plus auth, order, rating, product, cart, gateway images.
- **Risk:** Maven Central returned 403 from the build host, so all Phase 6 images were built through an offline mirror plus a thin runtime Dockerfile kept outside the repo. The stock Dockerfiles have not been run for these services.
- **Fix:** Once Central is reachable run `docker compose -f deploy/local/docker-compose.yml up --build` from clean and fix what breaks.

<a id="td-p6-07"></a>
## TD-P6-07 Notifications are fire-and-forget
- **Severity / Effort:** Med / M
- **Where:** `services/auth-service/.../service/NotificationGateway.java`; `services/notification-service/notification-service-app/`.
- **Risk:** Auth emails/SMS are sent on a virtual-thread executor with no retry: a notification-service outage silently drops reset, verify and OTP messages. In notification-service the event-id de-dupe is in memory (lost on restart), the `order.events` consumer starts at `latest` on first run (misses earlier events), and only SHIPPED/DELIVERED status changes send mail.
- **Fix:** Persist sent-event ids; retry with backoff or queue via outbox; send for the remaining statuses if wanted.

<a id="td-p6-08"></a>
## TD-P6-08 SMS is a dev stub that logs OTP codes
- **Severity / Effort:** Med / M
- **Where:** `services/notification-service/notification-service-app/`.
- **Risk:** No real SMS provider. The SMS text, including the OTP, is logged and mailed to `sms+<digits>@acme.local` (visible in Mailpit). Phone OTP cannot work for real users.
- **Fix:** Integrate Twilio/SNS (or an Indian DLT-registered gateway); stop logging message bodies.

<a id="td-p6-10"></a>
## TD-P6-10 Login not blocked for unverified email; no captcha
- **Severity / Effort:** Med / S
- **Where:** `services/auth-service/.../service/UserPrincipalService.java`, login path; `apps/product-catalog/src/` verify-email banner and OTP/phone forms.
- **Risk:** Email verification exists but is advisory: unverified users sign in and order normally. The banner shows only when `emailVerified === false` and cannot be dismissed per session. Phone/OTP forms have no captcha, relying on server rate limits (TD-P6-11). Residual of TD-SEC-04/TD-FE-05.
- **Fix:** Decide policy (block order placement, not login, until verified); add captcha or proof-of-work to OTP request and registration.

<a id="td-p6-11"></a>
## TD-P6-11 OTP abuse controls are soft
- **Severity / Effort:** Med / M
- **Where:** `services/auth-service/.../ratelimit/RateLimitFilter.java`, `OtpLoginService.java`.
- **Risk:** The per-IP limiter is in-memory and per replica. OTP login does not feed the per-account password lockout. SMS pumping is bounded only by a 30 s per-user cooldown plus the per-IP bucket.
- **Fix:** Redis-backed limiter (Redis is already in compose); per-phone and per-day caps; count OTP failures toward lockout.

<a id="td-p6-12"></a>
## TD-P6-12 INTERNAL_API_TOKEN is a shared static secret
- **Severity / Effort:** Med / M
- **Where:** `InternalTokenFilter`/`SecurityConfig`/`ProblemResponseWriter` copied into order-service, inventory-service, payment-service, promotion-service; `libs/common-web` Feign interceptor; `deploy/local/.env`.
- **Risk:** One secret for all service-to-service calls: no rotation, no per-caller identity, no mTLS. Comparison is constant-time. The Feign interceptor also forwards the caller's user bearer token to notification-service and payment-service order calls, where it is ignored. Adds to TD-BE-12 (copy-paste).
- **Fix:** Move the filter into a shared lib; per-service tokens or mTLS/service JWTs; stop relaying user tokens on internal calls.

<a id="td-p6-14"></a>
## TD-P6-14 Return refund rules incomplete
- **Severity / Effort:** Med / M
- **Where:** `order-service/.../service/ReturnService.java`, `domain/OrderReturn.java`.
- **Risk:** Refund pro-rates the order discount but never refunds shipping. COD returns are only marked refunded (no payment-service call; no payout path). Full vs partial refund is decided by comparing item value to refunded total. Admin Returns view shows the amount in hardcoded INR (see TD-P6-22). Residual of TD-BE-02/05.
- **Fix:** Policy for shipping refund; COD refund via bank/UPI payout record; store currency on the return.

<a id="td-p6-15"></a>
## TD-P6-15 Invoice PDF is a hand-written minimal PDF
- **Severity / Effort:** Med / S
- **Where:** order-service invoice generator (`services/order-service/order-service-app/.../`); `gradle/libs.versions.toml` has an unused `openpdf` alias.
- **Risk:** Courier, text only, no logo/layout, and no rupee glyph (prints INR). Unlikely to satisfy a GST invoice format audit. Done this way because OpenPDF could not be resolved (Central 403).
- **Fix:** Switch to OpenPDF with an embedded Unicode font; add GSTIN, HSN, and place-of-supply fields.

<a id="td-p6-17"></a>
## TD-P6-17 Shipping, tax and COD rules are hard-wired
- **Severity / Effort:** Med / M
- **Where:** order-service config (GST label, warehouse state via aliases such as KA/Karnataka; ship-to compare is string-based); `apps/product-catalog/src/pages/CheckoutPage.tsx` (tax estimate INR 18/118; COD limits 50000 INR / 1000 other copied from the contract); product page delivery check.
- **Risk:** No state table or HSN-wise rates; a state spelled differently gets the wrong CGST/SGST vs IGST split. Storefront COD limits and tax estimate can drift from the server (server is authoritative, shown on the order page). Delivery check uses the single-unit price for the free-shipping threshold, not the cart total. Related to TD-FE-02.
- **Fix:** State-code table, rates per category, a server pricing-preview endpoint (also fixes TD-FE-02), COD limits fetched from the server.

<a id="td-p6-18"></a>
## TD-P6-18 Coupon service gaps
- **Severity / Effort:** Low / S
- **Where:** `services/promotion-service/promotion-service-app/`; `apps/admin-product-catalog/src/` Coupons view.
- **Risk:** Admin coupon list is unpaged (limit 500; admin UI fetches the first 100, no pagination UI). Editing a coupon does not re-evaluate existing redemptions. `max_discount` is read in order currency with no per-currency caps. Coupon datetimes use the browser timezone with no timezone shown. The storefront validates a coupon once and does not re-validate when subtotal or address changes (server recomputes at placement).
- **Fix:** Paged list endpoint and UI; per-currency caps; show/store UTC explicitly; re-validate on cart change.

<a id="td-p6-20"></a>
## TD-P6-20 Phone enumeration and reset side effects
- **Severity / Effort:** Low / S
- **Where:** `auth-service/.../service/PhoneVerificationService.java`, `PasswordResetService.java`.
- **Risk:** Add-phone returns 409 `AUTH_PHONE_TAKEN`, revealing a number is registered (authenticated users only; uniqueness only among VERIFIED numbers). A completed password reset marks `email_verified=true` (mailbox proof) and creates a credential row for Google-only users.
- **Fix:** Generic response plus out-of-band notice; document or gate the reset-creates-credential behaviour.

<a id="td-p6-21"></a>
## TD-P6-21 Admin SPA boilerplate
- **Severity / Effort:** Low / S
- **Where:** `apps/admin-product-catalog/src/views/` Orders, Returns, Coupons; `adminApi.ts`.
- **Risk:** Views use local ref state and inline try/catch instead of the composable/`MutationState` pattern used by inventory; pagination/error code is triplicated; modals are bespoke markup; no confirm dialog on status advance; lists do not auto-refresh; order detail only offers the next single status (backend allows skipping) and has no manual COD-collected button (automatic on DELIVERED). No lint script exists in the admin app, so lint was not run. Role checks in nav/router are cosmetic; enforcement is backend `hasRole('ADMIN')`.
- **Fix:** Extract composables and a shared modal/confirm component; add a lint script.

<a id="td-p6-22"></a>
## TD-P6-22 Admin guesses at API shapes
- **Severity / Effort:** Low / S
- **Where:** `apps/admin-product-catalog/src/` (Returns view, order detail, `listCoupons`).
- **Risk:** `ReturnResponse` has no currency, so refund amount is shown as INR. Order address is read as `shippingAddress ?? address` because the contract does not name the field. `listCoupons` accepts a bare array or a paged model. Wrong for non-INR orders or if the field name differs.
- **Fix:** Add `currency` to the return DTO; confirm field names against the real payload and pin the types.

<a id="td-p6-23"></a>
## TD-P6-23 Storefront Phase 6 UX shortcuts
- **Severity / Effort:** Low / S
- **Where:** `apps/product-catalog/src/` Checkout, Order, Return form, cart store, product delivery check.
- **Risk:** Order page polls payment result 2 s x 15 and then needs a manual refresh (no SSE). Stripe Appearance reads design tokens once at mount (no live theme switch). Save-for-later is non-optimistic and signed-in only. Return form allows any quantity up to purchased (server enforces delivered-minus-returned; no client precheck). Remembered pincode sits unencrypted in localStorage. Invoice download is a blob link with no preview.
- **Fix:** Push or longer backoff polling; precheck returns; accept as-is where cosmetic.

<a id="td-p6-24"></a>
## TD-P6-24 Storefront main bundle not code-split
- **Severity / Effort:** Low / S
- **Where:** `apps/product-catalog/` (Vite build; ~545 kB main chunk after Stripe libs).
- **Risk:** Slower first load; Vite chunk-size warning.
- **Fix:** Lazy-load the checkout/payment routes and Stripe libs (`React.lazy`, manual chunks).

<a id="td-p6-25"></a>
## TD-P6-25 Test data left in the dev database
- **Severity / Effort:** Low / S
- **Where:** `auth_db`, `product_db`, `inventory_db`, `promotion_db` on the dev volume.
- **Risk:** Admin account `p6int-admin@example.com` (ROLE_ADMIN, inserted via SQL) and several `p6e2e-*@example.com` users remain. A deactivated INR product "P6 INR Test Item <id>" (with an inventory row of 100) and deactivated `P6E*` coupons remain. Credentials are intentionally not recorded here. If the volume is ever shared or promoted, the admin account is a backdoor.
- **Fix:** Delete these rows (or `docker compose down -v`) before sharing the environment; create admins as in the [first-admin runbook](../guides/runbooks/first-admin-user.md#local-development-docker-compose).

<a id="td-p6-26"></a>
## TD-P6-26 Notification infra is dev-only
- **Severity / Effort:** Low / S
- **Where:** `deploy/local/docker-compose.yml` (Mailpit `axllent/mailpit:latest`); `services/notification-service/`; `payment-service` and `order-service` Kafka config.
- **Risk:** Mailpit image is unpinned and has no auth; production needs a real SMTP relay via `MAIL_HOST/MAIL_PORT/MAIL_USERNAME/MAIL_PASSWORD`. Emails are hand-built HTML strings (no template engine). Services use the deprecated spring-kafka `JsonDeserializer`; `order.events` is serialized with type headers off and ISO-string times, so consumers must not require `__TypeId__`.
- **Fix:** Pin the image; add a template engine; move to typed deserializers with a shared event contract (see TD-RT-06).

<a id="td-p6-28"></a>
## TD-P6-28 Saved-for-later price snapshots go stale
- **Severity / Effort:** Low / S
- **Where:** `services/cart-service/cart-service-app/` saved items.
- **Risk:** `saved_items` keep a price snapshot, stale on move-to-cart (order-service re-prices at checkout, so no financial error). Capped at 50 lines.
- **Fix:** Re-fetch price on move-to-cart. Same family as TD-BE-09.

<a id="td-p6-32"></a>
## TD-P6-32 Razorpay is INR only, with no seller-side or recurring support
- **Severity / Effort:** Low / M
- **Where:** `PaymentProviders.resolve`, `PaymentService.pickProvider`.
- **Risk:** A non-INR order falls back to Stripe, or is refused (HTTP 4xx) if Razorpay was named and Stripe is off. Automatic capture must be enabled in the Razorpay dashboard; with manual capture, payments authorise but never settle. Payouts (RazorpayX) stay out of scope ([TD-P9-08](phase9.md)).
- **Fix:** Document and alert on manual capture; add multi-currency once the business enables it in Razorpay.

<a id="td-p6-33"></a>
## TD-P6-33 Only order-service has an outbox
- **Severity / Effort:** Med / M
- **Where:** product-service `ProductEventPublisher`, inventory-service `InventoryEventPublisher`, rating-, user-, seller-service publishers.
- **Risk:** Residual of TD-P6-01 (fixed 2026-11-17 for order-service: `orders.outbox` is written in the same transaction as the order change; `OutboxRelay` delivers stock restore, deal/exchange/coupon/coins release, payment cancel, COD collected, return restock and `order.events` with retries, per-order ordering and a DEAD state listed at `GET /api/admin/orders/outbox`). The other services still publish after commit, best effort: a crash or Kafka outage between commit and send loses e.g. a `product.events` update (search and inventory caches drift until the next change; embeddings already self-heal by polling).
- **Fix:** Lift `Outbox`/`OutboxRelay` into common-web and use it in product- and inventory-service first.
