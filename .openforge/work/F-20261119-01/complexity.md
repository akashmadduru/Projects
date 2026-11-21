# F-20261119-01 Complexity

| Factor | Score | Reason |
|---|---|---|
| Services or apps touched | 0 | Only user-service. The `libs/common-core` ErrorCode edit is two enum entries in a shared lib, not a service; its effect is counted under API contract. |
| Data change | 1 | Additive V5 migration (IP-record table, claim IP hash column). No backfill, nothing destructive. |
| Public API / event contract | 1 | Additive: two new 422 error codes in the shared enum and the api.md catalogue. Request and response bodies unchanged. |
| Security or money path | 2 | Direct: IP addresses are PII (salted hashing, retention) and the checks guard a reward/fraud path. Reward payment itself is untouched. |
| New dependency or infrastructure | 0 | None expected. Config properties, a scheduled cleanup in the existing app, JDBC and the Testcontainers base that already exists. |
| Estimated changed lines | 1 | About 300-550 including migration, service, controller, cleanup, config, docs and tests for 17 ACs. |

Total: 5

Class: **M**

## What the class requires
requirements, design.md and an ADR, tasks, implement, test, review. No Owner design approval needed (that is L and above).
PR limit is 600 changed lines. Tests are required for every AC.

## Notes for design
- Keep it to what the ACs need: no new service, datastore, vendor or generic fraud framework.
- Run the cleanup as a scheduled job inside user-service.
- The IP hash salt must come from configuration (a secret, not hard-coded). Raw IPs must not appear in any table, log or response.
- Bound or dedupe IP records per referrer so a hammered page does not grow the table unbounded.
- Concurrent claims must still produce exactly one claim row.
- If the estimate nears 600 lines, split the PR rather than ask for an override.

## Design review
Verdict: **ACCEPT**

Reasons
- AC coverage: all 17 ACs are mapped to a change and to a named test (section 3 and the test list in section 5). No AC lacks a test.
- Nothing beyond the ACs:
  - The one new table (`referral_ip_activity`) is the minimum for AC-3, 4, 6 and 7. It is keyed (user_id, ip_hash), which gives the per-referrer bound required in the notes.
  - `ClientIp` and `IpFingerprint` are two small, single-purpose helpers needed for AC-13 to AC-15 and AC-5. They are not frameworks.
  - No new service, datastore or vendor. The HMAC uses the JDK, and the cleanup is a scheduled job under the existing `JobLock`.
  - The build.gradle change adds two test-scope entries that already exist in the catalogue. They are needed for the MockMvc test of AC-6, 12 (401) and 17, so they are justified.
  - The shared client-IP helper in libs/common-web was correctly deferred.
- Public contract: the two new error codes are stated, CEO-approved and documented in api.md. Bodies and paths are unchanged, and the migration is additive and rollback-safe.
- PII: raw IP is never stored, logged or returned. It is an HMAC-SHA256 with a configured secret and a family byte. Retention is enforced by the purge job, and account deletion clears the data. I checked that `AuditInterceptor`, which does record a raw IP, skips GET requests, non-staff callers and paths outside admin, seller and catalog, so it does not capture referral calls. The unset-salt behaviour matches CEO decision 3.
- Size: the 480-line estimate is plausible, and the split plan (PR 1 storage and recording, PR 2 refusal checks and codes) is sound.

Conditions for implementation, not blockers
1. Measure the diff before review. If it exceeds about 550 changed lines, split as planned rather than request an override.
2. Design section 2 calls the blank-salt behaviour "fails open", while CEO decision 3 and the ADR say it fails safe. Use one wording in the docs (`environment-variables.md`) so operators do not read it as a security bypass. The behaviour itself is correct.
3. AC-5's "no log line" has no automated assertion. Add a simple check, for example capturing logs in the integration test and asserting the raw IP string is absent, or record it as a review-checklist item.
4. The `PrivacyService` edit is not required by any AC. It is accepted because it keeps the IP data inside account deletion, and it stays to the two lines described.
5. File the follow-up (wire the secret through Helm and fail closed under the `prod` profile) in the tech-debt register at step 11, as the CEO decided.
