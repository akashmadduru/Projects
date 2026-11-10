# Domain: {{domain_name}}

## Overview
- **Bounded Context:** {{bounded_context}}
- **Maturity Tag:** {{Built | Reserved-in-infra | Conceptual-only}}
- **Grounding:** {{one line — what in the actual repo backs this entry: a service, a reserved EventType/route, or "none — design only"}}

## Business Responsibilities
{{what this domain is responsible for deciding/enforcing, in plain language — 2-4 bullets}}

## Aggregate Roots
| Aggregate | Identity | Invariants it enforces |
|---|---|---|

## Entities
| Entity | Belongs to Aggregate | Notes |
|---|---|---|

## Value Objects
| Value Object | Shape | Notes |
|---|---|---|

## Domain Services
{{stateless domain logic that doesn't belong on a single entity — name + one line each}}

## Application Services
{{use-case orchestration: what a route/handler/consumer actually calls — name + one line each}}

## Repository Interfaces
{{persistence contracts this domain depends on — name + one line each}}

## Domain Events
| Event | Trigger | Maturity |
|---|---|---|

## Commands
{{state-changing intents this domain accepts — name + one line each}}

## Queries
{{read-only intents this domain answers — name + one line each}}

## Relationships to Other Domains
| Domain | Relationship | Direction | Notes |
|---|---|---|---|

## Open Questions / Design Gaps
{{anything left unresolved — mark explicitly if this is a Conceptual-only domain with no implementation to validate the design against}}
