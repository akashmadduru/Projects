# user-service: transactional outbox for USER_DELETED, so privacy erasure is atomic and its fan-out survives a Kafka outage (TD-P6-33, ADR 0011)

Branch `forge/F-20261119-12-outbox-publishers`, head `7c722639`, built on top of F-20261119-10 (820e5a8). Merge order: after the F-01 → F-04 → F-09 → F-10 chain. It does not depend on F-06, F-07 or F-08, but all of them change user-service. See "Before merge".

## Why
`PrivacyService.complete()` was private and called from inside its own class, so `@Transactional` had no effect. Each erasure statement committed separately, and the service then called Kafka synchronously before marking the request COMPLETED. A Kafka outage made the whole erasure run again on every scheduler tick. A crash in the middle left a user partly erased. payment-service, promotion-service and seller-service publish nothing to Kafka (checked), so this user-service event was the only remaining best-effort publisher in scope.

## What changed
- **One transaction.** The blocker HTTP check stays outside. One `TransactionTemplate` covers:
  - the guarded claim `UPDATE … SET status='COMPLETED' WHERE status='SCHEDULED'`, which skips the request when it matches 0 rows. This makes it safe against a concurrent cancel and a second runner;
  - the anonymisation;
  - the 10 erasure statements;
  - the outbox insert.

  `complete()` no longer calls Kafka.
- **V6 `users.outbox`:** additive only, one table and 3 partial indexes. It copies the semantics of `orders.outbox`. Per the CTO ruling, the code is copied rather than moved into a shared library. ADR 0011 records what would trigger moving it.
- **`UserOutboxRelay`:**
  - runs every 2 s under `JobLock` and sends in order per user;
  - backs off up to 1 h, and a row goes DEAD after 20 attempts with "OUTBOX DEAD" logged at ERROR;
  - a null or empty payload goes DEAD as "Corrupt outbox payload".
- **Wire format unchanged:** same topic, key and JSON (eventId, type, userId, occurredAt), pinned by an exact-string test.
- **Idempotency key `USER_DELETED:<userId>`:** a suppressed duplicate is logged at WARN with the userId only.
- **Admin endpoints (ROLE_ADMIN):**
  - `GET /api/users/admin/outbox?status=DEAD|PENDING` returns at most 200 rows, without the payload. Any other status returns 400.
  - `POST /api/users/admin/outbox/{id}/retry` moves only DEAD rows; otherwise it returns 404.
- **PII:** the payload holds UUIDs and a timestamp only. `last_error` holds the root-cause class and message, cut to 500 characters, or a fixed text for a corrupt payload. It never contains payload or Jackson text.
- **Tests:** consumer idempotency tests for payment, promotion and seller (AC-7). The listener code is unchanged.

## Evidence
- `forge-verify --changed` PASS at 7c722639: the env reference check, plus the payment, promotion, seller and user-service tests.
- QA mapped AC-1..AC-11 to passing tests in the JUnit XML. `forge-gate` PASS.
- 417 production lines (class M, limit 600, CTO stop at 500).
- Guard tests shown to fail against pre-fix code:
  - erasure outside one transaction: `expected "SCHEDULED" but was "COMPLETED"`;
  - Jackson text in `last_error`: the email appeared;
  - claim without the SCHEDULED guard: a cancelled user was erased (`expected 0 but was 1`);
  - the migration guard against a temporary `DROP TABLE`.

## Review
- Independent code and security review ran for 3 rounds. Both approve 7c722639, with no findings in the last round.
- Round 1 (both approved, with minors):
  - nothing tested the guarded claim (the cancel-during-blocker-check case);
  - a suppressed duplicate event was silent.
- Round 2 found a **test that could never fail**. The migration check used the Java literal `"\b"`, which is a backspace character, not a regex word boundary. It was fixed and now has a positive sanity test.
- Design: ADR 0011 (Accepted). The CTO accepted it with 11 conditions; all are met (see evidence.md).

## Rollback (CTO condition 7)
The old code never reads `users.outbox`. Before reverting to the previous image:
1. Stop the new user-service pods, or scale them to 0.
2. Check for rows that were never delivered:
   `SELECT count(*) FROM users.outbox WHERE status <> 'DONE';`
3. If the count is above 0, re-publish each PENDING or DEAD row by hand. Send its `payload` to topic `user.events` with key `aggregate_id` (the userId), then set `status='DONE'`. Consumers are idempotent, so a duplicate send is harmless.
4. Start the old image.

V6 can stay in place; the old code ignores the table.

## Before merge (Owner or ops)
- **Migration number:** re-check that V6 is still free once F-06, F-07 and F-08 merge. On 2026-11-21 all of them stopped at V5. If V6 is taken, renumber.
- **Overlap:** F-06, F-07 and F-08 also change user-service. This PR touches `PrivacyService` only in its constructor and `complete()`. Expect at most small textual conflicts.

## Known issues (not blocking)
- One event per userId relies on auth-service disabling deleted accounts. This is recorded in ADR 0011.
- `last_error` may contain a broker host:port from a Kafka exception, visible only to ADMIN (security, optional).
- DONE rows are never purged (backlog). Product, inventory, rating and auth audit publishers remain best-effort (TD-P6-33 residual).

🤖 Generated with [Claude Code](https://claude.com/claude-code)
