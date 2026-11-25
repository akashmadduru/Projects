# F-20261119-12 Complexity (O-08, TD-P6-33)

The CTO's ruling (forge-cto, 2026-11-21), copied from its reply by the CEO.

| Factor | Score | Reason |
|---|---|---|
| Services or apps touched | 0 | One production service, user-service. Payment, promotion and seller have no Kafka producer. Their listeners are only checked by tests (AC-7). |
| Data change | 1 | One additive migration creating `users.outbox`. |
| Public API / event contract | 0 | The `USER_DELETED` topic, key and JSON are unchanged. The admin DEAD-list endpoint mirrors the order-service one. |
| Security or money path | 2 | Privacy erasure fan-out and a new admin endpoint. |
| New dependency or infrastructure | 0 | None. `JobLock`, KafkaTemplate and the same Postgres are reused. |
| Estimated changed lines | 1 | About 300-450 production lines. |
| **Total** | **4** | |

**Class: M.** Limit: 600 production lines in one PR. No split. If the design estimate passes about 500 lines, stop and tell the CTO.

## Shared lib ruling: copy into user-service, do not lift
`OutboxRelay` hard-codes `orders.outbox` and order-specific kinds and remotes. Lifting it would mean redesigning it inside order-service (a money path) for a single adopter. Copy the semantics into user-service instead: same-transaction insert, `seq` ordering, backoff capped at 1 h, 20 attempts to DEAD, "OUTBOX DEAD" at ERROR, and `JobLock`. Record in the ADR that a second adopter is the trigger for lifting it into a shared lib.

## Conflict risk
- **Migration number:** the CEO checked on 2026-11-21. user-service tops out at V5 on 820e5a8 and on F-06, F-07 and F-08, so **V6 is free**. Re-check before merge.
- **Overlapping files:** `PrivacyService`, `PrivacyKafkaConfig`, and user-service security config for the admin route. Keep the edits there small.

## Design notes
- `PrivacyService.complete()`: erasure, outbox insert and COMPLETED in one transaction (AC-1).
- The idempotency key is unique per user (AC-5). No PII in the payload or `last_error` (AC-10).
- AC-7 adds consumer idempotency tests only.
- Close payment, promotion and seller in TD-P6-33 as "no producers".
