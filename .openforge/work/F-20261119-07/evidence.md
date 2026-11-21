# Evidence F-20261119-07
- @ 2e3ce50b (CEO-run, 2026-11-20): forge-verify --changed --base a04ad610: env reference PASS; gradle auth + notification PASS (gradle_rc=0); storefront vitest PASS; tsc PASS (after replacing the engineer's node_modules symlink with a real npm ci). The earlier run @ 613b3f1 FAILED on an undescribed env var and a gradle lock give-up (rc 75).

## QA (step 7)
@ 2e3ce50b. No code or tests added or changed. JUnit XML read from the worktree `build/test-results/test` (auth-service-app and notification-service-app). Storefront: CEO-run vitest PASS at this SHA, not re-run by QA.

| AC | Test (class::method) | Asserts | Verdict |
|---|---|---|---|
| AC-9 | PhoneEnumerationIntegrationTest::taken and free numbers give identical status, body and headers; no SMS for taken | taken vs free add give same 202, body, headers; no SMS for taken | PASS (6 tests, 0 fail, 0 skip) |
| AC-9 | PhoneEnumerationIntegrationTest::a resend within the cooldown is the same 429 for taken and free numbers | resend is the same 429 for both | PASS |
| AC-9 | PhoneEnumerationIntegrationTest::a decoy code for a taken number is AUTH_OTP_INVALID and the phone stays unverified | verify gives 401 AUTH_OTP_INVALID, phone_verified=false | PASS |
| AC-9 | PhoneEnumerationIntegrationTest::race: both add a free number, B verifies first, A gets 401 AUTH_OTP_INVALID; ::unique index backs the re-check | verify race maps to AUTH_OTP_INVALID, unique index refuses 2nd verified row | PASS |
| AC-9 | PhoneVerificationServiceTest::takenNumberSendsNoSmsAndNotifiesTheOwner; ::recheckFindingTheNumberVerifiedElsewhereIsReportedAsAnInvalidCode; ::concurrentUniqueIndexViolationIsReportedAsAnInvalidCode | no SMS for taken; re-check and DataIntegrityViolation map to AUTH_OTP_INVALID | PASS (4 tests, 0 fail, 0 skip) |
| AC-9 (UX) | PhoneSection.test.tsx (storefront) | neutral hint, no "already"/"in use" wording, 401 shows existing message | PASS (CEO-run vitest; not re-run by QA) |
| AC-10 | PhoneClaimNotifierRedisTest::twentyConcurrentAttemptsSendExactlyOneEmail; ::keyHoldsNoPhoneNumberAndExpiresAboutADayOut; ::anotherNoticeIsSentOnceTheKeyIsGone; ::noOwnerMeansNoEmailAndNoKey; ::redisDownMeansNoThrowNoEmailAndOneThrottledWarning; ::asyncEntryPointDeliversToo | one notice per owner per window (atomic), TTL about 24 h, no phone in key, fail-closed | PASS (6 tests, 0 fail, 0 skip) |
| AC-10 | PhoneVerificationServiceTest::takenNumberSendsNoSmsAndNotifiesTheOwner | attempted(P) called on taken | PASS |
| AC-10 | EmailRendererTest::phoneClaimAttemptShowsOnlyTheLastFourDigitsAndNamesNoOneElse; ::newTemplatesEscapeNameTailAndLinkInHtml | notice copy, escaping | PASS (3 tests, 0 fail, 0 skip) |
| AC-11 | PhoneEnumerationIntegrationTest::free number happy path is unchanged: 202, SMS, 204, verified (AC-11); PhoneVerificationServiceTest::freeNumberSendsTheSmsAndNoOwnerNotice | free P: SMS sent, verify 204, VERIFIED, no owner notice | PASS |
| AC-12 | PasswordResetIntegrationTest::Google-only user: 204, credential row created, PASSWORD_SET sent after commit | DB row exists and PASSWORD_SET email sent (option b) | PASS (3 tests, 0 fail, 0 skip) |
| AC-12 | PasswordResetIntegrationTest::password user: 204 and no PASSWORD_SET; ::a failing gateway does not fail the reset | no notice for password user; fail-open | PASS |
| AC-12 | EmailRendererTest::passwordSetExplainsTheResetAndLinksToSecurity | PASSWORD_SET template copy and link | PASS |

NOT RUN rows: none.
Gaps: the storefront result is the CEO's run, not re-run by QA. Docs-only parts of T7 and the AC-12 API docs wording were not mapped to tests.
