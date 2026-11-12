# Documentation Index

This directory contains architectural decision records (ADRs), high-level and low-level designs, and feature documentation for the e-commerce platform.

## Organization

### ADRs (Architecture Decision Records)

Foundational decisions that shape the platform architecture.

- **[ADR 0001](./adr/0001-monorepo-and-build-tooling.md)** — Monorepo layout, Gradle, dependency versions
- **[ADR 0002](./adr/0002-event-driven-inventory-kafka-kraft-and-event-contract-duplication.md)** — Event-driven inventory service, KRaft topology
- **[ADR 0003](./adr/0003-choreographed-catalog-saga-and-narrow-read-only-cross-service-call.md)** — Choreographed saga pattern for product creation

### System Design Documents

Comprehensive design covering multiple phases.

- **[HLD: Event-Driven Inventory](./HLD-event-driven-inventory.md)** — Phase 2 inventory service design
- **[LLD: Event-Driven Inventory](./LLD-event-driven-inventory.md)** — Detailed implementation of inventory events
- **[HLD: Catalog Enrichment & Saga](./HLD-catalog-enrichment-and-saga.md)** — Phase 3 catalog features and saga coordination
- **[LLD: Catalog Enrichment & Saga](./LLD-catalog-enrichment-and-saga.md)** — Detailed implementation of enrichment and saga

### Feature Documentation (Phase 1: Micro-interactions)

Complete documentation for the Phase 1 micro-interactions feature in the product-catalog app.

#### Quick Start
- **[Feature Overview](./Phase1-Feature.md)** — What was built, how it works, performance metrics
- **[Technical Changes](./Phase1-Changes.md)** — File-by-file modifications, bundle impact, testing guide
- **[Architecture Decisions](./Phase1-DecisionLog.md)** — Why each design choice was made (CSS vs libraries, ripple vs canvas, etc.)
- **[Technical Debt](./Phase1-Technical-Debt.md)** — 11 identified issues, severity assessment, Phase 2+ recommendations

#### For Developers Maintaining This Code
Start here:
1. **Phase1-Feature.md** — Understand what each animation does and how it works
2. **Phase1-Changes.md** — Find exactly which lines changed and what impact they have
3. **Phase1-DecisionLog.md** — Learn the reasoning behind each decision (crucial for future modifications)
4. **Phase1-Technical-Debt.md** — Understand known limitations and what to fix next

#### For Product & UX
1. **Phase1-Feature.md** (sections: Overview, User Benefits, Animation Timing)
2. **Phase1-Technical-Debt.md** (Issues #6: Accessibility feedback, #5: Loading states)

#### For Architects / Phase 2 Planning
1. **Phase1-DecisionLog.md** (all decisions; especially Decision 1: CSS vs libraries, Decision 7: Config strategy)
2. **Phase1-Technical-Debt.md** (Phase 2 recommendations section)

### Product Roadmap (Phases 6-10)

- **[Product Roadmap](./Product-Roadmap.md)** — Gap analysis vs Amazon / Flipkart; features ranked by importance, at least 10 per phase, with build order and the tech-debt items each one closes

### Feature Documentation (Phase 5: Customer Accounts)

Login, Google SSO and the user/cart/order services.

- **[Feature Overview](./Phase5-Feature.md)** — What was built, how it works, local run instructions
- **[Technical Changes](./Phase5-Changes.md)** — File-by-file changes and contract deviations
- **[Architecture Decisions](./Phase5-DecisionLog.md)** — Why (Google via auth-service :8081, checkout orchestration, idempotency)
- **[Backend API: Customer Services](./Backend-API.md#customer-services-api-phase-5)** — User, cart, wishlist and order endpoints
- **[Tech Debt Register](../techdebt/README.md)** — Known shortcuts, by area and severity

### Diagrams & Visual References

- **01-component-tree.md** — Component hierarchy of product-catalog app
- **02-data-flow-catalog-load.md** — Data flow during catalog page load
- **03-theme-system-sequence.md** — Theme toggle implementation sequence
- **04-deployment-topology.md** — Service deployment architecture
- **05-file-structure-tree.md** — Repository file organization
- **06-error-handling-flow.md** — Error handling patterns
- **07-pagination-algorithm.md** — Pagination logic visualization
- **08-single-flight-deduplication.md** — Request deduplication
- **09-jwt-token-refresh-flow.md** — Authentication refresh flow
- **10-api-contract-matrix.md** — API endpoint contracts
- **11-css-custom-properties-map.md** — CSS variable definitions and usage
- **12-hook-dependencies-store-relationships.md** — React hook and store dependencies
- **[13-auth-login-refresh-google-sequence.md](./diagrams/13-auth-login-refresh-google-sequence.md)** — Login, token refresh, Google SSO
- **[14-checkout-sequence.md](./diagrams/14-checkout-sequence.md)** — Idempotent checkout flow
- **[15-customer-data-er.md](./diagrams/15-customer-data-er.md)** — user_db / cart_db / order_db ER
- **[16-customer-components.md](./diagrams/16-customer-components.md)** — Customer accounts component diagram

## Phase 1 Micro-interactions Feature Summary

### Implemented Animations

| Animation | Component | Duration | Easing | Purpose |
|---|---|---|---|---|
| Ripple | ProductCard | 0.3s | ease-out | Click feedback with position tracking |
| Star Fill | StarRating | 0.4s (staggered) | cubic-bezier(0.34, 1.56, 0.64, 1) | Sequential star reveal with bounce |
| Price Counter | PriceDisplay | 0.8s | easeOut (cubic) | Numeric animation with proper formatting |
| Grid Stagger | ProductGrid | 0.6s (staggered) | ease-out | Sequential product card entrance |

### Key Characteristics

- **No external library dependencies** — CSS animations + requestAnimationFrame only
- **Accessibility-first** — All animations respect `prefers-reduced-motion` preference
- **Performance-optimized** — GPU-accelerated via `transform` and `opacity`
- **Bundle-conscious** — ~1.4 KB gzipped addition to CSS and component code

### Bundle Impact
- **Before:** ~45 KB component bundle + 8.2 KB style.css
- **After:** +2.7 KB unminified (~1.4 KB gzipped)
- **Percentage:** ~3% increase

## Reference Across Documents

### If You're Looking For...

**"How do I add a new animation?"**
1. Read Phase1-DecisionLog.md (Decision 1: why CSS only)
2. Review Phase1-Feature.md (Animation Timing section for conventions)
3. Check Phase1-Changes.md (style.css additions for pattern)

**"Why isn't star animation configurable?"**
→ Phase1-DecisionLog.md (Decision 9)

**"Why does ripple use DOM manipulation instead of canvas?"**
→ Phase1-DecisionLog.md (Decision 2)

**"How should we handle animations in Phase 2?"**
→ Phase1-Technical-Debt.md (Issue #11: Animation Configuration) and Phase1-DecisionLog.md (Future Decisions section)

**"What are the known issues and what should we fix first?"**
→ Phase1-Technical-Debt.md (sorted by severity and effort)

**"How do I test these animations?"**
→ Phase1-Changes.md (Testing Considerations section)

**"What happens if a user has motion sickness preferences set?"**
→ Phase1-Feature.md (Accessibility Features section) + Phase1-Changes.md (style.css prefers-reduced-motion)

## Accessibility Compliance

Phase 1 meets **WCAG 2.1 Level AA** standards:

- ✓ All animations respect `prefers-reduced-motion` (2.3.3 Animation from Interactions)
- ✓ Focus states visible for keyboard navigation (2.4.7 Focus Visible)
- ✓ Price display includes ARIA labels (1.3.1 Info and Relationships)
- ✓ Star ratings semantic and labeled (1.4.1 Color Not the Only Means)
- ✓ Color contrast meets standards across light/dark modes

See Phase1-Feature.md for detailed accessibility features.

## Related Components

- **ProductCard** (`src/components/ProductCard.tsx`) — Ripple effect implementation
- **StarRating** (`src/components/StarRating.tsx`) — Star fill animation
- **PriceDisplay** (`src/components/PriceDisplay.tsx`) — Price counter animation
- **ProductGrid** (`src/components/ProductGrid.tsx`) — Grid stagger entrance

## Performance Metrics

- **LCP Impact:** Negligible (animations non-blocking)
- **CLS Impact:** None (transform-only animations)
- **FCP Impact:** <1ms (CSS animations, GPU-accelerated)
- **Memory:** <10 KB per product grid (ripple elements cleaned up immediately)

## Future Work

See Phase1-Feature.md (Future Improvements section) and Phase1-Technical-Debt.md (Phase 2 Recommendations):

- Animation config constants / CSS custom properties
- Per-instance animation disable props
- Loading state animations (skeleton, shimmer)
- Gesture support (haptic feedback)
- Performance monitoring integration
- Enhanced accessibility (color-independent feedback)

## Version History

| Phase | Date | Feature | Status |
|---|---|---|---|
| 1 | November 2026 | Micro-interactions (ripple, star fill, price counter, grid stagger) | ✓ Implemented |
| 5 | November 2026 | Customer accounts: login, Google SSO, user/cart/order services | Implemented (no tests; see techdebt/) |
| 2 | TBD | TBD | Planned |
| 3 | TBD | TBD | Planned |

## Contact & Questions

For questions about Phase 1 documentation:
1. Check the specific document (Feature, Changes, DecisionLog, or TechnicalDebt)
2. Review cross-referenced ADRs or design documents
3. See "If You're Looking For..." section above for navigation help

---

**Last Updated:** November 2026  
**Phase:** 1 (Micro-interactions)  
**Status:** Complete
