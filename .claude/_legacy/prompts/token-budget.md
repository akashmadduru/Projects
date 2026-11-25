# Shared Prompt Fragment: Token Budget

> Included at the start of every command, immediately after Model Selection. Every command MUST render this prompt before doing any work, unless the user has already specified a budget in the same message.

## Prompt To Present

```
Select Token Budget:
Default: Medium

Options:
1. Medium — standard context window, targeted file reads, single-pass reasoning
2. High   — larger repository context, deeper reasoning, multi-file synthesis, full architecture analysis

Reply with 1 or 2, or press enter to accept the default (Medium).
```

## What Each Budget Enables

### Medium (default)
- Read only the files directly implicated by the request (entry point + direct imports/dependents, one hop).
- Single-pass reasoning per sub-agent; no redundant re-reads.
- Diagrams limited to the specific flow requested.
- Documentation limited to the feature/module in scope.

### High
- Expand context to two-hop dependents/dependencies (callers of callers, consumers of consumers).
- Allow multi-file synthesis: cross-reference frontend store/composable/component/service layers and backend router/service/repository/model layers together.
- Enable full architecture analysis: module boundaries, coupling, layering violations, domain model consistency.
- Allow the Diagram Agent to produce multi-diagram bundles (e.g. HLD + sequence + ER together) instead of a single diagram.
- Allow Documentation Writer to produce cross-cutting docs (Architecture.md, DecisionLog.md) that span the whole affected subsystem, not just the touched files.
- Allow Technical Debt Analyzer to scan the full module/service rather than only changed files.

## Automatic Recommendation Logic

Recommend **High** when:
- The request explicitly mentions "architecture," "system-wide," "cross-service," "microservice communication," or "end-to-end."
- Model Selection already recommended Sonnet (the two signals are correlated but not identical — always evaluate both independently).
- The repository has more than one deployable unit (multiple backend services, or a monorepo with separate frontend/backend packages).

Recommend **Medium** otherwise, including for single-file fixes, isolated component work, and narrow debugging.

## Output Contract

1. Record the chosen budget as `$TOKEN_BUDGET`.
2. State explicitly: `Using token budget: <Medium|High> (<default|recommended|user override>)`.
3. Sub-agents receiving delegated work MUST respect `$TOKEN_BUDGET` — a Medium budget forbids opening more than the directly-relevant file set; a High budget permits (but does not require) wider reads when they materially improve the answer.
