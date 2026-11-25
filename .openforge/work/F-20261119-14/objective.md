# F-20261119-14 Objective (portfolio O-09 part 2, run R-20261119-01)

Per-shipment cancel, reject and refund in order-service (TD-P9-12, TD-P9-13). This is the order-service half of O-09, split out of F-20261119-13 by CEO ruling. Its acceptance criteria are already written: **AC-9..AC-12 in [F-20261119-13/requirements.md](../F-20261119-13/requirements.md)**. Use them as this objective's AC-1..AC-4 and keep the original numbers as aliases.
- AC-9: a per-shipment cancel or reject refunds that shipment's value under a per-shipment key. On failure it returns 503 and the status is unchanged.
- AC-10: a cancelled shipment never gets SALE, COMMISSION or FEE booked, including on a stale DELIVERED replay. This needs the seller-ledger side from F-13, which skips cancelled shipments, so state any seller-service touch explicitly.
- AC-11: cancelling a shipped or delivered shipment returns 409. A seller can cancel only its own shipment; anything else returns 404.
- AC-12: shipping is refunded exactly once across the returns of a multi-shipment order, and never reversed from the seller.

Scope (Owner): security and money paths only. No new service, datastore or vendor without an ADR.

Base: the final head of F-20261119-11, which is in its last fix round. Every order-service change is stacked on that chain. Research uses the read-only snapshot at .openforge/worktrees/F-20261119-11R (71bba97). The real worktree is created when F-11 is final.

## Blocking questions
None. Defaults: refunds use the F-09 leg machinery (ADR 0008), and a seller cancel is allowed only before dispatch.

## CEO rulings (2026-11-23)
- **AC-4 (seller-service: a cancelled shipment is terminal in the register) is deferred.** It needs `ledger_shipments` from F-20261119-13, which sits on a different unmerged chain (seller-service, based on F-10), while this objective stacks on F-11 (order-service). Building on both would mean merging two unmerged chains locally, so it is not done. AC-4 becomes a small follow-up once F-13 merges (backlog). Risk meanwhile: seller-service books on DELIVERED only, and a cancelled shipment never reaches DELIVERED in the normal flow. Only a forged or stale DELIVERED replay could book it, and F-13's reconcile would then report a STATE_MISMATCH.
- **Scope:** AC-1..AC-3 and AC-5..AC-11, order-service only, based on the final F-11 head.
- The CPO's defaults are accepted: seller or admin cancels only before dispatch, a PENDING refund commits the cancel, and shipping rides on the completing event.
