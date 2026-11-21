# F-20261119-06 Complexity: shared limiter, OTP caps and OTP lockout (AC-1..AC-8)

| Factor | Score | Reason |
|---|---|---|
| Services or apps touched | 0 | auth-service only (RateLimitFilter, OtpLoginService, LoginAttemptService, OTP request path). The limiter consumes the resolved IP; no gateway change. |
| Data change | 0 | Redis keys only, TTL-bounded. No DB migration. |
| Public API / event contract | 0 | AC-3 and AC-4 keep the 429 contract and the OTP request response unchanged. |
| Security or money path | 2 | Direct: auth, OTP, lockout, SMS cost. |
| New dependency or infrastructure | 0 | Existing Redis (spring-boot-starter-data-redis already in auth); no new service, datastore or vendor. |
| Estimated changed lines | 1 | Redis limiter with fallback, per-phone and per-day caps, lockout wiring, config: roughly 300-500 production lines. |
| **Total** | **3** | |

## Class: M

Required: requirements, design.md and an ADR, tasks, implement, test, review. No Owner design approval needed. Size limit 600 production lines per PR.

Watch: if the design pushes production lines past 600, re-score to L.
