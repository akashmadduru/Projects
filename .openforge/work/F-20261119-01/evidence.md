# Evidence F-20261119-01

## Implementation verify at 1fc7bf34 (CEO, fresh results)
Command: `node .claude/hooks/forge-verify.mjs --changed --base 778d1025` in the worktree; test results deleted before the run
```
scope outside allowed paths: 0 files
PASS gradle :libs:common-core:test :services:user-service:user-service-app:test (exit 0)
BUILD SUCCESSFUL in 37s, gradle_rc=0
JUnit XML: tests 71, skipped 0, failures 0, errors 0
worktree clean after build
```

## Implementation verify (CEO, independent of the engineer's report)
Command: `node .claude/hooks/forge-verify.mjs --changed --base 778d1025` in the worktree, head 4a9e48f9
```
PASS gradle :libs:common-core:test :services:user-service:user-service-app:test (exit 0)
BUILD SUCCESSFUL, gradle_rc=0
JUnit XML: 15 report files, tests 66, skipped 0, failures 0, errors 0
worktree clean after build
```

## Implementation verify at 78a7b4f6 (CEO, fresh results)
```
scope outside allowed paths: 0 files
PASS gradle :libs:common-core:test :services:user-service:user-service-app:test (exit 0), gradle_rc=0
JUnit XML: tests 77, skipped 0, failures 0, errors 0 (incl. ReferralForwardedForTomcatTest)
worktree clean after build
```
The AC table below (QA, at 1fc7bf34) was re-checked by the CEO at 78a7b4f6: all 42 cited tests still exist; round 3 only added tests.

## Acceptance criteria (re-checked at 2b229705)
Test classes under `services/user-service/user-service-app/src/test/java/com/acme/ecommerce/user/`. RAI = membership/ReferralAbuseIntegrationTest, CFG = membership/ReferralAbuseConfigIntegrationTest, MVC = web/ReferralEndpointsMvcTest. AC-13 is read as "the entry selected by trusted hops" (objective.md correction).

| AC | Test | Result |
|---|---|---|
| AC-1 | RAI#agedCodeDifferentIpAccepted; MVC#acceptedClaimIs204 | PASS |
| AC-2 | RAI#youngCodeRefused; MVC#refusalBodyIsGeneric (422 REFERRAL_CODE_TOO_NEW) | PASS |
| AC-3 | RAI#sameIpRefused; MVC#refusalBodyIsGeneric (422 REFERRAL_IP_MATCH) | PASS |
| AC-4 | RAI#oldActivityIgnored; CFG#lookbackFromConfig | PASS |
| AC-5 | RAI#claimStoresHashOnly; MVC#noRawIpInLogs; MVC#responseShapeUnchanged (no IP in body) | PASS |
| AC-6 | RAI#viewRecordsThrottled; RAI#viewRefreshesAndDedupes; MVC#responseShapeUnchanged; MVC#recordsTrustedForwardedEntry | PASS |
| AC-7 | RAI#purgeRemovesOnlyExpired; ReferralAccountDeletionIntegrationTest#deletionClearsIpHashes | PASS |
| AC-8 | CFG#binds; CFG#minCodeAgeFromConfig; CFG#lookbackFromConfig; CFG#retentionFromConfig (non-default values) | PASS |
| AC-9 | CFG#bothChecksOff; CFG#ipCheckOffAgeCheckOn; CFG#ageCheckOffIpCheckOn; CFG#subSecondAndNegativeAreDisabled | PASS |
| AC-10 | RAI#ageCheckBeforeIpCheck | PASS |
| AC-11 | RAI#refusalKeepsSlotFree | PASS |
| AC-12 | RAI#unknownCodeRecordsNothing (404); RAI#existingRulesUnchanged (422, 409); RAI#duplicateBeforeAgeCheck; RAI#concurrentClaims; MVC#claimWithoutJwtIs401 | PASS |
| AC-13 | ClientIpTest#lastEntryForOneHop; ClientIpTest#spoofedLeftEntryIgnored; ClientIpTest#zeroHopsOrTooFewEntries; MVC#recordsTrustedForwardedEntry; MVC#claimComparesTrustedForwardedEntry; MVC#forgedLeftEntryHasNoEffect , ReferralForwardedForTomcatTest (embedded Tomcat, cloud-platform=kubernetes; fails without the forward-headers fix) | PASS |
| AC-14 | ClientIpTest#fallsBack; ClientIpTest#nonLiteralSelectedEntry; ClientIpTest#bothBad; MVC#badForwardedHeadersAreNot5xx | PASS |
| AC-15 | ClientIpTest#canonicalForms; ClientIpTest#ipv6Forms; IpFingerprintTest#familySeparated; RAI#sameIpRefused (v6 forms end to end); MVC#v4AndV6DoNotMatch | PASS |
| AC-16 | RAI#unknownCodeRecordsNothing (404, no activity row, no claim row) | PASS |
| AC-17 | MVC#refusalBodyIsGeneric (standard body, generic detail, no IP/time/count fields) | PASS |

No gaps found at this head; QA added no new tests this pass (earlier QA tests remain under their renamed names: MVC#claimComparesTrustedForwardedEntry, MVC#v4AndV6DoNotMatch).

Commands: no new run by QA (no test changes). Relying on the CEO's fresh run at 1fc7bf34: 71 tests, 0 failures. Vitest not applicable (backend-only).

Defects: none. Notes: AC-14 claim-path test asserts only status < 500, per AC wording.

## Round 4 update at 2b229705 (QA)
Owner decision round 4: production default for the IP check is OFF (lookback 0, no IP activity or claim hash recorded while off); the code-age check stays on. AC-3, AC-4, AC-5, AC-6, AC-7, AC-13, AC-15 (IP-check ACs) are evidenced with the lookback enabled explicitly in tests (`user.referral.ip-lookback=30d` in RAI, MVC, ReferralAccountDeletionIntegrationTest, ReferralForwardedForTomcatTest; `1d` in CFG), not with the production default.
New test: membership/ReferralIpCheckDefaultOffIntegrationTest#sameIpClaimSucceedsAndNothingRecorded (default config: same-IP claim 204, nothing recorded); supports AC-9 / AC-5 / AC-6 for the default-off state.
All test names cited in the AC table were confirmed present by grep at 2b229705; no gaps, no new QA tests.
Verify: forge-verify --changed PASS at 2b229705 (78 tests, gradle_rc=0) (CEO run; QA ran nothing). Vitest not applicable.
Defects: none.

## Round 5 (recorded by CEO)
- forge-verify --changed PASS at fcf6f2a2 (79 tests, 0 failures, gradle_rc=0); worktree clean after build.
- `node deploy/local/scripts/env-reference.mjs --check` → "env reference OK (116 variables, 6 frontend, 21 deploy settings)", exit 0.
- New tests: UserPropertiesValidationTest.enabledIpCheckWarns; ReferralIpCheckDefaultOffIntegrationTest asserts claim_ip_hash IS NULL count = 1. AC mapping unchanged.
