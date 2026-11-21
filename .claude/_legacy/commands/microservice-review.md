---
description: Review service boundaries, event contracts, and inter-service communication patterns. Read-only.
argument-hint: <target service(s) or event flow>
agent: microservices-specialist
pipeline: false
---

# /microservice-review

## Steps

1. Present Model Selection — recommend Sonnet by default (cross-service boundary
   reasoning is an explicit Sonnet-recommendation trigger).
2. Present Token Budget — recommend High by default (cross-service topology review is
   an explicit High-budget trigger per `prompts/token-budget.md`).
3. Delegate to Microservices Specialist (`agents/backend/microservices-specialist.md`)
   in review-only mode, scoped to `$ARGUMENTS`.
4. Specialist checks capability alignment, sync/async communication choice, event
   contract versioning, delivery semantics/idempotency, and dual-write/outbox-pattern
   risk per its Workflow.

## Output

`templates/review-report.md` plus Standard Output Contract.
