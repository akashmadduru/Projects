# Auth rate limits shared across replicas, OTP caps, OTP failures count toward lockout (TD-P6-11)

Branch `forge/F-20261119-06-auth-hardening`, head `fca9bc19`. It is stacked on F-20261119-03 (a04ad610). Merge order: F-01, F-03, then this branch.
F-08 also changes auth config files (application.yml, SecurityConfig), so whichever of F-06 and F-08 merges second will get a small merge conflict there.

## Why
- The auth per-IP limiter kept its counters in memory on each replica, so running N replicas multiplied every limit by N.
- OTP login had no per-phone cap and no daily cap. SMS sends were limited only by a 30 s cooldown and the per-IP bucket.
- Wrong OTPs never counted toward the account lockout.

## What changed
- **`SharedWindowCounter`** (new): a fixed-window counter in Redis, using one Lua `INCR`+`PEXPIRE` call per request so the count is atomic. Keys are `auth:rl:{path}:{ip}:{idx}`, `auth:otp:phone:{E164}:{idx}` and `auth:otp:day:{E164}:{idx}`, each with a TTL of window + 30 s. If Redis fails (closed port, script error or 250 ms timeout), it falls back to a bounded counter on the instance and logs a throttled WARN.
- **`RateLimitFilter`**: now uses the shared counter. It is keyed on the already-resolved client IP; the IP logic itself is untouched. The 429 contract (`AUTH_RATE_LIMITED`, no `Retry-After`) is unchanged and now pinned by a test. The bucket4j dependency is removed.
- **OTP caps**: 3 requests per 15 minutes and 10 per day for each phone. Caps are checked before the user lookup, so known and unknown phones get the same 202. Setting a cap to `0` turns it off (`AUTH_OTP_PER_PHONE_CAPACITY`, `AUTH_OTP_PER_PHONE_DAILY`). The caps count requests, not SMS sends.
- **OTP lockout**: a wrong OTP for a known user with a password credential calls `recordFailure`, but not while the account is already locked, so it cannot renew an active lock. `AUTH_ACCOUNT_LOCKED` is returned only after a correct code, so the response never reveals lock state. Unknown, unverified and OTP-only users record nothing; lockout for OTP-only users is deferred to backlog B-9.
- **Docs**: auth-service.md, api.md, overview.md, gateway.md and the environment reference (regenerated).

## Evidence
- `forge-verify --changed` passes at fca9bc19: the env reference check and the auth-service tests, 23 suites and 101 tests, 0 skipped, 0 failures. The new tests are a Redis Testcontainer integration test for two instances, a 50-thread atomicity test, and tests for the caps, the disabled caps and the lockout.
- QA maps AC-1..AC-9 to passing tests in the JUnit XML. `forge-gate` passes. The change is about 270 production lines (class M).

## Review
- Code and security reviews were independent and took 2 rounds. Both approve at fca9bc19, with no blocker or major findings.
- Round 1 found, through both reviewers independently, that wrong OTPs could keep a victim's account locked indefinitely. This is fixed and tested.
- Design decisions are recorded in ADR 0006.

## Known issues (not blocking, in the backlog)
- **Re-lock after expiry:** failed attempts reset only on a successful login. One wrong attempt right after a lock expires starts a new 15-minute lock on both the password and OTP paths. The "never permanent" wording in the docs overstates the bound.
- **Add-phone SMS pumping:** `POST /api/auth/me/phone` is not yet routed through these caps. It is scheduled after F-07 merges.
- **Redis outage:** the fallback map can be cleared all at once, which resets the caps on that instance.
- **Redis memory:** unknown phones still create keys under a botnet. Owner decision X-1 (2026-11-20): the shared Redis runs `maxmemory-policy noeviction`, so F-08 revocation entries are never dropped, plus memory alerts. Key growth is limited by the per-IP limiter and the 24 h 30 s TTL. If memory gets close to the limit, writes fail and the limiter and cache fail open, so the alert must fire before that.
- **Lock-state timing:** a small timing difference reveals lock state. The password path already exposes it.

## Deploy
No migration. Redis is already a dependency of auth-service. The two new env vars have defaults.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
