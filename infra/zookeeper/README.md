# ZooKeeper — coordination only, NOT service discovery

## What this is for

ZooKeeper is provisioned in `infra/docker-compose.yml` to provide **generic distributed
coordination primitives** for later phases:

- distributed locks (e.g. guarding a bulk catalog re-index so two instances cannot run it at once)
- leader election (e.g. electing one instance to run a scheduled job)
- ephemeral-node-based liveness signalling for non-HTTP workers

## What this is explicitly NOT for

**ZooKeeper is not the service registry.** Eureka (`services/eureka-server`, Phase 1B) owns service
discovery for this platform.

No service depends on `spring-cloud-starter-zookeeper-discovery`, and none should. That starter
auto-registers the application in ZooKeeper and registers a `DiscoveryClient`, which would leave the
platform with **two competing registries** disagreeing about which instances are alive. Symptoms of
that mistake are ugly and intermittent: load-balanced calls routed to instances Eureka already
evicted, and health state that differs depending on which registry you ask.

If a service needs coordination, add `spring-cloud-starter-zookeeper` (the *core* starter, no
discovery) or use a Curator recipe directly — and set `spring.cloud.zookeeper.discovery.enabled=false`
defensively.

## Configuration

Configuration is supplied entirely through the official image's `ZOO_*` environment variables in
`infra/docker-compose.yml` — there is no `zoo.cfg` in this directory, deliberately. A mounted config
file and env vars silently override each other in ways that are hard to debug; keeping one source of
truth avoids that.

Notable settings:

| Setting | Value | Why |
|---|---|---|
| `ZOO_STANDALONE_ENABLED` | `true` | Single node is right for local dev. Production needs an ensemble of 3 or 5 (odd, for quorum). |
| `ZOO_4LW_COMMANDS_WHITELIST` | `srvr,mntr,ruok,conf` | The four-letter words are disabled by default from 3.5 on. `ruok` is required by the container healthcheck. |
| `ZOO_TICK_TIME` | `2000` | Base time unit (ms) for heartbeats and session timeouts. |

## Health check

The container is health-checked with the `ruok` four-letter word, which answers `imok`:

```bash
echo ruok | nc -w 2 localhost 2181     # => imok
```

Verified working during Phase 1A.

## Operational notes

- Host port is overridable (`ZOOKEEPER_PORT`), because 2181 is commonly already taken by another
  local stack: `ZOOKEEPER_PORT=12181 docker compose -f infra/docker-compose.yml up -d`
- Data and transaction logs live in the named volumes `zookeeper-data` / `zookeeper-datalog`.
  `docker compose down -v` wipes them.
- **Production consideration:** if the only real need turns out to be a distributed lock, evaluate
  whether ZooKeeper is worth operating at all. A Postgres advisory lock or a Redis-based lock is far
  cheaper to run than a ZK ensemble, and this platform already operates Postgres.
