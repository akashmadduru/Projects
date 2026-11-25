# Complexity F-20261119-14 (per-shipment cancel, order-service)

The CTO's ruling (forge-cto, 2026-11-23), copied from its reply by the CEO.

| Factor | Score | Reason |
|---|---|---|
| Services or apps | 0 | order-service only; AC-4 (seller-service) is deferred |
| Data change | 1 | Additive migration for per-shipment refund tracking |
| API / event contract | 1 | New cancel endpoint; CANCELLED on SHIPMENT_STATUS_CHANGED (additive) |
| Security or money | 2 | Direct: refunds, keys, ledger events, authorisation |
| New dependency | 0 | None; the ADR 0008 legs are reused |
| Changed lines | 2 | About 500-800 production lines (conservative, not verified against the code) |
| **Total** | **6** | |

**Class: L.** Owner approval: standing (R-20261119-01). Limit: 600 production lines per PR.

**Split:**
- **Part A:** per-shipment cancel and refund (AC-1, 2, 3, 5, 6, 10, 11). It must include the guard against a whole-order cancel double-refunding.
- **Part B:** AC-9 and AC-7 (remainder refund, order-level follow-ups run once, shipping refunded exactly once).

## Risks
1. **Double refund on a whole-order cancel after partial cancels.** Compute from live shipments net of earlier refunds and shipping, and use a non-colliding key.
2. Coupon, coins and wallet are released only once.
3. Shipping is refunded exactly once, under a lock, across cancels and returns.
4. A cancel racing a ship needs a version or a lock.
5. The refund must be accepted before the status commits. A refund that goes PENDING and then FAILED is a known gap.
6. Rounding is to the cent, with the remainder on the last shipment.
7. Authorisation: 404 for a non-owner, 403 for an unapproved seller.
8. A stale DELIVERED replay stays possible while AC-4 is deferred. Accepted by CEO ruling; state it in the PR and the ADR.
9. This is stacked on the F-11 head, so rebases are likely.

## Design acceptance criteria
- order-service only, with no new datastore or vendor.
- An ADR covering the key format, the remainder rule, the shipping rule, the PENDING gap and the AC-4 risk.
- A worked double-refund example.
- A named test for each AC: replay, 503, race, COD and 0.00, shipping in both orders, whole-order cancel after a partial cancel, the 401/403/404/409 matrix, and rounding across 3 or more shipments.
- Explicit transaction and lock ordering.
- An additive migration with its rollback stated, and the SHIPMENT_STATUS_CHANGED consumers checked for CANCELLED.
- A line estimate per part, each within 600.
- No scope creep, and no PII in logs.

## Design review (2026-11-23)
The CTO accepted the design with 6 conditions; see design.md "## CTO decision". Owner approval: standing (R-20261119-01).
