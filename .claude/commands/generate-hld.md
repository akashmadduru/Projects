---
description: Generate a High Level Design document for a system or feature, including recommended diagrams.
argument-hint: <system/feature to design>
agent: polymath
pipeline: true
---

# /generate-hld

## Steps

1. Present Model Selection — recommend Sonnet (HLD generation is an explicit Sonnet
   trigger per `prompts/model-selection.md`).
2. Present Token Budget — recommend High (architecture analysis trigger per
   `prompts/token-budget.md`).
3. If a Feature Planner plan already exists in context for `$ARGUMENTS`, pass it as
   input to Architecture Planner. Otherwise Architecture Planner reads the repository
   directly to establish current-state context first.
4. Architecture Planner produces `templates/hld.md`, populated.
5. Architecture Planner identifies and requests the diagrams that best support the HLD
   (typically: Context/C4 Context, Component, Deployment, Data Flow) from Diagram Agent.

## Output

Populated `templates/hld.md`, supporting diagrams, and Standard Output Contract with
Architecture Decisions populated.
