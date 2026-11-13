# ADR 0002 — Event-driven inventory: Kafka in KRaft mode, and a duplicated (not shared) event contract

- **Status:** Accepted
- **Date:** 2026-08-17
- **Phase:** 2 (event-driven inventory slice)
- **Deciders:** Platform engineering

## Context

The event-driven inventory slice (`docs/HLD-event-driven-inventory.md`,
`docs/LLD-event-driven-inventory.md`) introduces this platform's first Kafka broker and its first
Kafka producer/consumer pair (`product-service` → `product.events` → `inventory-service`). Two
decisions made while standing that up have consequences beyond this one feature and are recorded
here rather than left implicit in the HLD.

## Decision 1 — Kafka runs in KRaft mode, independent of the platform's existing ZooKeeper

> **Note 2026-11-14:** the separate ZooKeeper container referred to below has since been removed
> (see ADR 0001 Decision 5). KRaft mode is unaffected.

Kafka (`apache/kafka:3.9.0`) is provisioned as a single-broker, KRaft-mode cluster in
`infra/docker-compose.yml`. It does **not** use the ZooKeeper container ADR 0001 already provisions
for app-level coordination (distributed locks, leader election).

**Rationale.**

- **KRaft is where Kafka itself is going.** Kafka's own ZooKeeper-mode coordination is deprecated
  upstream; standing up a new Kafka deployment in ZooKeeper mode in 2026 means adopting a path the
  project itself is retiring. `apache/kafka:3.9.0` is KRaft-only — there is no ZooKeeper-mode
  entrypoint in that image at all, so there is no "which mode" flag to get wrong.
- **Reusing the existing ZooKeeper would conflate two unrelated jobs.** ADR 0001 Decision 5 is
  explicit that this platform's ZooKeeper is reserved for future application-level coordination
  primitives (distributed locks, leader election) and is *not* service discovery. Pointing Kafka's
  own internal metadata at that same instance would make one ZooKeeper serve two owners with two
  different failure/availability requirements, and would make "is this ZooKeeper down because of an
  app-coordination problem or a Kafka problem" an ambiguous question during an incident.
- **One fewer moving part per broker.** KRaft means Kafka's controller quorum lives inside the Kafka
  process itself (`KAFKA_PROCESS_ROLES: broker,controller`), so there is no separate ZooKeeper
  ensemble to keep available for Kafka to function at all.

**Alternative considered and rejected: point Kafka at the existing ZooKeeper container.** Rejected
for the conflation reason above, and because it would have required an image/mode that is already
being phased out upstream for a platform started fresh in 2026.

**Trade-off accepted.** Kafka's replication factor is 1 in this single-broker prototype
(`KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR`, `KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR`, and
`product.events` itself all set to `replicas: 1`) — a documented durability gap (see HLD §8), not a
consequence of the KRaft-vs-ZooKeeper choice. A production deployment needs 3+ brokers and
`min.insync.replicas` regardless of which coordination mode Kafka uses internally.

## Decision 2 — The `ProductEvent` contract is duplicated per-module, not published from a shared library

`product-service` (producer) and `inventory-service` (consumer) each define their own
`ProductEvent`/`ProductEventType` types, independently, in `product-service-app` and
`inventory-service-app` respectively — not in a shared `libs/common-events` module, and not even in
either service's own `-api` module (these are internal event-plumbing types, not the service's
published HTTP contract).

**Rationale.**

- **There is exactly one producer and one consumer.** A shared library earns its cost the moment a
  second consumer needs the same shape; with one of each, extracting one now is speculative
  generality with no second caller to validate the abstraction against.
- **Keeps the coupling honest.** `inventory-service` has zero Gradle dependency on
  `product-service-api` or any product-service module. The only coupling between the two services is
  the Kafka topic itself. A shared events library would be a second, compile-time coupling on top of
  the topic — exactly the kind of dependency this design otherwise goes out of its way to avoid (see
  HLD Non-Goals: "No synchronous call between product-service and inventory-service, in either
  direction").
- **The duplication is small and reviewable.** Each side's copy is one record and one enum. A
  divergence between them is caught immediately: Jackson fails deserialization on a genuinely
  incompatible shape, and `inventory-service`'s payload field is typed loosely (`Object`, not
  `ProductResponse`) specifically because it does not need product-service's fields today — it has
  nothing to diverge on beyond `eventId`/`eventType`/`productId`/`occurredAt`, which are the fields
  that matter for idempotent provisioning.

**Alternative considered and rejected: extract `libs/common-events` now, mirroring `common-core`/
`common-web`/`common-security`.** Rejected for this iteration — those three libraries all have
multiple real consumers already; `product.events` has exactly one of each, and a shared-DTO
extraction with a single producer and a single consumer is a one-time, reviewable cost that is
cheaper to pay later, with a second consumer's actual requirements in hand, than to pay speculatively
now and guess wrong about what the shared shape should be.

**Trade-off accepted.** A schema change to `ProductEvent` (new field, renamed field) must currently
be made in two places by hand, with no compiler or schema-registry check that both sides agree. This
is the same category of risk the HLD's Non-Goals section already calls out ("No schema registry
(Avro/Protobuf)") — accepted for this prototype, not indefinitely.

## Consequences

- A second consumer of `product.events` is the trigger for reopening this decision: extract
  `libs/common-events` at that point, per the HLD's own Future Improvements list.
- `inventory-service`'s build has no dependency edge on `product-service` or `product-service-api` —
  worth preserving deliberately as the event stream gains more event types or more consumers.
- Kafka's KRaft-vs-ZooKeeper choice is now precedent: any future broker this platform stands up
  (a second Kafka cluster, a different message broker) should default to not sharing the
  app-coordination ZooKeeper either, for the same conflation reason.

## Revisit when

- A second consumer of `product.events` exists → extract `libs/common-events`.
- Kafka moves toward a multi-broker production deployment → revisit replication factor and
  `min.insync.replicas` (unrelated to KRaft-vs-ZooKeeper, tracked here only for proximity).
