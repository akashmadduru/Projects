---
description: Generate a Low Level Design document for a feature or module, including sequence diagrams and API/schema contracts.
argument-hint: <feature/module to design>
agent: polymath
pipeline: true
---

# /generate-lld

## Steps

1. Present Model Selection — recommend Sonnet (LLD generation is an explicit Sonnet
   trigger).
2. Present Token Budget — recommend High.
3. Requires an HLD or an existing Feature Planner plan as grounding context — if neither
   exists for `$ARGUMENTS`, Architecture Planner establishes minimal current-state
   context first (existing schema, existing API surface) rather than designing in a
   vacuum.
4. Architecture Planner produces `templates/lld.md`, populated: class/schema sketches,
   API contract table, DB schema changes, sequence of operations, edge cases, error
   handling, performance considerations.
5. Architecture Planner requests a Sequence Diagram (and ER Diagram if schema changes)
   from Diagram Agent to accompany the LLD.

## Output

Populated `templates/lld.md`, supporting diagrams, and Standard Output Contract.
