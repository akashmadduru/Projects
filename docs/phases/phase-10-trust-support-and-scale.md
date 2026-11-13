# Phase 10: Trust, support and scale

**Status (2026-11-14): built in code, prototype-grade, no automated tests, not yet documented at
build time.** This page was written after the fact by reading the code. No end-to-end run of
Phase 10 has been recorded. Treat every row as "code exists", not as "verified working".
Much of it was uncommitted when the [technical audit](../audit/technical-audit.md) ran.

**New service:** support-service (:8092, database `support_db`, Flyway `V1__support_baseline`, `V2__faq_seed`).
**New env vars** (`infra/.env`): `SUPPORT_DB_PASSWORD`, and `AUTH_DATA_KEY` (encrypts TOTP secrets in auth-service).
**Tracing:** Micrometer Tracing → OpenTelemetry → OTLP to Jaeger (UI at http://127.0.0.1:16686), set by `OTEL_TRACES_ENDPOINT`.

## Features

| # | Feature | What exists in the code | Where |
|---|---|---|---|
| 1 | Customer support | Help-centre articles with feedback; order-linked tickets with messages, close and CSAT; a chatbot endpoint; admin ticket queue (assign, reply, status) and article CMS | support-service `/api/support/{articles,tickets,chat}`, `/api/admin/support/**`; admin `SupportTicketsView`, `SupportArticlesView` |
| 2 | Two-factor authentication and login alerts | TOTP setup, enable, disable, status and verify (`/2fa/*`); MFA challenge on login; session list and revoke (one or all others); new-session email alert (`LoginAlertService`) | auth-service `TwoFactorController`, `TwoFactorService`, `UserTotp` |
| 3 | Fraud and abuse checks | Order risk score (`RiskService`): weighted velocity, high average, new account and COD-refusal signals. A score of 70 or more blocks COD; 90 or more holds the order. Return and rating fraud flags. | order-service `RiskService`, `RiskBlockedException`; rating-service `V5__rating_fraud_flags` |
| 4 | Admin RBAC and audit log | New roles `SUPPORT` and `FINANCE`; `audit.events` consumed by support-service; `GET /api/admin/audit` | `common-security` `Roles`; support-service `AuditConsumer`, `AuditController`; admin `AuditLogView` |
| 5 | Privacy controls | Data export as a job with download, and account deletion with an emailed code and a cancel window (`/api/users/me/export`, `/api/users/me/delete`). user-service fans out to `/internal/privacy/users/{id}/export` in 9 services; a `UserDeletedListener` in each service reacts to `user.events`. Storefront cookie consent. | user-service `PrivacyController`, `PrivacyService`; storefront `lib/consent.ts` |
| 6 | Multi-language and multi-currency | Hindi locale (`src/locales/hi.ts`); FX rates (`GET /api/products/fx-rates`, admin `PUT /api/admin/fx-rates`) | product-service `FxRateController`; admin `FxRatesView` |
| 7 | Progressive Web App | `manifest.webmanifest`, service worker `sw.js`, `offline.html`, maskable icon | `apps/product-catalog/public/` |
| 8 | Image optimisation | `ProductImage` component (responsive and lazy-loaded images) | storefront `components/ProductImage.tsx` |
| 9 | Accessibility (WCAG 2.2 AA) | No audit record found | — |
| 10 | Observability and reliability | OpenTelemetry tracing to Jaeger; Kafka dead-letter handlers (`DlqErrorHandlers` in `common-web`); auth rate limiting (`RateLimitFilter`, Bucket4j) | `gradle/libs.versions.toml` `tracing` bundle; `infra/docker-compose.yml` `jaeger` |
| 11 | Exchange offers | Trade-in quote and verification: promotion-service quotes, order-service applies it; admin verifies | promotion-service `ExchangeController`; order-service `AdminExchangeController`; storefront `ExchangeOffer.tsx`; admin `ExchangeVerifyView` |

## Known gaps

- No tests, and no recorded integration run for any Phase 10 feature.
- Gateway rate limiting is still missing; the Bucket4j limit in auth-service is per instance.
- TOTP and seller bank secrets use static AES keys from env vars (no KMS, no rotation; TD-P9-09).
- `/actuator/prometheus` is unauthenticated inside the network.
- The [technical audit](../audit/technical-audit.md#8-security-audit) lists the security findings in priority order.
