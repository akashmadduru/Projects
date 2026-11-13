# Tech Debt: Phase 8 "Retention and loyalty"

Index: [README](README.md). Feature scope: [Product Roadmap, Phase 8](../phases/phase-08-retention-and-loyalty.md).

Phase 8 was built under "no tests, no HLD/LLD, record shortcuts as debt". The integration run passed **78 of 78** Phase 8 end-to-end checks, with **99 of 99** Phase 6 and **68 of 68** Phase 7 regression checks still green. Integration also fixed five problems found in the first build (see "Resolved by Phase 8 integration" in the [README](README.md#resolved-by-phase-8-checked-against-the-code)). Paths below are relative to the repo root; `<svc>` means `services/<name>/<name>-app/src/main`. Storefront-only bullets that the frontend agent first appended to [frontend.md](frontend.md#phase-8-storefront-moved) are consolidated here (TD-P8-18).

<a id="td-p8-01"></a>
## TD-P8-01 No tests for Phase 8
- **Severity / Effort:** High / L
- **Where:** payment-service (wallet, gift cards, purposes), order-service (wallet/coins checkout, buy-again, subscriptions + job), promotion-service (coins ledger, order.events consumer, members-only deals), user-service (membership, preferences, referrals), notification-service (inbox, push, campaigns), cart-service (lists, reminders); storefront account pages, checkout, notification bell, personalised home; admin Campaigns view.
- **Risk:** The only safety net is the one-off e2e script. Money paths (wallet debit/credit, coin redeem/reverse, refund splits) have no contract or concurrency tests. Extends [TD-P7-01](phase7.md#td-p7-01), [TD-P6-05](phase6.md#td-p6-05), TD-TEST-01/02.
- **Fix:** Ledger tests (idempotency keys, balance invariants), checkout application-order tests, refund-split tests, campaign audience tests, subscription job tests; Playwright for wallet + coins checkout.

<a id="td-p8-02"></a>
## TD-P8-02 Campaign audience is incomplete and unthrottled
- **Severity / Effort:** Med / M
- **Where:** user-service internal `GET /internal/users?audience=` (reads `user_profiles` only); notification-service campaign sender; order-service `GET /internal/orders/last-order-dates`.
- **Risk:** Profiles are created lazily (on `/api/users/me`, preferences, membership or referral calls), so a user who only registered and ordered is invisible to campaigns until they open the app. The authoritative source would be auth-service. An `ALL` campaign emails every known profile (31 on dev) sequentially with no batching or throttle, and Mailpit accumulates the mail. `INACTIVE_30D` depends on order-service's `last-order-dates` map (`{userId: ISO instant}`, max 1000 ids, shape chosen by the money side and parsed defensively by notification-service) and fails closed (nobody) when it is unavailable.
- **Fix:** Source the audience from auth-service (or create the profile on registration); batch and rate limit sends; define the `last-order-dates` contract in a shared type and page past 1000 ids.

<a id="td-p8-03"></a>
## TD-P8-03 Campaign send is in-process; admin view is thin
- **Severity / Effort:** Med / M
- **Where:** `notification-service` campaign service (virtual-thread send); `apps/admin-product-catalog/src/views/CampaignsView.vue`.
- **Risk:** Sending runs on an in-process virtual thread, so a restart mid-send leaves the campaign in `SENDING` forever (status added beyond the contract; `POST /send` returns 202). Only `sent_count` is kept: no per-recipient delivery log or stats. The markdown-lite body is sent and previewed as plain text. Campaigns cannot be edited, deleted or duplicated (contract has only GET/POST/send), there is no audience-size estimate before sending, and the list loads the first 100 only. The admin view auto-refreshes every 3 s while any campaign is `SENDING`. The campaign admin API was first built without an admin credential available to the agent; the integration run is what exercised it.
- **Fix:** Persist send progress and resume on startup (or a Kafka/queue-driven sender); delivery log table; render markdown-lite; edit/delete/duplicate; audience estimate; paged list.

<a id="td-p8-04"></a>
## TD-P8-04 Web push never delivered to a real push service
- **Severity / Effort:** Med / M
- **Where:** notification-service push module (own JDK-only RFC 8291 aes128gcm + RFC 8292 VAPID implementation); `apps/product-catalog/public/sw.js`.
- **Risk:** Verified only up to encryption and the HTTP attempt against a fake endpoint. It has never reached FCM, Mozilla autopush or Apple. The library route was rejected because the available web-push library drags in netty/async-http-client; the hand-rolled crypto has had no review. No handling of expired endpoints beyond what the sender sees, and the storefront does not handle `pushsubscriptionchange`.
- **Fix:** Test against a real browser on all three push services; either adopt a maintained library or get the crypto reviewed; prune subscriptions on 404/410.

<a id="td-p8-05"></a>
## TD-P8-05 VAPID private key stored in plaintext
- **Severity / Effort:** Med / S
- **Where:** `notification_db.notification.settings`; env `VAPID_PUBLIC_KEY` / `VAPID_PRIVATE_KEY` / `VAPID_SUBJECT`.
- **Risk:** Unless the env vars are set, keys are generated at first start and the private key sits unencrypted in the database, so a DB leak lets an attacker send push as the site. Regenerating (e.g. on a fresh volume) silently invalidates every stored browser subscription.
- **Fix:** Require the keys from env/secret store in production; encrypt at rest; document rotation.

<a id="td-p8-06"></a>
## TD-P8-06 Gift cards: plaintext codes, no redeem rate limit
- **Severity / Effort:** High / S
- **Where:** `<payment>/` gift card service and controller; `gift_cards.code`; `GET /api/gift-cards`.
- **Risk:** The code is stored in plain text and the purchaser can read it back from `GET /api/gift-cards`. `POST /api/gift-cards/redeem` has no brute-force limit, so guessing codes (16 chars, `XXXX-XXXX-XXXX-XXXX`) is only bounded by gateway defaults. The recipient email is best effort with no resend. `PENDING_PAYMENT` cards are never swept or cancelled. No admin lookup or cancel. A "Gift card sent" inbox entry to the purchaser is also best effort.
- **Fix:** Store a hash of the code (show once); per-user and per-IP redeem limits with lockout; resend endpoint; sweep job for abandoned `PENDING_PAYMENT`; admin lookup view (also not built, see [TD-P8-03](#td-p8-03) admin gaps).

<a id="td-p8-07"></a>
## TD-P8-07 Wallet gaps: single currency, best-effort credit-back, races
- **Severity / Effort:** High / M
- **Where:** `<payment>/` wallet service; order-service checkout and cancel/expiry paths calling `/internal/wallet/debit|credit`.
- **Risk:** A wallet has one currency (reset only when the balance is 0); no top-up, withdrawal, expiry or admin adjustment UI. Credit-back on cancel, expiry and payment failure is best effort (logged, no retry queue or outbox), so a payment-service outage at that moment loses the customer's money. Checkout reads the balance then debits, so a race surfaces as 409 `INSUFFICIENT_BALANCE` on a failed checkout. Same shape as [TD-P6-01](phase6.md#td-p6-01). Wallet inbox entries (refund, gift card, referral) are posted best effort after a fresh credit.
- **Fix:** Outbox-driven credit-back with retry and alert; single atomic reserve-and-debit; multi-currency wallet or explicit conversion rules; admin adjustment with audit.

<a id="td-p8-08"></a>
## TD-P8-08 Refund split and return edge cases
- **Severity / Effort:** Med / M
- **Where:** order-service `ReturnService` / cancel flow; payment-service refund; promotion-service coin reversal.
- **Risk:** Return refunds split pro-rata by wallet share. If the wallet credit succeeds and the card refund then fails, the return stays `PICKED_UP`; a retry reuses idempotency keys (safe) but the wallet is already credited. COD returns without a payment record fall back to a direct wallet credit and leave `payments.refunded_amount` at 0. Redeemed coins are not returned on returns. Earned coins are reversed only on a full refund, not pro-rata for partial returns. Extends [TD-P6-14](phase6.md#td-p6-14).
- **Fix:** Make the refund a two-step state machine (wallet leg, card leg) with recorded progress; pro-rata coin reversal; return redeemed coins on cancel/return.

<a id="td-p8-09"></a>
## TD-P8-09 Coins: rate deviation, informational expiry, consumer limits
- **Severity / Effort:** Med / M
- **Where:** `promotion-service/.../` coin ledger and `order.events` consumer; `promotion.coins.max-event-age-days`; storefront Coins page and checkout.
- **Risk:** The contract said 1 coin per 100 currency units; that gives roughly zero coins on the USD catalog, so the build earns **1 coin per 100 INR but 1 coin per 1 unit of any other currency** (members 2x). Value is 1 coin = 1 INR or 0.01 of other currencies. This is a deliberate deviation, not yet a business decision. `expires_at` and `expiringSoon` are informational: there is no EXPIRE job, so coins never actually expire. The `order.events` consumer starts at `earliest` but ignores events older than 2 days, has no outbox or dedupe beyond the idempotent ledger, and skips poison records after 3 retries. The Coins page hardcodes `USD` (no per-user currency source).
- **Fix:** Decide the earn rate per currency in config; scheduled expiry job writing `EXPIRE` rows; DLQ for poison events; per-user currency on the profile.

<a id="td-p8-10"></a>
## TD-P8-10 Order list DTO lacks coins and wallet
- **Severity / Effort:** Low / S
- **Where:** `order-service/.../` `OrderSummaryResponse` (JPQL constructor projection).
- **Risk:** The order detail and invoice PDF show coins redeemed and wallet/method lines, but the list rows show only the total, so a wallet-paid order looks like a plain order in the list. The summary also has no line items, so thumbnails need N calls (see [TD-FE-13](frontend.md#td-fe-13)).
- **Fix:** Add `coinsRedeemed`, `walletAmount` and the first item's image/name to the projection.

<a id="td-p8-11"></a>
## TD-P8-11 Subscribe and Save shortcuts
- **Severity / Effort:** Med / M
- **Where:** `order-service/.../` subscription service, daily job, `POST /internal/subscriptions/run-due`.
- **Risk:** The daily job runs in-process on every order-service instance with no ShedLock (idempotent through the `sub-<id>-<date>` key, but N replicas all run it; see [TD-P6-13](phase6.md#td-p6-13)). The delivery address is fetched at run time through a user-service internal endpoint. `WALLET` subscriptions fail (and pause after 3 tries) when the wallet is short. The 5% is applied as a reduced unit price with no separate discount line and no price-change notice. `SUBSCRIPTION_ORDER_PLACED` is published instead of `ORDER_PLACED`, and `SUBSCRIPTION_FAILED` mail/inbox text is generic because the event has no failure reason field. The dev trigger accepts `?date=` to simulate days and is protected only by `X-Internal-Token`.
- **Fix:** ShedLock; carry a failure reason in the event; explicit discount line; notify before a price change; drop or guard the dev trigger in production.

<a id="td-p8-12"></a>
## TD-P8-12 Membership gaps
- **Severity / Effort:** Med / M
- **Where:** `user-service/.../` membership; order-service shipping, promotion-service coins and members-only deals; payment-service purpose routing.
- **Risk:** No auto-renew, no proration or refund on cancel, no expiring-soon reminder. The payment result relies on payment-service routing purpose `MEMBERSHIP` to `/internal/users/membership/payment-result`. Free shipping, 2x coins and members-only deals each make a live user-service lookup with no cache, and a failure means "not a member", so a member can be charged shipping or miss a bonus during an outage. Deals' `members_only_until` only filters the public active list and internal prices, so a member who sees a deal late still pays list price if promotion-service is down (extends [TD-P7-09](phase7.md#td-p7-09)). No admin view of plans or member counts (no admin endpoints in the contract).
- **Fix:** Short-TTL cache plus fail-open for members; renewal and reminder jobs; admin plans and members endpoints.

<a id="td-p8-13"></a>
## TD-P8-13 Referral programme gaps
- **Severity / Effort:** Med / S
- **Where:** user-service referrals (`/api/referrals`, `/internal/referrals/by-referee/{userId}`); promotion-service reward logic; storefront register/claim.
- **Risk:** Promotion-service reads the referee lookup defensively (accepts `referrerUserId|referrerId|referrer|userId`). It does not tell user-service a reward was paid, although user-service exposes `POST /internal/referrals/by-referee/{userId}/rewarded` (not in the contract), so `GET /api/referrals` `rewarded` and `rewards` stay 0. No abuse checks on a claim (same device/IP, account age). The storefront keeps the code in `localStorage` (`referral-code`) and claims after the first authenticated render with no feedback on success or rejection.
- **Fix:** Promotion-service calls the `rewarded` endpoint after the ledger write; fraud checks; surface the claim result.

<a id="td-p8-14"></a>
## TD-P8-14 Non-order payments reuse `payments.order_id`
- **Severity / Effort:** Med / S
- **Where:** `<payment>/` payments table and `/internal/payments`; admin payments ledger.
- **Risk:** For purposes `MEMBERSHIP` and `GIFT_CARD` the unique subject id is stored in `order_id` (= `referenceId`); there is no separate `unique(purpose, reference_id)`, and the admin ledger still labels the column "orderId". Membership attempts send `orderId=referenceId` plus `referenceId` and `purpose`. A collision between an order id and another subject id is only prevented by uuid randomness.
- **Fix:** Add a unique `(purpose, reference_id)` constraint, make `order_id` nullable, relabel the admin column.

<a id="td-p8-15"></a>
## TD-P8-15 Notification centre, preferences and inbox shortcuts
- **Severity / Effort:** Low / M
- **Where:** `notification-service/.../` inbox, preferences cache, order-event listener; gateway `notificationCb`.
- **Risk:** No retention/cleanup job, no pagination cursor, and the unread count is computed per request. Order-event inbox de-dupe uses `notifications.dedupe_key` (`order:<eventId>`) plus an in-memory set. The order inbox body is the second paragraph of the rendered email text, coupled to the `EmailRenderer` layout. Preferences are cached 60 s in notification-service, and cart-service calls user-service per cart (fail-open defaults). The transactional `/internal/notifications/email` is not preference-gated (no user id); order mail is gated by `emailOrders` only. The gateway `notificationCb` circuit-breaker instance has no explicit config (defaults).
- **Fix:** Retention job and keyset paging; a dedicated inbox-text field in the event; explicit breaker config.

<a id="td-p8-16"></a>
## TD-P8-16 Lists use hybrid storage and have sharing limits
- **Severity / Effort:** Low / S
- **Where:** `cart-service/.../` lists and wishlist; `GET /api/lists`, `/api/lists/shared/{token}`.
- **Risk:** Default-list items stay in `cart.wishlist_items` (hybrid storage) instead of migrating into `list_items`; the default list cap is 500 (wishlist rule) vs 200 for others. `GET /api/lists` embeds all items (no paging). `ownerFirstName` is derived from the email local part (no name claim in the JWT), "A friend" when unusable. A share token never rotates except via unshare then share. No move-item endpoint (the storefront composes add + remove, which is not atomic).
- **Fix:** Migrate the default list into `list_items`; page items; take the name from the profile; add a move endpoint.

<a id="td-p8-17"></a>
## TD-P8-17 Abandoned-cart reminder shortcuts
- **Severity / Effort:** Low / S
- **Where:** `cart-service/.../` reminder job, MVC interceptor, `POST /internal/cart/reminders/run`.
- **Risk:** The email is captured by an MVC interceptor after a successful non-GET `/api/cart/**` call, so guest/merged carts with no later mutation have no email. The job has no lock (every instance runs it, see [TD-P6-13](phase6.md#td-p6-13)). The reminder is stored in the inbox as type `PROMO`. The dev trigger accepts an extra `minAgeMinutes` parameter.
- **Fix:** Resolve the email from user-service at send time; ShedLock; a dedicated inbox type.

<a id="td-p8-18"></a>
## TD-P8-18 Storefront Phase 8 UX shortcuts
- **Severity / Effort:** Low / M
- **Where:** `apps/product-catalog/src/` account pages, `CheckoutPage.tsx`, notification bell, push opt-in, `PersonalisedHome`, `lib/` API clients.
- **Risk:**
  - Contract guesses: subscription key `frequencyDays`; `GET /api/lists` embeds `items`; `expiringSoon` is a number; the vapid-public-key response is accepted as `{publicKey|key}` or a bare string. Checkout sends `useWallet: true` together with `paymentMethod: WALLET` and with partial wallet + CARD/COD; the e2e passed, but confirm order-service treats both as intended.
  - Checkout computes coins (1 coin = 1 INR / 0.01 other), the 20% cap, member free shipping and the wallet amount client-side as an estimate; cap and unit are hardcoded. The server is authoritative. Coins now take the cart currency (see README resolved list).
  - The unread badge polls every 60 s (and on navigation) and can lag after reading on `/account/notifications`.
  - Gift card and membership payments reuse `PaymentPanel` with a `returnUrl` to the account page; there is no polling after return, so the user must refresh to see `ACTIVE`.
  - Lists page uses `window.prompt`/`confirm` for rename/delete; no drag-and-drop or note editing.
  - Push opt-in registers `/sw.js` only after opt-in; no `pushsubscriptionchange` handling, and the toggle state is per browser, not synced with server preferences.
  - Main chunk grew from about 584 kB to 608 kB (includes other in-flight changes); Checkout still statically imports `PaymentPanel`/Stripe (extends [TD-P7-18](phase7.md#td-p7-18)).
  - `PersonalisedHome` makes about 7 requests on home load (batch, 3x also-viewed, buy-again, deals).
- **Fix:** Aggregated recommendations endpoint; server pricing preview ([TD-FE-02](frontend.md#td-fe-02)); poll or SSE after payment return and for the badge; styled dialogs; lazy-load checkout.

<a id="td-p8-19"></a>
## TD-P8-19 Test data left in the dev database
- **Severity / Effort:** Low / S
- **Where:** `auth_db`, `user_db`, `cart_db`, `order_db`, `payment_db`, `promotion_db`, `notification_db` on the dev volume; Mailpit.
- **Risk:** `p8-*` users and their wallets, gift cards, coins, memberships, subscriptions, lists, referrals and orders remain, as do about 230 messages in Mailpit. The Phase 8 e2e needed more than 20 retries once to create an address right after registering (user-service warm-up); it passed on rerun, so the script is flaky. Credentials are intentionally not recorded here. Extends [TD-P7-20](phase7.md#td-p7-20).
- **Fix:** Delete these rows and clear Mailpit (or `docker compose down -v`) before sharing the environment; add a retry or readiness wait in the e2e user helper.

<a id="td-p8-20"></a>
## TD-P8-20 notification-service database setup and dev-only config
- **Severity / Effort:** Low / S
- **Where:** `infra/scripts/create-customer-dbs.sql`, `infra/postgres/init/01-schemas.sql`, `infra/.env.example` (`NOTIFICATION_DB_PASSWORD`).
- **Risk:** notification-service now has its own database (`notification_db`, role `notification_service`, schema `notification`). Existing volumes need `create-customer-dbs.sql` run manually (extends [TD-SEC-05](infra-security.md#td-sec-05)); the password is a committed throwaway value (extends [TD-SEC-03](infra-security.md#td-sec-03)). Phase 8 images were again built through the offline mirror, not the repo Dockerfiles ([TD-P6-06](phase6.md#td-p6-06)).
- **Fix:** Migration runner for infra databases; secrets manager for the password; a normal `compose up --build` once Maven Central is reachable.
