# Tech Debt Register

Shortcuts taken while building customer accounts (login, Google SSO, user/cart/order services) and Phase 6 "Sell for real" (payments, promotions, notifications, order lifecycle, returns, OTP) under the instruction "no test cases for now, focus on e2e features". Related docs: [Phase5-Feature](../phases/phase-05-customer-accounts.md), [Phase5-DecisionLog](../phases/phase-05-customer-accounts.md#decisions), [Product-Roadmap](../roadmap.md). Phase 6 items live in [phase6.md](phase6.md). Phase 7 admin-console items live in [admin.md](admin.md). Phase 7 "Discovery and conversion" (search, variants, facets, offers, deals, recommendations, Q&A, review media, alerts, compare, SEO) items live in [phase7.md](phase7.md). Phase 8 "Retention and loyalty" (wallet, gift cards, coins, membership, subscriptions, referrals, notification centre, web push, campaigns, lists, abandoned-cart reminders, personalised home) items live in [phase8.md](phase8.md). Phase 9 "Marketplace and sellers" (seller-service, onboarding/KYC, seller portal, offers and buy box, warehouses, split shipments, ledger and payouts, ads, analytics) items live in [phase9.md](phase9.md).

## How to use this folder

- One file per area. Each item has a stable ID (`TD-<AREA>-NN`); reference it in commits and PRs.
- Severity: High = blocks real customers/money or leaks secrets; Med = wrong or fragile under load/failure; Low = polish.
- Effort: S = under a day, M = a few days, L = a week or more.
- When fixing an item, delete it from its file and from the table below in the same PR. Do not renumber.
- Each item was checked against the code on 2026-11-12. Items found to be wrong were dropped or corrected.

## Index (highest severity first)

| ID | Title | Area | Severity | Effort | File |
|----|-------|------|----------|--------|------|
| TD-P6-01 | No outbox: stock/coupon/event side effects after commit can be lost | phase6 | High | L | [phase6.md](phase6.md#td-p6-01) |
| TD-P6-02 | Stripe live mode never exercised (only SIMULATED) | phase6 | High | S | [phase6.md](phase6.md#td-p6-02) |
| TD-P6-03 | Late payment after cancel; PaymentIntent not cancelled | phase6 | High | M | [phase6.md](phase6.md#td-p6-03) |
| TD-P6-04 | Stock reservation gaps (no inventory row = unlimited) | phase6 | High | M | [phase6.md](phase6.md#td-p6-04) |
| TD-P6-05 | No tests for Phase 6; auth/storefront tests stale or uncompilable | phase6 | High | L | [phase6.md](phase6.md#td-p6-05) |
| TD-TEST-01 | No tests for user/cart/order services | testing | High | L | [testing.md](testing.md#td-test-01) |
| TD-TEST-02 | No frontend tests for auth/cart/checkout | testing | High | M | [testing.md](testing.md#td-test-02) |
| TD-SEC-01 | Real GOOGLE_CLIENT_SECRET in local infra/.env (rotate) | infra-security | High | S | [infra-security.md](infra-security.md#td-sec-01) |
| TD-SEC-02 | Admin SPA Google login starts via gateway (redirect_uri rejected) | infra-security | High | S | [infra-security.md](infra-security.md#td-sec-02) |
| TD-P6-06 | Repo Dockerfiles for new services untested (Maven Central 403) | phase6 | Med | S | [phase6.md](phase6.md#td-p6-06) |
| TD-P6-07 | Notifications fire-and-forget; in-memory de-dupe | phase6 | Med | M | [phase6.md](phase6.md#td-p6-07) |
| TD-P6-08 | SMS is a dev stub that logs OTP codes | phase6 | Med | M | [phase6.md](phase6.md#td-p6-08) |
| TD-P6-09 | OTP codes hashed with plain SHA-256 | phase6 | Med | S | [phase6.md](phase6.md#td-p6-09) |
| TD-P6-10 | Login not blocked for unverified email; no captcha | phase6 | Med | S | [phase6.md](phase6.md#td-p6-10) |
| TD-P6-11 | OTP abuse controls are in-memory/per-replica | phase6 | Med | M | [phase6.md](phase6.md#td-p6-11) |
| TD-P6-12 | INTERNAL_API_TOKEN shared static secret, copy-pasted filters | phase6 | Med | M | [phase6.md](phase6.md#td-p6-12) |
| TD-P6-13 | Scheduled jobs have no distributed lock; re-reporter gives up silently | phase6 | Med | M | [phase6.md](phase6.md#td-p6-13) |
| TD-P6-14 | Return refunds skip shipping; COD refunds only marked | phase6 | Med | M | [phase6.md](phase6.md#td-p6-14) |
| TD-P6-15 | Invoice PDF is a minimal hand-written PDF (no rupee glyph) | phase6 | Med | S | [phase6.md](phase6.md#td-p6-15) |
| TD-P6-16 | WELCOME10 still listed/validated for returning customers | phase6 | Med | S | [phase6.md](phase6.md#td-p6-16) |
| TD-P6-17 | Shipping/tax/COD rules hard-wired; client-side estimates | phase6 | Med | M | [phase6.md](phase6.md#td-p6-17) |
| TD-TEST-03 | Stale `http.test.ts`; `npm run type-check` fails | testing | Med | S | [testing.md](testing.md#td-test-03) |
| TD-TEST-04 | Gateway route/authorization contract untested | testing | Med | M | [testing.md](testing.md#td-test-04) |
| TD-BE-03 | Cart clear after order is best-effort, no outbox | backend | Med | M | [backend.md](backend.md#td-be-03) |
| TD-BE-06 | Sequential product lookups in checkout | backend | Med | M | [backend.md](backend.md#td-be-06) |
| TD-BE-07 | No Feign circuit breakers in order-service | backend | Med | S | [backend.md](backend.md#td-be-07) |
| TD-BE-08 | Gateway 503 for 30-40s after a service restart | backend | Med | S | [backend.md](backend.md#td-be-08) |
| TD-BE-09 | Cart/wishlist trust client snapshots; productId unchecked | backend | Med | S | [backend.md](backend.md#td-be-09) |
| TD-BE-10 | Jackson `fail-on-null-for-primitives=false` in order-service | backend | Med | S | [backend.md](backend.md#td-be-10) |
| TD-SEC-03 | Dev DB passwords committed | infra-security | Med | S | [infra-security.md](infra-security.md#td-sec-03) |
| TD-SEC-04 | Open registration by default (email verification now exists; see TD-P6-10) | infra-security | Med | S | [infra-security.md](infra-security.md#td-sec-04) |
| TD-SEC-05 | Manual DB script needed for existing volumes | infra-security | Med | M | [infra-security.md](infra-security.md#td-sec-05) |
| TD-SEC-06 | Dev admin seed removed, no replacement | infra-security | Med | S | [infra-security.md](infra-security.md#td-sec-06) |
| TD-FE-02 | Checkout totals are client-side; price drift seen only after order | frontend | Med | M | [frontend.md](frontend.md#td-fe-02) |
| TD-FE-03 | Optimistic cart/wishlist edits are last-write-wins | frontend | Med | M | [frontend.md](frontend.md#td-fe-03) |
| TD-FE-04 | Failed login merge has no in-session retry | frontend | Low | S | [frontend.md](frontend.md#td-fe-04) |
| TD-BE-11 | No abandoned-cart cleanup / account-deletion propagation | backend | Low | M | [backend.md](backend.md#td-be-11) |
| TD-BE-12 | SecurityConfig copy-pasted across 6 services | backend | Low | M | [backend.md](backend.md#td-be-12) |
| TD-BE-14 | `user_profiles.email` never refreshed | backend | Low | S | [backend.md](backend.md#td-be-14) |
| TD-TEST-05 | `tsconfig.build.json` excludes tests | testing | Low | S | [testing.md](testing.md#td-test-05) |
| TD-BE-15 | Concurrent checkouts with different keys can double-order one cart | backend | Med | M | [backend.md](backend.md#td-be-15) |
| TD-SEC-09 | Prod cookie/redirect defaults (AUTH_COOKIE_SECURE, localhost allow-list) must be overridden | infra-security | Med | S | [infra-security.md](infra-security.md#td-sec-09) |
| TD-SEC-10 | Feign token relay goes to any `services.*.url` override | infra-security | Low | S | [infra-security.md](infra-security.md#td-sec-10) |
| TD-SEC-11 | `http.ts` attaches bearer token to absolute URLs | infra-security | Low | S | [infra-security.md](infra-security.md#td-sec-11) |
| TD-SEC-12 | Non-UUID JWT `sub` returns 500 instead of 401 | infra-security | Low | S | [infra-security.md](infra-security.md#td-sec-12) |
| TD-SEC-13 | DB `CONNECT` not revoked from PUBLIC | infra-security | Low | S | [infra-security.md](infra-security.md#td-sec-13) |
| TD-BE-16 | Build tooling quirks on Windows (spotless bind mount, buildx entitlement, LF endings) | backend | Low | S | [backend.md](backend.md#td-be-16) |
| TD-TEST-06 | Seed data cannot exercise the sale-price rule | testing | Low | S | [testing.md](testing.md#td-test-06) |
| TD-RT-01 | Demo product averages disagree with rating-service | ratings | Med | S | [ratings.md](ratings.md#td-rt-01) |
| TD-RT-02 | No scheduled reconcile of product rating fields | ratings | Med | M | [ratings.md](ratings.md#td-rt-02) |
| TD-RT-03 | Rating updates bump `Product.version` | ratings | Low | S | [ratings.md](ratings.md#td-rt-03) |
| TD-RT-04 | Reviewer display name is a snapshot | ratings | Low | S | [ratings.md](ratings.md#td-rt-04) |
| TD-RT-05 | Seed reviews have synthetic authors | ratings | Low | S | [ratings.md](ratings.md#td-rt-05) |
| TD-RT-06 | Duplicated rating event/summary shapes, no contract tests | ratings | Low | M | [ratings.md](ratings.md#td-rt-06) |
| TD-RT-07 | Ratings frontend cleanups (dead code, refetch, lag, no tests) | ratings | Low | S | [ratings.md](ratings.md#td-rt-07) |
| TD-SEC-08 | No account-linking UX for Google vs password accounts | infra-security | Low | M | [infra-security.md](infra-security.md#td-sec-08) |
| TD-FE-06 | Wishlist: snapshot-less items dropped; N product fetches | frontend | Low | M | [frontend.md](frontend.md#td-fe-06) |
| TD-FE-07 | Session-expiry full reload; no Retry-After on 429 | frontend | Low | S | [frontend.md](frontend.md#td-fe-07) |
| TD-FE-08 | Address form validation minimal; `window.confirm` dialogs | frontend | Low | S | [frontend.md](frontend.md#td-fe-08) |
| TD-FE-09 | No cross-tab session sync | frontend | Low | M | [frontend.md](frontend.md#td-fe-09) |
| TD-FE-10 | Google button shown without checking server config | frontend | Low | S | [frontend.md](frontend.md#td-fe-10) |
| TD-FE-11 | Post-login destination in sessionStorage can swap across tabs | frontend | Low | S | [frontend.md](frontend.md#td-fe-11) |
| TD-FE-12 | Storefront test harness does not boot on Node 26 (all 15 files fail in setup) | frontend | Med | S | [frontend.md](frontend.md#td-fe-12) |
| TD-FE-13 | Profile/deals UI pass untested; order cards lack thumbnails | frontend | Low | S | [frontend.md](frontend.md#td-fe-13) |
| TD-P6-18 | Coupon service gaps (unpaged list, timezone, no revalidation) | phase6 | Low | S | [phase6.md](phase6.md#td-p6-18) |
| TD-P6-19 | Expired one-time tokens never purged | phase6 | Low | S | [phase6.md](phase6.md#td-p6-19) |
| TD-P6-20 | Phone enumeration (AUTH_PHONE_TAKEN); reset side effects | phase6 | Low | S | [phase6.md](phase6.md#td-p6-20) |
| TD-P6-21 | Admin SPA boilerplate, no shared composables/modal | phase6 | Low | S | [phase6.md](phase6.md#td-p6-21) |
| TD-P6-22 | Admin guesses at API shapes (INR hardcoded, address field) | phase6 | Low | S | [phase6.md](phase6.md#td-p6-22) |
| TD-P6-23 | Storefront Phase 6 UX shortcuts (polling, no precheck) | phase6 | Low | S | [phase6.md](phase6.md#td-p6-23) |
| TD-P6-24 | Storefront main bundle ~545 kB, not code-split | phase6 | Low | S | [phase6.md](phase6.md#td-p6-24) |
| TD-P6-25 | Test data left in dev DB (admin user, p6e2e users, test product) | phase6 | Low | S | [phase6.md](phase6.md#td-p6-25) |
| TD-P6-26 | Notification infra dev-only (Mailpit unpinned, deprecated deserializer) | phase6 | Low | S | [phase6.md](phase6.md#td-p6-26) |
| TD-P6-27 | Admin order list N+1 | phase6 | Low | S | [phase6.md](phase6.md#td-p6-27) |
| TD-P6-28 | Saved-for-later price snapshots go stale | phase6 | Low | S | [phase6.md](phase6.md#td-p6-28) |
| TD-P6-29 | Spotless not run on Phase 6 code | phase6 | Low | S | [phase6.md](phase6.md#td-p6-29) |
| TD-ADM-01 | No tests for admin APIs/screens (last-admin, self-change, 403 rules) | admin | High | M | [admin.md](admin.md#td-adm-01) |
| TD-ADM-02 | Disable/demote leaves issued access tokens valid until TTL | admin | Med | M | [admin.md](admin.md#td-adm-02) |
| TD-ADM-03 | Dashboard stats scan orders; "today" is the UTC day | admin | Med | S | [admin.md](admin.md#td-adm-03) |
| TD-ADM-05 | No audit trail for admin actions | admin | Med | M | [admin.md](admin.md#td-adm-05) |
| TD-ADM-12 | payment-service not Gradle-compiled for this change (stripe-java not cached) | admin | Med | S | [admin.md](admin.md#td-adm-12) |
| TD-ADM-04 | Low-stock tile checks only the 50 lowest inventory rows | admin | Low | S | [admin.md](admin.md#td-adm-04) |
| TD-ADM-06 | Review moderation delete-only; product ids not names | admin | Low | M | [admin.md](admin.md#td-adm-06) |
| TD-ADM-07 | Payments ledger read-only, not cross-linked, OFFSET paging | admin | Low | M | [admin.md](admin.md#td-adm-07) |
| TD-ADM-08 | Customer search is unindexed `LIKE '%q%'` | admin | Low | S | [admin.md](admin.md#td-adm-08) |
| TD-ADM-09 | Category JSON `isActive` vs `active` inconsistency | admin | Low | S | [admin.md](admin.md#td-adm-09) |
| TD-ADM-10 | Category hard delete, fixed parent, no slug/order | admin | Low | S | [admin.md](admin.md#td-adm-10) |
| TD-ADM-11 | `auth.storage.spec.ts` fails on Node ≥ 25 (pre-existing) | admin | Low | S | [admin.md](admin.md#td-adm-11) |
| TD-ADM-13 | `/api/admin/**` ADMIN-only at the gateway | admin | Low | S | [admin.md](admin.md#td-adm-13) |
| TD-P7-01 | No tests for Phase 7; storefront component tests likely stale | phase7 | High | L | [phase7.md](phase7.md#td-p7-01) |
| TD-P7-07 | Variant stock gaps (no row = unlimited, no sync from editor) | phase7 | High | M | [phase7.md](phase7.md#td-p7-07) |
| TD-P7-02 | `git stash` swept uncommitted work (unknown actor); work uncommitted | phase7 | Med | S | [phase7.md](phase7.md#td-p7-02) |
| TD-P7-03 | Search: in-memory relevance over 2000-id cap, trigram index unused | phase7 | Med | M | [phase7.md](phase7.md#td-p7-03) |
| TD-P7-04 | Facets computed in Java, no own-facet exclusion, comma-joined URLs | phase7 | Med | S | [phase7.md](phase7.md#td-p7-04) |
| TD-P7-06 | Variant model thin (delete-recreate, no FK, no validation) | phase7 | Med | M | [phase7.md](phase7.md#td-p7-06) |
| TD-P7-08 | Returns keyed by variant only for new rows | phase7 | Med | S | [phase7.md](phase7.md#td-p7-08) |
| TD-P7-09 | Deals pricing best-effort; currency unchecked; no per-user cap | phase7 | Med | M | [phase7.md](phase7.md#td-p7-09) |
| TD-P7-10 | Bank offers and EMI display-only, no admin CRUD | phase7 | Med | M | [phase7.md](phase7.md#td-p7-10) |
| TD-P7-11 | Alerts: client-supplied prices, `latest` offsets, coarse triggers | phase7 | Med | M | [phase7.md](phase7.md#td-p7-11) |
| TD-P7-13 | Q&A unmoderated, N+1, frozen verified-buyer flag | phase7 | Med | M | [phase7.md](phase7.md#td-p7-13) |
| TD-P7-14 | Review media on a local volume; no scan, quota or sweeper | phase7 | Med | M | [phase7.md](phase7.md#td-p7-14) |
| TD-P7-15 | rating-service runs as root for the media volume | phase7 | Med | S | [phase7.md](phase7.md#td-p7-15) |
| TD-P7-16 | SEO client-side only (no SSR/prerender); sitemap unpaged | phase7 | Med | L | [phase7.md](phase7.md#td-p7-16) |
| TD-P7-05 | Brand/attribute/slug/price shortcuts in catalog data | phase7 | Low | S | [phase7.md](phase7.md#td-p7-05) |
| TD-P7-12 | View tracking unauthenticated, live co-occurrence queries | phase7 | Low | M | [phase7.md](phase7.md#td-p7-12) |
| TD-P7-17 | Storefront Phase 7 UX shortcuts (wishlist cards, lightbox, no 360) | phase7 | Low | S | [phase7.md](phase7.md#td-p7-17) |
| TD-P7-18 | Storefront main chunk 584 kB | phase7 | Low | S | [phase7.md](phase7.md#td-p7-18) |
| TD-P7-19 | Admin Phase 7 shortcuts (free-text variants, non-atomic save) | phase7 | Low | S | [phase7.md](phase7.md#td-p7-19) |
| TD-P7-20 | Phase 7 test data left in dev DB | phase7 | Low | S | [phase7.md](phase7.md#td-p7-20) |
| TD-P7-21 | Internal-token wiring and dead Feign method | phase7 | Low | S | [phase7.md](phase7.md#td-p7-21) |
| TD-P8-01 | No tests for Phase 8 (money ledgers, checkout, campaigns, UI) | phase8 | High | L | [phase8.md](phase8.md#td-p8-01) |
| TD-P8-06 | Gift card codes plaintext, no redeem rate limit, pending cards never swept | phase8 | High | S | [phase8.md](phase8.md#td-p8-06) |
| TD-P8-07 | Wallet: single currency, best-effort credit-back, debit race | phase8 | High | M | [phase8.md](phase8.md#td-p8-07) |
| TD-P8-02 | Campaign audience only users with a profile row; unthrottled ALL send | phase8 | Med | M | [phase8.md](phase8.md#td-p8-02) |
| TD-P8-03 | Campaign send in-process (restart leaves SENDING); thin admin view | phase8 | Med | M | [phase8.md](phase8.md#td-p8-03) |
| TD-P8-04 | Web push never delivered to a real push service | phase8 | Med | M | [phase8.md](phase8.md#td-p8-04) |
| TD-P8-05 | VAPID private key plaintext in notification_db | phase8 | Med | S | [phase8.md](phase8.md#td-p8-05) |
| TD-P8-08 | Refund split / return edge cases; coins not returned | phase8 | Med | M | [phase8.md](phase8.md#td-p8-08) |
| TD-P8-09 | Coins: earn-rate deviation, expiry informational, consumer limits | phase8 | Med | M | [phase8.md](phase8.md#td-p8-09) |
| TD-P8-11 | Subscribe and Save: no ShedLock, generic failure mail | phase8 | Med | M | [phase8.md](phase8.md#td-p8-11) |
| TD-P8-12 | Membership: no renew/proration, uncached live lookups | phase8 | Med | M | [phase8.md](phase8.md#td-p8-12) |
| TD-P8-13 | Referral: reward not reported back, no abuse checks | phase8 | Med | S | [phase8.md](phase8.md#td-p8-13) |
| TD-P8-14 | Non-order payments reuse `payments.order_id` | phase8 | Med | S | [phase8.md](phase8.md#td-p8-14) |
| TD-P8-10 | Order list DTO lacks coins/wallet | phase8 | Low | S | [phase8.md](phase8.md#td-p8-10) |
| TD-P8-15 | Inbox/preferences shortcuts (no retention, coupled text) | phase8 | Low | M | [phase8.md](phase8.md#td-p8-15) |
| TD-P8-16 | Lists: hybrid storage, unpaged, no move endpoint | phase8 | Low | S | [phase8.md](phase8.md#td-p8-16) |
| TD-P8-17 | Abandoned-cart reminders: email capture, no lock | phase8 | Low | S | [phase8.md](phase8.md#td-p8-17) |
| TD-P8-18 | Storefront Phase 8 UX shortcuts (estimates, polling, fan-out) | phase8 | Low | M | [phase8.md](phase8.md#td-p8-18) |
| TD-P8-19 | Phase 8 test data and Mailpit messages left in dev | phase8 | Low | S | [phase8.md](phase8.md#td-p8-19) |
| TD-P8-20 | notification_db setup script, throwaway password, offline builds | phase8 | Low | S | [phase8.md](phase8.md#td-p8-20) |
| TD-P9-01 | No tests for Phase 9; admin/storefront specs likely stale | phase9 | High | L | [phase9.md](phase9.md#td-p9-01) |
| TD-P9-02 | Two sources of truth for platform stock (warehouse_stock vs legacy) | phase9 | High | M | [phase9.md](phase9.md#td-p9-02) |
| TD-P9-03 | Seller products go live without moderation | phase9 | High | M | [phase9.md](phase9.md#td-p9-03) |
| TD-P9-07 | Seller ledger: consumer starts at latest, no DLQ, delivery fee not reversed | phase9 | High | M | [phase9.md](phase9.md#td-p9-07) |
| TD-P9-09 | KYC format-only; static bank-data key; suspend keeps role | phase9 | High | M | [phase9.md](phase9.md#td-p9-09) |
| TD-P9-04 | Offer + stock writes non-atomic; bulk CSV per row | phase9 | Med | M | [phase9.md](phase9.md#td-p9-04) |
| TD-P9-05 | Seller stock publishes no events; offer-without-stock fallback; sellers cannot stock platform warehouses | phase9 | Med | M | [phase9.md](phase9.md#td-p9-05) |
| TD-P9-06 | List price not re-synced when an offer sells out via orders | phase9 | Med | S | [phase9.md](phase9.md#td-p9-06) |
| TD-P9-08 | Payout run manual and simulated | phase9 | Med | M | [phase9.md](phase9.md#td-p9-08) |
| TD-P9-10 | Ads: unauthenticated clicks, FE-driven beacons, substring keywords | phase9 | Med | M | [phase9.md](phase9.md#td-p9-10) |
| TD-P9-11 | Seller lookups uncached; fixed fallback ratings | phase9 | Med | S | [phase9.md](phase9.md#td-p9-11) |
| TD-P9-12 | Shipment lifecycle: no per-shipment cancel/refund, no SLA | phase9 | Med | M | [phase9.md](phase9.md#td-p9-12) |
| TD-P9-13 | Returns, invoices, delivery estimates per shipment shortcuts | phase9 | Med | M | [phase9.md](phase9.md#td-p9-13) |
| TD-P9-15 | Wishlist/saved not offer-keyed; omitted offerId ambiguous | phase9 | Med | M | [phase9.md](phase9.md#td-p9-15) |
| TD-P9-14 | Seller analytics approximate | phase9 | Low | S | [phase9.md](phase9.md#td-p9-14) |
| TD-P9-16 | Gateway explicit seller paths; seller DB setup and secrets | phase9 | Low | S | [phase9.md](phase9.md#td-p9-16) |
| TD-P9-17 | Storefront Phase 9 UX shortcuts | phase9 | Low | M | [phase9.md](phase9.md#td-p9-17) |
| TD-P9-18 | Seller portal assumed API shapes, raw-UUID forms | phase9 | Low | M | [phase9.md](phase9.md#td-p9-18) |
| TD-P9-19 | Phase 9 test data left in dev (Laptop PDP 404) | phase9 | Low | S | [phase9.md](phase9.md#td-p9-19) |
| TD-SEC-14 | Database dumps with credential material remain in git history | infra-security | High | S | [infra-security.md](infra-security.md#td-sec-14) |
| TD-TEST-07 | `spotlessCheck` fails on 4 product/inventory files | testing | Med | S | [testing.md](testing.md#td-test-07) |

## Resolved by Phase 6 (checked against the code)

- TD-BE-01 oversell: order-service reserves stock via inventory-service (`OrderRemote.reserve`/`release`) and releases on cancel/failure. Residual gaps: TD-P6-04, TD-P6-01.
- TD-BE-02 no payment/shipping/tax: payment-service (Stripe + SIMULATED + COD), shipping and GST in order-service now exist. Residual: Stripe untested (TD-P6-02), no real carrier, rules hard-wired (TD-P6-17).
- TD-BE-04 no `order.events` publisher: `OrderEventPublisher` exists and notification-service consumes it. Residual: no outbox (TD-P6-01), notification gaps (TD-P6-07).
- TD-BE-05 cancel does not release stock or refund: cancel releases stock and calls `OrderRemote.refund`. Residual: TD-P6-03, TD-P6-14.
- TD-SEC-04 (email verification half) and TD-FE-05: email verification, password reset and phone OTP exist in auth-service and the storefront. Residual: unverified users can still log in, no captcha (TD-P6-10); open registration default remains in TD-SEC-04.
- Feign timeouts were never applied (legacy `feign.client.config` prefix, keys by service name instead of `@FeignClient` contextId). Fixed in order, rating, product and auth `application.yml` (prefix `spring.cloud.openfeign.client.config`); payment-service already used the right one. Timeouts now bite (1 s connect / 3 s read; product inventory 1.5 s), so previously masked slow calls may surface as failures. This also covers the earlier cart/user/product timeout-key note.

## Resolved by Phase 7 (checked against the code)

These were found during Phase 7 integration and fixed. Residuals are tracked in [phase7.md](phase7.md).

- Variant `availableQuantity` was null for anonymous PDP callers (product-service forwarded the caller's JWT to inventory). Now read via inventory `GET /internal/inventory/variants?ids=` with `X-Internal-Token`. Residual: TD-P7-07, TD-P7-21.
- Returns could not tell two variants of one product apart (keyed by productId only). Now keyed by (productId, variantId) via `order_return_items.variant_id`. Residual: TD-P7-08 (old rows).
- Admin product price edits did not reach the price customers pay when `sale_price` was set. Price now mirrors into `sale_price` and `discount_percent`. Residual: TD-P7-05.
- Wishlist ignored variants. Now keyed by productId + variantId. Residual: one card per product on the page (TD-P7-17).
- Price-drop alerts were created with no price/name/currency. The PDP now sends them. Residual: client-supplied and unvalidated (TD-P7-11).
- Admin Deals nav/route allowed CATALOG_MANAGER although the backend requires ADMIN, and an empty `quantityLimit` caused a 400. Now ADMIN-only and `quantityLimit` required.

## Resolved by Phase 8 (checked against the code)

These were found during Phase 8 integration and fixed. Residuals are tracked in [phase8.md](phase8.md).

- Coin redeem control was hidden for USD carts because `engageApi.coins()` took no currency and the server defaulted to INR. Checkout now passes the cart currency; the Coins page passes `USD`. Residual: hardcoded `USD` (TD-P8-09).
- Wallet credits and gift-card purchases left no trace in the notification centre. payment-service now posts an ACCOUNT inbox entry after a fresh wallet credit and "Gift card sent" to the purchaser (best effort). Residual: TD-P8-06, TD-P8-07.
- Invoice PDF omitted coins and wallet. It now shows "Coins redeemed" and "Paid from wallet / Paid by <method>" lines. Residual: the order list DTO still lacks them (TD-P8-10).
- Admin CampaignsView had no `SENDING` state, so a running send looked stuck. It now shows a badge and auto-refreshes every 3 s. Residual: TD-P8-03.

## Resolved by Phase 9 (checked against the code)

These were found during Phase 9 integration and fixed. Residuals are tracked in [phase9.md](phase9.md).

- Refunds were reversed against the seller ledger as a whole shipment only. A `RETURN_REFUNDED` event with `returnShipmentId`, `returnId` and `refundAmount` now books `REFUND_REVERSAL` and `COMMISSION_REVERSAL` once per return (V2 `ledger_entries.return_id`), so partial refunds work. Residual: delivery fee not reversed, consumer starts at latest (TD-P9-07).
- Cart lines were keyed by product + variant, so two sellers of one product merged into one line. Lines are now keyed (product, variant, offer). Residual: wishlist/saved and omitted `offerId` (TD-P9-15).
- The PDP buy box and "Other sellers" panel ignored the selected variant. They now follow the variant (`variantBuyBoxes`). Residual: TD-P9-17.
- Storefront brand text read "Sell on Acme"; now "Sell on Cartwise".
- List-row price did not follow the buy box after seller stock writes. inventory `writeStock` now calls product-service `/internal/products/{id}/sync-prices` (async, fail-open). Residual: sell-out through orders (TD-P9-06).
- Return restock did not say which seller's offer was returned. It now passes `offerId` to inventory release. Residual: TD-P9-13.
- Seller portal response shapes were guessed wrong: `/api/seller/offers` is nested (flattened in `sellerApi`), payout run returns `{count,payouts[]}`, shipment `shipTo` is an object with `placedAt`/`warehouseCode`, and validation problems carry `errors[{field,message}]` (now shown). Residual: TD-P9-18.

## Dropped after verification

- Phase 6 "admin nav hides items client-side only": by design; backend `hasRole('ADMIN')` enforces. Folded into TD-P6-21 as a note.
- Phase 6 "`order.events` type headers off / consumers must not require `__TypeId__`": a contract note, not a defect; the notification consumer works. Kept only as a line in TD-P6-26.
- Phase 6 "create-customer-dbs.sql needed for new DBs": same as existing TD-SEC-05; no new item (the script now also covers payment/promotion DBs).
- Duplicated across agents and merged: Maven Central 403/offline-build notes (4 agents, now TD-P6-06), no-tests notes (4 agents, TD-P6-05), shared-secret/copy-pasted filter notes (3 agents, TD-P6-12).

- "Address `isDefault` serialization unverified": `AddressResponse` is a record with component `isDefault`, and the frontend type uses the same name. Not an issue.
- TD-FE-01 "`npm run dev` forces port 3000; Google callback needs 5174": resolved by keeping the storefront on 3000 (`vite.config.ts` strictPort 3000) and allow-listing `http://localhost:3000/auth/callback` in compose.
- TD-BE-13 "No cap on distinct cart lines / wishlist size": resolved in review fixes (cart max 50 lines, wishlist max 500, 409 `CART_LIMIT` / `WISHLIST_LIMIT`).
- TD-FE-04 data loss: resolved (quantities clamped to 99, merge chunked, local items kept when merge fails). Remaining: no retry until next sign-in.
- TD-SEC-07 "rating-service not runnable in compose/gateway": resolved by the ratings feature (rating_db, Dockerfile, compose on 8084, gateway route, `roles`-based admin check). Related product-service listener bug (rating events failed to deserialize; denormalization stub) also fixed.
- "RouteAuthorizationContractTest not updated": that test does not exist in `services/gateway/src/test`; it is only referenced in a `SecurityConfig` javadoc. Reframed as TD-TEST-04.
- "Update platform error catalogue": done in [Backend-API.md](../architecture/api.md).
- Phase 7: the first-build notes for variant stock via JWT forwarding, non-variant returns, non-variant wishlist, alert payload, and "Deals RBAC unverified" were superseded by the integration fixes above and not filed as items. Gateway body-size and Maven Central notes were merged into TD-P7-14 and TD-P6-06. Test-data notes from the commerce and integration agents were merged into TD-P7-20. Roadmap item 5 (PDP pincode check) was already built in Phase 6 and has no Phase 7 debt.
- Phase 8: first-build notes superseded by integration fixes (coins currency, wallet/gift-card inbox entries, invoice lines, admin SENDING status) are listed under "Resolved by Phase 8" and not filed as items. The frontend agent's `[p8-fe]` bullets in [frontend.md](frontend.md) were moved into [TD-P8-18](phase8.md#td-p8-18). Duplicates merged: campaign audience (engage + integration) into TD-P8-02, campaign send (engage + admin) into TD-P8-03, no-tests notes into TD-P8-01. The "wallet single currency / coins deviation" notes are kept as separate items (TD-P8-07, TD-P8-09). The `GET /internal/orders/last-order-dates` shape note was folded into TD-P8-02.
- Phase 9: first-build notes superseded by integration fixes (offers without `variantId`, cart keyed by product + variant, whole-shipment-only ledger reversal, restock without `offerId`, `OrderShipment.items` shape, guessed admin response shapes, "Sell on Acme") are listed under "Resolved by Phase 9" and not filed as items. The `[p9-admin]` and `[p9-fe]` bullets were consolidated into TD-P9-17 and TD-P9-18; no-tests notes into TD-P9-01; seller-cache, platform-rating and views-attribution notes into TD-P9-11; ads notes (seller + frontend) into TD-P9-10.
