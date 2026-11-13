# Documentation

All project documentation lives in this folder. Start with **getting started** to run the platform,
**phases** to see what has been built, and **architecture** to see how it fits together.

| I want to… | Read |
|---|---|
| Run the platform locally | [getting-started.md](getting-started.md) |
| Know what each phase built and why | [phases/](phases/README.md) |
| Know what's next | [roadmap.md](roadmap.md) |
| Understand the system | [architecture/overview.md](architecture/overview.md) |
| Find known shortcuts and risks | [techdebt/](techdebt/README.md), [audit/technical-audit.md](audit/technical-audit.md) |
| Connect Stripe, email, SMS, push or Google | [operations/setup-integrations.md](guides/integrations.md) |
| Deploy to AWS | [operations/deploy-aws.md](guides/deploy-aws.md) |

## Layout

```
docs/
├── getting-started.md         prerequisites, env, build, run, ports, dev scripts
├── roadmap.md                 where the product stands and what comes next
├── phases/                    one page per phase (1–10) plus the UI and delivery tracks
├── architecture/
│   ├── overview.md            services, data flow, consistency, deployment topology
│   ├── api.md                 HTTP API reference (detailed for Phases 1–5)
│   ├── endpoints.md           every controller endpoint in every service (generated from code)
│   ├── events.md              Kafka topics, event types, idempotency, failure handling
│   ├── data-model.md          schemas (detailed for product, inventory, rating)
│   ├── decision-log.md        cross-cutting backend decisions
│   ├── adr/                   architecture decision records 0001–0004
│   ├── design/                HLD and LLD for Phases 2 and 3
│   └── diagrams/              Mermaid diagrams 01–16
├── services/                  deep dives: auth-service, gateway, eureka-server, product-service, inventory-service
├── apps/                      storefront (React) and admin (Vue) SPAs
├── operations/                operations runbook, CI/CD, AWS deploy, integrations, Postman
├── techdebt/                  tech debt register (TD-<AREA>-NN ids)
└── audit/                     technical audit of 2026-11-13/14
```

## Coverage and freshness

| Document | Covers | Last checked against code |
|---|---|---|
| phases/, getting-started.md, roadmap.md, architecture/overview.md, architecture/endpoints.md | Phases 1–10 | 2026-11-14 |
| architecture/api.md, data-model.md, events.md, decision-log.md | Detailed for Phases 1–5. Later phases are summarised in the phase pages and listed in endpoints.md. | 2026-11-10 to 11-12 |
| architecture/design/ (HLD/LLD) | Phase 2 and 3 designs as approved. Later phases changed some details; each phase page has a "Changed since" section. | 2026-11-10 |
| architecture/diagrams/ 01–12 | Storefront and platform as of Phases 1–4 | 2026-11-10 |
| architecture/diagrams/ 13–16 | Phase 5 customer accounts | 2026-11-12 |
| services/ | The five services built in Phases 1–2 | 2026-11-10 |
| techdebt/ | All phases. Items are re-checked against the code when a phase closes. | 2026-11-13 |

Services added in Phases 5–10 (user, cart, order, payment, promotion, notification, seller, support,
rating) have no deep-dive page. Their behaviour is described in the phase that introduced them.
