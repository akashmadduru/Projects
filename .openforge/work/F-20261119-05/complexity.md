# Complexity F-20261119-05

Re-scored at step 4 after the design added order-service (and a cart test only).

| Factor | Score | Reason |
|---|---|---|
| Services or apps touched | 1 | inventory, product and order services (3). Cart gets a test only. 2-3 scores 1. |
| Data change | 0 | No migration. Existing rows and tables. |
| Public API / event contract | 1 | Additive nullable `sellerId`/`offerId` on `inventory.events`, additive `sellerId` on the internal reserve DTO. Offer/product create gains a 503 failure mode (was 201 with no stock); that is a behaviour fix, not a schema break. |
| Security or money path | 1 | Stock and oversell integrity, seller ownership check (indirect). No payments, auth or ledger change. |
| New dependency or infrastructure | 0 | Reuses the after-commit publisher and `SellerDirectory`. No outbox, service or datastore. |
| Estimated changed lines | 1 | 230-280 production lines (150-600). |

Total: 4. Class: M (unchanged).

Required for M: requirements, design.md and an ADR, tasks, implement, test, review. PR limit 600 production lines. No Owner design approval needed, so no standing-approval line applies.

Conservative note: the total reaches 6 (class L) only if the 503 change is judged breaking (+1) and the stock path is judged direct money (+1). Neither holds: the 503 replaces a silent defect and stock is not a ledger. Re-score if the implementation exceeds 600 lines or adds a migration or flag.

## Design review
ACCEPT with conditions. See "## CTO decision" in design.md.
