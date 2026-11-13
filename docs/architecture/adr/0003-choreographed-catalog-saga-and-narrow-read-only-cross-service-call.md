# ADR 0003 — Choreographed (not orchestrated) product-creation saga, and a narrow read-only exception to the no-synchronous-call non-goal

- **Status:** Accepted (Decision 2 confirmed at the approval gate)
- **Date:** 2026-08-17
- **Phase:** 3 (catalog enrichment & creation saga)
- **Deciders:** Platform engineering

## Context

`docs/HLD-catalog-enrichment-and-saga.md` extends the Phase 2 event-driven inventory slice
(`docs/HLD-event-driven-inventory.md`, ADR 0002) with a "create a product with initial stock"
operation and an aggregate read. Two decisions here have consequences beyond this one feature and are
recorded per the precedent ADR 0002 already established for this platform.

## Decision 1 — The creation saga is choreographed via Kafka events, not orchestrated by a new service

No new coordinating service is introduced. `POST /api/products` is extended with an optional
`initialQuantity` field; `PRODUCT_CREATED` carries it; inventory-service provisions asynchronously;
a new `INVENTORY_PROVISIONING_FAILED` event on a new `inventory.events` topic triggers compensation
via product-service's existing `delete()`/`PRODUCT_DELETED` path.

**Rationale.** An orchestrated design would have required a synchronous call from the orchestrator to
inventory-service before returning success to the caller — exactly the coupling Phase 2's non-goal
("No synchronous call between product-service and inventory-service, in either direction") was
written to prevent. Choreography preserves that non-goal unchanged and adds no new service, at the
cost of the caller no longer getting an atomic-feeling create — see HLD §7.1 for the full tradeoff.

**Alternative considered and rejected:** a new orchestrating service issuing synchronous commands with
compensation, per the upstream Feature Planner's original plan. Rejected at the approval gate in favor
of choreography.

**Trade-off accepted.** A window exists, per creation with `initialQuantity`, where the product is
committed and returned to the caller but stock is not yet provisioned (or, rarely, provisioning fails
and the product is compensating-deleted after having already been shown to the caller as created).
Documented explicitly in the HLD rather than left as an implicit consequence.

## Decision 2 — `GET /api/catalog/products/{id}` narrowly relaxes the "no synchronous call" non-goal

Product-service makes one read-only, non-mutating HTTP call to inventory-service, specifically for
this aggregate-read endpoint, through a dedicated circuit breaker with graceful degradation to
`inventory: null` on any failure.

**Rationale.** The original non-goal exists to prevent write-path coupling that would reintroduce
distributed-transaction shape. A `GET` carries none of that risk — it is ordinary API composition, a
materially different risk category. The alternative (gateway response aggregation) was rejected
because it contradicts the gateway's own documented, deliberate "no business logic, no response
rewriting" stance (`services/gateway/.../package-info.java`) more directly than this narrow relaxation
contradicts the product/inventory non-goal.

**Alternative considered and rejected:** gateway-level response aggregation. Rejected because it would
be the gateway's first business-logic code, contradicting an existing, explicit architectural stance
rather than narrowing one.

**Trade-off accepted.** The "no synchronous call, in either direction" non-goal from
`HLD-event-driven-inventory.md` is now "no synchronous call for writes/mutations, in either direction;
one read-only aggregation call is permitted, scoped to this one endpoint." This is a revision of an
already-accepted decision from a prior approved design and is flagged for explicit approval-gate
confirmation, not treated as silently decided by this ADR alone.

## Consequences

- Any future synchronous call between product-service and inventory-service beyond this one
  read-only endpoint requires its own explicit justification — this decision does not open the door
  generally, only for this specific case.
- `inventory.events` follows `product.events`' exact precedent (ADR 0002): duplicated DTOs, no schema
  registry, no DLQ. A second consumer of either topic is still the trigger for extracting
  `libs/common-events`.

## Revisit when

- A second read-only aggregate-composition need arises — decide then whether it also qualifies for
  this narrowed non-goal or whether the pattern should be generalized (e.g., a dedicated composition
  layer) instead of repeating ad hoc per-endpoint exceptions.
- `INVENTORY_PROVISIONING_FAILED` firing becomes frequent enough that "compensating delete after
  the caller has already seen success" becomes a real product-experience complaint — at that point,
  reopen Decision 1 and consider whether the atomicity orchestration would have given up is worth the
  service/coupling cost after all, now with real failure-rate data instead of a prototype guess.
