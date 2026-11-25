# F-20261119-15 Objective (backlog B-10, run R-20261119-01)

COD orders with no payment row cannot be refunded (backlog B-10, found in the F-09 design under CTO condition 1). OrderService places some COD orders without creating a payment-service payment row; see OrderService around the COD path. Since F-09, return refunds for COD go through payment-service as recorded refunds, and the direct wallet fallback is gone. Such a refund therefore fails loudly and the return stays PICKED_UP.

The fix:
1. Every new COD order gets a payment row at placement. It must be idempotent and must not block or double-charge.
2. Existing COD orders without a row get one, through a safe, idempotent backfill path such as a lazy create-on-refund or an admin/job backfill. The architect picks one.
3. Refunds for those orders then succeed.

Scope (Owner): money paths. No new service, datastore or vendor. Migrations are additive.

Base: the final head of F-20261119-11, which is stacked on F-09/F-10. Research uses .openforge/worktrees/F-20261119-11R (71bba97) for order-service and payment-service code.

## Blocking questions
None.
