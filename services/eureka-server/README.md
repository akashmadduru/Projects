# eureka-server

The platform's **service registry**. Netflix Eureka, running as a single standalone node.

It is deliberately the smallest application in the repo: one `@SpringBootApplication`
class, one `application.yml`, no persistence, no business logic, no public route. Every
other service will register here and resolve its peers through it, so anything added to
this module becomes a shared risk.

> **ZooKeeper is not service discovery in this platform.** Eureka owns discovery. No
> service depends on `spring-cloud-starter-zookeeper-discovery` — adding it would give us
> two registries that disagree.

---

## Run it

```bash
# Local JVM (dev)
./gradlew :services:eureka-server:bootRun

# Container, alongside the rest of local infra
docker compose -f infra/docker-compose.yml up -d eureka-server
```

| Surface | URL | Notes |
|---|---|---|
| Dashboard | http://localhost:8761/ | HTML; lists registered instances |
| Registry API | http://localhost:8761/eureka/apps | What clients actually call |
| Health | http://localhost:8761/actuator/health | Drives the compose healthcheck |

---

## Security posture — read this before deploying anything

**Today (dev) the only access control is the network boundary.** The application has no
authentication whatsoever.

`infra/docker-compose.yml` publishes the port as `127.0.0.1:8761:8761`, **not** `8761:8761`.
The short form is shorthand for `0.0.0.0` and would expose the registry on every interface
of the developer's machine. Services on the compose network reach it at
`http://eureka-server:8761/eureka/` and never use the published port; the publish exists
purely so a human can open the dashboard.

**Production gap — documented, not implemented (Phase 1 is dev-only):**

1. **HTTP Basic auth (or mTLS) in front of the dashboard and `/eureka/**`.** Unauthenticated,
   `/eureka/apps` is a complete map of every service instance, host and port in the platform
   — a free reconnaissance endpoint. Worse, the API is writable: anyone who can reach it can
   register a rogue instance under a real service name and receive that service's traffic, or
   de-register healthy instances and black-hole it. Adding Spring Security here means also
   giving every client a credential in its `service-url.defaultZone`, and disabling CSRF on
   `/eureka/**` (the Netflix client does not send CSRF tokens).
2. **TLS.** Registrations carry hostnames and ports in cleartext today.
3. **Peer replication.** A single node is a single point of failure for discovery. Production
   wants ≥ 2 peered nodes across AZs, with `register-with-eureka` and `fetch-registry` set
   back to `true` and `defaultZone` listing the peers.

---

## Container image

Multi-stage: `eclipse-temurin:25-jdk` build stage → Boot layer extraction →
`eclipse-temurin:25-jre-alpine` runtime, running as the non-root `spring` user with the
JVM as PID 1 (so `SIGTERM` reaches it and `server.shutdown: graceful` actually works).
Resulting image ≈ 420MB. `curl` is installed solely so the compose healthcheck can pass.

The **build context is the repository root**, not this directory — a Gradle multi-project
build cannot be built from a single module directory:

```bash
docker build -f services/eureka-server/Dockerfile -t ecommerce/eureka-server .
```

> **The Gradle build stage needs ~3GB available to the Docker engine.** On a Docker Desktop
> VM at the 2GB default — especially with other stacks already running in it — the Gradle
> daemon is OOM-killed by the kernel and the *only* symptom is
> `Gradle build daemon disappeared unexpectedly`: no OOM message, no `hs_err` file, nothing
> in the daemon log. Raise Docker Desktop's memory (Settings → Resources) rather than
> debugging the build. CI runners are not affected.

---

## Configuration and the dev/prod split

Everything lives in [`src/main/resources/application.yml`](src/main/resources/application.yml).
The unprofiled document is dev; a `prod` document in the same file records the overrides, so
the delta is visible in one place rather than spread across environments.

| Property | Dev | Prod | Why it differs |
|---|---|---|---|
| `eureka.server.enable-self-preservation` | `false` | **`true`** | Dev really does kill services constantly, and self-preservation would keep serving their dead addresses. In prod the opposite assumption holds: a heartbeat drought is far more likely to be a network partition than a fleet-wide outage, and evicting a healthy fleet on that evidence takes the platform down. |
| `eviction-interval-timer-in-ms` | `10000` | `60000` | Fast feedback locally; less sweep churn in prod, where client-side retry absorbs the extra staleness. |
| `response-cache-update-interval-ms` | `5000` | `30000` | Same trade-off, on the read path. |
| `renewal-percent-threshold` | `0.85` | `0.85` | Inert while self-preservation is off, but already correct for the moment prod flips it on. |

`register-with-eureka: false` and `fetch-registry: false` are **not** a dev shortcut — they
are what "standalone single node" means. Left at their `true` defaults, the server spends
startup retrying connections to itself.

---

## Expected discovery lag in dev (~20s) — this is not a bug

From "a service process dies" to "callers stop trying it":

```text
  eviction sweep           up to 10s   eureka.server.eviction-interval-timer-in-ms
+ response cache refresh   up to  5s   eureka.server.response-cache-update-interval-ms
+ client registry fetch    up to  5s   client's eureka.client.registry-fetch-interval-seconds
= ~20s worst case
```

Squeezing this further costs more CPU and chattiness than the seconds are worth. Clients are
expected to tolerate a stale entry with a retry/circuit breaker, not to rely on the registry
being instantaneous.

---

## For phases that add Eureka *clients* (1C, 1D, 1E)

Client-side properties belong in the client service, not here, but one invariant is easy to
get wrong and painful to debug:

```text
eureka.instance.lease-expiration-duration-in-seconds
    >= 3 × eureka.instance.lease-renewal-interval-in-seconds
```

A single missed heartbeat must never be enough to evict a healthy instance. With the
conventional 5s renewal, expiration must be ≥ 15s. Violate it and instances flap in and out
of the registry under nothing worse than a mild GC pause — which then looks like a
mysterious intermittent 503 at the gateway.

Client registration URL on the compose network:

```yaml
EUREKA_CLIENT_SERVICEURL_DEFAULTZONE: http://eureka-server:8761/eureka/
```

`eureka.instance.hostname` here is set to `eureka-server` to match that compose service name.
