# Shared Prompt Fragment: Coding Standards & Global Rules

> Every implementation-capable agent (Feature Implementer, and any specialist agent that edits code) MUST load this fragment and treat it as non-negotiable unless the user explicitly overrides a specific rule for a specific task.

## Global Rules (apply to every change, frontend or backend)

1. Always preserve existing project architecture. Do not introduce a new pattern where an established one already solves the problem.
2. Never rewrite working code unnecessarily. Prefer the smallest diff that correctly and cleanly satisfies the requirement.
3. Minimize regressions — before changing shared code (composables, stores, services, base classes), enumerate every known call site.
4. Favor reusable components/functions over copy-paste. If you notice duplication while working, flag it in Technical Debt rather than silently refactoring unrelated code.
5. Follow SOLID, Clean Architecture, Clean Code, and Domain-Driven Design (where a domain model already exists or is being introduced).
6. Follow enterprise naming conventions consistently with the surrounding codebase (detect and match existing casing/naming style before applying a default).
7. Favor readability over cleverness. A junior engineer should be able to follow the change without additional explanation.
8. Always explain tradeoffs when more than one valid approach exists.
9. Always surface technical debt encountered, even debt you are not fixing.
10. Always propose future improvements relevant to the change.
11. Always self-validate before declaring a task complete (see each agent's Validation section).

## Frontend Standards (Vue 3 / TypeScript / Pinia / Tailwind)

- Composition API only (`<script setup lang="ts">`). No Options API in new code.
- Strong typing throughout: no implicit `any`, prefer explicit interfaces/types for props, emits, store state, and API payloads. Use `defineProps<T>()` / `defineEmits<T>()` generics.
- Follow the official Vue Style Guide (Priority A/B/C rules) — multi-word component names, prop definitions with types, `:key` on `v-for`, no `v-if` with `v-for` on the same element.
- Components should be small and composable: presentational components stay dumb (props in, events out); logic lives in composables (`useX`) or Pinia stores.
- Pinia stores use the setup-store syntax for consistency with Composition API; state is never mutated directly from components — always via actions.
- Co-locate types with their domain (`types/`, or alongside the store/composable that owns them) rather than one giant `types.ts`.
- Tailwind: prefer utility composition over `@apply` sprawl; extract repeated utility clusters into components, not custom CSS classes, unless a design token requires it.
- Routing: lazy-load route components (`() => import(...)`), guard routes centrally (navigation guards + RBAC meta fields), never scatter auth checks inside components.
- Axios: centralize instance creation, interceptors (auth header injection, 401 refresh flow, error normalization) in a single `services/http` layer — feature code never imports `axios` directly.
- Performance: memoize expensive computed values, avoid unnecessary reactivity (use `shallowRef`/`markRaw` for large non-reactive data), virtualize long lists, code-split by route and by heavy rarely-used components.

## Backend Standards (Python / FastAPI / SQLAlchemy / Microservices)

- Layered architecture: router (I/O boundary, validation via Pydantic) → service (business logic, orchestration) → repository (persistence) → model (SQLAlchemy ORM). Routers never touch the ORM session directly for anything beyond trivial reads.
- Pydantic schemas are the contract at the API boundary; ORM models never leak directly into responses.
- Dependency Injection via FastAPI `Depends` for DB sessions, current user, and service instances — no global mutable state.
- Alembic migrations are additive-first (never edit a migration that has shipped to another environment); every schema change ships with its migration in the same change set.
- Async I/O throughout the request path (`async def`, `AsyncSession`) unless a library forces sync; isolate sync code behind `run_in_threadpool`.
- Domain events (RabbitMQ/Kafka) are explicit, versioned, and schema-validated; producers and consumers never share code by accident (shared contracts live in a dedicated schema/package, not by importing one service's internals into another).
- JWT/OAuth: token validation is centralized middleware/dependency, never duplicated per-route; short-lived access tokens + refresh flow; scopes/roles carried in the token or resolved from a trusted source, never trusted from client-supplied fields.
- Configuration via environment variables validated at startup (Pydantic `BaseSettings`), never scattered `os.environ.get` calls.
- Errors: typed domain exceptions caught by a single FastAPI exception handler layer, translated to consistent HTTP problem-detail responses — no bare `except Exception` swallowing errors silently.

## Cross-Cutting

- Security: validate all external input at the boundary; never trust client-supplied identifiers for authorization decisions; secrets only via environment/secret manager, never hardcoded or logged.
- Observability: structured logging with correlation/trace IDs propagated frontend → gateway → service → event.
- Testing: every non-trivial change ships with at least one test proving the fix/feature and one test guarding the previously-broken edge case.
- Docker/Kubernetes: images are minimal and multi-stage; no secrets baked into images; readiness/liveness probes defined for anything deployed to k8s.
