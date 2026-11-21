# Complexity F-20261119-11

The CTO's ruling (forge-cto, 2026-11-21), copied from its reply by the CEO. The CTO scored it from requirements.md and constitution section 4 and did not inspect the code.

| Factor | Score | Reason |
|---|---|---|
| Services or apps touched | 1 | order-service is the main change. product-service is touched if the batch response needs checkout fields, and cart-service if the deferred clear needs a scoped or conditional clear endpoint (AC-18, AC-19). That is 2-3 services. |
| Data change | 1 | Additive migration only: a pending-clear table or marker, plus possibly a fingerprint fallback for AC-2. No destructive step and no backfill. If the AC-2 fix backfills existing null fingerprints, this rises to 2. |
| Public API / event contract | 1 | Additive: an extended batch response and possibly a new cart-clear contract. 409 and 503 already ship. |
| Security or money path | 2 | Direct: checkout charges stock, coupon, coins and wallet, and handles the duplicate-order guard, token propagation (AC-12) and cross-user clear safety (AC-19). |
| New dependency or infrastructure | 0 | None. resilience4j is already in the catalog. |
| Estimated changed lines | 1 | About 300-550 production lines. |
| **Total** | **6** | |

**Class: L (6-8).** This is borderline: if no cart-service or product-service change is needed, the total is 5, which is M. The CTO scored conservatively.

**Owner approval: standing (R-20261119-01).** A CTO-accepted L design counts as Owner-approved.

**Limit:** 600 production lines per PR. Ship as stacked PRs (`pr_split`, G-8):
1. PR-A: regression cover for AC-1 to AC-9b, plus the AC-2 null-fingerprint fix.
2. PR-B (TD-BE-06): batched lookup, AC-10 to AC-14, including any product-service batch extension.
3. PR-C (TD-BE-03): durable, scoped cart clear with its recovery job, AC-15 to AC-20. If it exceeds 600 lines, split it into the persistence and job part and the cart-service endpoint part.

Keep this as one objective. The pieces share the draft-then-place-then-clear flow, and AC-17 and AC-18 interact with the AC-1 guard.

## Risks
1. AC-2 changes the guard on the money path. A wrong fallback would either block legitimate repeat orders (AC-5) or leave the race open. A backfill raises the Data score to 2.
2. The deferred clear could wipe a newer cart or another user's cart, so it needs a conditional or scoped clear.
3. Multi-instance recovery job: claim rows with SKIP LOCKED or an equivalent, and keep the clear idempotent.
4. The batch endpoint may lack variants, offers, buy box, active flag and sale price. Any extension must stay additive.
5. A partial batch result (AC-14) must leave no live order row.
6. The breaker interaction with compensation (AC-9b) must be tested.

## Design acceptance criteria (step 4 is rejected on any of these)
- A new service, datastore, vendor or unneeded abstraction.
- A missing test for any AC.
- A silent contract break.
- A recovery job that is not safe across instances.
- A clear that is not scoped to the user and the consumed items.
