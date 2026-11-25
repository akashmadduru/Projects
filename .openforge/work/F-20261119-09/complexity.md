# F-20261119-09 Complexity (CTO, step 3, re-scored after split; AC-1..AC-13)

| Factor | Score | Reason |
|---|---|---|
| Services or apps touched | 1 | order-service and payment-service (2). |
| Data change | 1 | Additive migration for per-leg progress. No backfill; pre-migration rows must retry safely in code. |
| Public API / event contract | 1 | Additive: COD refund recorded with destination WALLET, PENDING handling. F-04 state machine only consumed. |
| Security or money path | 2 | Direct: payments, wallet ledger, refunded_amount. |
| New dependency or infrastructure | 0 | None. |
| Estimated changed lines | 1 | 150-600 production lines. If the design exceeds 600, split further rather than stack. |
| **Total** | **6** | |

**Class: L.** Required: design.md and ADR, Owner approval of the design before implementation, PRs under 600 production lines.

Owner approval: standing (R-20261119-01 charter, class-L designs accepted by the CTO)
