# F-20261119-16 Design

Class S (complexity.md): no architect design or ADR is required. The approach is the CTO's, recorded in complexity.md:
- `recordDelivered` reads the `ledger_shipments` status (`SELECT status ... FOR SHARE`); if it is CANCELLED, log a WARN with ids only and return without booking.
- `track` already ranks CANCELLED highest; guard same-rank replays so they do not rewrite amount or updated_at (AC-5).
- Legacy events with no register row: `track` covers them, or a test proves they stay safe.
- Reconcile AC-8 and AC-9 already hold: add tests only.
- Admin repair of MISSING_SALE on a cancelled shipment: 409, the discrepancy stays open, the message says the shipment is cancelled (CTO ruling Q2).

## CTO decision
Class S, total 2, limit 300 production lines. Accepted as above (forge-cto, 2026-11-23; copied by the CEO).
