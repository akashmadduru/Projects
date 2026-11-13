# ADR 0001 — Monorepo layout, build tooling, and platform baseline versions

- **Status:** Accepted
- **Date:** 2026-08-08
- **Phase:** 1A (Foundation & Conventions)
- **Deciders:** Platform engineering

## Context

We are starting a greenfield e-commerce platform composed of several Spring Boot services
(auth, product catalog, an edge gateway, a service registry) plus a Vue admin SPA and a set of
shared Java libraries. Phase 1A must establish the repository layout, the build tool, and — most
importantly — a **verified** set of baseline dependency versions that the remaining phases can rely
on without rediscovering incompatibilities one at a time.

## Decision 1 — Monorepo over polyrepo

All services, shared libraries, infrastructure definitions and the admin SPA live in a single
repository.

**Rationale.** At this stage the dominant cost is cross-cutting change. A change to the shared error
envelope or the JWT claim contract touches three or more modules at once; in a polyrepo that becomes
a version-bump-and-release dance across repositories before it can even be integration-tested. A
monorepo makes such a change one atomic, reviewable commit, and lets `./gradlew build` act as a
whole-platform gate.

**Trade-off accepted.** Monorepos couple release cadence unless deliberately managed, and CI must
eventually learn to build only affected modules or build times will grow super-linearly. Both are
tractable later (Gradle's build cache and configuration cache are already enabled). Splitting a
service out later is far cheaper than merging separate repositories together.

## Decision 2 — Gradle (Kotlin DSL) over Maven

The build is a Gradle multi-project build using the Kotlin DSL, with a
[version catalog](../../gradle/libs.versions.toml) as the single source of truth for versions.

**Rationale.**

- **Multi-project ergonomics.** Convention configuration is applied once in the root
  `subprojects {}` block. Maven's equivalent requires each module to declare a `<parent>` and repeat
  plugin configuration.
- **Version catalog.** `libs.versions.toml` is a typed, IDE-completed, single-source version
  registry shared by every module. It is strictly better than a wall of `<properties>` version
  entries, and it makes "what version of X are we on?" a one-file answer.
- **Build performance.** The configuration cache and build cache are enabled; incremental
  whole-tree builds complete in seconds, which matters because the tree will keep growing.
- **Kotlin DSL over Groovy.** Static typing gives real IDE completion and compile-time errors in
  build scripts instead of runtime `MissingMethodException`s.

**Trade-off accepted.** Kotlin DSL has slower *cold* script compilation than Groovy, and Gradle is
less familiar than Maven to some enterprise Java teams. The per-change ergonomics won.

**Note.** An earlier partial Maven scaffold existed in this repository (root aggregator POM plus
module POMs, no library implementation). It was removed as part of this phase — two competing build
systems in one repository is a defect, not an option. A copy was retained outside the repo during
migration. Its version research informed, but did not dictate, the numbers below; several of its
coordinates turned out to be wrong (see "What the verification actually caught").

## Decision 3 — Java 25 LTS + Spring Boot 4.0.7 + Spring Cloud 2025.1.2

**We did NOT fall back to Java 21 / Boot 3.5.** The Java 25 + Boot 4 combination was verified working
end to end, so the primary target stands.

Verified working combination:

| Component | Version | How verified |
|---|---|---|
| Java toolchain | **25.0.4 LTS** (Microsoft OpenJDK, arm64) | `JavaLanguageVersion.of(25)`, `options.release = 25`; whole tree compiles and tests pass |
| Gradle | **9.6.1** (wrapper-pinned) | `./gradlew build` succeeds |
| Spring Boot | **4.0.7** (→ Spring Framework 7.0.8) | resolved from `spring-boot-dependencies` BOM |
| Spring Cloud | **2025.1.2** (→ Cloud modules 5.0.2) | resolved from `spring-cloud-dependencies` BOM |
| Spring Security | **7.0.6** | managed by the Boot BOM |
| Hibernate | 7.2.19.Final | managed by the Boot BOM |
| Flyway | 11.14.1 | managed by the Boot BOM |
| PostgreSQL driver | 42.7.11 | managed by the Boot BOM |
| Nimbus JOSE+JWT | 10.4 | managed by the Boot BOM |
| Testcontainers | 2.0.5 | managed by the Boot BOM |

### Why Boot 4.0.7 and not the newer 4.1.0

Spring Boot 4.1.0 is GA, but `spring-cloud-dependencies:2025.1.2` — the newest release train —
declares `<spring-boot.version>4.0.7</spring-boot.version>`. It is built and tested against the
4.0.x line. Since this platform leans heavily on Spring Cloud (Eureka, Gateway, OpenFeign,
LoadBalancer), we pin to the version the Cloud train expects rather than run an untested pairing.
Revisit when a Boot 4.1-compatible Cloud train ships.

## What the verification actually caught

This is why Phase 1A resolved dependencies for real instead of writing plausible-looking version
numbers. Four concrete defects surfaced, each of which would have blocked a later phase:

1. **`spring-cloud-starter-gateway` no longer exists for Boot 4.** In the 2025.1.x train the gateway
   starter was renamed/split. The legacy coordinate's last release is **4.3.5** (Boot 3.x only); the
   Boot 4 coordinates are `spring-cloud-starter-gateway-server-webflux` and
   `-server-webmvc` (5.0.2). Would have blocked Phase 1D.

2. **Testcontainers 2.x renamed every module artifact.** `org.testcontainers:junit-jupiter` and
   `org.testcontainers:postgresql` stop at 1.21.4; under the 2.0.5 the Boot BOM manages, they are
   `org.testcontainers:testcontainers-junit-jupiter` and `testcontainers-postgresql`. The build
   failed with `Could not find org.testcontainers:junit-jupiter:` until corrected. Would have blocked
   every integration test.

3. **springdoc-openapi 3.1.0 silently upgraded the whole platform to Boot 4.1.0.** It depends on
   `spring-boot-autoconfigure:4.1.0`, which won Gradle conflict resolution:
   `By conflict resolution: between versions 4.1.0 and 4.0.7`. The result was a platform running
   Boot 4.1.0 under a Cloud train tested against 4.0.7 — with no error, just a quietly untested
   combination. Pinned to **springdoc 3.0.3** (the 3.0.x line targets Boot 4.0.x); the resolved
   `spring-boot` then stays at 4.0.7.

4. **`bcpkix-jdk18on:1.85.2` does not exist.** `bcprov-jdk18on` publishes 1.85.2 but `bcpkix-jdk18on`
   stops at 1.85, and the BouncyCastle modules must be version-aligned. Both pinned to **1.85**.
   (This version came from the earlier Maven scaffold and was simply wrong.)

Additionally, a separate Testcontainers BOM import was **removed**: the Boot BOM already manages
Testcontainers, and importing a second BOM re-creates exactly the silent-conflict class of bug that
springdoc caused.

## Decision 4 — Shared libraries are `compileOnly` against Spring

`libs/common-web` and `libs/common-security` declare their Spring dependencies as `compileOnly`
rather than `api`/`implementation`.

**Rationale.** Consuming services bring their own web/security stack via Boot starters. If the shared
library exported those dependencies transitively, it would force a servlet stack onto every consumer
(including non-web modules) and could fight the consumer's starter over versions.

**Consequence.** `common-web` cannot assume Spring Security is present at runtime. Because Spring
resolves `@ExceptionHandler` attribute types when it registers an advice bean, the security-related
handlers live in a **separate** `SecurityProblemExceptionHandler`, registered by auto-configuration
only when `AccessDeniedException` is on the classpath. Putting them in the main advice would throw
`NoClassDefFoundError` in any service without Spring Security.

## Decision 5 — ZooKeeper is coordination-only

ZooKeeper is provisioned in `infra/docker-compose.yml` for distributed locks and leader election.
**Eureka owns service discovery.** No service may depend on
`spring-cloud-starter-zookeeper-discovery` — doing so creates two competing registries that disagree
about instance liveness. See [`infra/zookeeper/README.md`](../../infra/zookeeper/README.md).

## Consequences

- Every module inherits the Java 25 toolchain, Spotless (google-java-format), and Checkstyle from the
  root build; no module can drift.
- Adding a dependency means adding an alias to `libs.versions.toml` — versions are never inlined.
- `./gradlew build` is a genuine whole-platform gate from day one, because empty modules for
  Phases 1B–1E are already wired into `settings.gradle.kts`.
- The Checkstyle `ConstantName` rule was relaxed to permit `log`/`logger`, matching the universal
  Spring-ecosystem idiom.
- Spotless with google-java-format requires `--add-exports` JVM flags for the Gradle daemon on
  JDK 16+; these are set in `gradle.properties`. Removing them breaks `spotlessCheck`.

## Revisit when

- A Spring Cloud train targeting Boot 4.1+ ships → re-evaluate the Boot pin and springdoc 3.1.x.
- Whole-tree build time exceeds ~2 minutes on CI → introduce affected-module-only builds.
- A second team needs independent release cadence for a service → consider extracting that service.
