# Shared Prompt Fragment: Model Selection

> Included at the start of every command. Every command MUST render this prompt to the user before doing any work, unless the user has already specified a model in the same message.

## Prompt To Present

```
Model:
Default: Haiku

Options:
1. Haiku   — fast, cheap, best for narrow/mechanical work
2. Sonnet  — deeper reasoning, best for architecture-level or cross-module work

Reply with 1 or 2, or press enter to accept the default (Haiku).
```

## Automatic Recommendation Logic

The invoking agent/command MUST inspect the request and repository signal before presenting the prompt, and MUST attach a one-line recommendation above the prompt when a strong signal is detected.

Recommend **Sonnet** and pre-select option 2 when any of the following are true:

- The target repository is larger than "small" (heuristic: > 150 tracked files, > 25k LOC, or more than 6 top-level domains/packages).
- The task touches architecture: new services, new bounded contexts, cross-cutting concerns, breaking API changes.
- The task is a major refactor: renaming/moving more than ~10 files, changing a core abstraction, changing state management shape.
- The task is feature planning that spans both frontend and backend, or spans more than 3 modules.
- The task is debugging that requires tracing across more than one layer (e.g. Vue component → Pinia store → Axios client → FastAPI route → SQLAlchemy model).
- The task is High Level Design (HLD) or Low Level Design (LLD) generation.
- The task is documentation generation that requires synthesizing multiple source files into a coherent narrative (Architecture.md, DecisionLog.md).

Recommend **Haiku** and pre-select option 1 when any of the following are true:

- The task is a documentation *update* to an existing, already-structured doc.
- The task is markdown generation/formatting with no architectural judgment required.
- The task is a simple, localized fix (single file, single function, obvious cause).
- The task is file/symbol searching or repository inventory.
- The task is a code explanation of an already-understood, bounded snippet.
- The task is TODO / checklist generation from an existing plan.

## Output Contract

Once the user responds (or the default is accepted), the command/agent MUST:

1. Record the chosen model as `$MODEL` for the remainder of the run.
2. State explicitly: `Using model: <Haiku|Sonnet> (<default|recommended|user override>)`.
3. If the automatic recommendation and the user's choice disagree, proceed with the user's choice but note the tradeoff in one sentence (e.g. "Sonnet is recommended for this cross-module refactor; continuing with Haiku per your selection — expect shallower reasoning across files.").

## Notes For Agent Authors

- Never silently upgrade or downgrade the model without telling the user.
- Model selection is a workspace-wide convention, not a per-agent one. All sub-agents inherit `$MODEL` from the command that invoked them unless the orchestrator explicitly overrides it for a specific delegated step (e.g. Polymath may run its own reasoning on Sonnet while delegating a mechanical sub-step to a Haiku-run agent).
