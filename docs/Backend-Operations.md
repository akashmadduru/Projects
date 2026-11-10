# Backend Operations

**Status:** Complete (Phases 1–4 slice)  
**Last Updated:** November 2026

## Table of Contents

1. [Local Development Setup](#local-development-setup)
2. [Database Migrations](#database-migrations)
3. [Health Checks & Readiness](#health-checks--readiness)
4. [Monitoring & Metrics](#monitoring--metrics)
5. [Logging & Correlation IDs](#logging--correlation-ids)
6. [Troubleshooting](#troubleshooting)
7. [Deployment Checklist](#deployment-checklist)

---

## Local Development Setup

### Prerequisites

- **JDK 25** — verify with `java -version`
- **Docker + Docker Compose** — for Postgres, Kafka, Redis, ZooKeeper, Eureka
- **Git** — to clone the repository

### Start Local Infrastructure

```bash
# Navigate to the project root
cd /path/to/ecommerce-platform

# Start all backing services (Postgres, Kafka, Redis, ZooKeeper, Eureka)
docker compose -f infra/docker-compose.yml up -d

# Verify services are running
docker compose -f infra/docker-compose.yml ps

# View logs for a specific service
docker compose -f infra/docker-compose.yml logs -f postgres
docker compose -f infra/docker-compose.yml logs -f kafka
```

### Generate JWT Signing Keys (Required from Phase 1C)

```bash
# Generate a dev keystore (RSA-2048, PKCS#12, file mode 600)
./infra/scripts/generate-keystore.sh

# Output: infra/secrets/jwt-keystore.p12
# This is .gitignored and never committed
```

### Build the Platform

```bash
# Whole-tree build: compile, test, Spotless check, Checkstyle
./gradlew build

# Tests only
./gradlew test

# Auto-fix formatting (do this before committing)
./gradlew spotlessApply
```

### Start Individual Services

Each service runs on a different port with virtual threads enabled.

```bash
# Terminal 1: auth-service (port 8081)
./gradlew :services:auth-service:auth-service-app:bootRun

# Terminal 2: product-service (port 8082)
./gradlew :services:product-service:product-service-app:bootRun

# Terminal 3: inventory-service (port 8083)
./gradlew :services:inventory-service:inventory-service-app:bootRun

# Terminal 4: rating-service (port 8084)
./gradlew :services:rating-service:rating-service-app:bootRun

# Terminal 5: gateway (port 8080)
./gradlew :services:gateway:bootRun

# Terminal 6: eureka-server (port 8761)
docker compose -f infra/docker-compose.yml up -d eureka-server
open http://localhost:8761/  # Dashboard
```

### Verify Services are Running

```bash
# Service registry
curl http://localhost:8761/actuator/health
# Output: {"status":"UP"}

# Product service (no auth required for reads)
curl http://localhost:8082/api/products
# Output: paginated product list

# Inventory service (requires auth, but health check doesn't)
curl http://localhost:8083/actuator/health
# Output: {"status":"UP","components":{...}}

# Rating service
curl http://localhost:8084/api/ratings/products/11111111-1111-4111-8111-111111111111
# Output: paginated rating list (empty initially)
```

---

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

### Manual Migration Triggers

Migrations run automatically on service startup (validated, not in dev mode):

```bash
# Manually trigger migrations (useful in Docker)
./gradlew :services:product-service:product-service-app:bootRun
# Logs will show:
# INFO c.a.e.product.ProductServiceApplication - Starting ProductServiceApplication ...
# INFO o.f.c.i.database.base.DatabaseFactory - Attempting to connect to jdbc:postgresql://...
# INFO o.f.c.i.migration.validate.ValidateOperation - Validating migrations ...
# INFO o.f.c.i.migration.sql.SqlScriptExecutor - V1__product_baseline.sql ...
# INFO o.f.c.i.migration.support.MigrationInfoServiceImpl - Successfully validated 5 migrations
```

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

### Liveness Probe

```bash
curl http://localhost:8082/actuator/health/live
# Output: {"status":"UP"}
```

**Meaning:** The service process is running. Used by Kubernetes `livenessProbe` to restart unhealthy containers.

### Readiness Probe

```bash
curl http://localhost:8082/actuator/health/ready
# Output: {"status":"UP","components":{"db":{"status":"UP"},"kafka":{"status":"UP"}}}
```

**Meaning:** The service can handle traffic. Checks:
- Database connectivity (successful connection to datasource)
- Kafka connectivity (producer/consumer can reach broker)
- Eureka registration (service is discoverable)

Used by orchestration (Kubernetes, gateway) to route traffic only to ready instances.

### Eureka Registration

```bash
curl http://localhost:8761/actuator/eureka/apps/PRODUCT-SERVICE
# Output: XML listing all registered product-service instances
```

**Timing invariant** (documented in services/eureka-server/README.md):
```
lease-expiration-duration >= 3 * lease-renewal-interval
30s >= 3 * 10s  ✓
```

If heartbeat is missed for 30s, the instance is evicted from the registry.

---

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

### Scraping with Prometheus

In a real environment, a Prometheus instance would scrape these endpoints:

```yaml
# prometheus.yml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: 'product-service'
    static_configs:
      - targets: ['localhost:8082']
    metrics_path: '/actuator/prometheus'
```

### Visualizing with Grafana

Grafana can visualize Prometheus data:
1. Add Prometheus as a data source
2. Create dashboards for each service (CPU, memory, request latency, error rate)
3. Set up alerts (e.g., "alert if error rate > 5% for 5m")

---

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
docker compose -f infra/docker-compose.yml logs -f product-service
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
docker compose -f infra/docker-compose.yml up -d kafka
docker compose -f infra/docker-compose.yml logs kafka
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
docker compose -f infra/docker-compose.yml up -d postgres
docker compose -f infra/docker-compose.yml logs postgres
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
docker compose -f infra/docker-compose.yml up -d eureka-server
```

### JWT token validation fails: "invalid_token"

**Symptom:**
```
curl -H "Authorization: Bearer eyJhbGc..." http://localhost:8082/api/products/11111111-1111-4111-8111-111111111111
# Output: 401 {"type": "...", "code": "UNAUTHENTICATED"}
```

**Causes:**
1. JWT signing keystore not generated: `./infra/scripts/generate-keystore.sh`
2. JWT issuer/audience mismatch: check `product.jwt.issuer`, `product.jwt.audience` in application.yml match auth-service config
3. Token is expired or malformed

**Debug:**
```bash
# Check JWT token claims (decode at jwt.io)
# Verify issuer is https://auth.acme-ecommerce.internal
# Verify audience is ecommerce-platform
# Verify `sub` and `scope` claims are present

# Check service logs for specific error
docker compose -f infra/docker-compose.yml logs product-service | grep "invalid_token"
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
docker compose -f infra/docker-compose.yml logs inventory-service | grep ProductEventListener

# Manually create inventory if needed
curl -X POST http://localhost:8083/api/inventory \
  -H "Content-Type: application/json" \
  -d '{"productId":"11111111-1111-4111-8111-111111111111","quantity":100}'
```

### Rating denormalization not updating

**Symptom:**
```
GET /api/products/11111111-1111-4111-8111-111111111111
# Returns: averageRating: null, ratingCount: 0
# But ratings exist for the product
```

**Cause:** RatingEventListener's `updateRatingDenormalization()` is a placeholder and doesn't actually update the product.

**Current state:** The method exists and is called by the listener, but the implementation is incomplete (see ProductCatalogService line 269-270: TODO comment).

**Workaround:** Rating denormalization will be implemented in a future phase. For now, ratings are queryable directly via `/api/ratings/products/{productId}` and `/api/ratings/products/{productId}/summary`.

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

## Deployment Checklist

### Pre-Deployment

- [ ] All tests passing: `./gradlew build`
- [ ] No hardcoded secrets in codebase (use environment variables)
- [ ] Database migrations are tested (Flyway checksum stable)
- [ ] Logging is configured (no DEBUG level in production config)
- [ ] Correlation IDs are enabled
- [ ] Circuit breakers are configured (timeouts, thresholds)
- [ ] Kafka topics are created and accessible
- [ ] Health checks are working locally

### Deployment (Docker Compose Example)

```bash
# Build Docker images for each service
./gradlew jibBuildImage

# Start containers
docker compose -f infra/docker-compose.yml up -d

# Verify health
curl http://localhost:8080/actuator/health  # Gateway
curl http://localhost:8082/actuator/health  # Product service
curl http://localhost:8083/actuator/health  # Inventory service
curl http://localhost:8084/actuator/health  # Rating service
```

### Post-Deployment

- [ ] All services report UP in Eureka
- [ ] Gateway routes requests successfully
- [ ] Prometheus scrapes all metrics endpoints
- [ ] Logs are being collected (correlation IDs visible)
- [ ] Database migrations applied (check flyway_schema_history)
- [ ] Kafka topics exist and have consumers
- [ ] No errors in logs (check ERROR and WARN levels)

---

## References

- [Backend-Architecture.md](Backend-Architecture.md) — Service architecture and deployment topology
- [Backend-API.md](Backend-API.md) — API endpoints and error responses
- [Backend-EventSystem.md](Backend-EventSystem.md) — Event choreography and Kafka topics
- [README.md](../README.md) — Build and local development quickstart
