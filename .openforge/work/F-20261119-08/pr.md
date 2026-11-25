# Revoke access tokens immediately on admin disable or demote (TD-ADM-02)

Branch `forge/F-20261119-08-token-revocation`, head `48847f8f`. It is built on F-20261119-03 (a04ad610), so merge F-01 first, then F-03, then this branch.

## Why
When an admin disabled or demoted a user, only the refresh tokens were revoked. The user's access token kept its old roles for up to its 10-minute lifetime, so a disabled admin could go on acting for up to 10 minutes.

## What changed
- **auth `AdminUserService`:** after a disable, or a role removal, commits, it writes `access-deny:{sub}` to Redis. The value is a cutoff in epoch seconds. A Lua script stores the larger of the old and new values, so the cutoff only ever moves forward. The entry's TTL is 660 s (token TTL + 60 s). Re-enabling a user never deletes the entry, and refused or rolled-back operations write nothing.
- **Gateway:** `DenyListReactiveJwtDecoder` wraps the existing decoder. **auth:** `AccessTokenDenyValidator` is added to its own JWT decoder. Both reject a token whose `iat <= cutoff` with `BadJwtException` or `JwtValidationException`, which gives a 401 problem body (mapping proven from the Spring Security 7.0.6 source in evidence.md). A null or blank `sub` is rejected without any Redis lookup.
- **Redis down:** fails open, with a 500 ms timeout, a WARN and a `*.access_deny.check.unavailable` counter. Exposure is no worse than today's 10-minute TTL.
- **`libs/common-security`:** new framework-free `AccessTokenDenyList`, holding the key format and the comparison. It is purely additive.
- **SecurityConfig in gateway and auth:** only the decoder wiring changes. Route rules are untouched.
- **Docs:** `docs/services/auth-service.md`.

## Evidence
- `forge-verify --changed` PASS: env reference check, plus common-security, auth-service and gateway tests. 41 suites, 185 tests, 0 skipped, 0 failures. 33 of them are new.
- QA: AC-13..AC-17 each map to passing tests in the JUnit XML. `forge-gate` PASS. 301 production lines (class M, limit 600).

## Review
Independent code review and security review both approved at 48847f8f in the first round, with no blocker or major findings. Design decision: ADR 0005 (Accepted).

## Known issues (not blocking, tracked in the backlog)
- **Accepted residual risk B-8:** the downstream services validate JWTs but cannot reach Redis. A pod inside the namespace that holds a revoked token can replay it directly to a service for up to 10 minutes.
- **Clock skew between auth replicas** could let a pre-cutoff token through. Either add a 2 s skew allowance or monitor NTP offset.
- **Silent fail-open:** if Redis evicts or loses an entry, the token is accepted with no metric. Owner decision X-1 (2026-11-20): the shared Redis runs `maxmemory-policy noeviction` plus memory alerts. A Redis restart without persistence still drops entries, with exposure bounded by the 10-minute TTL.
- **WARN floods:** during a Redis outage the WARN fires on every request and should be rate-limited.
- **Test gaps:**
  - The end-to-end "cutoff advances" check uses `>=`.
  - The 5 s bound is not timed.
  - The AC-17 WARN log line is not asserted.
  - The gateway tests mock Redis.

## Deploy
No migration and no new env var. auth and the gateway can be deployed in either order: the check is inert until auth writes entries.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
