# Evidence F-20261119-06
- @ 2a745cb3 (CEO-run, 2026-11-20): forge-verify --changed --base a04ad610 --dir <worktree>: env reference --check PASS; gradle auth-service-app test PASS (gradle_rc=0). JUnit: 23 suites, 100 tests, 0 skipped, 0 failures, 0 errors. Dirty after build: 0. Prod lines 266. (Earlier run @ d6a8b52 FAILED the env check: 2 undescribed vars; fixed in 2a745cb.)
- Fix round @ fca9bc19 (CEO-run, 2026-11-20): forge-verify --changed --base a04ad610: env reference PASS; gradle auth-service-app PASS (gradle_rc=0). JUnit: 23 suites, 101 tests, 0 skipped, 0 failures, 0 errors.

## QA (step 7)
- @ fca9bc19, no code or tests added (SHA-bound reviews preserved). Checked the JUnit XML in the worktree's `build/test-results/test`: each mapped class has 0 failures, 0 errors, 0 skips.

| AC | Test (class::method) | What it asserts | JUnit | Verdict |
|---|---|---|---|---|
| AC-1 | SharedRedisLimiterIT::sharedAllowance, ::counterLivesInRedisWithTtl | Two filters on two Redis connections: C pass, C+1 returns 429 AUTH_RATE_LIMITED. The `auth:rl:*` key holds "2" with a TTL. | 2/0/0 | PASS |
| AC-2 | SharedWindowCounterTest::closedPort, ::scriptError, ::timeout (and ::concurrentIncrementsAreAtomic) | No exception, fallback trips at C+1, WARN_MESSAGE logged. Concurrency test gives exactly 50. | 4/0/0 | PASS |
| AC-3 | SharedRedisLimiterIT::sharedAllowance | 429 status, code, and headers are only Content-Type with no Retry-After, content type problem+json. | 2/0/0 | PASS |
| AC-4 | OtpCapsIT::windowCap, ::formatsShareOneKey, ::unknownPhoneSameResponse; OtpCapsDisabledIT | Over-cap request gets the same 202 and body, no SMS, counter above the cap. Formats share one key. Cap 0 disables. | 4/0/0 and 1/0/0 | PASS |
| AC-5 | OtpCapsIT::dailyCap | D+1 sends no SMS; sends again after the 24h advance. | 4/0/0 | PASS |
| AC-6 | OtpLockoutIT::fiveWrongOtpsLock, ::wrongOtpsDoNotRenewLock | 5 wrong OTPs give failed_attempts=5 and locked_until; correct OTP and password get AUTH_ACCOUNT_LOCKED. | 5/0/0 | PASS |
| AC-7 | OtpLockoutIT::unknownAndUnverifiedPhonesAreIndistinguishableAndRecordNothing | AUTH_OTP_INVALID identical, no credential state change. | 5/0/0 | PASS |
| AC-8 | OtpLockoutIT::successResetsCounters | Count resets after a successful login. | 5/0/0 | PASS |
| AC-9 | OtpLockoutIT::otpOnlyUserIsNeverLocked | No lock, only AUTH_OTP_INVALID. | 5/0/0 | PASS |

Result: 9 PASS, 0 NOT RUN.
Gaps (not blocking):
- AC-3 baseline is "no Retry-After today", pinned by assertion, not compared against the pre-change build.
- No test for concurrent requests at the OTP cap boundary through the service. The atomic increment is covered only at the counter level.
- I read the tests' assertions from source; I did not re-run them.
