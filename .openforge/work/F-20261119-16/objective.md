# F-20261119-16 Seller ledger treats a cancelled shipment as terminal (F-14 AC-4 follow-up)

## Objective (verbatim, CEO-originated from the F-14 CTO condition 5 and the backlog)
seller-service must record CANCELLED from SHIPMENT_STATUS_CHANGED and ORDER_CANCELLED parts in the `ledger_shipments` register, as a terminal state, and `recordDelivered` must skip a CANCELLED shipment. A stale or replayed DELIVERED for a cancelled shipment books no SALE, COMMISSION or FEE (WARN logged). A CANCELLED shipment never moves back in the register, whatever the event order. No new table, no reversal logic, no change to FEE policy.

## Context
- Base: F-13 head 1662edc4 (`forge/F-20261119-13-seller-ledger`), which adds `ledger_shipments` and the reconcile. Stacked on F-13; merge after F-13.
- Independent of F-14 (order-service only): F-14 cancel events already carry no return fields (CancelEventShapeTest), so this touch only reads the shipment status from events that exist today.
- Owner mandate: standing approvals for class L via the CTO; no push, merge or governance edit without the Owner; security and money paths scope.
- Branch `forge/F-20261119-16-seller-cancel-terminal`, worktree `.openforge/worktrees/F-20261119-16`.
- Docker Desktop is currently down: implementation can be written, but verification waits.

## Blocking questions
None. Defaults: WARN (not ERROR) for a DELIVERED that arrives after CANCELLED; F-13 reconcile must not report STATE_MISMATCH for a shipment that is CANCELLED in both order-service and the register.
