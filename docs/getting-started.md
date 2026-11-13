# Getting started

How to build the platform and run it on one machine. Integration settings (Stripe, email, SMS,
push, Google) are in [setup-integrations.md](guides/integrations.md). Day-two
operations (migrations, health checks, logs, troubleshooting) are in [operations.md](guides/operations.md).

## Prerequisites

- **JDK 25.** Gradle's toolchain support can download one if your default JDK differs.
- **Docker** with Compose, and at least 3 GB of memory for the engine. Below that, the Gradle stage of
  the image build is killed with "Gradle build daemon disappeared unexpectedly".
- **Node.js** for the two SPAs.

## 1. Configure

```bash
cp infra/.env.example infra/.env     # then fill in the values
./infra/scripts/generate-keystore.sh # -> infra/secrets/jwt-keystore.p12 (gitignored)
```

Compose refuses to start without these variables: every `*_DB_PASSWORD` (auth, product, inventory,
rating, user, cart, order, payment, promotion, notification, seller, support), `JWT_KEYSTORE_PASSWORD`,
`INTERNAL_API_TOKEN`, `SELLER_DATA_KEY` and `AUTH_DATA_KEY` (base64, 32 bytes each).

## 2. Build

```bash
./gradlew build           # compile + test + Spotless + Checkstyle, whole tree
./gradlew spotlessApply   # fix formatting before committing
./gradlew build -x test   # integration tests need a working Docker for Testcontainers
```

Never inline a dependency version. Add an alias to `gradle/libs.versions.toml` and reference it as `libs.<alias>`.

## 3. Run the backend

```bash
docker compose -f infra/docker-compose.yml up -d --build
docker compose -f infra/docker-compose.yml ps
```

The gateway answers 503 for about 30 to 40 seconds after a restart, until Eureka registrations
propagate (TD-BE-08).

On the **first** start, `infra/postgres/init/01-schemas.sql` creates 12 databases, each owned by its
own role. That script only runs on an empty volume. On an **existing** volume, create any databases
added since (re-runnable):

```bash
docker exec -i ecommerce-postgres psql -U postgres < infra/scripts/create-customer-dbs.sql
```

## 4. Run the SPAs

```bash
cd apps/product-catalog && npm install && npm run dev        # storefront  http://localhost:3000
cd apps/admin-product-catalog && npm install && npm run dev  # admin       http://localhost:5173
```

Both ports are pinned with `strictPort`. They are the origins allow-listed for CORS and for the
Google sign-in callback, so a silent move to another port would break sign-in.

## Ports

Services are published to `127.0.0.1`, except the gateway (the public edge) and Postgres. Each can
be overridden with the variable shown, for example `POSTGRES_PORT=15432`.

| Port | Component | Variable |
|---|---|---|
| 3000 | Storefront (React, dev server) | — |
| 5173 | Admin SPA (Vue, dev server) | — |
| 8080 | gateway (all interfaces) | `GATEWAY_PORT` |
| 8761 | eureka-server | `EUREKA_PORT` |
| 8081 | auth-service | `AUTH_PORT` |
| 8082 | product-service | `PRODUCT_PORT` |
| 8083 | inventory-service | `INVENTORY_PORT` |
| 8084 | rating-service | `RATING_PORT` |
| 8085 | user-service | `USER_PORT` |
| 8086 | cart-service | `CART_PORT` |
| 8087 | order-service | `ORDER_PORT` |
| 8088 | payment-service | `PAYMENT_PORT` |
| 8089 | promotion-service | `PROMOTION_PORT` |
| 8090 | notification-service | `NOTIFICATION_PORT` |
| 8091 | seller-service | `SELLER_PORT` |
| 8092 | support-service | `SUPPORT_PORT` |
| 5432 | Postgres (all interfaces; flagged in the [audit](audit/technical-audit.md)) | `POSTGRES_PORT` |
| 6379 | Redis | `REDIS_PORT` |
| 8025 | Mailpit UI (dev mail catcher) | `MAILPIT_UI_PORT` |
| 16686 | Jaeger UI (traces) | — |

Kafka publishes no host port. Only services on the compose network talk to it (`kafka:9092`).

## Dev scripts (`infra/scripts/`)

| Script | Use |
|---|---|
| `generate-keystore.sh` | Creates the dev JWT signing keystore. Never commit a private key. |
| `create-customer-dbs.sql` | Creates newer databases on an existing Postgres volume (re-runnable). |
| `backfill-product-ratings.sh` | Dev-only: copies seed ratings into product-service's denormalized columns ([TD-RT-01](techdebt/ratings.md#td-rt-01)). |
| `gen-product-images.mjs` → `update-product-images.sql` | Looks up product images on Wikimedia Commons by product name and writes an idempotent SQL update. Re-apply with `docker exec -i ecommerce-postgres psql -U postgres -d product_db < infra/scripts/update-product-images.sql`. |

## Stop and reset

```bash
docker compose -f infra/docker-compose.yml down      # keep data
docker compose -f infra/docker-compose.yml down -v   # wipe all volumes (re-runs the init script next start)
```

## Troubleshooting

- **Port already taken:** override it with the variable in the ports table.
- **Service not healthy:** `docker compose -f infra/docker-compose.yml logs <service>`.
- **SPA cannot reach the API:** check `curl http://localhost:8080/actuator/health`, then make sure the
  SPA's origin is in `GATEWAY_CORS_ALLOWED_ORIGINS`.
- More: [operations.md § Troubleshooting](guides/operations.md#troubleshooting).
