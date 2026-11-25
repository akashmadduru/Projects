# 0005 Revoke access tokens with a per-`sub` `iat` cutoff in the shared Redis, checked at the gateway and in auth-service, fail-open

## Status
Accepted (2026-11-20, CTO, class M).

## Context
When an admin disables or demotes a user, today only the refresh tokens are revoked (`AdminUserService.java:133`, `:171`). The 10 m access token keeps working (TD-ADM-02). Facts relevant to a fix:
- The gateway and auth-service already share one Redis: the same `REDIS_HOST`, db 0, and no prefix (Helm `deployment.yaml:114-118`, `values.yaml:119,129`).
- The 11 downstream services validate JWTs themselves but have no Redis. Only the gateway is reachable from outside the namespace (`network.yaml:55-72,109-155`), and no service relays a user bearer token.
- `iat` has one-second resolution (`AccessTokenIssuer.java:71`). No acceptance point caches decoded tokens.

## Decision
- **Entry:** key `access-deny:{sub}`, holding the cutoff in epoch seconds, with TTL = access-token TTL + 60 s (660 s).
- **Rule:** a token is revoked when an entry exists and `iat <= cutoff`, or the token has no `iat`. The key and the rule live in `libs/common-security` `AccessTokenDenyList`.
- **Writer:** auth-service `AccessTokenDenyStore` writes the entry after the admin transaction commits, on disable and on demote-with-removal. It uses an atomic Lua `max` so the cutoff only ever moves forward. Enable never deletes the entry.
- **Checks:**
  - the gateway wraps its `ReactiveJwtDecoder` and does a non-blocking GET, returning `BadJwtException` and so a 401;
  - auth-service adds an `OAuth2TokenValidator` to its own decoder.
  - There is no local cache, so revocation takes effect at the next request.
- **Redis unavailable:** fail-open, with a WARN log and an `*.access_deny.check.unavailable` / `auth.access_deny.write.failed` counter. Redis is a soft dependency throughout the platform (the health check is disabled, the `UserSessionCache` and the edge limiter both fail open). Fail-closed would turn a cache outage into a total outage of authenticated traffic. Admin-only fail-closed would lock out the people who respond to the incident. The worst case equals today's 10 m window, and refresh revocation still holds.
- auth-service sets `spring.data.redis.timeout: 500ms` to match the gateway, replacing the Lettuce default of 60 s.

## Alternatives considered
- **Shorter access TTL:** a non-goal, and it multiplies refresh load. Rejected.
- **`jti` deny-list:** auth does not track the live `jti`s of a user. Rejected.
- **Check in every resource server:** needs Redis in 11 services, so it would be class L and need its own ADR. Deferred to the backlog.
- **Local cutoff cache:** breaks the 5 s bound. Rejected.
- **Fail-closed (B) or admin-only fail-closed (C):** an availability cost greater than the bounded exposure. Rejected.

## Consequences
- A disabled or demoted user's token is rejected at the edge and by auth-service within milliseconds.
- Cost: one Redis GET per authenticated request.
- A re-login in the same second as the cutoff is rejected once; the client's refresh recovers.
- **Accepted residual risk (backlog B-8, CTO condition 6):** pods inside the namespace can replay a revoked token directly to a downstream service for up to the 10 m access-token TTL. This is the same ClusterIP trust as ADR 0002. Any later fix needs a new ADR.
- Other residual risks:
  - during a Redis outage the platform falls back to the 10 m window;
  - issuer clock skew opens a window equal to the skew.
- Rollback: revert the image; the keys expire within 11 m. There is no data migration.

## Work id
F-20261119-08 (portfolio O-01 part 3, TD-ADM-02). Design: `.openforge/work/F-20261119-08/design.md`.
