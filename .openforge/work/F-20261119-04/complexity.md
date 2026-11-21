# F-20261119-04 Complexity

| Factor | Score | Reason |
|---|---|---|
| Services or apps touched | 0 | payment-service only; order-service changes are a non-goal. |
| Data change | 2 | New refunds status column with a backfill of existing rows to SUCCEEDED, plus likely a uniqueness or attempt record for single Razorpay order. |
| Public API / event contract | 1 | Additive: new refund webhook event handling and refund status; existing contracts unchanged (AC-12). |
| Security or money path | 2 | Direct: refunds, refundable balance, signed webhooks. |
| New dependency or infrastructure | 0 | None. |
| Estimated changed lines | 1 | Roughly 300-600 production lines (refund lifecycle, webhook handlers, order idempotency, capture alert, migration). |

Total: 6
Class: L

Required: design.md and an ADR; Owner approves the design before implementation (standing approval only if the run charter grants it for CTO-accepted L designs); split into PRs under 600 changed production lines.

## Design review
ACCEPT with conditions. See "CTO decision" in design.md.
Owner approval: standing (R-20261119-01 charter, class-L designs accepted by the CTO), applied 2026-11-19 by forge-ceo.
