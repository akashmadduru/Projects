# Portfolio R-20261119-01

Source: `.openforge/worktrees/F-20261119-01/docs/techdebt/README.md` (index, 121 open rows) and `remediation-plan.md`.
Charter exclusions applied. Class per constitution section 4 (S 0-2, M 3-5, L 6-8, XL 9+). Nothing planned as L or XL.

Disposition key: IN = in-scope; OWN = excluded:owner-only; EXT = excluded:external; FIXED = excluded:already-fixed (proof given).
Note: the register index still lists 6 items the remediation plan says were fixed on 2026-11-18; verified below with file:line.

## 1. Every open register item

| ID | Title (short) | Sev | Eff | Area / services | Disposition |
|---|---|---|---|---|---|
| TD-P6-02 | Stripe/Razorpay live mode never exercised | High | S | payment | OWN (listed Owner-only in charter; needs real test accounts) |
| TD-P6-05 | Phase 6 tests: coupons, OTP, returns, promo/notif | Med | L | promotion, auth, order, notification | IN (O-31, O-32) |
| TD-TEST-01 | Ownership not tested per HTTP endpoint | Med | M | order, cart, user | IN (O-29) |
| TD-TEST-02 | No FE tests for checkout and Google sign-in | Med | M | storefront | IN (O-30) |
| TD-SEC-01 | Real Google client secret in local .env | High | S | infra | OWN (charter) |
| TD-P6-06 | Dockerfiles for new services untested | Med | S | all images | EXT: Maven Central returns 403 from build host; needs network or a clean external build host |
| TD-P6-07 | Notifications fire-and-forget, in-memory de-dupe | Med | M | notification | IN (O-16) |
| TD-P6-08 | SMS is a dev stub that logs OTP | Med | M | notification, auth | EXT: needs an SMS vendor account and credentials |
| TD-P6-10 | Login not blocked for unverified email; no captcha | Med | S | auth | IN (O-01; block-unverified only, captcha needs a vendor and stays out) |
| TD-P6-11 | OTP abuse controls in-memory per replica | Med | M | auth | IN (O-01; use existing Postgres, no new datastore) |
| TD-P6-12 | INTERNAL_API_TOKEN shared static, copy-pasted filters | Med | M | libs + all services | IN (O-02) |
| TD-P6-14 | Refunds skip shipping; COD refund only marked | Med | M | order, payment | IN (O-05) |
| TD-P6-15 | Invoice PDF hand-written, no rupee glyph | Med | S | order | IN (O-13) |
| TD-P6-17 | Shipping/tax/COD rules hard-wired, client estimates | Med | M | order, storefront | IN (O-07) |
| TD-TEST-04 | Gateway route/authorization contract untested | Med | M | gateway | IN (O-28) |
| TD-BE-03 | Cart clear after order best-effort | Low | M | order, cart | IN (O-06) |
| TD-BE-06 | Sequential product lookups in checkout | Med | M | order, product | IN (O-06) |
| TD-BE-07 | No Feign circuit breakers in order-service | Med | S | order | IN (O-06) |
| TD-BE-09 | Cart/wishlist trust client snapshots | Med | S | cart | IN (O-17) |
| TD-SEC-04 | Open registration by default | Med | S | auth | OWN (charter; sign-up policy is a business decision) |
| TD-SEC-05 | Manual DB script needed for existing volumes | Med | M | deploy/local | IN (O-27) |
| TD-FE-02 | Checkout totals client-side | Med | M | storefront, order | IN (O-07) |
| TD-FE-03 | Optimistic cart/wishlist last-write-wins | Med | M | storefront | IN (O-17) |
| TD-FE-04 | Failed login merge has no in-session retry | Low | S | storefront | IN (O-25) |
| TD-BE-12 | SecurityConfig copy-pasted across 6 services | Low | M | 6 services | IN (O-03) |
| TD-BE-15 | Concurrent checkouts can double-order a cart | Med | M | order | IN (O-06) |
| TD-BE-16 | Windows build tooling quirks | Low | S | build scripts | IN (O-27) |
| TD-TEST-06 | Seed data cannot exercise sale-price rule | Low | S | seed data | IN (O-27) |
| TD-RT-01 | Demo product averages disagree with ratings | Med | S | product, rating | IN (O-21) |
| TD-RT-02 | No scheduled reconcile of rating fields | Med | M | product, rating | IN (O-21) |
| TD-RT-03 | Rating updates bump Product.version | Low | S | product | IN (O-21) |
| TD-RT-04 | Reviewer display name is a snapshot | Low | S | rating | IN (O-21) |
| TD-RT-05 | Seed reviews have synthetic authors | Low | S | rating seed | IN (O-21) |
| TD-RT-06 | Duplicated rating event shapes, no contract tests | Low | M | product, rating | IN (O-21) |
| TD-RT-07 | Ratings frontend cleanups | Low | S | storefront | IN (O-26) |
| TD-SEC-08 | No Google vs password account-linking UX | Low | M | auth, storefront | IN (O-01) |
| TD-FE-06 | Wishlist snapshot-less items dropped; N fetches | Low | M | storefront, cart | IN (O-17) |
| TD-FE-07 | Session-expiry full reload; no Retry-After on 429 | Low | S | storefront | IN (O-25) |
| TD-FE-08 | Address validation minimal; window.confirm | Low | S | storefront | IN (O-26) |
| TD-FE-09 | No cross-tab session sync | Low | M | storefront | IN (O-25) |
| TD-FE-10 | Google button shown without server config check | Low | S | storefront, auth | IN (O-25) |
| TD-FE-11 | Post-login destination can swap across tabs | Low | S | storefront | IN (O-25) |
| TD-FE-13 | Profile/deals UI untested; order cards no thumbnails | Low | S | storefront | IN (O-26) |
| TD-P6-18 | Coupon service gaps (unpaged, timezone) | Low | S | promotion | IN (O-12) |
| TD-P6-20 | Phone enumeration; reset side effects | Low | S | auth | IN (O-01) |
| TD-P6-21 | Admin SPA boilerplate, no shared composables | Low | S | admin app | IN (O-24) |
| TD-P6-22 | Admin guesses API shapes (INR hardcoded) | Low | S | admin app | IN (O-24) |
| TD-P6-23 | Storefront Phase 6 UX shortcuts (polling) | Low | S | storefront | IN (O-07) |
| TD-P6-24 | Storefront bundle not code-split | Low | S | storefront | FIXED: `apps/product-catalog/vite.config.ts:32-35` (`manualChunks` -> `react-vendor`); plan row 53 |
| TD-P6-25 | Test data left in dev DB | Low | S | dev DB | OWN (plan "Needs the owner": `down -v` is destructive to local data) |
| TD-P6-26 | Notification infra dev-only (Mailpit unpinned) | Low | S | deploy, notification | IN (O-16) |
| TD-P6-28 | Saved-for-later price snapshots stale | Low | S | cart | IN (O-17) |
| TD-P6-30 | Razorpay order creation no idempotency key | Low | S | payment | IN (O-04) |
| TD-P6-31 | Razorpay refunds recorded done once accepted | Med | M | payment | IN (O-04) |
| TD-P6-32 | Razorpay INR only; manual capture undetected | Low | M | payment | IN (O-04; document and alert, no dashboard access needed) |
| TD-P6-33 | Only order-service has an outbox | Med | M | payment, promotion, seller, user | IN (O-08) |
| TD-ADM-01 | Admin routes: no 403 tests, no SQL tests | Med | M | admin routes | IN (O-28) |
| TD-ADM-02 | Disabled/demoted user tokens valid until TTL | Med | M | auth, gateway | IN (O-01) |
| TD-ADM-03 | Dashboard stats scan orders; UTC day | Med | S | order, admin | IN (O-23) |
| TD-ADM-06 | Review moderation delete-only | Low | M | rating, admin | IN (O-23) |
| TD-ADM-07 | Payments ledger read-only, OFFSET paging | Low | M | payment, admin | IN (O-23) |
| TD-ADM-10 | Category hard delete, no slug/order | Low | S | product, admin | IN (O-23) |
| TD-P7-01 | No tests for Phase 7 | High | L | product, rating, storefront | IN (O-33, O-34) |
| TD-P7-02 | git stash swept uncommitted work | Med | S | repo process | OWN (needs Owner decision on stash drop and commit slicing; agents may not run state-changing git) |
| TD-P7-03 | Search relevance in memory over 2000-id cap | Med | M | product | IN (O-18) |
| TD-P7-04 | Facets computed in Java | Med | S | product | FIXED: `product-service/.../repository/ProductFacetQueries.java:19`, test `ProductFilterNativeSqlTest.java:15`; plan row 50 |
| TD-P7-06 | Variant model thin | Med | M | product | IN (O-19) |
| TD-P7-09 | Deals pricing best-effort, no per-user cap | Med | M | promotion, product | IN (O-19) |
| TD-P7-10 | Bank offers/EMI display-only | Med | M | promotion, product | IN (O-19) |
| TD-P7-11 | Alerts: client prices, coarse triggers | Med | M | product/notification | IN (O-20) |
| TD-P7-13 | Q&A unmoderated, N+1 | Med | M | rating/product | IN (O-20) |
| TD-P7-14 | Review media on local volume, no quota/sweeper | Med | M | rating | IN (O-22; scoped to quota, sweeper, size limit, re-encode; S3/MinIO part would be a new datastore and needs an ADR, so the item may close only partially) |
| TD-P7-16 | SEO client-side only | Med | L | storefront, product | IN (O-22; scoped to sitemap index/paging, base URL, clean category paths, build-time prerender; no new rendering service) |
| TD-P7-05 | Brand/attribute/slug/price shortcuts | Low | S | product | FIXED: `product-service/.../web/dto/UpdateProductRequest.java:59` (`listPrice`); plan row 51 |
| TD-P7-12 | View tracking unauthenticated | Low | M | product | IN (O-20) |
| TD-P7-17 | Storefront Phase 7 UX shortcuts | Low | S | storefront | FIXED: `apps/product-catalog/src/pages/ProductDetailPage.tsx:308` (`view360Url`); plan row 52 |
| TD-P7-18 | Storefront main chunk 584 kB | Low | S | storefront | FIXED: `apps/product-catalog/vite.config.ts:32-35`; plan row 53 |
| TD-P7-19 | Admin Phase 7 shortcuts | Low | S | admin, product | FIXED: `product-service/.../web/dto/CreateProductRequest.java:80` (`variants` saved in one request); plan row 54 |
| TD-P7-20 | Phase 7 test data in dev DB | Low | S | dev DB | OWN (same as TD-P6-25) |
| TD-P8-01 | No tests for Phase 8 | High | L | payment, promotion, notification, storefront | IN (O-35, O-36) |
| TD-P8-02 | Campaign audience only profile rows; unthrottled | Med | M | notification, user | IN (O-15) |
| TD-P8-03 | Campaign send in-process, restart leaves SENDING | Med | M | notification | IN (O-15) |
| TD-P8-04 | Web push never delivered to a real push service | Med | M | notification | EXT: needs real push service and browser subscriptions to verify delivery |
| TD-P8-08 | Refund split / return edge cases; coins not returned | Med | M | order, payment, promotion | IN (O-05) |
| TD-P8-09 | Coins: earn-rate deviation, no expiry job | Med | M | promotion | IN (O-12) |
| TD-P8-11 | Subscribe and Save: generic failure mail | Med | M | order, notification | IN (O-13) |
| TD-P8-12 | Membership: no renew/proration, uncached | Med | M | payment, promotion | IN (O-12) |
| TD-P8-10 | Order list DTO lacks coins/wallet | Low | S | order | IN (O-13) |
| TD-P8-15 | Inbox/preferences shortcuts | Low | M | notification | IN (O-16) |
| TD-P8-16 | Lists: hybrid storage, unpaged | Low | S | user/cart | IN (O-17) |
| TD-P8-17 | Abandoned-cart reminder shortcuts | Low | S | notification, cart | IN (O-15) |
| TD-P8-18 | Storefront Phase 8 UX shortcuts | Low | M | storefront | IN (O-26) |
| TD-P8-19 | Phase 8 test data / Mailpit left in dev | Low | S | dev env | OWN (same as TD-P6-25) |
| TD-P8-20 | notification_db setup script, throwaway password | Low | S | deploy | IN (O-16) |
| TD-P8-22 | Wallet: one currency, no top-up/admin adjustment | Low | M | payment | IN (O-13; top-up through SIMULATED provider only) |
| TD-P8-24 | Referral IP hash salt not wired through Helm | Med | S | user, deploy (Terraform/Helm) | IN (O-27; only salt wiring and prod fail-closed, not client IP) |
| TD-SEC-15 | Google OIDC callback vs stripped X-Forwarded-* | Med | S | gateway, auth | IN, delivered by F-20261119-03 (same root cause as B-1); NOT planned here |
| TD-P9-01 | No tests for Phase 9 | High | L | seller, inventory, storefront, seller portal | IN (O-37, O-38) |
| TD-P9-02 | Legacy stock totals beside warehouse stock | Med | M | inventory, seller | IN (O-11; derive from warehouse_stock, no destructive drop, else stop rule 3) |
| TD-P9-09 | KYC format-only; bank data one static key | High | M | seller | OWN (charter; provider choice) |
| TD-P9-04 | Offer + stock writes non-atomic | Med | M | seller, inventory | IN (O-10) |
| TD-P9-05 | Seller stock publishes no events | Med | M | seller, inventory | IN (O-10) |
| TD-P9-08 | Payout run manual and simulated | Med | M | seller | EXT: real payout provider (RazorpayX, Cashfree, bank API) needs a vendor account |
| TD-P9-10 | Ads: unauthenticated clicks, substring keywords | Med | M | seller/ads, gateway | IN (O-14) |
| TD-P9-11 | Seller lookups uncached; fixed fallback ratings | Med | S | order, product | IN (O-14; already narrowed 2026-11-18, remaining parts only) |
| TD-P9-12 | No per-shipment cancel/refund, no SLA | Med | M | order, seller | IN (O-09) |
| TD-P9-13 | Returns, invoices, ETAs per shipment shortcuts | Med | M | order | IN (O-09) |
| TD-P9-15 | Wishlist/saved not offer-keyed | Med | M | cart, storefront | IN (O-17) |
| TD-P9-14 | Seller analytics approximate | Low | S | seller | IN (O-14) |
| TD-P9-16 | Gateway explicit seller paths; seller DB setup | Low | S | gateway, deploy | IN (O-27) |
| TD-P9-17 | Storefront Phase 9 UX shortcuts | Low | M | storefront | IN (O-26) |
| TD-P9-18 | Seller portal assumed API shapes | Low | M | seller portal | IN (O-24) |
| TD-P9-19 | Phase 9 test data in dev (Laptop PDP 404) | Low | S | dev DB | OWN (same as TD-P6-25) |
| TD-P9-20 | Seller ledger: fee on returns, no reconcile | Med | M | seller | IN (O-09) |
| TD-P9-21 | Product edits after approval not re-reviewed | Low | S | seller, product | IN (O-14) |
| TD-SEC-14 | DB dumps with credentials in git history | High | S | git history | OWN (charter; history rewrite) |
| TD-AI-02 | No IRSA role / NetworkPolicy egress for Bedrock | Med | S | helm, terraform | EXT: needs AWS account and a Bedrock VPC endpoint to apply or verify |
| TD-AI-03 | No search-specific rate limit | Med | M | gateway/product | IN (O-18) |
| TD-AI-04 | Search relevance never evaluated | Med | M | product | IN (O-18; offline eval set and metric, local embeddings or provider "none") |
| TD-AI-05 | Search and facets each run retrieval | Low | S | product | IN (O-18) |
| TD-AI-08 | env-reference generator drops unknown groups | Low | S | tooling | IN (O-18) |

## 2. Objectives (security/money first, test-only last)

Class is the CTO's pre-estimate; the CTO rescored each objective in its own complexity.md. Items per objective are at most 7.

| Obj | Class | Items | Services touched | Rationale / dependencies |
|---|---|---|---|---|
| O-01 | M | TD-P6-10, P6-11, P6-20, ADM-02, SEC-08 | auth-service, storefront | Auth hardening: login/OTP/enumeration/token revocation. Security-first. Do not touch IP logic (F-03). |
| O-02 | M (watch L: split into 2 PRs) | TD-P6-12 | libs, all services using INTERNAL_API_TOKEN | Replace copy-pasted internal-token filters with one shared lib and rotatable token. |
| O-03 | M | TD-BE-12 | 6 services' SecurityConfig, libs | Dedupe SecurityConfig. Depends on O-02 (same lib). Conflicts with F-03 if it edits gateway SecurityConfig: run after F-03 merges. |
| O-04 | M | TD-P6-30, P6-31, P6-32 | payment-service | Razorpay idempotency, refund confirmation via webhook state, capture mode alert. Money path. |
| O-05 | M | TD-P6-14, P8-08 | order, payment, promotion | Refund correctness: shipping, COD, split, coins returned. Depends on O-04 (refund states). |
| O-06 | M | TD-BE-15, BE-07, BE-06, BE-03 | order, cart, product (client) | Checkout integrity and resilience: double-order lock, circuit breakers, batched lookups, reliable cart clear. |
| O-07 | M | TD-P6-17, FE-02, P6-23 | order, storefront | Server-side checkout quote and configurable shipping/tax/COD; FE uses it. Depends on O-06. |
| O-08 | M | TD-P6-33 | payment, promotion, seller, user | Outbox for remaining publishers; reuse order-service pattern. Additive migrations only. |
| O-09 | M | TD-P9-20, P9-12, P9-13 | seller, order | Seller ledger fee reversal/reconcile, per-shipment cancel/refund/SLA/returns. Depends on O-05. |
| O-10 | M | TD-P9-04, P9-05 | seller, inventory | Atomic offer+stock writes, stock events. |
| O-11 | M | TD-P9-02 | inventory, seller | Retire legacy stock totals by derivation, no data drop. Depends on O-10. |
| O-12 | M | TD-P8-09, P8-12, P6-18 | promotion, payment | Coins expiry job and rate, membership renew/proration/cache, coupon gaps. |
| O-13 | M | TD-P8-22, P8-11, P8-10, P6-15 | payment, order, notification | Wallet adjustments/currency, subscribe-and-save mail and discount line, order DTO, invoice PDF. |
| O-14 | M | TD-P9-10, P9-11, P9-14, P9-21 | seller, product, order, gateway | Ads click auth/keywords, seller cache/ratings, analytics, re-review after edits. |
| O-15 | M | TD-P8-02, P8-03, P8-17 | notification, user, cart | Campaign audience/throttle/durable send, abandoned-cart reminders. Depends on O-08 pattern if outbox reused. |
| O-16 | M | TD-P6-07, P8-15, P6-26, P8-20 | notification, deploy | Durable notifications and de-dupe, inbox retention, pin Mailpit, DB setup script. |
| O-17 | M | TD-BE-09, P6-28, P9-15, P8-16, FE-03, FE-06 | cart, storefront | Cart/wishlist integrity: server-validated snapshots, offer-keyed saved, paging, concurrency. |
| O-18 | M | TD-P7-03, AI-05, AI-03, AI-04, AI-08 | product, gateway, tooling | Search relevance in SQL, shared retrieval, search rate limit (per-user key, NOT per-IP; IP belongs to F-03), eval harness. |
| O-19 | M | TD-P7-06, P7-09, P7-10 | product, promotion | Variant model FK/validation, deal pricing/currency/per-user cap, bank offers and EMI with admin CRUD. |
| O-20 | M | TD-P7-11, P7-13, P7-12 | product, rating, notification | Alerts server-side prices, Q&A moderation/N+1, view tracking auth. |
| O-21 | M | TD-RT-01..RT-06 | product, rating | Rating consistency, reconcile job, contract tests. |
| O-22 | M | TD-P7-14, P7-16 | rating, product, storefront | Media quota/sweeper, SEO sitemap/prerender (scoped, may close partially). |
| O-23 | M | TD-ADM-03, ADM-06, ADM-07, ADM-10 | order, rating, payment, product, admin app | Admin backend gaps: stats, moderation, ledger paging, category ops. |
| O-24 | S | TD-P6-21, P6-22, P9-18 | admin app, seller portal | Shared composables/modal, real API shapes. Depends on O-23. |
| O-25 | M | TD-FE-04, FE-07, FE-09, FE-10, FE-11 | storefront, auth (config endpoint) | Session/login UX. Depends on O-01. |
| O-26 | M | TD-FE-08, FE-13, P8-18, P9-17, RT-07 | storefront | UX shortcuts and cleanups, with tests. |
| O-27 | S | TD-SEC-05, P8-24, P9-16, BE-16, TEST-06 | deploy, gateway, build, seed | Infra/ops: DB script for existing volumes, salt through Helm with prod fail-closed, seller paths, Windows build quirks, sale-price seed. Gateway edits depend on F-03. |
| O-28 | M | TD-TEST-04, ADM-01 | gateway, admin routes | Test-only: route authorization contract, 403 per admin route, admin SQL tests. |
| O-29 | M | TD-TEST-01 | order, cart, user | Test-only: ownership per HTTP endpoint. |
| O-30 | S | TD-TEST-02 | storefront | Test-only: checkout and Google sign-in pages. |
| O-31 | M | TD-P6-05 (part 1: coupons, OTP) | promotion, auth | Test-only. |
| O-32 | M | TD-P6-05 (part 2: returns, promotion/notification services) | order, promotion, notification | Test-only. Closes TD-P6-05 together with O-31. |
| O-33 | M | TD-P7-01 (part 1: backend) | product, rating | Test-only. |
| O-34 | M | TD-P7-01 (part 2: storefront/admin specs) | storefront, admin app | Test-only. Closes TD-P7-01 with O-33. |
| O-35 | M | TD-P8-01 (part 1: ledgers beyond done, checkout, campaigns) | payment, promotion, notification, order | Test-only. |
| O-36 | M | TD-P8-01 (part 2: UI) | storefront | Test-only. Closes TD-P8-01 with O-35. |
| O-37 | M | TD-P9-01 (part 1: backend) | seller, inventory, order | Test-only. |
| O-38 | M | TD-P9-01 (part 2: storefront and seller portal specs) | storefront, seller portal | Test-only. Closes TD-P9-01 with O-37. |

Cross-objective notes:
- F-20261119-03 owns client IP (WalletController per-IP limit, AuditInterceptor IP, gateway trusted-proxies, TD-SEC-15). No objective here plans those. O-03, O-18 and O-27 touch gateway or SecurityConfig files, so sequence them after F-03 merges, or rebase on it.
- Dependency chain: O-02 -> O-03; O-04 -> O-05 -> O-09; O-06 -> O-07; O-10 -> O-11; O-23 -> O-24; O-01 -> O-25. Tests O-28..O-38 run last (O-31/O-32 after O-01, O-05; O-35 after O-12/O-13).
- Items with scoped or partial closure risk: TD-P6-10 (captcha out), TD-P7-14 (object storage out), TD-P7-16 (no rendering service). Count them closed only if the register entry is fully removed; otherwise report as partial and do not count toward 90%.
- Register hygiene: the six FIXED rows are still in the register index. Removing them is a docs edit that can ride along inside O-27 (docs excluded from size limit).

## 3. Totals

| Measure | Count |
|---|---|
| Open register rows | 121 |
| In-scope | 100 (99 planned in objectives + TD-SEC-15 delivered by F-20261119-03) |
| Excluded: owner-only | 10 (TD-SEC-01, SEC-14, P6-02, P9-09, SEC-04 per charter; TD-P6-25, P7-20, P8-19, P9-19 dev-DB wipe; TD-P7-02 git stash) |
| Excluded: external | 5 (TD-P6-06, P6-08, P8-04, P9-08, AI-02) |
| Excluded: already-fixed | 6 (TD-P6-24, P7-04, P7-05, P7-17, P7-18, P7-19) |
| Objectives | 38 (O-01..O-38): 27 implementation (O-01..O-27), 11 test-only (O-28..O-38) |
| 90% target | 90 of 100 in-scope items closed (about 38 of 38 objectives needed only if the 10 slack items are not concentrated in one objective; slack is roughly O-22, O-24, O-26) |

Token estimate (60k-150k per item incl. review): 100 items = 6.0M to 15.0M agent tokens, mid-point about 10.5M. This excludes CEO main-session tokens (blind spot) and per-objective overhead for requirements/design/ADR, which makes the high end the more likely. At a 3M checkpoint cadence expect 2 to 5 benchmark snapshots. Reaching 90 items implies roughly 5.4M to 13.5M.
