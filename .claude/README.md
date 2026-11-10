# Polymath Enterprise `.claude` Workspace

A complete, ready-to-use Claude Code workspace for enterprise-scale, polyglot
applications built on **Vue 3 / Composition API / TypeScript / Pinia / TailwindCSS /
Vue Router / Axios / Vite** on the frontend, and a backend that can span **Python /
FastAPI / SQLAlchemy / Alembic**, **Java / Spring (Boot, MVC, WebFlux, Security, Cloud,
Batch, Integration, AI)**, and **Rust (Tokio, Axum/Actix/tonic)** microservices —
communicating via **PostgreSQL / Redis / RabbitMQ / Kafka** and deployed via **Docker /
Kubernetes / AWS**.

Copy this entire `.claude` directory into the root of your VS Code project. No further
edits are required to start using it — every command works out of the box with sensible
defaults, and `settings/settings.json` is there when you want to tune them.

## How This Workspace Thinks

One orchestrator, **Polymath**, is the entry point for any non-trivial request. Polymath
never implements directly — it understands intent, triages the repository, selects the
right specialist agent(s), sequences them, reviews their output, and merges everything
into one deliverable. Narrow, single-domain requests can also be routed straight to the
relevant specialist agent or invoked via a specific slash command.

This particular checkout of the workspace additionally has **`sdlc-root`**
(`agents/orchestrator/sdlc-root.md`) — a project-pinned specialization of Polymath for
*this* repository specifically (not a portable template like the rest of `.claude/`). It
runs the same Standard Pipeline but starts from this repo's concrete shape (the 5
independent components, the `python/` workspace layout, the `docs/` convention, the CI/CD
already built for it) and enforces this repo's non-negotiables — documentation destination,
the Approval Gate, mandatory Security Review triggers — as fixed policy rather than
per-request judgment. Use `sdlc-root` as the default entry point for feature/bug work in
this repo; see `docs/SDLC.md` for the human-readable version of the same process.

For requests spanning more than one language/runtime, or introducing a new component
whose stack isn't already fixed by the codebase, Polymath delegates to **Solution
Architect** first. Solution Architect doesn't write code — it decides which stack each
component should be built with (Vue/TypeScript, Python/FastAPI, Java/Spring, or Rust),
defines service boundaries and communication patterns, and then routes each component to
the specialist that owns it. This keeps stack selection evidence-based and decoupled
from any single specialist's natural bias toward its own language.

Every command begins by asking two questions, defined once in `prompts/` and reused
everywhere:

1. **Model** — Haiku (default) or Sonnet, with automatic recommendations based on
   repository size and task shape (see `prompts/model-selection.md`).
2. **Token Budget** — Medium (default) or High, controlling how much repository context
   an agent is allowed to pull in (see `prompts/token-budget.md`).

Every deliverable closes with the same **Standard Output Contract** (see
`prompts/output-format.md`): Summary, Files Changed, Reasoning, Architecture Decisions,
Risks, Technical Debt, Future Improvements, Performance Notes, Security Notes, Testing,
Documentation, and a Checklist. This makes every agent's output predictable and mergeable
regardless of which one produced it.

## Directory Structure

```
.claude/
├── agents/
│   ├── orchestrator/        Polymath — the main orchestrator agent
│   ├── planning/             Feature Planner, Debug Planner
│   ├── implementation/       Feature Implementer
│   ├── optimization/         Technical Debt Analyzer, Performance Optimizer
│   ├── documentation/        Documentation Writer
│   ├── architecture/         Architecture Planner (HLD/LLD), Solution Architect
│   ├── diagrams/             Diagram Agent (20-type diagram menu)
│   ├── testing/              Test Planner
│   ├── frontend/             Vue/TypeScript, Pinia, Tailwind specialists
│   ├── backend/              Python/FastAPI, Java/Spring, Rust Systems, Microservices specialists
│   ├── aws/                  AWS Architect (AWS + Docker + Kubernetes)
│   ├── review/               Code Review Agent, Security Review Agent
│   └── debug/                Debug Specialist (interactive/executed debugging)
├── commands/                 27 reusable slash commands (see below)
├── templates/                13 markdown templates used by agents to structure output
├── prompts/                  6 shared prompt fragments loaded by every agent/command
├── settings/                 settings.json + its own README
└── README.md                 this file
```

## Agents At A Glance

| Agent | Category | Default Model | Purpose |
|---|---|---|---|
| Polymath | orchestrator | Haiku (escalates) | Understand intent, delegate, merge, validate |
| SDLC Root | orchestrator | Haiku (escalates) | Project-pinned entry point for this repo — same pipeline, fixed docs/approval/security policy |
| Feature Planner | planning | Haiku | Turn a request into a phased implementation plan |
| Debug Planner | planning | Haiku | Root-cause a bug before any fix is written |
| Feature Implementer | implementation | Sonnet | Apply an approved plan as production code |
| Technical Debt Analyzer | optimization | Haiku | Find dead code, duplication, anti-patterns, smells |
| Performance Optimizer | optimization | Sonnet | Apply targeted, evidence-based performance fixes |
| Documentation Writer | documentation | Haiku | Generate Feature/Architecture/Changes/Migration/etc. docs |
| Architecture Planner | architecture | Sonnet | Produce HLD/LLD and request diagrams |
| Solution Architect | architecture | Sonnet | Cross-language stack selection, service boundaries, communication design |
| Diagram Agent | diagrams | Haiku | 20-type diagram menu → Mermaid/PlantUML/MD/PNG/SVG |
| Test Planner | testing | Haiku | Unit/integration/E2E tests, edge cases, regressions |
| Vue/TypeScript Specialist | frontend | Haiku | Components, composables, routing, forms, typing |
| Pinia Specialist | frontend | Haiku | Store boundaries, reactivity, cross-store design |
| Tailwind Specialist | frontend | Haiku | Design tokens, utility consolidation, a11y styling |
| UI/UX Designer | frontend | Haiku | Researched visual direction (layout/palette/type/assets) via Dribbble/Behance/Awwwards/21st.dev/Pixabay/Pexels/OpenArt — precedes Tailwind/Vue implementation |
| Python/FastAPI Specialist | backend | Haiku | Router/service/repository/model layering, migrations |
| Java/Spring Architect | backend | Sonnet | Spring Boot/Cloud, JPA/Hibernate, JVM performance, enterprise patterns |
| Rust Systems Architect | backend | Sonnet | Tokio/Axum/tonic, ownership/lifetimes, zero-cost performance |
| Microservices Specialist | backend | Sonnet | Service boundaries, event contracts, resilience patterns |
| AWS Architect | aws | Sonnet | AWS services, Docker, Kubernetes, Well-Architected review |
| Code Review Agent | review | Haiku | Readability, SOLID, Clean Architecture, code smells |
| Security Review Agent | review | Sonnet | OWASP Top 10, JWT/OAuth, RBAC, input validation |
| Debug Specialist | debug | Sonnet | Executed/interactive debugging for low-confidence diagnoses |

Every agent file follows the same structure: Purpose, Elite Knowledge Domains,
Responsibilities, Workflow, Inputs, Outputs, Constraints, Best Practices, Checklist,
Validation, Failure Recovery, Escalation, Examples, Expected Deliverables — and every
agent loads `prompts/elite-knowledge-base.md` as its knowledge floor.

## Commands At A Glance

| Command | What it does |
|---|---|
| `/analyze` | Read-only architecture/risk analysis of the repo or a scope |
| `/read-project` | Fast stack/structure/convention inventory |
| `/plan-feature` | Full Standard Pipeline: plan → design → approval → implement → optimize → document → review → diagram → test |
| `/plan-debug` | Root-cause diagnosis pipeline, stops before a fix is applied |
| `/apply-plan` | Implement an already-approved plan through to tests |
| `/refactor` | Behavior-preserving structural improvement with call-site safety checks |
| `/optimize` | Apply targeted, evidence-based performance fixes |
| `/find-tech-debt` | Full technical debt scan, ranked, read-only |
| `/review` | General code review, auto-escalates to security/domain specialists as needed |
| `/document` | Generate/update Feature.md, Architecture.md, Changes.md, Migration.md, DecisionLog.md, FutureWork.md |
| `/changelog` | Generate Changes.md / ReleaseNotes.md entries |
| `/generate-hld` | High Level Design + supporting diagrams |
| `/generate-lld` | Low Level Design + sequence/ER diagrams |
| `/generate-diagram` | Diagram Agent's full 20-type menu |
| `/generate-tests` | Unit/integration/E2E tests with edge cases and regression guards |
| `/aws-review` | AWS/Docker/Kubernetes review against the Well-Architected Framework |
| `/security-review` | OWASP-grounded security review |
| `/performance-review` | Read-only performance findings (precursor to `/optimize`) |
| `/code-smell` | Focused dead code / duplication / smell scan |
| `/frontend-review` | Combined Vue/TS + Pinia + Tailwind review |
| `/backend-review` | Combined Python/FastAPI + Microservices review |
| `/pinia-review` | Pinia-specific review |
| `/vue-review` | Vue Composition API-specific review |
| `/tailwind-review` | Tailwind-specific review |
| `/design-direction` | Researched visual design direction (layout/palette/type/assets), generative not a review |
| `/typescript-review` | TypeScript typing-specific review |
| `/python-review` | Python/FastAPI/SQLAlchemy/Alembic-specific review |
| `/java-review` | Java/Spring layering, transactional boundaries, JPA/Hibernate, migration safety |
| `/rust-review` | Ownership/lifetime, unsafe justification, concurrency, benchmark-backed performance |
| `/microservice-review` | Service boundary / event contract / communication review |

## The Standard Pipeline

`/plan-feature` and `/apply-plan` run the full cross-agent pipeline defined in
`prompts/orchestration-protocol.md`:

```
Feature Planner → Debug Planner* → Architecture Planner → [ APPROVAL GATE ] →
Feature Implementer → Technical Debt Analyzer / Performance Optimizer →
Documentation Writer → Code Review Agent / Security Review Agent* →
Diagram Agent* → Test Planner → Summary

  * only when applicable to the specific request
```

The pipeline pauses at the Approval Gate and will not write code without explicit
go-ahead, unless you've pre-authorized a single pass in your original request.

## Rules This Workspace Never Breaks

Defined in full in `prompts/coding-standards.md`; the headlines:

Always preserve project architecture. Never rewrite working code unnecessarily.
Minimize regressions. Favor reusable components. Prefer the Composition API. Prefer
strongly typed TypeScript. Follow the Vue Style Guide. Follow SOLID, Clean Architecture,
Clean Code, and Domain-Driven Design where applicable. Follow enterprise naming
conventions. Favor readability over cleverness. Always explain tradeoffs. Always
identify technical debt. Always recommend future improvements. Always validate output
before declaring completion.

## Extending This Workspace

- **New agent**: add a markdown file under the right `agents/<category>/` folder,
  following the same section structure as any existing agent, and load
  `prompts/elite-knowledge-base.md` + `prompts/coding-standards.md` at minimum.
- **New command**: add a markdown file under `commands/`, with frontmatter
  (`description`, `argument-hint`, `agent`, `pipeline`) and a Steps section that always
  opens with Model Selection and Token Budget.
- **New template**: add to `templates/`; reference it from the agent(s) that should
  produce it.
- **New category**: add a folder under `agents/` and register it in
  `settings/settings.json` → `agents.categories`.

Nothing elsewhere in the workspace needs to change for an extension to work — Polymath
discovers agents by reading `agents/` at delegation time.
