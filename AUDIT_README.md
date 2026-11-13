# Technical Audit: ecommerce-platform (branch `version_v1`)

Review-only audit. No code was modified. Every finding comes from reading files in this repository.

> **Snapshot caveat:** Phase 10 work was being edited while this audit ran (2FA gateway routes and `AUTH_DATA_KEY` wiring appeared mid-audit; support-service grew from 4 to 17 Java files). There were 188 uncommitted or untracked paths. Treat Phase 10 findings as a snapshot of work in progress.

---

## A. Current Architecture

### What exists
A Java 25 / Spring Boot 4.0.7 / Spring Cloud 2025.1.2 Gradle monorepo: 14 backend services, 3 shared libraries (`libs/`), 2 single-page apps (React storefront, Vue admin and seller portal). The repo keeps its own tech-debt register in `techdebt/`.

```
        Browser: React storefront (:3000)  /  Vue admin + seller portal (:3001)
                          |  Bearer JWT (memory only) + HttpOnly refresh cookie
                          v
   +-----------------------------------------------+
   | gateway :8080 (Spring Cloud Gateway, WebFlux)  |  JWT check (JWKS), CORS, header
   | Resilience4j circuit breaker on every route    |  sanitising, correlation id
   +----------------------+------------------------+
                          | lb://SERVICE-ID   (eureka-server :8761)
 +--------+--------+------+-----+--------+---------+---------+
 auth 8081  product 8082  inventory 8083  rating 8084  user 8085  cart 8086
 order 8087  payment 8088  promotion 8089  notification 8090  seller 8091  support 8092
          |  every service re-checks the JWT; /internal/** uses a shared X-Internal-Token
          v
 PostgreSQL 17 (one instance, 12 databases, one least-privilege role each)
 Kafka 3.9 KRaft (1 broker) | Redis 7 (auth only) | Mailpit (dev) | ZooKeeper (unused)
```

### Service dependencies
**Synchronous (OpenFeign via Eureka):**
- order -> cart, user, product, inventory, promotion, payment, seller (checkout runs these sequentially with manual compensation)
- payment -> order, notification, user
- rating -> order, user
- support -> order, notification
- auth -> notification

**Events (Kafka, published after commit, best-effort):**

| Topic | Producer | Consumers |
|---|---|---|
| `product.events` | product | inventory, cart (alerts) |
| `inventory.events` (3 partitions) | inventory | product, cart (back-in-stock) |
| `rating.events` | rating | product |
| `order.events` | order | notification, promotion (coins), seller (ledger) |
| `audit.events` | auth | none yet |

There is no outbox and no dead-letter topic anywhere (`order/event/OrderEventPublisher.java:17`).

### Databases
Database-per-service, no cross-database foreign keys (`infra/postgres/init/01-schemas.sql`). Flyway with `validate-on-migrate: true`, `ddl-auto: none`, `open-in-view: false` everywhere. Cross-service data is plain UUIDs plus price/address snapshots.

### Authentication flow
1. auth-service signs RS256 JWTs (10 min TTL) from a PKCS#12 keystore; multiple `kid`s supported for rotation (`JwtKeyProvider.java`).
2. Refresh tokens: 14 days, rotation with family reuse detection, HttpOnly cookie.
3. argon2id passwords, account lockout, Google OIDC, phone OTP.
4. Gateway and every service validate issuer, audience and signature against cached JWKS.
5. Gateway allows admin-family roles into `/api/admin/**` (`gateway SecurityConfig.java:191`); each service narrows with `@PreAuthorize` (verified on payment, rating, promotion, order, seller, inventory, product admin controllers).

### Per-service summary

| Service | Responsibility | Main / test files | Notable gaps |
|---|---|---|---|
| gateway | Edge routing, JWT, CORS, circuit breakers | 17 / 8 | No rate limiting; no forwarded-header config for an ALB |
| eureka-server | Registry | 2 / 1 | Single instance, unauthenticated |
| auth | Login, OIDC, OTP, refresh, JWKS, admin users, 2FA (in progress) | 86 / 14 | Rate limiting in-memory per instance |
| product | Catalog, search (Postgres FTS + pg_trgm), offers, buy box | 61 / 11 | One stale test; seller listings unmoderated (TD-P9-03) |
| inventory | Stock, warehouses, reservations | 36 / 8 | Two sources of truth for stock (TD-P9-02) |
| rating | Reviews, Q&A, media | 29 / 0 | Media on local disk (`/data/media`) |
| user | Profiles, addresses, membership | 20 / 0 | No tests |
| cart | Cart, wishlist, lists, alerts | 22 / 0 | No tests |
| order | Checkout, returns, shipments, invoices, subscriptions, risk | 56 / 0 | Largest, riskiest service; no tests |
| payment | Stripe/simulated payments, refunds, wallet, gift cards | 22 / 0 | See section 3 |
| promotion | Coupons, deals, coins, exchange | 24 / 0 | No tests |
| notification | Email, web push, inbox, campaigns, "SMS" | 23 / 0 | SMS is a stub that logs message text |
| seller | KYC, ledger, payouts (simulated), ads | 23 / 0 | Fake payouts; ledger misses pre-start events |
| support | Help centre; tickets/chat/audit in progress | 17 / 0 | Untracked, in progress |

Common to all 14: multi-stage Dockerfile, layered jar, `eclipse-temurin:25-jre-alpine`, non-root, `exec java` as PID 1, `MaxRAMPercentage=75`, actuator liveness/readiness, Prometheus endpoint, graceful shutdown, virtual threads, Hikari pool of 10.

---

## B. Implementation Status Matrix

| Feature | Current status | Missing | Priority |
|---|---|---|---|
| Payment gateway | Partial. Stripe PaymentIntents, webhook, refunds, COD, wallet. Never run live (TD-P6-02). No Razorpay. | Provider abstraction, Razorpay adapter, schema changes, audit log, reconciliation | P0 |
| AWS integration | Missing. No AWS SDK or config. | Everything in section 4 | P1 |
| Docker | Mostly done. 16 Dockerfiles; compose covers backends. | Frontends commented out of compose; `http-server` frontend images; every image rebuilds the whole tree | P1 |
| Kubernetes | Missing. No manifests, Helm or Kustomize. | Section 5 | P1 |
| HPA | Missing | Section 6 | P2 |
| Node autoscaling | Missing | Karpenter or Cluster Autoscaler | P2 |
| Terraform | Missing | Section 7 | P1 |
| Ansible | Missing | Mostly not needed | P3 |
| Monitoring | Partial. Prometheus endpoints, correlation ids. | No Prometheus/Grafana, no tracing bridge, no JSON logs, no alerts | P1 |
| CI/CD | Effectively missing. `.github/workflows/gradle.yml:12` triggers on `master`; branch is `main`. | Frontend jobs, image build/push, scanning, deploy | P0 |
| Security | Good foundation, real gaps | Section 8 | P0 |
| Testing | Weak. 9 of 14 services have no tests; last integration runs failed (no Docker). | Section 9 | P0 |
| Multi-cloud | Not started. Code is cloud-neutral (Postgres, Kafka, Redis, SMTP). | Storage and secrets abstractions | P3 |

---

## 3. Razorpay Readiness

**Ownership:** keep payments in **payment-service**. It already has its own database, an internal create API called by order-service, webhook dedup, idempotent refunds, row locks, and a re-report job. No new service is needed.

### What blocks Razorpay today
1. **Stripe is wired into business logic.** `PaymentService.java:13-16` imports `com.stripe.model.Event`, `PaymentIntent`, `Webhook`. `Stripe.apiKey` is a static global.
2. **Stripe-shaped schema.** `V1__payment_baseline.sql:9-16`: `method IN ('CARD','COD')` (no UPI/netbanking), `mode IN ('STRIPE','SIMULATED')`, `stripe_payment_intent_id` column. Refunds have no status; Razorpay refunds complete asynchronously.
3. **Storefront uses Stripe Elements** (`apps/product-catalog/src/components/PaymentPanel.tsx:8`).

### Bugs to fix regardless of provider
- **Critical: simulated mode fails open.** If `STRIPE_SECRET_KEY` is blank, the service silently runs SIMULATED. `POST /api/payments/{id}/simulate` (`PaymentService.java:113`) lets a customer mark their own card payment SUCCEEDED. The `prod` profile does not require the key (`application.yml:223`).
- **High: webhook event can be recorded, then lost.** Event id is committed (line 170) before the outcome is applied (line 188), not in one transaction. A retry is then treated as a duplicate.
- **High: webhooks do not check amount or currency** against the stored payment.
- **Medium: refund can be lost locally.** The provider refund (line 216) runs before the DB transaction; a later failure leaves money returned with no local record.
- **High: late capture after cancel** is not auto-refunded (TD-P6-03).

### Required design
- **Provider port:** `PaymentProvider` interface (`createOrder`, `verifyCheckoutSignature`, `fetchPayment`, `refund`, `parseWebhook`) with `StripeProvider` and `RazorpayProvider`. `PaymentService` sees no SDK types.
- **Migration `V3__provider_agnostic.sql`:**
  - add `provider`, `provider_order_id`, `provider_payment_id`, `provider_refund_id` (backfill from Stripe column)
  - widen `method` (CARD/UPI/NETBANKING/WALLET_EXT/COD) and `mode` (add RAZORPAY)
  - refund `status` (PENDING/PROCESSED/FAILED)
  - provider and status on `processed_events`
  - append-only `payment_audit_log` (actor, source api/webhook/reconcile, payload hash; no card or VPA data)
- **Order flow:**
  1. order-service calls `/internal/payments` (unchanged)
  2. Razorpay `POST /v1/orders` (paise, `receipt=orderId`, `notes.orderId`)
  3. return `razorpay_order_id` and `key_id`
  4. browser opens Checkout.js
  5. browser calls `POST /api/payments/{id}/razorpay/verify` with order id, payment id, signature
  6. server verifies HMAC-SHA256(`order_id|payment_id`, key_secret) with constant-time compare, then fetches the payment from Razorpay and asserts `captured` plus matching amount and currency
- **Webhook:** `POST /api/payments/webhook/razorpay`. Verify `X-Razorpay-Signature` over the raw body, dedupe on `x-razorpay-event-id` in the same transaction as the state change. Handle `payment.captured`, `payment.failed`, `order.paid`, `refund.processed`, `refund.failed`.
- **Reconciler:** poll Razorpay for payments stuck in REQUIRES_PAYMENT; needs a distributed lock.
- **Gateway:** public route for the Razorpay webhook; allow `checkout.razorpay.com` in CSP.
- **Secrets:** `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET` from Secrets Manager; startup fails under `prod` if the provider is unconfigured.
- Seller payouts via RazorpayX are a separate later decision (TD-P9-08).

---

## 4. AWS Readiness

| AWS service | Status | Reason |
|---|---|---|
| EKS | Missing | No manifests. Images are compatible (non-root, probes, graceful shutdown). |
| ECR | Missing | No push pipeline. Dockerfiles are ready. |
| RDS PostgreSQL | Partial | Database-per-service with roles and Flyway maps onto one Multi-AZ RDS. Missing TLS (`verify-full`), Secrets Manager credentials, RDS Proxy (14 services x replicas x 10 connections). |
| S3 | Missing | Rating media written to local disk (`ReviewMediaService.java:84`). Also needed for SPA bundles. |
| ElastiCache Redis | Partial | Only auth uses Redis. Needs TLS/auth config; useful for gateway rate limiting and scheduler locks. |
| ALB | Missing | One ALB via AWS Load Balancer Controller to gateway. Gateway must trust ALB `X-Forwarded-For`; auth deliberately does not trust it directly. |
| Route 53 | Missing | Records for shop, admin, api. |
| CloudFront | Missing | Serve SPAs from S3 instead of `http-server` containers. |
| Secrets Manager | Missing | Everything is env vars; JWT keystore is a mounted file. Use External Secrets Operator or CSI driver. |
| IAM | Missing | IRSA or Pod Identity per service (S3, SES, Secrets Manager). |
| CloudWatch | Missing | Container Insights and log shipping. Logs are plain text. |
| SQS/SNS | Not required | Kafka already carries all events; SQS would add a second messaging system. Use MSK or self-managed Kafka. Code needs SASL/TLS properties; Kafka config is hand-wired per service. |
| SES | Missing (easy) | Notification uses SMTP; point it at SES SMTP. |
| VPC | Missing | 3 AZs; public subnets for ALB only; private for EKS, RDS, MSK, ElastiCache; VPC endpoints for ECR, S3, Secrets Manager. |

---

## 5. Docker and Kubernetes Readiness

**Already in place:** multi-stage builds, layered jars, non-root, `exec` PID 1, graceful shutdown, liveness/readiness probes, env-var config, no baked secrets (`.dockerignore` excludes `infra/secrets/` and `*.p12`).

**Gaps:**
- Every image runs `COPY . .` and a full Gradle bootJar: 14 images each rebuild the tree. Build jars once in CI and use thin runtime Dockerfiles (or Jib).
- `spring.lifecycle.timeout-per-shutdown-phase` is set nowhere. Kubernetes also needs a `preStop` sleep of 5-10s.
- Frontend images use `node:20` + `http-server --cors`, no security headers; `VITE_*` URLs baked at build time (`admin http.ts:70`). On AWS use S3 + CloudFront, otherwise nginx-unprivileged.
- Eureka should be replaced by Kubernetes Service DNS (needs approval).
- Nothing for Kubernetes exists. Recommended layout:
  - `deploy/helm/platform-service/`: one shared chart (Deployment, Service, HPA, PDB, ServiceAccount, ConfigMap, ExternalSecret, NetworkPolicy, ServiceMonitor)
  - `deploy/helm/values/<service>.yaml` (14 small files) and `values/env/{dev,staging,prod}.yaml`
  - Ingress for the gateway only; namespaces `ecommerce-{env}`
  - Default-deny NetworkPolicy plus explicit allows (essential, since `/internal/**` relies on one shared token)
  - Persistent volumes: none once media moves to S3; Postgres, Kafka, Redis stay managed

---

## 6. Scaling

**Blockers to running more than one replica:**
1. All 8 `@Scheduled` jobs have no lock. *(Correction: an earlier version of this report said `RefreshTokenReaper` used an advisory lock; that was a misread of a comment. It is idempotent, so harmless when it runs on every replica, but unlocked. All 8 are now guarded by `JobLock`, see ADR 0004.)* The subscription job is safe because its idempotency key is deterministic. Unsafe: ads, reminders, membership, discovery, order lifecycle, payment reporter. Add ShedLock on a JDBC table.
2. Rating media on local disk.
3. Auth rate limiting is in-memory, so the limit multiplies with replicas.
4. Kafka consumers are capped by partitions; only `inventory.events` declares 3.

**Starting configuration** (JVM services: CPU request 300-500m; memory request = limit at 640-768Mi; startup probe allowing ~90s):

| Service | Min/Max | Scale on | Notes |
|---|---|---|---|
| gateway | 3 / 12 | CPU 60% | PDB minAvailable 2 |
| auth | 2 / 6 | CPU 65% | argon2id is CPU-heavy |
| product | 3 / 12 | CPU 65% | Read-heavy; cache candidate |
| inventory | 2 / 6 | CPU 70% | Limited by row-lock contention |
| cart | 2 / 8 | CPU 70% | |
| order | 2 / 8 | CPU 70% | Long fan-out; watch DB pool |
| payment | 2 / 4 | CPU 70% | PDB minAvailable 1 |
| promotion, user, rating | 2 / 4 | CPU 70% | |
| notification | 1 / 6 | Kafka lag (KEDA) | Max <= partitions |
| seller | 1 / 3 | Kafka lag (KEDA) | Ledger consumer |
| support | 1 / 3 | CPU | |

- Scale-down: `stabilizationWindowSeconds: 300`, max 50% per minute. Scale-up: up to 100% per 30s.
- Nodes: Karpenter; on-demand for payment, order, auth; spot for the rest; `topologySpreadConstraints` across 3 AZs.
- Manual override / suspend: Helm `hpa.enabled` or `minReplicas == maxReplicas`, or KEDA `autoscaling.keda.sh/paused-replicas`. Both stay in git.
- DB connections: 14 services x max replicas x 10 exceeds RDS limits; use RDS Proxy or per-service budgets.

---

## 7. Terraform and Ansible

**Terraform provisions all AWS resources:**
- Modules: `network`, `eks` (Karpenter, add-ons, Pod Identity), `ecr` (14 repos, scan on push), `rds` (Multi-AZ PG17, `rds.force_ssl`, Proxy), `elasticache`, `msk` (or skip if Kafka is in-cluster), `s3_cdn`, `dns_acm`, `secrets`, `iam_workloads`, `observability`.
- Environments `envs/{dev,staging,prod}`, S3 backend with locking per environment.
- Portability: separate modules per cloud behind the same capability interfaces (AKS, GKE equivalents). Do not attempt one module for three clouds.

**Ansible, kept narrow:**
- Bastion/SSM host hardening
- One-off Jobs for RDS database/role bootstrap (replacing `01-schemas.sql`, `create-customer-dbs.sql`)
- Self-managed Kafka VMs, only if chosen over MSK
- Developer environment setup

Secrets via `amazon.aws.secretsmanager_secret` lookups, never vaulted copies. Do not build OS-hardening roles for EKS nodes; use Bottlerocket or EKS-optimised AMIs.

---

## 8. Security Audit

### Critical
1. Simulated payment fails open when `STRIPE_SECRET_KEY` is blank (section 3).

### High
2. SMS OTP codes logged in plaintext with phone number: `notification/web/InternalNotificationController.java:81`.
3. DB dumps with credential material committed: `db-snapshots/` (an argon2id hash, 2 refresh-token hashes with IP/user agent, SCRAM verifiers for 4 roles). Purge from history and rotate.
4. One shared static `INTERNAL_API_TOKEN` across all services (TD-P6-12). Any compromised service reaches every `/internal/**` endpoint, including order-service's payment-result endpoint.
5. Webhooks skip amount/currency checks; dedup is not atomic.
6. CI never runs, so there is no dependency or vulnerability gate.

### Medium
7. Eureka unauthenticated.
8. No gateway rate limiting; auth's Bucket4j limit is per instance.
9. Kafka PLAINTEXT, replication factor 1, no dead-letter topics.
10. `/actuator/prometheus` unauthenticated on every service (gateway blocks it externally; no network policy internally).
11. Frontends have no CSP/security headers; `http-server --cors`.
12. Seller bank and TOTP secrets use static AES keys from env vars (no KMS, no rotation; TD-P9-09).
13. Postgres (`docker-compose.yml:38`) and ZooKeeper (`:74`) published on 0.0.0.0 in compose.

### Low
14. Dev DB passwords committed in init SQL (documented as throwaway, TD-SEC-03).
15. Non-UUID `sub` claim gives 500 (TD-SEC-12).
16. `CONNECT` not revoked from PUBLIC (TD-SEC-13).

### Strengths (keep)
- No SQL injection found: every dynamic WHERE/ORDER fragment is a constant with bound parameters (seller, payment-admin, inventory, support ticket queries checked).
- RS256 + JWKS + `kid` rotation; argon2id; refresh-token family revocation; lockout.
- Access token in memory only; HttpOnly refresh cookie.
- Gateway strips spoofed identity headers; constant-time token compare; `include-stacktrace: never`; credential-printing loggers pinned to INFO.
- Layered `@PreAuthorize` on admin endpoints.

---

## 9. Testing and Code Quality

**Tests:**
- auth 14 test files, product 11, inventory 8, gateway 8, libs 7. **user, cart, order, payment, promotion, rating, seller, notification, support have none.**
- Integration tests need Docker. The last recorded run (2026-11-11) failed 60+ tests with "Could not find a valid Docker environment".
- Stale assertion: `ProductEndpointsIntegrationTest.java:111-116` expects 401 on a now-public `GET /api/products`.
- Frontends: 15 (storefront) and 10 (admin) spec files; the debt register says some are stale (TD-TEST-03, TD-P9-01).

**Quality:**
- 13 copies each of `SecurityConfig` and `ProblemResponseWriter`, 6 of `InternalTokenFilter`; belongs in `libs/common-security` and `libs/common-web`.
- Kafka consumer factories hand-built per service.
- Error responses are consistent (RFC 7807 Problem JSON, shared `ErrorCode`); page size capped at 100.
- `OrderService.place` is ~300 lines of orchestration with in-process compensation (TD-P6-01, TD-BE-15).
- Stale docs: `IMPLEMENTATION_STATUS.md`, `DOCKER_SUMMARY.md`, `DOCKER_SETUP.md`; `.claude/` agents describe a FastAPI/Python stack that does not match this repo.

---

## C. Files to Create, Modify, Replace, Deprecate

**Modify:**
- `.github/workflows/gradle.yml`: branch `main`, fix "JDK 17" label, add frontend jobs
- `services/payment-service/.../service/PaymentService.java`: provider port, atomic webhook, amount checks
- `.../service/StripeGateway.java`: becomes `StripeProvider`
- `.../config/PaymentProperties.java`, payment `application.yml`: Razorpay settings; prod fails fast without a provider
- `.../web/PaymentController.java`, payment `SecurityConfig.java`: verify and webhook endpoints
- `services/gateway/src/main/resources/application.yml`, gateway `SecurityConfig.java`: webhook route, rate limiter, forwarded headers
- `notification/web/InternalNotificationController.java`: stop logging text; real SMS adapter
- `rating/service/ReviewMediaService.java`: storage port with S3 implementation
- All 14 `application.yml`: shutdown-phase timeout, JSON logging, tracing, Kafka SASL/TLS
- 8 unlocked `@Scheduled` classes: cluster-wide lock (done: `JobLock`, ADR 0004)
- `infra/docker-compose.yml`: remove ZooKeeper, bind Postgres to loopback, enable frontends
- `PaymentPanel.tsx`, storefront `package.json`, `types/account.ts`: Razorpay Checkout
- `ProductEndpointsIntegrationTest.java`: fix stale assertion

**Create:**
- `payment-service/.../provider/{PaymentProvider,RazorpayProvider}.java`
- `payment-service/.../db/migration/V3__provider_agnostic.sql`
- `payment-service/.../service/PaymentReconciler.java`
- Per-service Flyway migration adding the `shedlock` table
- `libs/common-security/.../InternalTokenFilter.java` and shared security auto-config
- `deploy/helm/**`, `infra/terraform/{modules,envs}/**`
- `.github/workflows/{ci-backend,ci-frontend,images,deploy}.yml`
- `deploy/observability/` (Prometheus stack values, Grafana dashboards, alerts)
- Tests: payment (providers, webhook, refund), order (placement and compensation), seller ledger

**Replace:**
- Frontend Dockerfile runtime stages (`http-server`): S3 + CloudFront or nginx-unprivileged
- Eureka discovery with Kubernetes DNS (on approval)

**Deprecate / remove:**
- ZooKeeper container and `infra/zookeeper/` (unused)
- `db-snapshots/` (purge from history)
- `IMPLEMENTATION_STATUS.md`, `DOCKER_SUMMARY.md`, `DOCKER_SETUP.md`
- The simulate endpoint outside dev profiles
- Legacy `stock_levels`/`variant_stock` write path (TD-P9-02)

---

## D. Recommended Target Architecture

- **Keep all 14 services; add none.** Payment stays in payment-service.
- **Edge:** Route 53 -> CloudFront (SPAs from S3, `/api/*` passed through) -> ALB -> gateway; gateway rate limiting backed by Redis.
- **Discovery:** Kubernetes Service DNS (remove Eureka and `lb://`).
- **Data:** RDS PostgreSQL Multi-AZ with RDS Proxy (keep the 12 databases and roles); ElastiCache for auth cache, rate limiting, locks; MSK (3 brokers, RF 3) with an outbox per producer and a DLQ per consumer; S3 for media.
- **Service-to-service:** NetworkPolicies now; mTLS or per-caller service tokens to replace the shared secret.
- **Observability:** OpenTelemetry traces (Micrometer bridge) to Tempo or X-Ray; Prometheus/Grafana; JSON logs to CloudWatch or Loki.
- **Delivery:** GitHub Actions -> ECR -> Helm. Argo CD optional.

---

## E. Implementation Roadmap

| # | Phase | Required work | Depends on | Complexity | Main risk | Acceptance criteria |
|---|---|---|---|---|---|---|
| 1 | Critical fixes | Simulate fails closed; stop OTP logging; purge snapshots and rotate; fix CI trigger; atomic webhook dedup; Testcontainers in CI; fix stale test | none | S-M | History rewrite affects clones | CI green on `main`; prod refuses to start without a provider |
| 2 | Razorpay | Provider port, V3 migration, adapter, verify + webhook, reconciler, audit log, storefront Checkout, tests | 1 | L | Money correctness | Test-mode success, failure, partial refund, duplicate webhook pass end-to-end; amount mismatch rejected |
| 3 | Docker | Build jars once; thin images; frontend runtime; remove ZooKeeper; shutdown timeouts | 1 | M | Build-cache regressions | Images under ~250MB; compose runs everything incl. SPAs |
| 4 | AWS infra | VPC, EKS, ECR, RDS, ElastiCache, MSK, S3/CloudFront, Secrets Manager | 3 + section F decisions | L | Cost; IAM mistakes | `terraform plan` clean per environment |
| 5 | Kubernetes | Helm chart + values, NetworkPolicies, ExternalSecrets, Eureka removal, S3 media | 4 | L | Discovery cutover | All services Ready in staging; default-deny enforced |
| 6 | Scaling | ShedLock, HPA/KEDA, PDBs, Karpenter, AZ spread, connection budget | 5 | M | Duplicate jobs; exhausted DB connections | 3x-replica load test with no duplicate side effects |
| 7 | Terraform automation | Remote state, env split, CI plan/apply | 4 | M | State drift | PR shows plan; apply needs approval |
| 8 | Ansible | DB bootstrap job, bastion hardening only | 4 | S | Over-engineering | Idempotent re-runs |
| 9 | Observability | OpenTelemetry, dashboards, SLO alerts (payment success, consumer lag) | 5 | M | Noisy alerts | Trace gateway -> order -> payment visible |
| 10 | CI/CD | Lint, test, scan, image push, staged deploy | 1, 5 | M | Flaky pipelines | Merge to `main` deploys to staging |
| 11 | Security hardening | Internal auth replacement, gateway rate limits, CSP, KMS keys, Kafka TLS, outbox | 5 | L | Breaking internal calls | Pen-test checklist passes |
| 12 | Azure/GCP | Parallel Terraform modules; storage and secrets abstractions | 7 | L | Maintenance cost | Only if there is a business need |

---

## F. Technical Decisions

- **Keep unchanged:** database-per-service, Flyway conventions, JWT/JWKS design, gateway with per-route circuit breakers, Problem JSON errors, virtual threads, Postgres full-text search (no OpenSearch needed yet), Kafka as the only event bus, Dockerfile runtime pattern.
- **Refactor:** payment provider abstraction; shared security and Kafka config into `libs/`; split `OrderService.place`.
- **Introduce:** outbox and DLQs, ShedLock, S3 storage port, tracing, gateway rate limiting, Helm, Terraform, CI.
- **Remove:** ZooKeeper, Eureka (on Kubernetes), committed snapshots, stale docs, simulate in production.
- **Not needed:** SQS/SNS, OpenSearch, a service mesh on day one, heavy Ansible, a separate payment microservice, GraphQL/BFF.

**Decisions that need approval:**
1. Replace Stripe with Razorpay, or keep both?
2. Eureka -> Kubernetes DNS (recommended), or keep Eureka in-cluster?
3. MSK (managed, costlier) or Strimzi Kafka on EKS?
4. SPAs on S3 + CloudFront (recommended) or as containers?
5. Internal auth: mesh mTLS or signed service JWTs?
6. Rewrite git history to purge `db-snapshots/`?
7. Real SMS provider (MSG91, Twilio, SNS) and real seller payouts (RazorpayX): now or later?
8. Is multi-cloud a real requirement? If not, drop phase 12.

---

## G. Top 20 Changes (priority order)

1. Make SIMULATED payment fail closed; require a provider under `prod`.
2. Stop logging SMS/OTP text (`InternalNotificationController.java:81`).
3. Purge `db-snapshots/` and rotate affected credentials.
4. Fix the CI trigger (`master` -> `main`) and run Testcontainers in CI.
5. Make webhook dedup and state change one transaction; verify amount and currency.
6. Introduce the `PaymentProvider` port and provider-neutral schema (V3).
7. Implement the Razorpay order, verify and webhook flow, plus reconciler and audit log.
8. Auto-refund late captures on cancelled orders (TD-P6-03).
9. Tests for payment and order placement/compensation paths.
10. Replace the shared `INTERNAL_API_TOKEN`; add NetworkPolicies.
11. Transactional outbox for `order.events`.
12. ~~ShedLock on the 7 unlocked scheduled jobs.~~ Done for all 8 with a Postgres advisory lock (`JobLock`).
13. Move rating media to S3.
14. Gateway rate limiting (Redis) and ALB forwarded-header trust.
15. Helm chart + values for 14 services with probes, PDBs, preStop hook.
16. Terraform for VPC, EKS, ECR, RDS (+Proxy), ElastiCache, MSK, S3/CloudFront, Secrets Manager.
17. HPA/KEDA per section 6, plus Karpenter and AZ spread.
18. OpenTelemetry tracing, JSON logs, dashboards, alerts on payment success and consumer lag.
19. Dead-letter topics, Kafka TLS/SASL, replication factor 3.
20. Consolidate duplicated `SecurityConfig` / `ProblemResponseWriter` / `InternalTokenFilter` into `libs/`, and finish the in-progress Phase 10 endpoints (data export/delete, FX rates, tickets, audit consumer) before any deploy.

---

*Awaiting approval before any implementation. Phase 1 has no dependency on the section F decisions.*
