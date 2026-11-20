# Backend Operations

**Verified against the code:** 2026-11-14. Examples use product-service (:8082); every service
exposes the same actuator endpoints on its own port ([ports](../getting-started.md#ports)).
First-time setup is in [getting started](../getting-started.md); this page is for running and debugging.

## Table of Contents

1. [Local Development Setup](#local-development-setup)
2. [Database Migrations](#database-migrations)
3. [Health Checks & Readiness](#health-checks--readiness)
4. [Monitoring & Metrics](#monitoring--metrics)
5. [Logging & Correlation IDs](#logging--correlation-ids)
6. [Troubleshooting](#troubleshooting)
7. [Deployment](#deployment)

---

## Local Development Setup

Start everything with Docker as described in [getting started](../getting-started.md). To debug one
service from your IDE or Gradle instead, stop its container and run it locally; it registers with
the same Eureka, so the gateway routes to it:

```bash
docker compose -f deploy/local/docker-compose.yml stop product-service
./gradlew :services:product-service:product-service-app:bootRun
```

The service reads the same variables as its compose block (database password, `INTERNAL_API_TOKEN`,
Kafka and Eureka addresses). From the host, Postgres is `localhost:5432` and Eureka
`localhost:8761`; Kafka has no host port, so a service run outside Docker cannot reach it and its
event publishing and consumers fail (the HTTP API still works).

### Smoke test

```bash
curl http://localhost:8761/actuator/health   # Eureka: {"status":"UP"}
curl http://localhost:8080/actuator/health   # gateway
curl http://localhost:8080/api/products      # public catalog through the gateway
```

## Database Migrations

### Flyway Overview

Each service uses **Flyway** to manage its schema. Migrations are SQL files in `src/main/resources/db/migration/` following the naming convention `V{N}__{description}.sql`.

**Configuration** (in `application.yml`):
```yaml
spring:
  flyway:
    enabled: true
    schemas: product  # service-specific schema
    default-schema: product
    locations: classpath:db/migration
    validate-on-migrate: true  # fail on checksum mismatch
```

### Forward-Only Migrations

All migrations are **forward-only** — no down migrations. Rollbacks are done additively:

**Before (wrong):**
```sql
ALTER TABLE products DROP COLUMN price_amount;  -- BAD: loses data
```

**After (correct):**
```sql
-- V2: Add price_amount_new column
ALTER TABLE products ADD COLUMN price_amount_new numeric(19, 2);

-- (later: data backfill, dual reads, etc.)

-- V3: Drop old column after migration is complete
ALTER TABLE products DROP COLUMN price_amount;
```

### When migrations run

Every service runs Flyway at startup (`spring.flyway.enabled: true`, `validate-on-migrate: true`)
before it accepts traffic. To apply a new migration, restart the service:

```bash
docker compose -f deploy/local/docker-compose.yml up -d --build product-service
docker compose -f deploy/local/docker-compose.yml logs product-service | grep -i flyway
```

If validation fails ("checksum mismatch"), an applied `V*` file was edited. Revert the edit and put
the change in a new migration.

### Checking Migration Status

```bash
# From the database itself
psql -U product_service -d product_db -c "SELECT * FROM product.flyway_schema_history;"

# Output:
# success | version | description            | type | installed_by | installed_on | execution_time
# --------+---------+------------------------+------+--------------+--------------+---------------
# t       | 1       | product_baseline       | SQL  | product_service | ... | 234
# t       | 2       | product_writes         | SQL  | product_service | ... | 156
# t       | 3       | product_catalog_enrichment | SQL | ... | ... | 89
# ...
```

---

## Health Checks & Readiness

| Endpoint | Meaning | Used by |
|---|---|---|
| `/actuator/health` | Overall status including the database | Compose health checks (each container waits for `"status":"UP"`) |
| `/actuator/health/liveness` | The process is alive | Kubernetes liveness probe ([Helm chart](deploy-aws.md)) |
| `/actuator/health/readiness` | The service can take traffic | Kubernetes readiness probe |

```bash
curl http://localhost:8082/actuator/health/readiness
```

### Eureka Registration

```bash
curl -H 'Accept: application/json' http://localhost:8761/eureka/apps/PRODUCT-SERVICE
```

A restarted service takes up to about 10 seconds to become routable through the gateway (Eureka
registration, the gateway's 5 s registry fetch and its 5 s load-balancer cache); until then the
gateway answers 503.
The timing settings are explained in [eureka-server](../services/eureka-server.md).

## Monitoring & Metrics

### Prometheus Endpoint

```bash
curl http://localhost:8082/actuator/prometheus
# Output: Prometheus text format metrics
```

**Key metrics:**
- `http_server_requests_seconds_*` — HTTP request latency, count, status code distribution
- `jvm_memory_used_bytes` — JVM heap usage
- `jvm_threads_live` — thread count (useful with virtual threads)
- `hikaricp_connections_active` — database connection pool usage
- `kafka_producer_record_send_total` — Kafka records sent
- `kafka_consumer_records_lag_max` — Kafka consumer lag

### What collects metrics

- **Locally:** nothing scrapes `/actuator/prometheus`; read it directly with `curl`.
- **On AWS:** the Terraform `observability` module creates CloudWatch alarms for RDS (CPU,
  storage, connections) and ElastiCache CPU, sent to an SNS topic with an email subscription. No
  Prometheus or Grafana is provisioned; the [technical audit](../audit/technical-audit.md) lists
  that as future work.

## Logging & Correlation IDs

### Log Format

```
2026-11-09 10:23:45.123 INFO [correlationId] ClassName - Message
```

**Correlation ID** is part of the MDC (Mapped Diagnostic Context) and included in every log line for a request.

### Viewing Logs

```bash
# From the IDE (e.g., IntelliJ IDEA)
# Run → Run (or BootRun), then view the console output with colors

# From the command line
./gradlew :services:product-service:product-service-app:bootRun 2>&1 | grep ERROR

# From Docker container
docker compose -f deploy/local/docker-compose.yml logs -f product-service
```

### Correlation ID Propagation

The `CorrelationIdFilter` in `common-web` ensures correlation IDs are:
1. **Extracted** from inbound `X-Correlation-Id` header (or generated if missing)
2. **Validated** against CRLF injection and length (to prevent log forging)
3. **Bound** to the request context (MDC)
4. **Relayed** on outbound Feign calls via `CorrelationAndAuthForwardingInterceptor`
5. **Echoed** in the response header

**Tracing a request across services:**
```
Client POST /api/products [X-Correlation-Id: abc-123]
   → product-service:8082 (MDC: abc-123)
       → Kafka publish (eventId: xyz-456, correlationId: abc-123 in message)
   → Kafka broker
   → inventory-service consumer (MDC: abc-123)
       → POST /api/inventory (X-Correlation-Id: abc-123)
   → inventory-service:8083 (MDC: abc-123)

Logs from all stages share [abc-123] making the full path traceable
```

### Log Levels (Configurable)

```yaml
logging:
  level:
    root: INFO
    com.acme.ecommerce.product: INFO
    org.springframework.web: INFO
    org.springframework.security: INFO
    org.hibernate.SQL: INFO
    org.hibernate.orm.jdbc.bind: INFO
```

**Important:** Request headers (including `Authorization`) are logged only at DEBUG level, so they're never exposed in production (where level is WARN).

---

## Troubleshooting

### Service won't start: "Kafka broker unreachable"

**Symptom:**
```
ERROR o.s.k.l.KafkaMessageListenerContainer - Error handler threw an exception
org.apache.kafka.common.KafkaException: Failed to construct kafka consumer
```

**Cause:** Kafka container is not running.

**Fix:**
```bash
docker compose -f deploy/local/docker-compose.yml up -d kafka
docker compose -f deploy/local/docker-compose.yml logs kafka
```

**Note:** Services start successfully even if Kafka is unavailable (not a hard dependency). Event publishing fails at runtime but is logged, not thrown.

### Service won't start: "Database connection failed"

**Symptom:**
```
ERROR o.s.b.a.h.HikariDataSource - HikariPool-1 - Exception during pool initialization
org.postgresql.util.PSQLException: Connection to localhost:5432 refused
```

**Cause:** Postgres container is not running.

**Fix:**
```bash
docker compose -f deploy/local/docker-compose.yml up -d postgres
docker compose -f deploy/local/docker-compose.yml logs postgres
```

### Service won't start: "Eureka not found"

**Symptom:**
```
WARN c.n.d.s.transport.EurekaHttpClientV2 - DiscoveryClient_PRODUCT-SERVICE/... - Request execution failed
com.sun.jersey.api.client.ClientHandlerException: java.net.ConnectException: Connection refused
```

**Cause:** Eureka server is not running.

**Fix:**
```bash
docker compose -f deploy/local/docker-compose.yml up -d eureka-server
```

### JWT token validation fails: "invalid_token"

**Symptom:**
```
curl -H "Authorization: Bearer eyJhbGc..." http://localhost:8082/api/products/11111111-1111-4111-8111-111111111111
# Output: 401 {"type": "...", "code": "UNAUTHENTICATED"}
```

**Causes:**
1. JWT signing keystore not generated: `./deploy/local/scripts/generate-keystore.sh`
2. JWT issuer/audience mismatch: check `product.jwt.issuer`, `product.jwt.audience` in application.yml match auth-service config
3. Token is expired or malformed

**Debug:**
```bash
# Check JWT token claims (decode at jwt.io)
# Verify issuer is https://auth.acme-ecommerce.internal
# Verify audience is ecommerce-platform
# Verify `sub` and `scope` claims are present

# Check service logs for specific error
docker compose -f deploy/local/docker-compose.yml logs product-service | grep "invalid_token"
```

### Inventory aggregate read returns "inventory: null"

**Symptom:**
```json
{
  "product": { /* ProductResponse */ },
  "inventory": null
}
```

**Possible causes:**
1. Inventory row hasn't been provisioned yet (normal; product was created before PRODUCT_CREATED event was consumed)
2. inventory-service is slow or unreachable (timeouts, circuit breaker open)
3. UNIQUE constraint violation prevented auto-provisioning (product_id already exists)

**Debug:**
```bash
# Check if inventory row exists
curl http://localhost:8083/api/inventory/by-product/11111111-1111-4111-8111-111111111111
# If 404: no row provisioned

# Check inventory-service logs for event processing errors
docker compose -f deploy/local/docker-compose.yml logs inventory-service | grep ProductEventListener

# Manually create inventory if needed
curl -X POST http://localhost:8083/api/inventory \
  -H "Content-Type: application/json" \
  -d '{"productId":"11111111-1111-4111-8111-111111111111","quantity":100}'
```

### Rating denormalization not updating

**Symptom:** a product shows `averageRating: null` or `ratingCount: 0` although
`GET /api/ratings/products/{id}/summary` returns ratings.

**Causes, in the order to check:**
1. The ratings were inserted by a seed migration, which publishes no `rating.events`. Run
   `deploy/local/scripts/backfill-product-ratings.sh` ([TD-RT-01](../techdebt/ratings.md#td-rt-01)).
2. product-service could not reach rating-service when the event arrived. The event is retried 3 times
   and then moved to `rating.events.DLT`; check that topic. The next rating change for that product
   repairs it, or run the backfill script.
3. The consumer group is lagging:
   `kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group product-service-rating-listener`
   inside `ecommerce-kafka`.

### Duplicate events / "already processed"

**Symptom:**
```
ERROR c.a.e.i.event.ProductEventListener - Received duplicate PRODUCT_CREATED event
org.postgresql.util.PSQLException: duplicate key value violates unique constraint "stock_levels_product_id_key"
```

**Cause:** Same ProductEvent delivered twice (Kafka at-least-once guarantee). UNIQUE constraint prevents data corruption but the error is logged.

**Expected behavior:** Error is caught and logged; the second attempt is safely rejected. State is correct despite the duplicate.

**No action needed** — the system is working as designed. The UNIQUE constraint is the idempotency guard. Check logs to see event IDs and confirm duplicates are the same event, not a logic error.

---

## Return coin reversals

Each refunded return gives back its pro-rata share of the order's redeemed coins and reverses its share of the earned coins in promotion-service (`CoinService.onReturnRefunded`). Coins never block the money refund: the event rides the order-service outbox.

- **Deploy order:** deploy promotion-service BEFORE order-service. Old order-service events still work (legacy path); the new event fields are ignored by an old promotion-service.
- **Rollback:** an old promotion-service runs `onFullyRefunded` on a completing return even when per-return reversals exist. That is an over-reversal, clamped to the balance. Roll back only before any completing return is refunded, or accept the clamped over-reversal.
- **Shortfall:** when the user already spent the coins, the reversal is clamped to the balance and the log carries `COIN REVERSAL SHORTFALL order=... return=... earnDue=... taken=...`. `promotion.coin_return_reversals` records `earn_due` and `earn_taken` for every return, also when `earn_taken` is 0 (a ledger row cannot hold 0). Shortfall is `earn_due - earn_taken`; it is not recovered later.
- **Replaying a DLT:** safe. Each return is applied once (primary key `return_id`, checked under the per-user lock). `RETURN_REFUNDED` events with a `returnId` with both `returnId` and `returnItemsValue` are not dropped by the 2-day age gate, so a late replay still applies.
- **Give-back does not expire:** redeemed coins given back on a return carry no expiry, the same as the existing cancel give-back (`releaseRedeem`). Accepted.
- **Legacy paths:** after per-return reversals exist, the legacy full-refund path (`onFullyRefunded`) and `releaseRedeem` subtract what the returns were already due, so an order never reverses or gives back more than N or E.
- **Privacy:** `coin_return_reversals` is retained on USER_DELETED like the coin ledger and is not part of `PrivacyExportController`. Follow-up: add it to the export (kept here, not in the tech-debt register).

---

## Deployment

Production deployment is not done from this page:

- Images are built and scanned by CI ([ci-cd.md](ci-cd.md)) and published to GHCR on pushes to `main` and `v*` tags.
- AWS deployment (Terraform, EKS, Helm) is `deploy/aws/deploy.sh`, documented in [deploy-aws.md](deploy-aws.md).
- Before a real deployment, clear the critical items in the [technical audit](../audit/technical-audit.md#g-top-20-changes-priority-order).

## References

- [Architecture overview](../architecture/overview.md) — Service architecture and deployment topology
- [API reference](../reference/api.md) — API endpoints and error responses
- [Event reference](../reference/events.md) — Event choreography and Kafka topics
- [Getting started](../getting-started.md) — build and local quickstart
