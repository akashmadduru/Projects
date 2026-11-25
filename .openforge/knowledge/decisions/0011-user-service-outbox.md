# 0011 USER_DELETED is delivered through a user-service transactional outbox (copied, not shared)

## Status
Accepted (2026-11-21, CTO; conditions in .openforge/work/F-20261119-12/design.md "CTO decision"). Proposed by the Architect, class M.

## Context
TD-P6-33 asks for a transactional outbox for the remaining Kafka publishers in payment-, promotion-, seller- and user-service. The inventory in `requirements.md` §2 shows that only user-service produces anything: `USER_DELETED` on `user.events`, from `PrivacyService.complete()`.

That method has two problems:
- It has no transaction. Its erasure statements auto-commit one by one.
- It sends to Kafka synchronously before it marks the request `COMPLETED`.

So a Kafka outage repeats the erasure on every tick, and a crash can leave a partial erasure. order-service already solved this with `orders.outbox`, `Outbox` and `OutboxRelay` (TD-P6-01). But `OutboxRelay` hard-codes its table, its `Kind` enum and `OrderRemote`.

## Decision
- **Table.** Add `users.outbox` (V6, additive) with the same columns, constraints and partial indexes as `orders.outbox`.
- **One transaction.** `PrivacyService.complete()` runs these steps in one `TransactionTemplate` transaction:
  1. A guarded `SCHEDULED -> COMPLETED` update. Zero rows means skip.
  2. The existing erasure statements.
  3. An outbox insert, deduplicated by the idempotency key `USER_DELETED:<userId>`.

  The blocker check over HTTP stays outside the transaction.
- **Payload.** It holds only `eventId`, `type`, `userId` and `occurredAt`, all as strings. The relay writes it out again in today's field order, so the wire format is unchanged.
- **Relay.** `UserOutboxRelay` copies the order-service semantics:
  - `JobLock` "user-outbox-relay", running every 2 s;
  - per-user `seq` ordering;
  - exponential backoff capped at 1 h;
  - DEAD after 20 attempts, logged as "OUTBOX DEAD" at ERROR;
  - at-least-once delivery.

  `last_error` holds only the exception class and the root-cause message, never the payload.
- **Admin endpoints.** These are `GET /api/users/admin/outbox` and `POST /api/users/admin/outbox/{id}/retry`, with `@PreAuthorize("hasRole('ADMIN')")`. They are reached through the existing `/api/users/**` gateway route. The list never returns the payload.
- **Copy, not shared.** Per the CTO ruling, the code is copied into user-service and not lifted into `common-web`.

## Trigger for lifting into a shared lib
Lift the outbox into `libs/common-web` (a generic `Outbox`, a relay with a pluggable `Deliverer` per kind, the admin controller and a migration template) when **a second service besides order-service and user-service needs an outbox**. Examples are product, inventory, rating, or the auth audit that remains in TD-P6-33. Also lift it when either copy needs a semantic change, such as new backoff or DEAD rules, that would otherwise have to be made twice. The lift is its own objective:
- part 1 lifts the code with order-service behaviour unchanged;
- part 2 moves user-service onto it and deletes the copy.

## Alternatives considered
- **Shared-lib lift now.** It would redesign a money-path class for a single adopter. Rejected by the CTO.
- **Send after commit (`afterCommit`).** The event is still lost on a crash or an outage. Rejected.
- **Kafka transactions.** They are not atomic with Postgres, and every consumer would need config changes. Rejected.
- **Admin route under `/api/admin/**`.** It needs a gateway route change, and that prefix already belongs to auth-service. Rejected. The service's own `@PreAuthorize` is the trust boundary.

## Consequences
- **Deletion and Kafka are decoupled.** A deletion completes even when Kafka is down, and the event is durable. Consumers may see duplicates, which is fine because they are idempotent (SET-constant and DELETE statements).
- **Two copies of the relay logic now exist** until the lift trigger fires. Any fix to one copy must be checked against the other.
- **One event per user relies on auth-service disabling deleted accounts.** The idempotency key is `USER_DELETED:<userId>`, so a second deletion of the same userId is suppressed (and logged at WARN with the userId). That is correct only while a deleted account cannot sign in or request deletion again, which auth-service guarantees by disabling it.
- **Rollback.** Before a code rollback, drain or hand-publish the PENDING and DEAD rows, because the old code never reads the outbox. V6 stays in place.
- **TD-P6-33 closes for payment, promotion and seller** as "no producers". Product, inventory, rating and auth audit remain.

## Work id
F-20261119-12 (O-08). Design: `.openforge/work/F-20261119-12/design.md`. Follows TD-P6-01 (order-service outbox).
