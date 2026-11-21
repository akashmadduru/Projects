# F-20261119-12 Design: user-service outbox for USER_DELETED (O-08, TD-P6-33, class M)

Paths are relative to `services/user-service/user-service-app/src/main/java/com/acme/ecommerce/user/` unless they are absolute in the repo. ADR: `.openforge/knowledge/decisions/0011-user-service-outbox.md`.

## 1. Current behaviour
- `PrivacyService.runDueScheduled` (PrivacyService.java:478-488) runs `runDue` under `JobLock` "user-account-deletion" every 15 min. `runDue` (:454-473) calls the private `complete(userId)` once per due request and catches each `RuntimeException`.
- `complete` (:490-537) has no transaction. It is private and self-invoked, so a `@Transactional` on it would do nothing. It calls `assertNoBlockers` over HTTP (:492), anonymises the profile (:497-500), runs 10 erasure statements (:501-514), builds the JSON by hand (:515-522), and calls `kafka.send(TOPIC, userId, event).get(10s)` (:524). It sets `COMPLETED` only after the send succeeds (:531-535). Each statement auto-commits. When Kafka is down, the erasure has already happened, the request stays `SCHEDULED` and the whole erasure runs again on the next tick. A crash between the statements leaves a partial erasure.
- The `privacyKafkaTemplate` bean (PrivacyKafkaConfig.java:28-37) uses acks=all and max.block 5 s.
- Security: `anyRequest().authenticated()` and `@EnableMethodSecurity` (SecurityConfig.java:56, :92). No user-service controller uses `@PreAuthorize` yet. The gateway routes `/api/users/**` to user-service (gateway application.yml:212-214), and `/api/admin/users/**` goes to auth-service (:143).
- order-service reference: `Outbox.enqueue` (Outbox.java:46-55), `OutboxRelay` (OutboxRelay.java:53-179: seq ordering, backoff `min(3600, 2<<attempts)`, 20 attempts, "OUTBOX DEAD" at ERROR), `OutboxAdminController` (:21-75), V10__outbox.sql.

## 2. Reuse before build (what I found)
| Found | Decision |
|---|---|
| order-service `Outbox`, `OutboxRelay`, `OutboxAdminController`, V10 | **Copy the semantics** (CTO ruling). They hard-code `orders.outbox`, `Kind` and `OrderRemote`. Lift trigger: see ADR 0011. |
| `libs/common-web` `JobLock` (bean already used by PrivacyService) | Reuse as is. |
| `privacyKafkaTemplate` bean | Reuse as is. The relay injects it. `PrivacyKafkaConfig` is **not** edited. |
| Spring `TransactionTemplate` (platform API) | Reuse. It gives the transaction boundary without making `complete` public or proxied. |
| `CodedException(RESOURCE_NOT_FOUND)` (common-core, already used in PrivacyService) | Reuse for a retry of a row that is missing or not DEAD (404). |
| Other repo hits for "outbox" (product EmbeddingReconciler, auth NotificationGateway) | Comments only. They are not outbox implementations. |

## 3. Proposed change
**3.1 Migration `db/migration/V6__outbox.sql`** (additive; V6 is free on 820e5a8 and on F-06, F-07 and F-08; re-check at rebase). It creates `users.outbox` with the same columns as `orders.outbox`: `id uuid PK default gen_random_uuid()`, `seq bigserial UNIQUE`, `aggregate_id uuid`, `kind varchar(40)`, `payload jsonb`, `idem_key varchar(200) UNIQUE`, `status` with CHECK in (PENDING, DONE, DEAD), `attempts`, `next_attempt_at`, `last_error varchar(500)`, `created_at`, `done_at`. It adds the three partial indexes `ix_user_outbox_due`, `ix_user_outbox_aggregate_pending` and `ix_user_outbox_dead`. It has no ALTER and no DROP.

**3.2 `outbox/UserOutbox.java`** (`@Component`, about 45 lines). It provides `enqueue(UUID userId, UserDeletedEvent e)`, which runs `INSERT ... ON CONFLICT (idem_key) DO NOTHING` with kind `USER_DELETED` and idem key `USER_DELETED:<userId>`. It joins the caller's transaction through JdbcClient. `record UserDeletedEvent(String eventId, String type, String userId, String occurredAt)` has **String fields only**, so Jackson writes `occurredAt` exactly as `Instant.toString()` does today. Jackson's default for an `Instant` would be a numeric timestamp, which is wrong here. The mapper is Jackson 2, `new ObjectMapper()`.

**3.3 Transaction boundary in `PrivacyService.complete`** (about 40 lines changed). The constructor takes a `PlatformTransactionManager` and builds a `TransactionTemplate`, and it drops the `KafkaTemplate` parameter.
1. `assertNoBlockers(userId)` stays **outside** the transaction, so no HTTP call runs while a DB connection or row lock is held.
2. `tx.execute(...)`:
   - (a) `UPDATE users.deletion_requests SET status='COMPLETED', completed_at=now(), email=NULL WHERE user_id=:u AND status='SCHEDULED'`. If it updates 0 rows, return false. That happens when the request was cancelled or another run completed it. The row lock serialises this step against `cancelDeletion` and a second runner.
   - (b) The same profile anonymisation and 10 erasure statements as today.
   - (c) `outbox.enqueue(userId, new UserDeletedEvent(UUID.randomUUID(), "USER_DELETED", userId, Instant.now()))`.
   - Any exception rolls back (a), (b) and (c) together. `runDue` already logs the failure and retries on the next tick.

   No Kafka call is left in `complete`. `TOPIC` moves to the relay.

**3.4 `outbox/UserOutboxRelay.java`** (`@Component`, about 140 lines). It copies `OutboxRelay` with these changes:
- Table `users.outbox`. The only kind is `USER_DELETED`.
- `@Scheduled(fixedDelayString="${user.outbox.relay-interval-ms:2000}", initialDelayString="${user.outbox.initial-delay-ms:10000}")`, run through `jobLock.runExclusive("user-outbox-relay", this::relay)`.
- `deliver` reads the payload into `UserDeletedEvent`, writes it again with the same mapper (field order eventId, type, userId, occurredAt, no spaces), and calls `kafka.send("user.events", userId.toString(), json).get(10, SECONDS)`. `InterruptedException` resets the interrupt flag. Execution and timeout failures become `IllegalStateException`.
- It keeps `seq` ordering per aggregate, BATCH 100, MAX_ROUNDS 10, backoff `min(3600, 2L << min(attempts,20))` seconds, MAX_ATTEMPTS 20, `log.error("OUTBOX DEAD: ...")` and WARN on a retry.
- PII rule for `last_error`: it stores `e.getClass().getName()` plus the message of the *root cause*, truncated to 500 characters. The payload is never put into an exception message. The corrupt-payload error says only "Corrupt outbox payload".

**3.5 `outbox/UserOutboxAdminController.java`** (about 70 lines). It has `@RequestMapping("/api/users/admin/outbox")` and `@PreAuthorize("hasRole('ADMIN')")`.
- `GET ?status=DEAD|PENDING` (default DEAD, `LIMIT 200 ORDER BY seq`) returns `OutboxRow(id, aggregateId, kind, status, attempts, lastError, createdAt, nextAttemptAt)`. It does **not** return the payload.
- `POST /{id}/retry` runs `UPDATE ... SET status='PENDING', attempts=0, next_attempt_at=now() WHERE id=:i AND status='DEAD'`. If it updates 0 rows, it returns 404.
- The path is under `/api/users/**`, so the existing gateway route reaches it with **no gateway change**. 401 and 403 come from user-service's own chain (SecurityConfig.java:101-114).

**3.6 Config and docs.** application.yml is not changed, because the defaults sit in `@Value`. Documentation updates:
- `docs/reference/endpoints.md`: add the 2 rows.
- `docs/reference/events.md`: add a note that USER_DELETED goes through the outbox.
- `docs/techdebt/phase6.md#td-p6-33`: mark user-service done and close payment, promotion and seller as "no producers".
- `remediation-plan.md`: add a row.

**Production-line estimate:** V6 about 25, UserOutbox about 45, Relay about 140, Admin about 70, PrivacyService about 40 changed (net), for **about 320 lines** in total. That is under the 500 stop and the 600 limit.

## 4. AC → change → test (test plan)
| AC | Change | Test (user-service unless named; `AbstractUserIntegrationTest`, `@MockitoBean(name="privacyKafkaTemplate")`) |
|---|---|---|
| AC-1 | 3.3 single `TransactionTemplate` | `UserDeletionOutboxIT.atomic`: a due request gives one PENDING row plus an anonymised profile plus COMPLETED. `forcedFailureRollsBack`: the test creates a trigger on `users.outbox` that raises, then runs `runDue`. It asserts 0 outbox rows, the request is still SCHEDULED, the profile email is unchanged and the addresses are still there. The trigger is dropped afterwards. |
| AC-2 | 3.3 (no Kafka in `complete`) | Kafka mock `send` returns a failed future or throws. `runDue` returns 1 and nothing is thrown. The request is COMPLETED and the row is PENDING with `attempts=0`. |
| AC-3 | 3.4 deliver | `relay()` calls `send("user.events", userId, json)` exactly once (ArgumentCaptor). The JSON parses to exactly the keys eventId, type=USER_DELETED, userId and occurredAt (ISO string). The row becomes DONE. |
| AC-4 | 3.4 fail() | A failed future makes `attempts` 1 and `next_attempt_at > now()+1s`. With attempts preset to 19, one failure gives DEAD, and an OutputCaptureExtension check finds "OUTBOX DEAD" at ERROR. |
| AC-5 | idem key `USER_DELETED:<userId>` and the guarded UPDATE in 3.3 | `runDue` twice gives 1 row. A request reset to SCHEDULED and rerun still gives 1 row. Calling `enqueue` twice directly gives 1 row. `relay()` twice gives `send` once. |
| AC-6 | JobLock plus the `seq` NOT EXISTS | Two threads call `scheduledRun`. The mock `send` blocks on a latch in thread A. Thread B returns and `send` is called once in total. Ordering: two PENDING rows with the same aggregate and different idem keys (SQL insert). The older row has `next_attempt_at` in the future, so the newer row is not sent. |
| AC-7 | none (consumers unchanged) | payment, promotion and seller each get a `UserDeletedListenerIdempotencyIT` on their Testcontainers base. It seeds the user's rows, calls `Listener.onEvent(payload)` twice, sees no exception, and checks that the row state equals the state after one call. |
| AC-8 | 3.5 | `UserOutboxAdminMvcTest` (MockMvc): `jwt().authorities(ROLE_ADMIN)` gives 200 with attempts, lastError and kind, and no `payload` field. ROLE_CUSTOMER gives 403. No token gives 401. Retry on a DEAD row gives 200, the row becomes PENDING and `relay()` delivers it (DONE). Retry on a PENDING or unknown id gives 404. |
| AC-9 | 3.1 | `OutboxMigrationIT`: the Flyway max version is 6, `users.outbox` and its 3 indexes exist, and V6 contains no ALTER or DROP (checked in review and by grep in the test). |
| AC-10 | 3.2 String-only record, 3.4 `last_error` rule, admin view has no payload | Assert that the payload jsonb keys are exactly the 4 allowed ones. Seed the profile email or phone, force a failure, and assert that neither `payload::text` nor `last_error` contains the email, phone or display name. |
| AC-11 | none | `node .claude/hooks/forge-verify.mjs --changed`. The order-service outbox tests have no diff. `ReferralAccountDeletionIntegrationTest` still passes, because its Kafka stub is now unused and `@MockitoBean` is lenient. |

## 5. Alternatives considered
1. **Lift Outbox and Relay into `common-web`.** Rejected by the CTO. It would redesign a money-path class for one adopter.
2. **Send after commit (`TransactionSynchronization.afterCommit`).** Atomic erasure, but a crash or Kafka outage after commit still loses the event, and AC-2 and AC-3 fail. Rejected.
3. **Kafka transactions (transactional producer chained with the DB transaction).** This is not atomic with Postgres (no XA). It needs a producer config change and read_committed consumers in 9 services. Rejected.
4. **Admin route `/api/admin/user-outbox`.** It would get the gateway's edge ADMIN check, but it needs a gateway route change in a second service, and `/api/admin/users/**` already belongs to auth-service. Rejected. The service-level `@PreAuthorize` is the trust boundary, as the SecurityConfig.java:32-38 comment says.

## 6. Risks, failure modes, rollback
- **Duplicate delivery** (relay crash after send and before DONE). This is accepted (at least once). All USER_DELETED consumers are SET-constant or DELETE statements, which are idempotent. AC-7 proves it for 3 of them.
- **Re-deletion of the same userId** would be suppressed by the idem key. auth-service DISABLES a deleted account, so this does not happen in practice.
- **Behaviour change:** COMPLETED no longer waits for Kafka. That is intended, and AC-2 requires it.
- **Relay holds a JobLock connection for up to 10 s per send while Kafka is down.** This is bounded by the 5 s max.block. Other rows back off.
- **Retention:** DONE rows keep `userId` only, which is not PII under AC-10. A purge job goes to the backlog.
- **Conflict:** F-06, F-07 and F-08 may touch `PrivacyService`. The edits are limited to the constructor and `complete`. There are no edits to `PrivacyKafkaConfig` or `SecurityConfig`. Re-check that V6 is still free at rebase.
- **Rollback:** before reverting, confirm `SELECT count(*) FROM users.outbox WHERE status<>'DONE'` = 0. Old code would never send those rows, so if any remain, re-publish them by hand. Leave V6 in place, because it is additive and harmless. Record this in the PR runbook.

## 7. Files expected to change
- New: `V6__outbox.sql`, `outbox/UserOutbox.java`, `outbox/UserOutboxRelay.java`, `outbox/UserOutboxAdminController.java`.
- Edited: `privacy/PrivacyService.java`.
- Tests: user-service `outbox/*IT`, `outbox/UserOutboxAdminMvcTest`; payment-, promotion- and seller-service `privacy/UserDeletedListenerIdempotencyIT`.
- Docs: `docs/reference/endpoints.md`, `docs/reference/events.md`, `docs/techdebt/phase6.md`, `docs/techdebt/remediation-plan.md`.
- Not changed: order-service, gateway, `PrivacyKafkaConfig`, `SecurityConfig`, consumers.

## CTO decision

**Accepted** (forge-cto, 2026-11-21; copied from its reply by the CEO). Class M stands: about 320 production lines, no split, no new service, datastore or vendor. Copy rather than lift is correct. ADR 0011 moves to Accepted now that these conditions are recorded here.

Conditions:
1. **Admin role mapping.** Confirm that `PlatformJwtAuthenticationConverter` (SecurityConfig.java:123) maps the platform admin claim to `ROLE_ADMIN`. `UserOutboxAdminMvcTest` must run at least one case through the real converter, or a converter test must assert the mapping; `jwt().authorities(ROLE_ADMIN)` alone does not prove it. Assert 401 and 403 on `POST /{id}/retry` as well as on the GET.
2. **Single transaction.** The guarded UPDATE, the anonymisation, the erasure statements and `enqueue` all run in one `tx.execute`, and no rollback is swallowed inside the callback. Keep `forcedFailureRollsBack`, asserting the request is still SCHEDULED.
3. **Admin response.** No payload field, and keep the 200-row cap.
4. **No PII in `last_error`.**
   - The corrupt-payload error text is constant: it never includes the payload or the Jackson message.
   - If the root-cause message can echo input, truncate it and test it.
   - The AC-10 test forces a failure whose message would contain the email.
5. **Wire format.** A test compares the serialised JSON with the legacy format: field order eventId, type, userId, occurredAt; ISO `occurredAt`; no spaces.
6. **Migration.** V6 is additive only. Re-check that V6 is free at rebase onto F-06, F-07 and F-08. Reviewers read V6 itself for ALTER and DROP.
7. **Rollback note in pr.md.** Include the `SELECT count(*) FROM users.outbox WHERE status<>'DONE'` check and the manual re-publish step.
8. **Line limit.** If production lines pass 500, stop and report to the CTO.
9. **AC-11 evidence.** Record the forge-verify output. The order-service outbox tests have no diff. `PrivacyKafkaConfig` and `SecurityConfig` are untouched.
10. **Docs and tech debt.** TD-P6-33 says product, inventory, rating and auth audit remain open. A DONE-row purge job goes to the backlog.
11. **Review.** Independent review of the named SHA.
