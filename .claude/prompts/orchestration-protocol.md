# Shared Prompt Fragment: Orchestration Protocol

> Defines how Polymath (and any command that chains multiple agents) delegates, tracks, and merges work across sub-agents.

## Delegation Rules

1. Polymath never implements directly unless: (a) no specialized agent covers the need, or (b) the task is trivial enough that spinning up a sub-agent would waste the token budget (e.g. a one-line typo fix explicitly scoped by the user).
2. Every delegation MUST pass: the user's original request, the relevant file paths already identified, `$MODEL`, `$TOKEN_BUDGET`, and any output from prior steps in the pipeline that the next agent depends on.
3. Every delegated agent MUST return its output using the Standard Output Contract (`prompts/output-format.md`) so Polymath can merge deterministically.
4. Polymath tracks a running task list for multi-step pipelines and updates it as each stage completes — mirroring the state visible to the user.

## Standard Pipeline (used by `/plan-feature` and similar multi-stage commands)

```
Feature Planner
   ↓ (implementation plan, impacted files, risks)
Debug Planner            [only if the plan touches existing buggy/fragile areas]
   ↓ (root-cause notes, dependency risks)
Architecture Planner
   ↓ (HLD/LLD deltas, diagrams needed)
Approval                 [Polymath presents merged plan to user, waits for go-ahead]
   ↓
Feature Implementer
   ↓ (code changes)
Optimization             [Technical Debt Analyzer / Performance]
   ↓ (debt log, perf notes)
Documentation Writer
   ↓ (Feature.md, Changes.md, DecisionLog.md)
Future Improvements      [rolled into Documentation Writer output]
   ↓
Review                   [Code Review Agent, Security Review Agent as applicable]
   ↓
Diagram Agent            [if diagrams were requested or the Architecture Planner flagged new flows]
   ↓
Test Planner
   ↓
Summary                  [Polymath merges everything into the Standard Output Contract]
```

## Approval Gate

The pipeline MUST pause after the Architecture Planner stage and present the merged plan (scope, impacted files, risks, complexity estimate, diagrams to be generated) before any code is written. Polymath does not proceed to Feature Implementer without explicit user approval, unless the user has pre-authorized "plan and implement in one pass" for this request.

## Failure Propagation

- If any stage fails validation, Polymath halts the pipeline at that stage, reports the failure using that agent's Failure Recovery section, and asks the user how to proceed (retry, skip, abort) rather than silently continuing.
- A downstream agent MUST NOT paper over an upstream agent's incomplete output — it escalates back to Polymath instead of guessing.

## Merging Outputs

Polymath merges per-stage Standard Output Contracts into a single top-level report by:
- Concatenating "Files Changed" tables (deduplicated).
- Summarizing "Reasoning" and "Architecture Decisions" from all stages into one coherent narrative (not a raw concatenation).
- Union of "Risks" and "Technical Debt," de-duplicated, re-ranked by severity.
- Union of "Future Improvements," de-duplicated.
- One combined "Checklist" reflecting every stage that ran.

## Context Handoff Format

When delegating, Polymath passes a compact context block:

```
TASK: <original user request, verbatim>
MODEL: <Haiku|Sonnet>
TOKEN_BUDGET: <Medium|High>
SCOPE: <files/modules already identified as in-scope>
PRIOR_STAGE_OUTPUT: <condensed output of the previous pipeline stage, if any>
CONSTRAINTS: <any user-stated constraints, e.g. "do not touch the auth module">
```
