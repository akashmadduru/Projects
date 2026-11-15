# Changes
New standalone project `synthetic-db-ai/` at the repository root. **No existing file was modified**: it only reads
`services/*/*-app/src/main/resources/db/migration`, `application.yml` and `src/main/java` (entities) for discovery.

* `main.py` — `inspect`, `plan`, `generate`, `validate`, `stats` (+ `scratch` helper for a throw-away Postgres).
* `sdai/` — discovery, catalog, planner, engine (stages, workers, resume), emitter (schema contract + COPY), world and
  generators per service group, validator (210 checks), stats, optional LLM pools, safety guards.
* `tests/` — 27 pytest tests (unit, schema contract, business rules, determinism, guards, planner, LLM fake, end-to-end).
* `inventory/` — generated schema inventory (JSON + Markdown). `README.md`, `docs/`.
* `Dockerfile`, `dev.sh`, `reset-scratch.sh` — run without a local Python / rebuild the scratch server.
