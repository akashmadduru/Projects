# F-20261119-01 Referral claim abuse checks (TD-P8-13) - Tasks

Conventions
- Worktree root: `c:\Users\Akash\Desktop\Projects\.openforge\worktrees\F-20261119-01` (call it WT). Run commands from WT.
- GRADLE = `bash /c/Users/Akash/Desktop/Projects/.openforge/scripts/gradle.sh -C .` (formats first, prints `gradle_rc=`). One build at a time.
- USER_TEST = `GRADLE :services:user-service:user-service-app:test` (add `--tests <Class>` for a single class if the script passes it through; otherwise run the full module test).
- Main-code paths are relative to `services/user-service/user-service-app/src/main/java/com/acme/ecommerce/user/`, test paths to `services/user-service/user-service-app/src/test/java/com/acme/ecommerce/user/`.
- Tasks 1-6 are PR 1 (V5 migration, ClientIp, IpFingerprint, recording, cleanup). Tasks 7-10 are PR 2 (error codes, refusal checks). Tasks 11-14 are docs, QA and measurement and go with whichever PR they belong to (docs for PR 2 content go in PR 2). Each task leaves the build green, so the split is clean at the end of task 6.
- No raw IP in any table, log line or response in any task.

## PR 1

### 1. Migration V5, referral config, test deps
- Owner: backend-engineer
- Goal: add the additive V5 migration exactly as in design.md section 2 (table `users.referral_ip_activity` PK (user_id, ip_hash), index on last_seen_at, column `referral_claims.claim_ip_hash char(64)`, partial index). Add nested `UserProperties.Referral` (`Duration minCodeAge=10m`, `ipLookback=30d`, `ipRetention=30d`, `String ipHashSalt` with `@ToString.Exclude`; WARN at startup if `ipRetention < ipLookback`). Map `user.referral.*` in `application.yml` to `USER_REFERRAL_MIN_CODE_AGE`, `USER_REFERRAL_IP_LOOKBACK`, `USER_REFERRAL_IP_RETENTION`, `USER_REFERRAL_IP_HASH_SALT` (default blank). Set a fixed salt in `application-test.yml`. Add test-scope `libs.spring.security.test` and `libs.spring.boot.webmvc.test` to the module `build.gradle`. Create `ReferralAbuseIntegrationTest` (extends `AbstractUserIntegrationTest`) with a first test that the table, column and PK exist and that `UserProperties.Referral` binds the defaults.
- Files: `services/user-service/user-service-app/src/main/resources/db/migration/V5__referral_abuse_checks.sql` (new), `config/UserProperties.java`, `src/main/resources/application.yml`, `src/test/resources/application-test.yml`, `services/user-service/user-service-app/build.gradle`, `membership/ReferralAbuseIntegrationTest.java` (new, test tree).
- ACs covered: AC-8 (properties exist with defaults; behaviour is tested in task 10).
- Verification: `GRADLE :services:user-service:user-service-app:test` (Flyway applies V5 in Testcontainers; all existing tests stay green).

### 2. ClientIp resolver
- Owner: backend-engineer
- Goal: new static `web/ClientIp.resolve(HttpServletRequest) -> Optional<InetAddress>` per design: first `X-Forwarded-For` entry, trim, 64-char cap, strip `[]`, IPv4 `:port`, IPv6 `%zone`, literal-only guard `^[0-9A-Fa-f:.]+$` before `InetAddress.getByName` (no DNS), fall back to `getRemoteAddr()` with the same parser, never throws. Unit test `ClientIpTest` (no Spring): `A, B, C` gives A; missing, blank, `garbage`, `example.com` (no DNS), 5 kB header and multi-value with spaces fall back to remote; `2001:DB8::1`, `2001:0db8:0:0:0:0:0:1`, `[2001:db8::1]`, `2001:db8::1%eth0` are equal; IPv4 never equals IPv6; both sources bad gives empty.
- Files: `web/ClientIp.java` (new), `web/ClientIpTest.java` (new, test tree).
- ACs covered: AC-13, AC-14, AC-15.
- Verification: `GRADLE :services:user-service:user-service-app:test` (ClientIpTest included).

### 3. IpFingerprint hasher
- Owner: backend-engineer
- Goal: new `@Component` `membership/IpFingerprint.hash(InetAddress) -> String`: HMAC-SHA256 (JDK `Mac`) keyed with `user.referral.ip-hash-salt` over `[family byte][address bytes]`, 64 lowercase hex chars. Blank salt: random 32-byte per-process key and one WARN (no raw IP, no salt in the message). Unit test `IpFingerprintTest`: deterministic for the same IP and salt, different salts give different hashes, 64 hex chars, v4 and v6 with equal bytes differ, blank salt still produces stable hashes within one instance, the hash string does not contain the dotted IP.
- Files: `membership/IpFingerprint.java` (new), `membership/IpFingerprintTest.java` (new, test tree).
- ACs covered: AC-5 (hash-only form), AC-15 (v4 vs v6 separation).
- Verification: `GRADLE :services:user-service:user-service-app:test`.

### 4. Record referrer IP on view; record claim IP on claim
- Owner: backend-engineer
- Goal: `EngageController` referral handlers take `HttpServletRequest` and pass `ClientIp.resolve(http)`; response bodies, paths and statuses unchanged. `ReferralService.view(userId, Optional<InetAddress> ip)`: after `codeFor`, upsert `referral_ip_activity` (user_id, hash) only when the row is new or `last_seen_at` is older than 1 hour; skip when the IP is empty. `ReferralService.claim(userId, rawCode, Optional<InetAddress> ip)` stores `claim_ip_hash` on insert (null when the IP is empty). No refusal checks yet. Update existing callers and tests for the new signatures. Tests in `ReferralAbuseIntegrationTest` (service level): view records one row with a 64-hex hash; repeat views within the hour leave one row and do not advance `last_seen_at`; a view with a stale row (backdated 2h) refreshes it; same user from two IPs gives two rows; claim stores a 64-hex `claim_ip_hash`; no column in `referral_ip_activity` or `referral_claims` contains the raw IP string; a user with no code (claim of unknown code) has no activity row and the claim is 404 (AC-16). Create `web/ReferralEndpointsMvcTest` (MockMvc + `jwt()`): GET /api/referrals JSON body has exactly the pre-change fields.
- Files: `web/EngageController.java`, `membership/ReferralService.java`, `membership/ReferralAbuseIntegrationTest.java`, `web/ReferralEndpointsMvcTest.java` (new), any existing test that calls `view`/`claim`.
- ACs covered: AC-5, AC-6, AC-16.
- Verification: `GRADLE :services:user-service:user-service-app:test`.

### 5. Retention cleanup job and account-deletion coverage
- Owner: backend-engineer
- Goal: `ReferralService.purgeExpiredIps()` with `@Scheduled(cron = "${user.referral.ip-cleanup-cron:0 40 3 * * *}")` under `jobLock.runExclusive("user-referral-ip-retention", ...)`: delete `referral_ip_activity` rows with `last_seen_at < now() - ipRetention`; set `claim_ip_hash = NULL` where `claimed_at < now() - ipRetention` (claims stay). `PrivacyService.complete`: add exactly the two lines from design (delete the user's `referral_ip_activity`; null `claim_ip_hash` for the referee). Tests in `ReferralAbuseIntegrationTest`: old activity row deleted, new row kept; old claim keeps its row but hash becomes null, new claim keeps its hash; job is a no-op on an empty table; account deletion removes the user's activity rows and nulls their claim hash (add to the existing privacy test class or a new test here).
- Files: `membership/ReferralService.java`, `privacy/PrivacyService.java`, `membership/ReferralAbuseIntegrationTest.java`.
- ACs covered: AC-7 (CTO condition 4: PrivacyService stays to the two lines).
- Verification: `GRADLE :services:user-service:user-service-app:test`.

### 6. PR 1 checkpoint (QA)
- Owner: qa
- Goal: run the full user-service module test and confirm green with nothing refusing yet (AC-12 existing behaviour unchanged: unknown code 404, own code 422 DOMAIN_RULE_VIOLATION, duplicate 409). Add to `ReferralAbuseIntegrationTest` a regression block for those three if not already covered by existing tests. Record `git diff --shortstat 778d10253422dc39ace9f9f5cba51d288f634753..HEAD` in the task note so PR 1 size is known.
- Files: `membership/ReferralAbuseIntegrationTest.java` (only if gaps).
- ACs covered: AC-12 (404, 422, 409 parts).
- Verification: `GRADLE :services:user-service:user-service-app:test`.

## PR 2

### 7. Error codes in common-core
- Owner: backend-engineer
- Goal: add to `ErrorCode` (following the COUPON_* pattern) `REFERRAL_CODE_TOO_NEW("REFERRAL_CODE_TOO_NEW", 422, "Referral code too new")` and `REFERRAL_IP_MATCH("REFERRAL_IP_MATCH", 422, "Referral not allowed from this network")`. These two entries are the only change outside user-service (CEO decision 1). Add them to the error catalogue in `docs/reference/api.md`. Test: extend an existing `ErrorCode` test if present, otherwise add a small unit test asserting code string, 422 status and uniqueness of codes.
- Files: `libs/common-core/src/main/java/com/acme/ecommerce/common/core/error/ErrorCode.java`, a test under `libs/common-core/src/test/java/...`, `docs/reference/api.md`.
- ACs covered: AC-2, AC-3, AC-10 (codes exist; behaviour in task 8).
- Verification: `GRADLE :libs:common-core:test :services:user-service:user-service-app:test`.

### 8. Refusal checks in claim
- Owner: backend-engineer
- Goal: in `ReferralService.claim`, order: 1 unknown code 404, 2 own code 422 DOMAIN_RULE_VIOLATION, 3 existing claim by referee 409 (`SELECT EXISTS`; keep the `DuplicateKeyException` catch for races), 4 code age (`created_at > now() - minCodeAge`, DB clock, skipped when zero) throws `REFERRAL_CODE_TOO_NEW` with fixed generic text, 5 IP match (EXISTS row for referrer and hash with `last_seen_at > now() - ipLookback`, skipped when lookback is zero or IP empty) throws `REFERRAL_IP_MATCH` with fixed generic text, 6 insert. Throws roll back, so no row stored; detail text must not reveal IP, creation time or counts. Tests in `ReferralAbuseIntegrationTest` (default config; backdate `created_at`/`last_seen_at` via SQL): aged code + different IP gives 204 and a row (AC-1); young code gives `REFERRAL_CODE_TOO_NEW` and no row (AC-2); referrer viewed from X, referee claims from X gives `REFERRAL_IP_MATCH` and no row (AC-3); activity backdated 31 days gives 204 (AC-4); young code + same IP gives only `REFERRAL_CODE_TOO_NEW` (AC-10); refused, then backdate code, retry gives 204, and refused then claim of a different valid code gives 204 (AC-11); same IPv6 written differently matches (AC-15 end to end); code in lower case with surrounding whitespace hits the same checks; `X-Forwarded-For`-style first entry is what is hashed (compare to hash of A, AC-13); two concurrent claims by one referee give exactly one row, the other 409; AC-12 duplicate stays 409 and precedes the age check.
- Files: `membership/ReferralService.java`, `membership/ReferralAbuseIntegrationTest.java`.
- ACs covered: AC-1, AC-2, AC-3, AC-4, AC-10, AC-11, AC-12, AC-13, AC-15.
- Verification: `GRADLE :services:user-service:user-service-app:test`.

### 9. Endpoint-level tests: 401, error shape, no leakage, log check
- Owner: qa
- Goal: extend `web/ReferralEndpointsMvcTest` (MockMvc, `jwt()`): POST claim without JWT gives 401 (AC-12); refused claims return the standard RFC 9457 problem body with the right `code`, and the body contains no IP, timestamp or count (AC-17); GET with `X-Forwarded-For` header returns the unchanged body and records the hash of the first entry (AC-6, AC-13); malformed, blank and 5 kB headers return 2xx/4xx-by-rule, never 5xx (AC-14). CTO condition 3: add a log-capture assertion (for example a Logback `ListAppender` on the root logger, or `OutputCaptureExtension`) around a full view plus claim flow asserting the raw IP string appears in no log line and the salt is absent.
- Files: `web/ReferralEndpointsMvcTest.java`, `membership/ReferralAbuseIntegrationTest.java` (if the log check sits there).
- ACs covered: AC-5 (no log line), AC-6, AC-12 (401), AC-13, AC-14, AC-17.
- Verification: `GRADLE :services:user-service:user-service-app:test`.

### 10. Config tests with non-default values
- Owner: qa
- Goal: new `membership/ReferralAbuseConfigIntegrationTest` with `@TestPropertySource` (age 2h, lookback 1d, retention 1d): a code aged 1h is refused and aged 3h passes (AC-8); activity 2d old is ignored and 12h old matches; purge removes rows older than 1d but keeps 12h-old ones (AC-8 retention). A second test class or nested context with min age 0 and lookback 0: young code and same IP both pass (AC-9), including each check disabled independently.
- Files: `membership/ReferralAbuseConfigIntegrationTest.java` (new), possibly `membership/ReferralAbuseDisabledChecksIntegrationTest.java` (new).
- ACs covered: AC-8, AC-9.
- Verification: `GRADLE :services:user-service:user-service-app:test`.

## Docs, QA, measurement

### 11. Reference docs and tech-debt register
- Owner: backend-engineer
- Goal: `docs/reference/endpoints.md`: note the claim refusal codes and that GET /api/referrals records a hashed IP. `docs/reference/environment-variables.md` and `docs/reference/env-descriptions.json`: add the four `USER_REFERRAL_*` variables with defaults; describe blank salt with one wording only, "fails safe" (it can only miss IP matches, never refuse wrongly; the age check still applies; production must set it), not "fails open" (CTO condition 2). Tech debt: remove TD-P8-13 from `docs/techdebt/phase8.md`, update `docs/techdebt/README.md`, set `remediation-plan.md` row 37 to Fixed, add the row 8 note in `docs/phases/phase-08-retention-and-loyalty.md`. CTO condition 5: file a new register item for the follow-up (wire the salt secret through Helm and fail closed under the `prod` profile) in the tech-debt register.
- Files: `docs/reference/endpoints.md`, `docs/reference/environment-variables.md`, `docs/reference/env-descriptions.json`, `docs/techdebt/phase8.md`, `docs/techdebt/README.md`, `docs/techdebt/remediation-plan.md`, `docs/phases/phase-08-retention-and-loyalty.md`.
- ACs covered: AC-8 (documented defaults).
- Verification: `grep -rn "TD-P8-13" docs/techdebt` shows only the remediation row and the new follow-up reference; `grep -n "fails open" docs/reference/environment-variables.md` returns nothing; `bash -c "python -c 'import json;json.load(open(\"docs/reference/env-descriptions.json\"))'"` parses (JSON valid).

### 12. Full regression and AC audit
- Owner: qa
- Goal: run the whole user-service module test and common-core tests; walk the coverage table below against the test names and confirm every AC has a passing test; confirm no raw IP in logs, tables or responses; confirm only the planned files changed (`git status`, no stray files, `PrivacyService` diff is two lines).
- Files: none (test fixes only if gaps are found).
- ACs covered: AC-1 to AC-17 (audit).
- Verification: `GRADLE :libs:common-core:test :services:user-service:user-service-app:test`.

### 13. Measure the diff size
- Owner: qa
- Goal: run `git diff --shortstat 778d10253422dc39ace9f9f5cba51d288f634753..HEAD` from WT (commit or stage as the workflow requires so the numbers include all work; if the work is uncommitted use `git diff --shortstat 778d10253422dc39ace9f9f5cba51d288f634753` instead and say so). Report insertions + deletions against the 550-line threshold (CTO condition 1). If above 550, report it and recommend the split at the end of task 6 (PR 1 = tasks 1-6, PR 2 = tasks 7-13); do not request an override.
- Files: none.
- ACs covered: none (process condition).
- Verification: `git diff --shortstat 778d10253422dc39ace9f9f5cba51d288f634753..HEAD`

## Coverage table

| AC | Tasks |
|---|---|
| AC-1 | 8, 12 |
| AC-2 | 7, 8, 12 |
| AC-3 | 7, 8, 12 |
| AC-4 | 8, 12 |
| AC-5 | 3, 4, 9, 12 |
| AC-6 | 4, 9, 12 |
| AC-7 | 5, 12 |
| AC-8 | 1, 10, 11, 12 |
| AC-9 | 10, 12 |
| AC-10 | 7, 8, 12 |
| AC-11 | 8, 12 |
| AC-12 | 6, 8, 9, 12 |
| AC-13 | 2, 8, 9, 12 |
| AC-14 | 2, 9, 12 |
| AC-15 | 2, 3, 8, 12 |
| AC-16 | 4, 12 |
| AC-17 | 9, 12 |

CTO conditions: 1 -> task 13; 2 -> task 11; 3 -> task 9; 4 -> task 5; 5 -> task 11.
