# Documentation

All project documentation lives in this folder, grouped by what you are trying to do.

| I want to… | Read |
|---|---|
| Run the platform locally | [getting-started.md](getting-started.md) |
| Know what each phase built and why | [phases/](phases/README.md) |
| Know what is next | [roadmap.md](roadmap.md) |
| Understand how the system fits together | [architecture/overview.md](architecture/overview.md) |
| Look up an endpoint, error code, table or event | [reference/](#reference) |
| Operate, debug, deploy or configure | [guides/](#guides) |
| Set up AWS, the domain, Stripe and Google before deploying | [guides/cloud-setup.md](guides/cloud-setup.md) |
| Look up what an environment variable does | [reference/environment-variables.md](reference/environment-variables.md) |
| Respond to a leaked secret or committed DB dump | [guides/runbooks/leaked-database-dump.md](guides/runbooks/leaked-database-dump.md) |
| Find known shortcuts and risks | [techdebt/](techdebt/README.md), [audit/technical-audit.md](audit/technical-audit.md) |

## Categories

### Start here
- [getting-started.md](getting-started.md): prerequisites, configuration, build, run, ports, dev scripts.
- [roadmap.md](roadmap.md): where the product stands and the ordered next steps.

### History: phases
- [phases/](phases/README.md): one page per phase (1–10) plus the storefront UI and delivery tracks.
  Each page says what was built, why, and what later phases changed.

### Explanation: architecture
- [architecture/overview.md](architecture/overview.md): platform map (14 applications, 6 topics), principles, observability, limits.
- [architecture/decision-log.md](architecture/decision-log.md): cross-cutting decisions and whether each still holds.
- [architecture/adr/](architecture/adr/): ADR 0001–0006.
- [architecture/design/HLD-ai-platform.md](architecture/design/HLD-ai-platform.md): AI roadmap AI-1..AI-5 (AI-1 semantic search built).
- [architecture/design/](architecture/design/): HLD and LLD for Phases 2 and 3 (historical designs).
- [architecture/diagrams/](architecture/diagrams/README.md): Mermaid diagrams 01–18.

### Reference
- [reference/endpoints.md](reference/endpoints.md): all 309 HTTP endpoints, generated from the controllers.
- [reference/api.md](reference/api.md): auth, roles, all 38 error codes, paging, rate limits, idempotency, and detailed request/response for the core endpoints.
- [reference/events.md](reference/events.md): every Kafka topic, consumer group, payload, and failure path.
- [reference/environment-variables.md](reference/environment-variables.md): every environment variable, its default, whether production needs it, and where it is set. Generated from the code and checked in CI.
- [reference/data-model.md](reference/data-model.md): all 12 databases and their tables, from the Flyway migrations.

### Guides
- [guides/operations.md](guides/operations.md): debugging one service, migrations, health, logs, troubleshooting.
- [guides/integrations.md](guides/integrations.md): Stripe, email, SMS, push and Google for local development.
- [guides/cloud-setup.md](guides/cloud-setup.md): what to configure before the first AWS deploy: account, quotas, domain, Stripe, Google, `config.env`.
- [guides/ci-cd.md](guides/ci-cd.md): GitHub Actions workflows, the enforcement switch, the baseline to clear.
- [guides/deploy-aws.md](guides/deploy-aws.md): Terraform, EKS and Helm deployment.
- [guides/postman.md](guides/postman.md): the Postman collection.
- [guides/runbooks/](guides/runbooks/): [first admin user](guides/runbooks/first-admin-user.md), [rotate a secret](guides/runbooks/rotate-secrets.md), [leaked database dump](guides/runbooks/leaked-database-dump.md), [payment problems](guides/runbooks/payment-problems.md).

### Components
- [services/](services/): deep dives for auth-service, gateway, eureka-server, product-service, inventory-service, [payment-service](services/payment-service.md).
  The nine services added in Phases 4–10 are described in their phase pages.
- [apps/storefront.md](apps/storefront.md) and [apps/admin.md](apps/admin.md): the two SPAs.

### Risks
- [techdebt/](techdebt/README.md): the register, with a stable `TD-<AREA>-NN` id per item.
- [audit/technical-audit.md](audit/technical-audit.md): the 2026-11-13/14 audit snapshot, with a progress note.

## Freshness

Every page states the date it was last checked against the code.

| Pages | Last checked against code |
|---|---|
| getting-started, roadmap, phases/, architecture/overview, architecture/decision-log, reference/ (all four), guides/operations, guides/integrations, guides/runbooks/, services/ (status sections), apps/ (status sections) | 2026-11-14 |
| guides/ci-cd, guides/deploy-aws | 2026-11-14 (written that day) |
| guides/cloud-setup, guides/runbooks/first-admin-user, guides/runbooks/rotate-secrets, architecture/diagrams/17 | 2026-11-15 (written that day; AWS steps never run against a real account) |
| reference/environment-variables | Generated from the code; `--check` in CI keeps it current |
| techdebt/ | Items dated individually, 2026-11-12 to 2026-11-14 |
| architecture/adr/, architecture/design/, architecture/diagrams/, audit/ | Historical records. Not rewritten; dated notes mark what has since changed. |
