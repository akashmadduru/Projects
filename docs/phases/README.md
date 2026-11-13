# Phases

One page per phase: what was built, how it works, the decisions behind it, and what later phases changed.
Earlier per-phase Feature / Changes / DecisionLog files were merged into these pages on 2026-11-14.

| Phase | Scope | Built | Status | Page |
|---|---|---|---|---|
| 1 (1A–1F) | Monorepo, shared libs, Eureka, auth-service, gateway, product-service reads, Vue admin SPA | 2026-11 | ✅ Built, with tests | [phase-01-foundation](phase-01-foundation.md) |
| 2 | Event-driven inventory: product writes, inventory-service, Kafka `product.events`, Redis user cache | 2026-11 | ✅ Prototype-grade | [phase-02-event-driven-inventory](phase-02-event-driven-inventory.md) |
| 3 | Catalog enrichment and choreographed saga (`inventory.events`) | 2026-11 | ✅ Built | [phase-03-catalog-enrichment-and-saga](phase-03-catalog-enrichment-and-saga.md) |
| 4 | rating-service: reviews, summaries, storefront review UI | 2026-11 | ✅ Built | [phase-04-ratings](phase-04-ratings.md) |
| 5 | Customer accounts: login, Google SSO, user / cart / order services, checkout | 2026-11-12 | ✅ Built, no tests | [phase-05-customer-accounts](phase-05-customer-accounts.md) |
| 6 | Sell for real: payments + COD, stock reservation, order lifecycle, shipping, GST invoice, notifications, returns, coupons, OTP | 2026-11-12 | ✅ Prototype-grade, no tests | [phase-06-sell-for-real](phase-06-sell-for-real.md) |
| 7 | Discovery and conversion: search, variants, facets, offers, deals, recommendations, Q&A; admin console completion | 2026-11-12 | ✅ Prototype-grade, no tests | [phase-07-discovery-and-conversion](phase-07-discovery-and-conversion.md) |
| 8 | Retention and loyalty: membership, coins, wallet, gift cards, subscriptions, referrals, notification centre, push, campaigns | 2026-11-13 | ✅ Prototype-grade, no tests | [phase-08-retention-and-loyalty](phase-08-retention-and-loyalty.md) |
| 9 | Marketplace and sellers: seller-service, KYC, seller portal, buy box, split shipments, payouts, ads, warehouses | 2026-11-13 | ✅ Prototype-grade, no tests | [phase-09-marketplace-and-sellers](phase-09-marketplace-and-sellers.md) |
| 10 | Trust, support and scale: support-service, 2FA, fraud checks, audit log, privacy, i18n/FX, PWA, tracing, exchange offers | 2026-11-14 | 🟡 In code, unverified, no tests | [phase-10-trust-support-and-scale](phase-10-trust-support-and-scale.md) |

Tracks that run alongside the phases:

| Track | Page |
|---|---|
| Storefront UI micro-interactions (previously mislabelled "Phase 1") | [storefront-micro-interactions](storefront-micro-interactions.md) |
| Delivery: CI/CD pipeline | [operations/ci-cd](../guides/ci-cd.md) |
| Delivery: AWS (Terraform, EKS, Helm) | [operations/deploy-aws](../guides/deploy-aws.md), [ADR 0004](../architecture/adr/0004-aws-eks-deployment.md) |

## Numbering

Phase numbers follow the root README and the [roadmap](../roadmap.md). Two older numbering schemes were retired:

- The storefront micro-interaction docs called themselves "Phase 1". They are now the UI track above.
- `IMPLEMENTATION_STATUS.md` (deleted) used "Backend overhaul Phases 2–8" for work that is now part of Phase 3.

## Working rules from Phase 6 on

No automated tests and no per-feature HLD/LLD. Every shortcut gets a stable ID in the
[tech debt register](../techdebt/README.md) in the same change that introduces it.
