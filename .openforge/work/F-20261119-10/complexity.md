# F-20261119-10 Complexity (CTO, step 3; AC-14..AC-18, coins, stacked on F-09)

| Factor | Score | Reason |
|---|---|---|
| Services or apps touched | 1 | promotion-service and order-service (2). |
| Data change | 1 | Additive migration: coin ledger row linked to the return, idempotency per return. No backfill. |
| Public API / event contract | 1 | Additive: pro-rata earned-coin reversal endpoint if none exists. |
| Security or money path | 2 | Direct: coin ledger and balances (value-bearing ledger). |
| New dependency or infrastructure | 0 | None. |
| Estimated changed lines | 1 | 150-600 production lines. If the design exceeds 600, split further rather than stack. |
| **Total** | **6** | |

**Class: L.** Required: design.md and ADR, Owner approval of the design before implementation, PRs under 600 production lines. Depends on F-09 completion hook. AC-18 follows the CEO ruling: reversal capped at current balance, never negative, recorded.

Owner approval: standing (R-20261119-01 charter, class-L designs accepted by the CTO)
