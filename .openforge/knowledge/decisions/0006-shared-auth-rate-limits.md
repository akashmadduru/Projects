# 0006 Auth abuse limits are shared Redis fixed-window counters; OTP failures feed the existing per-account lockout

## Status
Proposed (2026-11-20). Class M. Needs CTO acceptance; no Owner design approval required.

## Context
- The auth per-IP limiter keeps bucket4j buckets in a per-replica `ConcurrentHashMap` (auth `ratelimit/RateLimitFilter.java:79`), so N replicas multiply every limit.
- SMS cost is bounded only by a 30 s per-user cooldown (`PhoneVerificationService.java:29`).
- OTP verify never calls `LoginAttemptService` (`OtpLoginService.java:88-106`).
- Redis is already a dependency of auth (`auth-service-app/build.gradle:38`). Only `bucket4j-core` is on the classpath (`build.gradle:62`, `libs.versions.toml:139`). There is no bucket4j Redis or Lettuce module.
- Lockout state lives on `user_credentials`, keyed by user id (`UserCredential.java:42-46`). Social-only accounts have no row (`UserCredential.java:15-17`).

## Decision
- One auth component, `SharedWindowCounter`, counts fixed windows in Redis:
  - key `<name>:<epoch/window>`;
  - a Lua script runs `INCR` plus `PEXPIRE window+30s` atomically.
- If Redis fails, the counter **fails open to a per-instance in-memory counter** with the same limits:
  - it logs a throttled WARN;
  - it skips Redis for 5 s;
  - `spring.data.redis.timeout` is set to 250 ms.
- Three users of the counter:
  - `RateLimitFilter` keys on the already-resolved client IP (ADR 0002 unchanged). The 429 contract is unchanged: `AUTH_RATE_LIMITED` with no `Retry-After`.
  - OTP request caps per normalised E.164 phone: 3 per 15 min and 10 per UTC day, both configurable. The caps apply to every valid number before the user lookup and are silent (always 202), so they are no oracle.
  - The `bucket4j-core` dependency is removed.
- OTP verify failures for a known, verified phone call `LoginAttemptService.recordFailure(userId)`. The lock is reported only after a correct code. Success calls `recordSuccess`. Unknown phones record nothing.

## Alternatives considered
- **bucket4j Redis integration:** adds a dependency from the `bucket4j_jdk17-*` coordinate family to a soft control. It saves nothing over a 3-line script. Rejected.
- **Reuse the gateway `RateLimitWebFilter` counter via a shared lib:** it is reactive, its INCR and EXPIRE are not atomic, and the lib would put spring-data-redis on every servlet service. Rejected; backlog item.
- **Fail-closed on Redis outage:** turns a cache outage into a login outage. Rejected.
- **A Redis lockout store for social-only accounts:** gives the lock two sources of truth. Deferred. These accounts stay bounded by 5 attempts per code times the daily SMS cap.

## Consequences
- Limits hold across replicas while Redis is up. During an outage they degrade to today's per-replica behaviour.
- A fixed window allows up to 2x the burst at a window edge. This is accepted for a soft control; the per-account lockout stays the real control.
- An attacker can exhaust a victim's OTP caps for at most 15 min or one UTC day. An attacker can also lock a victim's password login through wrong OTPs, for at most 15 min (existing backoff). These match the email path's accepted trade-off (`AuthErrorCode.java:23-27`).
- Gap: OTP-only accounts gain no lockout counter.
- Rollback is an image revert. Keys expire within 24 h, and there is no migration.

## Work id
F-20261119-06 (portfolio O-01 part 1, TD-P6-11). Design: `.openforge/work/F-20261119-06/design.md`.
