# F-20261119-13 Objective (portfolio O-09, run R-20261119-01)

Seller ledger integrity:
- reverse and reconcile seller ledger fees when orders are cancelled, refunded or returned (TD-P9-20);
- handle per-shipment cancel, refund, SLA and returns in the seller ledger (TD-P9-12, TD-P9-13).

Services: seller-service and order-service.

Depends on O-05. Return refunds (F-20261119-09) and return coins (F-20261119-10) are gate-passed but unmerged.

Scope (Owner): security and money paths only. Size limits count production lines. No new service, datastore or vendor without an ADR.

Base: to be decided at the worktree step. It is likely the head of F-20261119-11 once that gate passes, because both change order-service, or the F-10 head (820e5a8) if order-service changes are not needed. For research, read the code in .openforge/worktrees/F-20261119-10, which sits at 820e5a8 and is clean.

Register entries: docs/techdebt/phase9.md (td-p9-12, td-p9-13, td-p9-20). Verify each against the code, since some may be partly done.

## Blocking questions
None.

## CEO rulings (2026-11-21)
- **Split.** This objective is part A only: seller-service ledger correctness, AC-1..AC-8 and AC-13. AC-9..AC-12 (per-shipment cancel in order-service, TD-P9-12/13) move to a follow-up objective based on F-20261119-11's head, because both change order-service.
- **Base.** 820e5a8 (F-10 head). `returnItemsValue` is already in the RETURN_REFUNDED event.
- **Q1, fee on return:** PROVISIONAL DEFAULT, Owner confirmation pending. The fee is reversed only when cumulative returns cover the whole shipment, which is the CPO's recommended option. The Owner was AFK with a standing "approve" instruction. This is a product policy, so it is flagged for Owner confirmation in pr.md before merge. Changing it later is a small code change.
- **Q2, reconcile:** report-only, with admin repair through the idempotent path (default).
- **Q3, discounts:** platform-funded, never charged to the seller (default).
