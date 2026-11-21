# F-20261119-01 Referral claim abuse checks (TD-P8-13) - Design

Paths are relative to `services/user-service/user-service-app/src/` unless they start at the repo root.

## 1. Current behaviour
- `GET /api/referrals` -> `EngageController.referrals` (`main/java/.../user/web/EngageController.java:78-81`) -> `ReferralService.view` (`main/java/.../user/membership/ReferralService.java:38-55`), which creates the code lazily via `codeFor` (`:108-143`). No IP is read or stored.
- `POST /api/referrals/claim` -> `EngageController.claim` (`:83-87`) -> `ReferralService.claim` (`ReferralService.java:57-82`): normalises the code (trim, upper case), 404 if unknown, 422 `DOMAIN_RULE_VIOLATION` for own code, inserts a row, and maps `DuplicateKeyException` to 409 (PK `referee_user_id`). That is all it checks.
- Schema `main/resources/db/migration/V3__membership_prefs_referrals.sql:71-86`: `referral_codes(user_id PK, code UNIQUE, created_at)` and `referral_claims(referee_user_id PK, ...)`. Latest migration is V4.
- Client IP: the same inline `X-Forwarded-For` first-entry pattern appears in `libs/common-web/.../audit/AuditInterceptor.java:90-96` (private) and `services/payment-service/.../web/WalletController.java:101-103`. Neither canonicalises the IP or checks it is valid.
- Scheduled data jobs run under `JobLock.runExclusive` (`libs/common-web/.../job/JobLock.java:49`), e.g. `MembershipService.java:245-251`. `@EnableScheduling` is already on `UserServiceApplication`.
- Settings: `UserProperties` (`main/java/.../user/config/UserProperties.java`, prefix `user`, only `jwt` today); `user.referral.reward-coins` is read with `@Value` (`ReferralService.java:32`).
- Account deletion clears referral codes (`privacy/PrivacyService.java:501-512`).
- `ErrorCode` (`libs/common-core/.../error/ErrorCode.java`) has no referral-specific codes.

## 2. Proposed change
**Components (all in user-service unless noted)**
- `web/ClientIp` (new, static, about 50 lines): `resolve(HttpServletRequest) -> Optional<InetAddress>`. Takes the first comma-separated `X-Forwarded-For` entry, trims it, caps it at 64 characters, removes `[...]` brackets, an IPv4 `:port` suffix and an IPv6 `%zone`. It accepts only literals (`^[0-9A-Fa-f:.]+$`) before calling `InetAddress.getByName`, so it never does a DNS lookup. If the entry is missing, blank or unparsable it uses `getRemoteAddr()` with the same parser. It never throws: if neither source parses, it returns empty. IPv6 forms written differently parse to the same 16 bytes.
- `membership/IpFingerprint` (new `@Component`, about 40 lines): `hash(InetAddress) -> String`, which is HMAC-SHA256 (JDK `javax.crypto.Mac`) keyed with `user.referral.ip-hash-salt` over `[family byte][address bytes]`, returned as 64 hex characters. The family byte keeps v4 and v6 apart. A raw IP never leaves this method and is never logged.
- `ReferralService` (changed):
  - `view(userId, Optional<InetAddress> ip)`: after `codeFor`, upserts `referral_ip_activity`. It only writes when the row is new or `last_seen_at` is more than 1 hour old, to cut write load from a hammered page. The response record is unchanged.
  - `claim(userId, rawCode, Optional<InetAddress> ip)` checks in this order:
    1. Code lookup: 404 if unknown.
    2. Own code: 422 `DOMAIN_RULE_VIOLATION`.
    3. Existing claim by this referee: 409 (`SELECT EXISTS`). The insert still catches `DuplicateKeyException` for races.
    4. Code age: `created_at > now() - minCodeAge` gives 422 `REFERRAL_CODE_TOO_NEW` (database clock, skipped if the age is 0).
    5. IP match: `EXISTS` row for (referrer, hash) with `last_seen_at > now() - ipLookback` gives 422 `REFERRAL_IP_MATCH` (skipped if the lookback is 0 or the IP is empty).
    6. Insert with `claim_ip_hash`.
    The exceptions use fixed, generic detail text such as "This referral code cannot be used yet" and "This referral code cannot be used from this network". Throwing rolls back the transaction, so no row is written.
  - `purgeExpiredIps()`: `@Scheduled(cron = "${user.referral.ip-cleanup-cron:0 40 3 * * *}")` calling `jobLock.runExclusive("user-referral-ip-retention", ...)`. It deletes `referral_ip_activity` rows with `last_seen_at < now() - ipRetention` and sets `claim_ip_hash = NULL` where `claimed_at < now() - ipRetention`. Claims stay.
- `EngageController`: both referral handlers take `HttpServletRequest` and pass `ClientIp.resolve(http)`. Paths, bodies and statuses are unchanged.
- `PrivacyService.complete`: add `DELETE FROM users.referral_ip_activity WHERE user_id = :u` and null `claim_ip_hash` for the referee (one line each). This keeps the IP-hash data inside account deletion.
- `libs/common-core` `ErrorCode`: `REFERRAL_CODE_TOO_NEW("REFERRAL_CODE_TOO_NEW", 422, "Referral code too new")` and `REFERRAL_IP_MATCH("REFERRAL_IP_MATCH", 422, "Referral not allowed from this network")`. CEO-approved.

**Interfaces.** There are no new endpoints, DTOs or events. Error catalogue: two rows in `docs/reference/api.md`, with the RFC 9457 shape unchanged. `docs/reference/endpoints.md` gets a note on the claim refusal codes.

**Data: `main/resources/db/migration/V5__referral_abuse_checks.sql`** (additive, forward-only)
```sql
CREATE TABLE users.referral_ip_activity (
    user_id uuid NOT NULL, ip_hash char(64) NOT NULL, last_seen_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, ip_hash));                        -- dedupe: one row per referrer per IP
CREATE INDEX ix_referral_ip_activity_seen ON users.referral_ip_activity (last_seen_at);
ALTER TABLE users.referral_claims ADD COLUMN claim_ip_hash char(64);
CREATE INDEX ix_referral_claims_ip_age ON users.referral_claims (claimed_at) WHERE claim_ip_hash IS NOT NULL;
```

**Config.** `UserProperties` gets a nested `Referral` class (`Duration minCodeAge = 10m`, `ipLookback = 30d`, `ipRetention = 30d`, `String ipHashSalt` with `@ToString.Exclude`). `application.yml` `user.referral.*` maps them to `USER_REFERRAL_MIN_CODE_AGE`, `USER_REFERRAL_IP_LOOKBACK`, `USER_REFERRAL_IP_RETENTION` and `USER_REFERRAL_IP_HASH_SALT` (default blank). `reward-coins` stays on `@Value`.
- Blank salt: a random 32-byte key is generated per process and a WARN is logged once. This fails open: hashes stop matching across restarts and replicas (missed matches only, never false refusals), and the age check still applies. Production must set the secret. This is documented in `docs/reference/environment-variables.md`.
- `application-test.yml` sets a fixed salt.
- If `ipRetention < ipLookback`, a WARN is logged at startup (the effective lookback is the retention).

## 3. AC mapping
| AC | Met by |
|---|---|
| 1 | Checks pass, so the insert runs and returns 204. Test: aged code (`created_at` set back) plus a different IP. |
| 2 | Step 4 returns 422 `REFERRAL_CODE_TOO_NEW`, and the rollback means no row. |
| 3 | `view` records the hash; step 5 matches it and returns 422 `REFERRAL_IP_MATCH`. |
| 4 | Step 5 filters on `last_seen_at > now() - ipLookback`. Test backdates the row to 31 days. |
| 5 | `claim_ip_hash` holds the HMAC. Tests assert the column is 64 hex characters and no column contains the raw string. Nothing logs the IP. |
| 6 | Upsert in `view`. The response record is unchanged (asserted on the JSON in the MockMvc test). |
| 7 | `purgeExpiredIps` is called directly. Old activity rows are deleted, old claim hashes are nulled, and new rows and the claim rows stay. |
| 8 | `UserProperties.Referral`. A second test class uses `@TestPropertySource` (2h age, 1d lookback and retention). |
| 9 | `isZero()` skips the respective check. Test sets both to 0. |
| 10 | Step 4 runs before step 5. Test: young code plus same IP gives only `REFERRAL_CODE_TOO_NEW`. |
| 11 | A refusal throws before the insert, so the PK slot stays free. Test: refused, then backdate the code, then 204. |
| 12 | Steps 1 to 3 and the insert are unchanged. The 401 is covered by a MockMvc call without a JWT. |
| 13 | `ClientIp` takes the first entry. Unit test `A, B, C` gives A, and the integration test compares hashes. |
| 14 | `ClientIp` falls back to the remote address and never throws. Unit tests cover missing, blank, `garbage`, `example.com` (no DNS) and a 5 kB header. |
| 15 | `InetAddress` bytes. Unit test: `2001:DB8::1` = `2001:0db8:0:0:0:0:0:1` = `[2001:db8::1]` = `2001:db8::1%eth0`, and v4 is not v6. |
| 16 | No code means 404 from step 1. Test asserts no `referral_ip_activity` row for that user. |
| 17 | Platform `CodedException` problem body. The MockMvc test asserts `code` and that the body has no IP, timestamp or count. |

## 4. Alternatives considered
- **Store the raw IP and compare in SQL (`inet`):** simpler queries and subnet matching, but it keeps PII the CEO said to minimise. Rejected.
- **Plain SHA-256 without a secret:** the IPv4 space is small enough to brute-force, so a leaked table reveals the IPs. Rejected in favour of a keyed HMAC.
- **Hold refused claims for review (the register's alternative):** needs a status, a queue and an admin UI, which are non-goals. Rejected; refusal is not stored (CEO decision 4).
- **Shared `ClientIp` in `libs/common-web`:** would also fix the audit and payment copies, but it widens scope beyond the CEO's two-enum exception. Noted for a follow-up; kept local.
- **Append-only activity log:** grows without bound when the page is hammered. Rejected in favour of a PK upsert.

## 5. Risks, failure modes, rollback, tests
- **Shared NAT/household false positives (accepted by the CEO):** mitigation is `ipLookback=0` with no deploy.
- **Spoofed XFF when the gateway is bypassed:** evades the IP check only. The age check is the backstop. The service is not publicly routable.
- **DNS lookup from a hostile header:** prevented by the literal-only guard, covered by the `example.com` unit test.
- **Salt leak through `toString` or actuator:** `@ToString.Exclude`. `/env` and `/configprops` are not exposed (`application.yml:178`).
- **Blank salt in production:** weaker matching, never an outage or a wrong refusal. Logged as WARN.
- **Extra write on every GET `/api/referrals`:** throttled to once an hour per (user, IP). It is a PK upsert.
- **Migration:** additive. An app rollback leaves V5 in place, which is harmless: the old code ignores the table and column. No down-migration.
- **Feature off without rollback:** `USER_REFERRAL_MIN_CODE_AGE=0` and `USER_REFERRAL_IP_LOOKBACK=0`.
- **Tests:**
  - `test/.../membership/ReferralAbuseIntegrationTest` (Testcontainers, service level: ACs 1-11 and 16).
  - `ReferralAbuseConfigIntegrationTest` (non-default properties: AC-8 and AC-9).
  - `test/.../web/ClientIpTest` (unit: ACs 13-15).
  - `test/.../web/ReferralEndpointsMvcTest` (MockMvc plus `jwt()`: ACs 6, 12 (401) and 17). It adds `libs.spring.security.test` and `libs.spring.boot.webmvc.test` to test scope; both are already in the catalogue and used by other services.
- **Size:** estimated at about 480 changed lines. If it goes over 600, split into PR 1 (V5, `ClientIp`, `IpFingerprint`, recording, cleanup) and PR 2 (the refusal checks and error codes).

## 6. Files expected to change
- `libs/common-core/src/main/java/com/acme/ecommerce/common/core/error/ErrorCode.java`
- `services/user-service/user-service-app/build.gradle` (two test deps)
- `.../main/java/com/acme/ecommerce/user/membership/ReferralService.java`, `.../membership/IpFingerprint.java` (new)
- `.../main/java/com/acme/ecommerce/user/web/EngageController.java`, `.../web/ClientIp.java` (new)
- `.../main/java/com/acme/ecommerce/user/config/UserProperties.java`, `.../privacy/PrivacyService.java`
- `.../main/resources/application.yml`, `.../main/resources/db/migration/V5__referral_abuse_checks.sql` (new)
- `.../test/resources/application-test.yml`, plus the four test classes above (new)
- `docs/reference/api.md`, `docs/reference/endpoints.md`, `docs/reference/environment-variables.md`, `docs/reference/env-descriptions.json`
- `docs/techdebt/phase8.md` (remove TD-P8-13), `docs/techdebt/README.md`, `docs/techdebt/remediation-plan.md` (row 37 to Fixed), `docs/phases/phase-08-retention-and-loyalty.md` (row 8 note)
