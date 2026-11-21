# F-20261119-12 Requirements: outbox for remaining publishers (O-08, TD-P6-33)

## 1. Problem and users affected
Only order-service writes its events to a transactional outbox (`orders.outbox`, `Outbox`, `OutboxRelay`, V10).
Other services publish to Kafka after commit, best effort, so a crash or broker outage can lose an event.
Inventory first finding: **payment-, promotion- and seller-service have no Kafka producer at all** (code
search: no `KafkaTemplate`, `StreamBridge` or publisher class; they only consume `order.events` and
`user.events`). The only producer in the four named services is user-service `PrivacyService`.
Affected: customers requesting account erasure (privacy/security), and payment, promotion and seller services
that must erase user data when `USER_DELETED` arrives.

## 2. Publisher inventory (worktree F-20261119-12, base 820e5a8)
| Service | Publisher | Topic / event | Class | Today |
|---|---|---|---|---|
| payment-service | none (consumer `UserDeletedListener` only; outbound HTTP is Razorpay API, not Kafka) | none | n/a | no change possible |
| promotion-service | none (consumers `OrderEventListener`, `UserDeletedListener`) | none | n/a | no change possible |
| seller-service | none (consumers `OrderEventConsumer` ledger, `UserDeletedListener`) | none | n/a | no change possible |
| user-service | `PrivacyService.complete()` via `privacyKafkaTemplate`, `kafka.send(...).get(10s)` | `user.events` / `USER_DELETED` | security-relevant (privacy erasure fan-out) | sent after local erasure statements, which are not in one transaction; failure leaves request `SCHEDULED` and the whole erasure re-runs next 15 min tick |
Money events (payment, coins, seller ledger) are produced by order-service, which already uses the outbox
(`ORDER_EVENT`, `CANCEL_PAYMENT`, `RELEASE_COINS`, `COD_COLLECTED`). Nothing money-relevant remains unprotected
in the four services. Other unprotected publishers (product, inventory, rating, auth audit) are other classes
and not in this objective.

## 3. Scope
- user-service: deliver `USER_DELETED` on `user.events` through a transactional outbox, reusing the order-service
  semantics (same-transaction write, retry with backoff, per-aggregate ordering, DEAD state, idempotency key,
  single-replica relay, at-least-once).
- Additive Flyway migration in user-service only (new `users.outbox` table; no change to existing tables).
- An admin-visible way to list DEAD rows, as in order-service.
- Shared lib (`common-web` lift) only if the architect justifies it; not required by these ACs.

## 4. Non-goals
- No outbox in payment-, promotion- or seller-service: they publish nothing. Do not add producers to them.
- No change to product, inventory, rating or auth-service publishers (other classes; stay in TD-P6-33 residual).
- No change to the `USER_DELETED` JSON shape, topic name, key (userId) or the three consuming listeners.
- No new service, datastore or vendor; no destructive migration; no change to order-service behaviour.
- No change to deletion rules (grace period, blockers, OTP/password checks).

## 5. Acceptance criteria
- AC-1 (user-service, USER_DELETED): Given a due deletion request with no blockers, when the deletion runs, then
  the `USER_DELETED` row is inserted in `users.outbox` in the same database transaction as the profile
  anonymisation and the request's move to `COMPLETED`; a forced failure in any of those steps leaves no outbox row
  and no partial change.
- AC-2 (user-service, USER_DELETED): Given Kafka is unreachable when a deletion completes, when the deletion runs,
  then the request still reaches `COMPLETED`, no exception escapes, and the outbox row stays `PENDING`.
- AC-3 (user-service, USER_DELETED): Given a `PENDING` row and Kafka becomes reachable, when the relay runs, then
  exactly one record is sent to topic `user.events`, key = userId, same JSON fields as today (`eventId`, `type`
  = `USER_DELETED`, `userId`, `occurredAt`), and the row becomes `DONE`.
- AC-4 (user-service, USER_DELETED): Given a send fails, when the relay retries, then `attempts` increments, the
  next attempt is delayed with backoff, and after 20 failures the row is `DEAD` and "OUTBOX DEAD" is logged at ERROR.
- AC-5 (user-service, USER_DELETED): Given the deletion for one user is triggered twice (rerun or two replicas),
  then at most one outbox row exists for that user (idempotency key) and the relay delivers it once per row.
- AC-6 (user-service): Given two relay replicas, when both tick, then only one delivers (job lock), and a row for a
  user is not delivered before an older pending row for the same user.
- AC-7 (consumers, payment/promotion/seller `UserDeletedListener`): Given a `USER_DELETED` delivered twice (at least
  once), then each service's erasure result is the same as after one delivery (no error, no double effect).
- AC-8 (user-service admin): Given a `DEAD` row, when an admin with the admin role calls the DEAD-list endpoint,
  then it is returned with status 200 and `attempts`, `last_error`, `kind`; a non-admin gets 403 and no token gets 401.
  A retry of a DEAD row resets it to `PENDING` and it is then delivered by the relay.
- AC-9 (migration): Given a database at user-service V5, when the new migration runs, then it succeeds, creates only
  `users.outbox` and its indexes, and alters or drops nothing existing; the Flyway version is the next free number
  after any unmerged user-service migration (see conflict risk).
- AC-10 (privacy): Given any outbox row, then its payload and `last_error` contain no email, phone, name or
  password data; only `userId`, `eventId`, `type`, `occurredAt`.
- AC-11 (no regression): Given the existing user-service, payment, promotion, seller and order test suites, when
  `node .claude/hooks/forge-verify.mjs --changed` runs, then all pass and the order-service outbox tests are unchanged.

## 6. Edge cases and abuse cases
- Crash after anonymisation but before send: today re-runs the whole erasure; after the change the event is durable.
- Kafka down for hours: rows back off to 1 h cap; deletion is not blocked by Kafka.
- Duplicate `USER_DELETED` and out-of-order replays: consumers must stay idempotent (AC-7).
- Cancel deletion during grace (`cancelDeletion`) must not leave or create an outbox row.
- Poison row (corrupt payload): goes `DEAD`, does not block other users' rows.
- Admin endpoint abuse: role check, no payload PII in the response, no unbounded list (page limit).
- Unmerged chain: user-service has unmerged changes on F-06, F-07, F-08 (based on F-03). Conflict risk: the migration
  version number (V6 may be taken), `PrivacyService`, and user-service config/security files. Check at rebase.

## 7. Open questions
None blocking. Defaults taken: (a) the outbox lives in user-service as `users.outbox`, mirroring order-service,
a `common-web` lift is the architect's call; (b) payment, promotion and seller are closed as "no publishers,
nothing to do" and TD-P6-33 is updated accordingly; (c) one part, size M, no split needed.
Suggested split if the architect lifts `Outbox`/`OutboxRelay` into common-web: part 1 lift with order-service
unchanged in behaviour, part 2 user-service adoption. Remaining TD-P6-33 services (product, inventory, rating,
auth audit) go to later objectives.
