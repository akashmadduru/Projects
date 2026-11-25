# F-20261119-12 Tasks: user-service outbox for USER_DELETED (class M)

Owner of every task: forge-backend-engineer. Work in the worktree `c:\Users\Akash\Desktop\Projects\.openforge\worktrees\F-20261119-12`, branch `forge/F-20261119-12-*`.
**Commit at each task boundary** (one commit per task, message `[F-20261119-12] Tn <title>`), after the task's verification passes. Never push. Run one gradle build at a time.

Paths: `UH` = `services/user-service/user-service-app/src/main/java/com/acme/ecommerce/user`, `UT` = `services/user-service/user-service-app/src/test/java/com/acme/ecommerce/user`, `RES` = `services/user-service/user-service-app/src/main/resources`.
Module test command (V-USER): `bash .openforge/scripts/gradle.sh :services:user-service:user-service-app:test` (add `--tests <Class>` if the script forwards args; otherwise run the module). Expect `gradle_rc=0`.
Final gate (V-FINAL): `node .claude/hooks/forge-verify.mjs --changed`.
Keep a running count of production lines; if it passes 500, stop and report to the CTO (C8).

## T1 Migration V6 and migration test
- Goal: add `users.outbox` (same columns as `orders.outbox`, 3 partial indexes `ix_user_outbox_due`, `ix_user_outbox_aggregate_pending`, `ix_user_outbox_dead`). Additive only: no ALTER, no DROP. Confirm V6 is the next free number (look in `RES/db/migration` and in the F-06/F-07/F-08 branches if reachable).
- Files: `RES/db/migration/V6__outbox.sql`, `UT/outbox/OutboxMigrationIT.java`.
- Test: Flyway max version is 6; table and 3 indexes exist; test greps V6 text for `ALTER`/`DROP`.
- ACs: AC-9. CTO: C6.
- Verify: V-USER.

## T2 UserOutbox and UserDeletedEvent
- Goal: `@Component UserOutbox.enqueue(UUID userId, UserDeletedEvent e)` doing `INSERT ... ON CONFLICT (idem_key) DO NOTHING`, kind `USER_DELETED`, idem key `USER_DELETED:<userId>`, joins caller's transaction via JdbcClient. `record UserDeletedEvent(String eventId, String type, String userId, String occurredAt)`, String fields only, plain `new ObjectMapper()` (Jackson 2).
- Files: `UH/outbox/UserOutbox.java`, `UT/outbox/UserOutboxIT.java`.
- Tests: enqueue twice gives 1 row; payload jsonb keys are exactly eventId, type, userId, occurredAt; wire-format test compares serialised JSON to the legacy string format (field order eventId, type, userId, occurredAt; ISO `occurredAt`; no spaces).
- ACs: AC-5 (direct enqueue), AC-10 (payload keys). CTO: C5 (serialisation half).
- Verify: V-USER.

## T3 Single transaction in PrivacyService.complete
- Goal: constructor takes `PlatformTransactionManager` (build `TransactionTemplate`), drops the `KafkaTemplate` parameter, takes `UserOutbox`. `assertNoBlockers` stays outside the tx. One `tx.execute` runs: (a) guarded `UPDATE users.deletion_requests SET status='COMPLETED', completed_at=now(), email=NULL WHERE user_id=:u AND status='SCHEDULED'` (0 rows means return false), (b) profile anonymisation and the 10 erasure statements, (c) `outbox.enqueue`. No Kafka call and no swallowed exception inside the callback. Do not edit `PrivacyKafkaConfig` or `SecurityConfig`. Move `TOPIC` out (relay owns it; keep a constant if needed by T4).
- Files: `UH/privacy/PrivacyService.java`, `UT/outbox/UserDeletionOutboxIT.java` (on `AbstractUserIntegrationTest`, `@MockitoBean(name="privacyKafkaTemplate")`). Fix any existing test that constructs `PrivacyService` directly.
- Tests: `atomic` (one PENDING row, anonymised profile, COMPLETED); `forcedFailureRollsBack` (temporary trigger on `users.outbox` that raises, then `runDue`: 0 rows, request still SCHEDULED, email unchanged, addresses still present; drop trigger afterwards); Kafka mock failing/throwing: `runDue` returns 1, no exception, COMPLETED, row PENDING attempts=0; `runDue` twice gives 1 row; request reset to SCHEDULED and rerun gives 1 row; `cancelDeletion` during grace leaves no outbox row. Confirm `ReferralAccountDeletionIntegrationTest` still passes.
- ACs: AC-1, AC-2, AC-5. CTO: C2.
- Verify: V-USER.

## T4 UserOutboxRelay
- Goal: copy `OutboxRelay` semantics for `users.outbox`: `@Scheduled(fixedDelayString="${user.outbox.relay-interval-ms:2000}", initialDelayString="${user.outbox.initial-delay-ms:10000}")` through `jobLock.runExclusive("user-outbox-relay", this::relay)`; seq ordering per aggregate (NOT EXISTS older pending), BATCH 100, MAX_ROUNDS 10, backoff `min(3600, 2L << min(attempts,20))` s, MAX_ATTEMPTS 20, WARN on retry, `log.error("OUTBOX DEAD: ...")`. `deliver` reads payload into `UserDeletedEvent`, re-serialises with the same mapper, `privacyKafkaTemplate.send("user.events", userId.toString(), json).get(10, SECONDS)`; reset interrupt flag on `InterruptedException`; execution/timeout become `IllegalStateException`. `last_error` = `e.getClass().getName()` + root-cause message truncated to 500; corrupt payload error text is the constant "Corrupt outbox payload" (never payload or Jackson message). Poison row goes DEAD and does not block other users.
- Files: `UH/outbox/UserOutboxRelay.java`, `UT/outbox/UserOutboxRelayIT.java`.
- Tests: send once with ArgumentCaptor (topic `user.events`, key userId, JSON keys and wire format equal legacy), row DONE; `relay()` twice sends once; failed future gives attempts=1 and `next_attempt_at > now()+1s`; attempts preset 19 then one failure gives DEAD and OutputCaptureExtension sees "OUTBOX DEAD" at ERROR; two threads call `scheduledRun`, send blocks on latch in A, B returns, `send` called once; two PENDING rows same aggregate with different idem keys, older one backed off, newer not sent; corrupt payload row goes DEAD with constant text and other users' rows still delivered; AC-10 test seeds profile email/phone/name, forces a failure whose message contains the email, asserts neither `payload::text` nor `last_error` contains email, phone or display name (and truncation to 500).
- ACs: AC-3, AC-4, AC-5 (relay once), AC-6, AC-10. CTO: C4, C5.
- Verify: V-USER.

## T5 Admin controller and security tests
- Goal: `@RestController @RequestMapping("/api/users/admin/outbox") @PreAuthorize("hasRole('ADMIN')")`. `GET ?status=DEAD|PENDING` (default DEAD, `ORDER BY seq LIMIT 200`) returns `OutboxRow(id, aggregateId, kind, status, attempts, lastError, createdAt, nextAttemptAt)` with no payload. `POST /{id}/retry` runs guarded UPDATE (`status='PENDING', attempts=0, next_attempt_at=now() WHERE id=:i AND status='DEAD'`); 0 rows throws `CodedException(RESOURCE_NOT_FOUND)` (404). No gateway change.
- Files: `UH/outbox/UserOutboxAdminController.java`, `UT/outbox/UserOutboxAdminMvcTest.java` (plus a converter test file if needed).
- Tests: first read `PlatformJwtAuthenticationConverter` (SecurityConfig.java:123) and confirm it maps the platform admin claim to `ROLE_ADMIN`; at least one MockMvc case goes through the real converter (real JWT claim), or a separate converter unit test asserts the mapping. Cases for GET and for POST retry: ADMIN 200, ROLE_CUSTOMER 403, no token 401. GET response has attempts, lastError, kind and no `payload` field; seed more than 200 DEAD rows and assert cap 200. Retry DEAD row gives 200, row PENDING, then `relay()` delivers it (DONE). Retry on PENDING or unknown id gives 404.
- ACs: AC-8. CTO: C1, C3.
- Verify: V-USER.

## T6 payment-service UserDeletedListener idempotency
- Goal: test only. Seed the user's rows, call `UserDeletedListener.onEvent(payload)` twice, no exception, state equals state after one call. Change production code only if the test proves non-idempotence (then stop and report to the CEO).
- Files: `services/payment-service/payment-service-app/src/test/java/com/acme/ecommerce/payment/privacy/UserDeletedListenerIdempotencyIT.java` (adjust package to the real listener package).
- ACs: AC-7 (payment).
- Verify: `bash .openforge/scripts/gradle.sh :services:payment-service:payment-service-app:test`.

## T7 promotion-service UserDeletedListener idempotency
- Goal and approach as T6.
- Files: `services/promotion-service/promotion-service-app/src/test/java/com/acme/ecommerce/promotion/privacy/UserDeletedListenerIdempotencyIT.java` (adjust package).
- ACs: AC-7 (promotion).
- Verify: `bash .openforge/scripts/gradle.sh :services:promotion-service:promotion-service-app:test`.

## T8 seller-service UserDeletedListener idempotency
- Goal and approach as T6.
- Files: `services/seller-service/seller-service-app/src/test/java/com/acme/ecommerce/seller/privacy/UserDeletedListenerIdempotencyIT.java` (adjust package).
- ACs: AC-7 (seller).
- Verify: `bash .openforge/scripts/gradle.sh :services:seller-service:seller-service-app:test`.

## T9 Docs and tech debt
- Goal: `docs/reference/endpoints.md` add 2 rows (GET and POST retry, ADMIN role, no payload); `docs/reference/events.md` note USER_DELETED goes through `users.outbox`; `docs/techdebt/phase6.md#td-p6-33` mark user-service done, close payment/promotion/seller as "no producers", state product, inventory, rating and auth audit remain open, add backlog item "purge job for DONE rows in users.outbox"; `docs/techdebt/remediation-plan.md` add a row.
- Files: the four docs above.
- ACs: none directly (supports AC-8 docs rule, scope). CTO: C10.
- Verify: `grep -n "users.outbox\|TD-P6-33" docs/reference/events.md docs/reference/endpoints.md docs/techdebt/phase6.md docs/techdebt/remediation-plan.md` shows all four updated.

## T10 Final verification and PR notes
- Goal: run V-FINAL and record its full output in `.openforge/work/F-20261119-12/verify.md`. Confirm `git diff 820e5a8 --stat` shows no change to order-service, gateway, `PrivacyKafkaConfig`, `SecurityConfig` and no order-service outbox test diff. Count production lines (about 320 expected; over 500 means stop, report to the CTO). Re-check V6 is still free vs F-06/F-07/F-08. Re-read V6 for ALTER/DROP. Write `.openforge/work/F-20261119-12/pr.md` with a rollback note: before reverting run `SELECT count(*) FROM users.outbox WHERE status<>'DONE'` and it must be 0; if not, re-publish remaining rows by hand; leave V6 in place. Request independent review of the final SHA (the engineer does not review).
- Files: `.openforge/work/F-20261119-12/verify.md`, `.openforge/work/F-20261119-12/pr.md`.
- ACs: AC-11. CTO: C6 (re-check), C7, C8, C9, C11.
- Verify: `node .claude/hooks/forge-verify.mjs --changed` (output recorded).

## Coverage: AC to tasks
| AC | Tasks |
|---|---|
| AC-1 | T3 |
| AC-2 | T3 |
| AC-3 | T4 |
| AC-4 | T4 |
| AC-5 | T2, T3, T4 |
| AC-6 | T4 |
| AC-7 | T6, T7, T8 |
| AC-8 | T5 (docs T9) |
| AC-9 | T1 |
| AC-10 | T2, T4 |
| AC-11 | T10 |

## Coverage: CTO conditions to tasks
| Condition | Tasks |
|---|---|
| C1 admin role mapping, 401/403 on GET and retry | T5 |
| C2 single transaction, forcedFailureRollsBack | T3 |
| C3 no payload field, 200 cap | T5 |
| C4 no PII in last_error, constant corrupt text, email-echo test | T4 |
| C5 wire format test | T2, T4 |
| C6 additive V6, re-check at rebase | T1, T10 |
| C7 rollback note in pr.md | T10 |
| C8 500-line limit | T10 (tracked in all) |
| C9 AC-11 evidence, untouched files | T10 |
| C10 docs, TD-P6-33 residual, purge backlog | T9 |
| C11 independent review of the SHA | T10 (requested by CEO) |
