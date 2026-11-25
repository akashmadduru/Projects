# F-20261119-13 Complexity (part A: seller-service ledger integrity, AC-1..AC-8 and AC-13)

The CTO's ruling (forge-cto, 2026-11-21), copied from its reply by the CEO. It was scored from requirements.md, ADR 0008 and the code at 820e5a8 (LedgerService.java, V1__seller_baseline.sql, OrderFeignClient.java).

| Factor | Score | Reason |
|---|---|---|
| Services or apps touched | 0 | seller-service only. The reconcile must use the existing OrderFeignClient. A new order-service endpoint would mean 2 services and would break the CEO split. |
| Data change | 1 | Additive V3. It widens the ledger_entries.type CHECK (V1:70) to add FEE_REVERSAL by drop and re-add, and adds a discrepancy-report table. No backfill. |
| Public API / event contract | 1 | Additive: a new ledger type and new admin reconcile and repair endpoints. The RETURN_REFUNDED consumer now requires returnShipmentId and returnItemsValue; the producer is unchanged and malformed events go to the DLT. |
| Security or money path | 2 | Direct: the seller ledger, payouts, platform money, and an admin-triggered repair. |
| New dependency or infrastructure | 0 | None. JobLock, Feign and JdbcClient already exist. |
| Estimated changed lines | 1 | 250-450 production lines. It could approach 600 if the reconcile needs paging plus an order-service lookup. |
| **Total** | **5** | |

**Class: M.** The limit is 600 production lines (target 450). Requires design.md and an ADR. Owner approval of the design is not required. The CTO re-scores to L if the design exceeds about 500 lines or needs an order-service change.

## Risks
1. **Reconcile data source (highest).**
   - Only `GET /internal/orders/shipments/{id}` exists, and ShipmentInfo has no amount field.
   - Option 1 iterates the shipment ids that seller-service has already seen. It cannot detect a delivered shipment that has no SALE.
   - Option 2 (an order-service list endpoint) breaks the split.
   - The Architect picks one and states its limitation. Gaps go to part B with the Owner told, and the scope is never widened silently.
2. **Fee policy is provisional (Q1 = B).** The exact booked FEE is reversed once, when cumulative returned items value reaches the shipment SALE. It is a single, isolated decision point, recorded in the ADR as provisional and flagged in pr.md.
3. **Cap arithmetic.** The cap comes from the ledger sum under a per-shipment lock, so concurrent events cannot read the same remainder. The cap is shared by the per-return and legacy keys (AC-5); a unique-key trick is not enough.
4. **Order independence (AC-13).** The final return clears the rounding remainder. A permutation test proves the result does not depend on event order.
5. **Fee amount.** Reverse the exact booked FEE row, never a value recomputed from the rule.
6. **Payouts.** Reversals are always new rows, and rows with payout_id set are never edited. Reconcile uses paged read-only queries with no FOR UPDATE during payout runs.
7. **Malformed events (AC-6).** Confirm an existing DLT or error handler exists. If one has to be added, that is extra scope.
8. **RETURN_REFUNDED before DELIVERED.** Skip it with a WARN, and reconcile recovers it.
9. **Repair (AC-8).** Admin-only. It reuses the idempotent recordDelivered, and each repair is audit-logged.
10. **Legacy events** without returnItemsValue are rejected per AC-6. Needs a test and a note.

## Design acceptance criteria (step 4)
1. Stays in seller-service. Any order-service endpoint or field is declared and sent back for re-scoring.
2. No new service, datastore, vendor, scheduler framework or abstraction. Reuses JobLock, JdbcClient, OrderFeignClient and the existing consumer. V3 is additive, with a rollback note.
3. Names the fee-policy decision point (Q1 = B, provisional), the FEE_REVERSAL type, and the V3 CHECK change.
4. Specifies the reversal basis (returnItemsValue), the cap query and its locking, and the legacy reverseRefund path sharing the same cap (AC-3, AC-4, AC-5).
5. Malformed events go to the DLT with an alert-level log, and the current error-handler state is described (AC-6).
6. Reconcile: paging, JobLock, no lock during payout, report-only, a dedupe key for report rows, admin repair through the idempotent postings, and an honest list of what it cannot detect (AC-7, AC-8).
7. A named test for every AC. AC-13 is a permutation test. AC-6 and AC-7 have replay and second-run tests.
8. Lists the contract changes, states that readers of GET ledger are unaffected, and that ADR 0008 leg logic is unchanged.
9. An ADR covering the fee policy (awaiting Owner confirmation), the cap rule and the reconcile approach.
10. An estimate of at most 600 production lines, target 450. Above that, split or re-score as L.

## Size ruling (CTO, 2026-11-21)
- **Overshoot:** LedgerReconciler was estimated at about 170 production lines and came in at 320. The total reached 651 against the M limit of 600; the CEO recount uses the gate measure, adds plus deletes.
- **Decision:** split into 2 stacked PRs, class M each:
  - **Part A**, 820e5a8..c316236 (T1-T5, posting correctness, AC-1..AC-6 and AC-13): 298 lines.
  - **Part B**, c316236..head (T6-T9, reconcile and repair, AC-7 and AC-8): 353 lines before the trim.
- **Rejected:** re-scoring to L, because that would only fit the class to the line count.
- **Conditions:**
  - Part A stands alone and must not reference reconciler code.
  - Before PR-B, trim the discrepancy list to a capped list of newest-first open rows, with no page and size parameters and no `Paged` type. FEIGN_ERRORS stays only if it is about 5 lines and adds no new type.
  - Part B stays under 600 lines.
  - Each part head gets its own independent review and forge-verify record.
  - The Owner approves each push and merge, and A merges before B.
  - Reconciler docs go with B, and T1-T5 docs with A. Docs are not counted.
- **Class, ACs and design acceptance:** unchanged.

## CEO scope ruling for the split (2026-11-21)
- **AC-13 re-scoped to part B.** The order-independence proof, LedgerPermutationTest (T6), lives in part B. Part A covers AC-1..AC-6 only.
- **A ships only with B.** Security found that A writes UNMATCHED_REVERSAL rows with no repair path. A is a reviewable checkpoint and is never pushed or merged on its own; A and B go to the Owner together.
- **A's minor findings land in part B** as new commits, without rewriting A: the reconcile config and env docs placement, the non-positive returnItemsValue and non-UUID ids alert, and the security minors.
