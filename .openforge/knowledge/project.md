# Project facts (for OpenForge agents)

Keep this file short: it is read by most roles. Detail lives in `docs/`.

## Layout
| Path | What |
|---|---|
| `services/<name>/<name>-app/` | Spring Boot 4 services (Java 25). API DTOs in `<name>-api/`. |
| `services/gateway/` | Spring Cloud Gateway (reactive). |
| `libs/common-core`, `libs/common-web`, `libs/common-security` | shared code (errors, Kafka DLQ, JobLock, audit, JWT validators). |
| `apps/product-catalog` | React + Zustand storefront, vitest, `npx tsc --noEmit`. |
| `apps/admin-product-catalog` | Vue 3 admin, vitest, `npx vue-tsc --noEmit`. |
| `docs/` | architecture, ADRs, reference (api.md, endpoints.md, events.md, environment-variables.md), tech-debt register. |
| `deploy/local/docker-compose.yml`, `infra/` | local stack, AWS (Terraform + Helm). |

## Build and test
- Java: `bash .openforge/scripts/gradle.sh :services:<svc>:<svc>-app:test` (docker; formats first; prints `gradle_rc=`). One build at a time. Testcontainers bases exist for order, cart, user, payment, promotion, seller.
- Spotless runs per `-app` module (`:services:x:x-app:spotlessApply`), and on libs.
- Storefront: `cd apps/product-catalog && npx vitest run && npx tsc --noEmit`.
- Admin: `cd apps/admin-product-catalog && npx vitest run && npx vue-tsc --noEmit`.

## Conventions
- Flyway is forward-only: add `V<n+1>__*.sql`, never edit an applied migration.
- Jackson 2 (not 3). JdbcClient or JPA. Scheduled jobs that change data run under `JobLock`.
- Kafka listeners let failures propagate to `DlqErrorHandlers` (3 retries, then `.DLT`).
- Internal endpoints `/internal/**` use `X-Internal-Token` and are never routed by the gateway.
- Every new endpoint: update `docs/reference/endpoints.md` (and `api.md` for public ones).
- Tech debt: fixing an item removes it from `docs/techdebt/` and adds a row to `remediation-plan.md`.

## Environment quirks
- Windows + Git Bash. Use the upper-case drive letter for vitest (handled in the storefront config).
- `gh` CLI is not installed; GitHub settings are done by the Owner in the web UI.
