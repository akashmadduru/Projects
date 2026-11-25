# OpenForge objective backlog

Objectives found while running others. Each becomes a `/forge` run; the CEO files it in the
tech-debt register at the next step 11 if it is debt rather than a feature.

## B-1 (in progress: F-20261119-03) One shared client-IP resolver (from F-20261119-01 quality review)
- Five separate client-IP routines with different trust rules: gateway `RateLimitWebFilter.remoteIp`
  (right-hand, trusted hops), auth-service `RequestDeviceContext`, payment-service `WalletController`,
  common-web `AuditInterceptor.ip` (leftmost `X-Forwarded-For`: **the audit log IP is forgeable**),
  user-service `ClientIp` (right-hand, F-20261119-01).
- Objective: one resolver in `libs/common-web` (right-hand, configurable trusted hops, literal-only
  parsing via `InetAddress.ofLiteral`), used by all five; fix the audit-log forgery; correct the
  auth-service `application.yml` comment claiming the gateway overwrites the header.
- Expected class: M (several services, security path). Priority: high (audit integrity).

## Awaiting Owner approval (governance files; the protect hook blocks the CEO)
- G-1 (APPLIED 2026-11-19) `forge-discover` skill: every runtime assumption must cite proof (file:line or library source/jar);
  unproven = blocking question or spike. (Lesson from F-20261119-01; applied by hand in F-20261119-03.)
- G-2 (APPLIED) `forge-review` skill: re-review rounds are delta-first (`review-delta.diff` + previous verdicts).
- G-3 (APPLIED) Constitution: apply the class size limit to production lines only (tests and docs excluded).

## B-2 Relay the client IP on service-to-service calls (from F-20261119-03 CTO condition 6)
Feign calls between services do not carry `X-Internal-Client-Ip`; a service acting for a user on another
service's behalf sees the caller pod's IP. Add a Feign request interceptor that relays the header only when
the inbound request had it. Not needed for F-20261119-03's ACs.
- G-4 (APPLIED) forge-verify (hook): run `node deploy/local/scripts/env-reference.mjs --check` when env docs/config change.

## Follow-ups from F-20261119-01 review round 5
- F-1 env-descriptions.json VAPID_PUBLIC_KEY (deploy table): drop the non-prod sentence; "blank turns web push off on AWS".
- F-2 env-reference.mjs isSecretSetting: exclude PUBLIC (VAPID_PUBLIC_KEY shows a lock icon).
- G-5 forge-protect: the main session runs as agent `forge-ceo` (settings `agent`), so the hook denies it on
  governance files and the permission "ask" (Owner prompt) is never reached. Owner-approved edits needed a
  scripted workaround on 2026-11-19. Fix: exempt forge-ceo from the governance deny and rely on the ask rules.

## B-3 Order shows REFUNDED after a Razorpay refund later fails (from F-20261119-04 requirements)
payment-service will hold the refund PENDING/FAILED and alert, but order-service's order/refund status is set
at request time and is not reverted on `refund.failed`. Needs an event from payment-service and an order-side
state (REFUND_FAILED). Customer-visible: the order says refunded while money did not move.
- G-6 Role write boundaries are enforced only for Edit/Write/NotebookEdit. An engineer wrote outside its
  worktree with Bash (F-20261119-03 slice 1). Fix options: forge-guard denies Bash commands from engineer/qa
  roles that redirect or write (`>`, `tee`, `cp`, `mv`, `sed -i`, `node -e …writeFile`) to paths outside their
  worktree (best effort), plus a SubagentStop check that diffs mtimes of .openforge/ and governance paths.

## B-4 (HIGH) Gateway reachable without CloudFront: the trusted-hop rule can be bypassed (from F-20261119-03 ADR 0002 review)
ALB is internet-facing; the CloudFront-only origin prefix list is optional and fails open (deploy stages.sh:316-327);
no secret origin header; gateway NetworkPolicy admits the whole VPC. A client that hits the ALB directly controls
the entry N from the right of X-Forwarded-For, so X-Internal-Client-Ip (audit, wallet limit, login throttle, referral)
and the rate-limit key can be forged on that path. Fix: make the prefix list mandatory (fail closed) and/or a
CloudFront secret origin header verified at the gateway; narrow the NetworkPolicy. Needs AWS testing (Owner).

- **G-7 (OpenForge, low)**: `forge-verify --changed` defaults its base to `merge-base HEAD origin/dev`, not the objective's `state.json` `base_sha`. On stacked forge branches this checks the whole repo diff (10k files) and fails for unrelated reasons. Fix: read `base_sha` from the work folder when run inside a worktree. Governance file, so the Owner must approve. Also: from inside a worktree the hook's gradle path does not resolve (no .openforge/scripts there), which gives exit 127. Run it from the main checkout with `--dir <worktree>`; engineers need this in their brief.

- **B-5 (found in O-10)**: order-service tests did not compile at the run base fcf6f2a2. The cause is an ambiguous `enrich` lambda in DependencyBreakerCapabilityTest:68, introduced in 778d102. Any earlier "verify" that skipped order-service could not have caught this, and CI on dev should be checked. It is fixed on the F-05 branch only (a `(Client)` cast). Other branches still carry it until F-05 merges.
- claimUncaptured (found in O-04 review): add a partial index on payments(authorized_at) WHERE capture_alerted_at IS NULL AND mode='RAZORPAY' if the 5-minute watch scan gets slow.
- **B-6 (found in O-10 security review, pre-existing)**: inventory `WarehouseService.setStock`, the internal path from product-service, passes anyWarehouse=true. A seller can therefore name another tenant's or a platform warehouse code, and their own row gets placed at that warehouse. Fix: enforce warehouse ownership unless sellerId is PLATFORM.
- **B-7 (found in O-10 security review, pre-existing)**: bulk CSV row errors echo raw RuntimeException messages, which can include SQL or constraint text, to sellers. Map them to a generic error plus a correlation id.
- auth-service application.yml has no default for server.forward-headers-strategy (found in B-1 security review r3, defence in depth). Only the Helm chart protects auth. Pin `${SERVER_FORWARD_HEADERS_STRATEGY:none}` as the yml default. Not reachable with the current chart.

- **G-8 (OpenForge, medium) — FIXED 2026-11-20 (Owner-approved; forge-gate pr_split + hooks test)**: forge-gate cannot check stacked PRs. It measures size as `state.base_sha..HEAD` and expects one review.json for the head, so a class-L objective delivered as the constitution's "split into PRs under 600" always fails the gate. Seen on F-04: 733 total, with parts of about 213 and 520. Fix: support `state.prs: [{base, head, review, security}]` and check each part's size and verdicts. This is a governance file, so the Owner must approve. Until then, the only path is an Owner-written 'Owner override' line in complexity.md.
- **B-8 (found in F-08 design)**: the 11 downstream services validate JWTs but have no Redis, so a pod inside the namespace that holds a revoked access token can replay it directly to a service for up to the 10 m TTL. External traffic is covered by the gateway check. Options: a shorter TTL, a shared revocation check in libs, or a NetworkPolicy so only the gateway can reach services.
- **B-9 (found in F-06 design)**: OTP-only and social-only users have no user_credentials row, so failed OTP attempts never lock their account. Per-phone caps limit guessing to at most 50 tries a day against 10^6 codes. A real fix needs lockout state that does not depend on user_credentials (migration or redesign). The CTO rejected a second Redis lock store.
- **G-9 (OpenForge, low)**: there are two ADR number spaces. OpenForge writes ADRs to .openforge/knowledge/decisions/ (0002-0007), and the repo has its own docs/architecture/adr/ (0001-0006). The F-07 engineer copied OpenForge ADR 0007 into the repo as docs/architecture/adr/0007; the number happens not to collide, but the two series will diverge. Pick one home for product ADRs.
- SMS pumping via add-phone (found in F-06 security review): POST /api/auth/me/phone sends SMS to any unverified number, with only a 30 s per-user cooldown and the per-IP limit. Route it through the SharedWindowCounter caps once F-06 and F-07 have merged.
- inventory InternalReservationController trusts a caller-supplied sellerId within /internal/** (found in O-10 security review). Hardening: when sellerId==PLATFORM and the offerId has no rows, confirm ownership through SellerDirectory.
- F-08 follow-ups (approved with minors, deferred to avoid another review round): add a 2 s skew allowance to the cutoff, or document an NTP/max-skew alert; require maxmemory-policy noeviction for access-deny keys and count parse failures; rate-limit the deny-check WARN during Redis outages; make the end-to-end test assert the cutoff advances (isGreaterThan).
- Lockout re-lock after expiry (found in F-06 security review r2): failedAttempts is reset only on success, so one wrong attempt right after each expiry starts a new 15 m lock, on both the password and OTP paths. Fix the docs wording ('never permanent' overstates it) and consider decaying failedAttempts after expiry.
- **X-1 — DECIDED 2026-11-20 by Owner: noeviction + memory alerts** (conflict between objectives, found by CEO): F-06 recommends an evicting maxmemory policy, so OTP and limiter keys cannot fill Redis. F-08 recommends noeviction, so access-deny entries are never silently dropped. They share one Redis, and the two policies conflict: every one of these keys has a TTL, so volatile-* would evict deny entries too. Options: (a) noeviction plus memory alerts and a hard cap on OTP key creation; (b) a separate Redis logical DB is not enough, because maxmemory is per instance; (c) a separate small Redis for access-deny. Owner or CTO decision.
- F-07 follow-ups (found in security review, approved with minors): add a per-requester daily cap on add-phone and claim notices (mass-mail abuse); a short Redis command timeout for PhoneClaimNotifier (Lettuce default is 60 s); an out-of-band 'password set' signal (account page or first-login alert). Also: F-07's design claim that it 'cuts' SMS cost is wrong; SMS pumping is unchanged.
- O-10 follow-ups (approved with minors): reject owner-less offer lines at placement (PRODUCT_UNAVAILABLE) instead of booking them to PLATFORM; revert the dead Order.attach al.sellerId branch and the post-save tagOffer; only record sellerName when the owner is known. All are unreachable while offers.seller_id is NOT NULL.
- **B-10 (found in F-09 design, CTO condition 1)**: COD orders with no payment row (OrderService.java:406-411) cannot be refunded once F-09 removes the direct wallet fallback. The refund fails loudly and the return stays PICKED_UP. Fix: make sure every COD order has a payment row (backfill plus creation at placement).
- Spotless reformats OrderService.cartFingerprint and OrderPlacementIntegrationTest on every order-service build, in every worktree. The base is not spotless-clean, so every build leaves noise. Fix: one small objective that applies spotless to those two files on dev.
- F-14 follow-up (after F-13 merges): seller-service ledger_shipments records CANCELLED as terminal, and recordDelivered skips cancelled shipments (F-14 AC-4 / F-13 AC-10).
- F-14 follow-up: after a partial shipment cancel on an uncollected COD order, the COD payment row and collect amount stay at total - wallet, so the cash due is overstated. Adjust the COD due amount (the F-15 COD row owns it). Source: review-A-a764802 minor.
- F-14 follow-up: ship-cancel-* card refunds that stay PENDING and later settle FAILED are recorded as refunded, with no settlement watch. Reuse the return-refund poller or add an alert. Source: security-A-a764802 minor.
- F-14 follow-up: seller cancel free text is shown in the customer timeline. Consider a fixed customer-facing note. Source: security-A-a764802 minor.
- F-14 follow-up: a FAILED shipment cancel blocked by the r3 ship guard can stay stuck if the card refund keeps being declined. Add a runbook entry, then an admin wallet fallback (refundTo=WALLET under the next -r<n> key). Source: security-A-0727afe minor.
- F-14 follow-up: requestReturn locks the order row before the ownership check (negligible; lock with an owner predicate). Source: security-B-b4c7f44 minor.
- F-14 B3 follow-ups (CEO-recorded): (1) AC-4 seller-service guard so a CANCELLED shipment is terminal and a later DELIVERED is not booked (after F-13); (2) promotion coins computed on totalAmount after a partial cancel; (3) inventory release warehouse split on a shipment cancel; (4) settlement watch for PENDING ship-cancel card refunds (already listed); (5) admin force-complete or refund-to-wallet action for a cancel whose card refund keeps being declined (already listed).
- F-14 follow-up: test the return arm of the FAILED-ship guard (FAILED S1 refused 409 when a return holds the shipping). Source: review-B-57e20e6 minor.
- F-16 follow-ups (seller ledger, approved minors): (1) register upsert and the recordDelivered CANCELLED skip should check ledger_shipments.order_id matches the event's order, so a forged or buggy cross-order CANCELLED cannot suppress another order's SALE (security); (2) reconcile should check CANCELLED rows with no SALE against order-service past grace, so a wrongly suppressed SALE is reported (security); (3) legacy DELIVERED should call track first so the FOR SHARE lock has a row, or document the orderId partition-key dependency (security + review); (4) deterministic interleaving test for the FOR SHARE guard (review).

## From F-20261119-17 (gateway route/authz contract, 2026-11-25)
- **Edge role rule for /api/seller/\*\*** (security minor, CEO ruling Q1): the gateway checks only authentication; a CUSTOMER token reaches seller ledger/payout APIs, which enforce SELLER themselves. Add `.pathMatchers("/api/seller/**").hasAnyAuthority("ROLE_SELLER","ROLE_ADMIN")` before `anyExchange()`, then flip `sellerPathsAreAuthenticatedOnlyAtTheEdge` to 403 and add the rule to EXPECTED_ROLE_RULES. Production change; needs CEO/Owner sign-off.
- **Rate-limit coverage for abusable public endpoints:** assert that POST /api/support/chat, /api/auth/otp/request, /api/auth/password/forgot, ad click/impression beacons and POST /api/products/*/views are covered by the gateway limiter (the contract test runs with the limiter off).

## From F-20261119-18 (storefront checkout tests, 2026-11-25)
- **Checkout Idempotency-Key rotation on ambiguous 4xx (money path, PRODUCTION):** `CheckoutPage.tsx` (~line 359) drops the key on any status < 500, including 408, 409 and 429, where the first request may already have been processed. A retry with a fresh key could create a second order or charge. Keep the key for 408, 409 (except known business rejections such as OUT_OF_STOCK or PAYMENT_METHOD_UNAVAILABLE) and 429; add tests. Check order-service's idempotency replies first.
- **Checkout route guard:** CheckoutPage has no auth gate of its own; login-required is covered only via the 4xx place failure.

- GOVERNANCE PROPOSAL (needs Owner approval, edits .claude/hooks/): make forge-verify run `npm run lint` (and tsc) for changed frontend apps, matching CI ci.yml:152. Reason: F-18 passed the gate with a lint error.
