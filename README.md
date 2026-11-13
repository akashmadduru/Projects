# Cartwise e-commerce platform

A Spring Boot microservices marketplace with a React storefront and a Vue admin console, built as a
Gradle multi-project monorepo.

> **State (2026-11-14):** Phases 1–9 built; Phase 10 (support, 2FA, fraud checks, privacy, i18n, PWA,
> tracing) is in the code but unverified. Everything from Phase 5 on is **prototype-grade with no
> automated tests**, by decision. Payments have only run in SIMULATED mode.
> See [phases](docs/phases/README.md), the [tech debt register](docs/techdebt/README.md) and the
> [technical audit](docs/audit/technical-audit.md).

**All documentation is in [`docs/`](docs/README.md).** Start with [getting started](docs/getting-started.md).

## Quick start

```bash
cp infra/.env.example infra/.env && ./infra/scripts/generate-keystore.sh
docker compose -f infra/docker-compose.yml up -d --build
cd apps/product-catalog && npm install && npm run dev        # http://localhost:3000
cd apps/admin-product-catalog && npm install && npm run dev  # http://localhost:5173
```

## Stack

| Layer | Technology |
|---|---|
| Language / build | Java 25, Gradle 9.6 (Groovy DSL, version catalog) |
| Framework | Spring Boot 4.0, Spring Cloud 2025.1 (Gateway, Eureka, OpenFeign), Spring Security (OAuth2 resource server, RS256 JWT) |
| Data | PostgreSQL 17 (one database per service), Flyway, Spring Data JPA, Redis 7 |
| Events | Apache Kafka 3.9 (KRaft, single broker): `product`, `inventory`, `rating`, `order`, `user`, `audit` events |
| Observability | Micrometer, OpenTelemetry tracing to Jaeger |
| Storefront | React 19, Vite 6, TypeScript, Tailwind 4, Zustand, Vitest + MSW (`apps/product-catalog`) |
| Admin | Vue 3, Vite, TypeScript, Tailwind (`apps/admin-product-catalog`) |
| Delivery | GitHub Actions, GHCR images; Terraform + EKS + Helm under `deploy/aws` |

## Repository layout

```
├── apps/            product-catalog (storefront), admin-product-catalog (admin)   — not Gradle modules
├── libs/            common-core, common-web, common-security
├── services/        gateway, eureka-server and 13 domain services (most split into -api / -app)
├── infra/           docker-compose.yml, postgres init SQL, dev scripts
├── deploy/aws/      Terraform, Helm chart, Dockerfiles, deploy.sh
├── postman/         Postman collection and local environment
├── config/          Checkstyle rules
├── gradle/          version catalog and wrapper
└── docs/            all documentation
```

## Conventions

- Package root `com.acme.ecommerce.<module>`; controller → service → repository.
- `@Transactional` at the service layer, one unit of work.
- Throw a typed `ApplicationException`; errors are RFC 9457 problem+json with stable `ErrorCode`s.
- Inject `TimeProvider`; never call `Instant.now()` in domain code.
- No secrets in `application.yml`; every schema change ships with its Flyway migration.
- Run `./gradlew spotlessApply` before committing; CI enforces formatting.
