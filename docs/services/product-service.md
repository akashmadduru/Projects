# product-service (Phase 1E, + Phase 2 write endpoints/eventing slice)

The platform's **catalog service**, and deliberately the shallowest service in the repo.

- Stack: Spring MVC (**blocking, on virtual threads**), Spring Data JPA + Flyway, Spring Security
  resource server.
- Port: `8082` (dev compose), `SERVER_PORT` to override.
- Registers with Eureka as **`PRODUCT-SERVICE`**; reachable publicly only through the gateway.
- Database: `product_db`, schema `product`, role `product_service`.

---

## Scope, and what is deliberately absent

Phase 1E existed to prove one thing end to end: **a request survives gateway routing → Eureka
resolution → independent token validation → a real database read.** It shipped as two `GET`
endpoints over one table. A Phase 2 slice has since added role-gated writes (`POST`/`PUT`/`DELETE`)
with optimistic locking, plus publishing product lifecycle events to Kafka — see "Writes" and
"Kafka event publishing" below.

Still not here, on purpose — this remains a *catalog* service, not the rest of Phase 2: search,
filtering beyond pagination, pricing rules/discounts, inventory/stock (that's `inventory-service`
now — see [`services/inventory-service/README.md`](../inventory-service/README.md)),
categories/taxonomy, product images, caching.

| Method | Path | Auth | Notes |
|---|---|---|---|
| `GET` | `/api/products` | Bearer (any role) | Paged. Default `size=20`, sort `sku ASC`, cap `size<=100`. |
| `GET` | `/api/products/{id}` | Bearer (any role) | 404 as RFC 9457 problem+json when unknown. |
| `POST` | `/api/products` | `CATALOG_MANAGER` or `ADMIN` | `201` / `400` / `409` (duplicate `sku`). |
| `PUT` | `/api/products/{id}` | `CATALOG_MANAGER` or `ADMIN` | Requires the current `version` in the body (see "Writes" below). `200` / `400` / `404` / `409` (stale version). |
| `DELETE` | `/api/products/{id}` | `CATALOG_MANAGER` or `ADMIN` | `204` / `404`. |

Every endpoint listed is one indexed query. That is not an accident: the gateway's `productCb`
circuit breaker counts anything over **1 second** as a slow call, so a catalog operation that gets
near it trips the breaker for everyone.

### Writes — RBAC and optimistic locking

`POST`/`PUT`/`DELETE /api/products/{id}` are gated to `CATALOG_MANAGER`/`ADMIN` via
`@PreAuthorize` on the controller method — the platform's **first** use of method-level security.
Reads carry no annotation; `anyRequest().authenticated()` in `SecurityConfig` already covers them.
This is a deliberate departure from the "one rule, one place" posture Phase 1E had when this
controller only had reads: role now differs by HTTP method on the same resource, which a single
`authorizeHttpRequests` matcher cannot express as cleanly as method-level annotations without
duplicating the path.

`Product` gained a JPA `@Version` column (`version`, mirrors `auth.users.version` exactly —
optimistic concurrency via JPA's own field, not a hand-rolled counter). `PUT` requests must echo the
`version` the caller last read; `ProductCatalogService.update` checks it explicitly against the
persisted row **before** touching any column, producing a clean `409 RESOURCE_CONFLICT` rather than
relying solely on Hibernate's `OptimisticLockException` at flush time (which would produce the same
outcome, less immediately attributably).

### Kafka event publishing

Every successful `create`/`update`/`delete` publishes a `ProductEvent`
(`PRODUCT_CREATED`/`PRODUCT_UPDATED`/`PRODUCT_DELETED`) to the `product.events` topic (3 partitions,
key = `productId`, JSON, replicas = 1 — single-broker KRaft prototype), consumed by
`inventory-service` to keep its stock rows in sync.

**Strictly after the write's transaction commits — never inside it.** `ProductEventPublisher`
registers a `TransactionSynchronization` whose `afterCommit()` callback does the actual
`KafkaTemplate.send(...)`; if the transaction rolls back, `afterCommit()` never runs and nothing is
published. This is the single most load-bearing guarantee in the eventing design: a rolled-back
write must never appear to inventory-service as if it happened.

**A publish failure is logged, never thrown.** By the time `afterCommit()` runs, the write has
already committed and the HTTP response is about to return; a Kafka outage must delay the event, not
turn a successful write into a 500 the caller never asked for. `KafkaTopicConfig` provisions the
topic on startup if it does not already exist.

`ProductEvent`/`ProductEventType` live in `com.acme.ecommerce.product.event`
(`product-service-app`, not `-api`) and are **deliberately duplicated**, not shared, with
inventory-service's consumer-side copy — see
[`services/inventory-service/README.md`](../inventory-service/README.md#the-kafka-consumer) for why.

### Pagination shape

Returns `PagedModel<ProductResponse>` — the `{ "content": [...], "page": { size, number,
totalElements, totalPages } }` envelope — not a raw `Page`. Serialising `PageImpl` directly emits
Spring Data's internal shape, which the framework itself warns is not a stable contract.

The default sort is **`sku`, and it must stay sortable-and-unique**. An unordered paged query has no
stable page boundaries: Postgres may return rows in a different order between two calls, so a client
walking pages can see one row twice and never see another.

---

## Security — this service does not trust the gateway

The gateway already enforces `authenticated()` for the whole `/api/products/**` prefix. **This
service validates the bearer token again anyway**, independently, against the same JWKS, issuer and
audience.

That is not redundancy for its own sake. The gateway is a *filter*, not the trust boundary — it is
not the only thing on the network that can reach this port. A misconfigured route, a mesh sidecar, a
developer's `kubectl port-forward` or a future internal caller all bypass it, and a service whose
only protection is somebody else's filter is unprotected in all four cases.

> **The `X-User-Id` / `X-User-Roles` / `X-User-Email` / `X-Auth-Method` headers the gateway injects
> are for logging and correlation ONLY, and are never consulted for an authorization decision.** If
> they were, anything that can reach this port could grant itself `ADMIN` with a header. There is a
> test asserting exactly that (`trustedHeadersAreNotCredentials`).

Reads stay covered by `anyRequest().authenticated()` in one filter chain, matching auth-service's
posture. Writes now carry `@PreAuthorize("hasAnyRole('CATALOG_MANAGER','ADMIN')")` directly on the
controller method (see "Writes" above) — `@EnableMethodSecurity` had to be turned on in
`SecurityConfig` for this, the first per-method rule in the platform. This is a deliberate,
narrowly-scoped exception to "two places expressing one rule is how an endpoint ends up protected by
neither": role differs by HTTP method on one resource here, which a path-based matcher cannot
express without duplicating the path.

### JWKS resolution

`NimbusJwtDecoder` (synchronous — this is a blocking MVC service, unlike the gateway) fetching from
`lb://AUTH-SERVICE/.well-known/jwks.json` through a `@LoadBalanced RestTemplate`, so the URL is
resolved through Eureka **at fetch time**, not captured at startup. A URL frozen at boot would be
pinned to whichever auth-service instance happened to be registered then — quite possibly none, given
compose bring-up order.

**No eager fetch and no `depends_on: auth-service`.** The first token to arrive triggers the first
fetch, so this service starts cleanly while its identity provider is down.

Caching is Spring Security's default `JWKSourceBuilder` policy. The gateway hand-assembles a stricter
one (explicit outage tolerance, rate limiting, refresh-ahead) because it is on the critical path of
*every* request in the platform; this service is on the critical path of catalog reads only, and a
second copy of that policy to keep in sync would cost more than it buys.

`PRODUCT_JWKS_URI` is the escape hatch: a non-blank absolute URL bypasses discovery entirely (used by
the test suite, which has no registry).

---

## Configuration

| Variable | Default | Notes |
|---|---|---|
| `SERVER_PORT` | `8082` | |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/product_db` | Use `15432` if the compose stack runs with `POSTGRES_PORT=15432`. |
| `SPRING_DATASOURCE_USERNAME` | `product_service` | |
| `SPRING_DATASOURCE_PASSWORD` | *(local throwaway)* | No default under the `prod` profile. |
| `AUTH_JWT_ISSUER` | `https://auth.acme-ecommerce.internal` | **Must equal** auth-service's `auth.jwt.issuer` and the gateway's `gateway.jwt.issuer`. |
| `AUTH_JWT_AUDIENCES` | `ecommerce-platform` | **Must equal** the other two. |
| `PRODUCT_JWKS_SERVICE_ID` | `AUTH-SERVICE` | Eureka id whose JWKS is trusted. |
| `PRODUCT_JWKS_URI` | *(blank)* | Absolute URL; non-blank bypasses discovery. |
| `EUREKA_CLIENT_SERVICEURL_DEFAULTZONE` | `http://localhost:8761/eureka/` | |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | `ProductEventPublisher`'s producer. Not a startup gate — see "Kafka event publishing" above. |

`spring.application.name` is **`product-service`** and that is a contract, not a preference: Eureka
uppercases it to `PRODUCT-SERVICE`, which is what the gateway's route table already targets. Rename it
and the route stops resolving *silently* — the gateway cannot distinguish an unregistered target from a
down one, so the only symptom is a 503 from `/__fallback/product`.

---

## Schema

`V1__product_baseline.sql` creates `product.products` and seeds **5 rows** (one of them
`active = false`, so "Phase 1E does not filter on `active`" is an observable fact rather than a
claim). Fixed UUIDs, so a test or a `curl` can address a known row without listing the catalog first.

`price_amount` is `numeric(19,2)`, never `double precision`: binary floating point cannot represent
`0.10` exactly, and the error surfaces months later as a one-cent discrepancy nobody can reproduce.
`price_currency` is `char(3)` with a regex CHECK.

`V2__product_writes.sql` adds the `version` column (`bigint NOT NULL DEFAULT 0`) the write
endpoints' optimistic locking needs — strictly additive, no existing column widened or renamed.

Forward-only, like auth-service's. No down migrations.

> **Phase 2 note:** the seed rows ship to every environment, including a production first deploy. When
> real catalog loading exists they should be removed by a *later* migration — never by editing
> `V1`, which Flyway checksums and will refuse to start against.

---

## Running locally

```bash
# 1. registry
java -jar services/eureka-server/build/libs/eureka-server-0.1.0-SNAPSHOT.jar

# 2. auth-service (needs the dev keystore and Postgres)
PLATFORM_JWT_KEYSTORE_PATH=infra/secrets/jwt-keystore.p12 \
PLATFORM_JWT_KEYSTORE_PASSWORD=devkeystorepw \
PLATFORM_JWT_KEYSTORE_ALIAS=jwt-signing \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15432/auth_db \
java -jar services/auth-service/auth-service-app/build/libs/auth-service-app-0.1.0-SNAPSHOT.jar

# 3. this service
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15432/product_db \
java -jar services/product-service/product-service-app/build/libs/product-service-app-0.1.0-SNAPSHOT.jar

# 4. the edge
java -jar services/gateway/build/libs/gateway-0.1.0-SNAPSHOT.jar
```

Or `docker compose -f infra/docker-compose.yml up -d product-service`.

Through the gateway, with a real token:

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@acme-ecommerce.internal","password":"..."}' | jq -r .accessToken)

curl -s http://localhost:8080/api/products -H "Authorization: Bearer $TOKEN"
curl -s http://localhost:8080/api/products/11111111-1111-4111-8111-111111111111 \
  -H "Authorization: Bearer $TOKEN"

# and without a token - 401 problem+json from the edge, which never reaches this service
curl -i -s http://localhost:8080/api/products

# a write - $TOKEN must carry CATALOG_MANAGER or ADMIN, or this is a 403
curl -s -X POST http://localhost:8080/api/products \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"sku":"WIDGET-042","name":"Widget","priceAmount":9.99,"priceCurrency":"USD","active":true}'

# a version-checked update - "version" must match the row's current value or this is a 409
curl -s -X PUT http://localhost:8080/api/products/11111111-1111-4111-8111-111111111111 \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"Widget v2","priceAmount":11.99,"priceCurrency":"USD","active":true,"version":0}'
```

---

## Tests

`./gradlew :services:product-service:product-service-app:build`

- `ProductCatalogServiceTest` — the 404 problem document's shape, with a mocked repository and a
  fixed clock. Runs in milliseconds; a change to the error contract fails here rather than only in
  the test that needs a database.
- `ProductEndpointsIntegrationTest` — `@SpringBootTest` + MockMvc over the real filter chain against
  a **Testcontainers Postgres** (not H2: the migration uses `pgcrypto`, `char(3)`, a regex CHECK and
  `numeric(19,2)`). Flyway runs on first context start, so the migration and its seed data are under
  test on every run. Covers anonymous → 401, valid token → 200 with data, wrong-audience →
  401, wrong-issuer → 401, expired → 401, forged `X-User-Roles` → 401, pagination, the size cap, the
  by-id lookup and its 404.
- Tokens are minted by `support/TestKeys` with a **real** RSA key and served over a **real** HTTP
  stub (`support/StubJwksServer`), because a mocked `JwtDecoder` would assume working exactly the
  part under test.
- `ProductEventPublisherTest` — asserts, using `TransactionSynchronizationManager`'s test utilities,
  that a rolled-back transaction never calls `KafkaTemplate.send(...)`. This is the single riskiest
  guarantee in the eventing design (see "Kafka event publishing" above) and gets a dedicated unit
  test rather than relying on an integration smoke test to catch a regression.

---

## Not implemented (tracked)

- Search, filtering beyond pagination, pricing rules/discounts, categories/taxonomy, product
  images, caching — still Phase 2 work this service does not do.
- **`ProductClient` has no consumer yet.** It is published now so the contract is defined by the
  service that owns it, rather than reverse-engineered from a URL by whichever service needs it first.
- No caching. Correct for now: reads are a primary-key or indexed-scan hit on a handful of rows.
  Revisit when `pg_stat_statements` says to, not before.
- No schema registry for `ProductEvent`; the DTO is duplicated by hand in inventory-service. See
  [`services/inventory-service/README.md`](../inventory-service/README.md#the-kafka-consumer).
