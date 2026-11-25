# F-20261119-13 handoff notes for pr.md (part A)

## Reconcile blind spots (Owner-visible, condition 5)
The nightly reconcile compares the ledger with the `ledger_shipments` register, which is fed only by order.events. It cannot detect:
1. Shipments none of whose events reached seller-service (outbox loss, all dead-lettered).
2. An amount that order-service changed without publishing an event.
3. Returns that order-service refunded but whose `RETURN_REFUNDED` was dead-lettered (seller-service never learns of them).
4. Shipments delivered before this deploy that have no SALE (the V3 seed is built from existing SALE rows only).
Closing these needs an order-service list/returns endpoint: part B or the backlog.

## Open Q1: fee policy is PROVISIONAL
Policy B (give back the booked per-shipment fee once, when the shipment becomes fully reversed) lives in the single method `LedgerService.feeReversible(completing)`. The Owner must confirm it; policy A (never) or C (pro rata) change only that method. No FEE_REVERSAL rows are ever deleted: rollback is "revert the image", corrections are new rows.

## Deploy order and DLT expectations (condition 4)
- order-service with F-09/F-10 (publishes `returnItemsValue`) deploys BEFORE seller-service. Otherwise every RETURN_REFUNDED is dead-lettered.
- Expect ERROR logs "LEDGER ALERT malformed RETURN_REFUNDED order=..." and a message on `order.events.DLT` for each malformed event. Nothing is posted for it. A consumer-group reset to earliest sends old events lacking `returnItemsValue` there too (no money effect, they were applied before).
- Replay: after the cause is fixed, republish the DLT record to `order.events`. The consumer is idempotent per return.
- Admin repair (ADMIN/FINANCE) handles MISSING_SALE and UNMATCHED_REVERSAL; other kinds are manual. Refused repairs (409/503) log at WARN with the actor; `resolved_by` is the JWT subject.
- V3 migration is additive (DROP/re-ADD of the type CHECK, two new tables). Runbook text is in docs/reference/events.md.

## Debt line (condition d)
td-p9-22: seller-service `LedgerService.reverseRefund` has no event caller (RETURN_REFUNDED fallback removed); part B (per-shipment cancel) must use it for the whole-shipment refund path or delete it. Recorded in docs/techdebt/phase9.md and README.md. td-p9-20 was removed and the remediation plan has row 8a.

## Other notes
- Test-only build change: `testImplementation libs.spring.boot.webmvc.test` in seller-service-app/build.gradle (Boot 4 moved @AutoConfigureMockMvc), needed for the admin endpoint tests.
- Independent review (condition 9) must be run by another agent on the final SHA. The commit list per task is in `git log 820e5a8..HEAD`.

## STATUS at stop (condition 8)
Stopped at head f63f848: production lines vs 820e5a8 are 578 added / 70 deleted (net 508; add+delete 648) against the ~416 estimate and ~500 limit. Not done: guard-removal runs, forge-verify (verify.md not written). Full seller-service suite passed at 37c05ba plus the ApplicationException fix (gradle_rc=0).

## Size note and part B (final)
- The reconciler was estimated at 170 production lines and came in at 320. The total reached 651 against the M limit of 600, so the CTO split the work: part A 820e5a8..c316236 (298 lines) and part B c316236..HEAD.
- Part B trims: discrepancy list is a plain List, open rows newest first, LIMIT 200 (no page/size, no Paged); OVER_REVERSED check dropped (beyond AC-7/AC-8 wording; ADR 0012 lines edited accordingly; V3 CHECK still allows the kind); FEIGN_ERRORS kept (a map counter, no new type).
- Part B fixes: reverse() rejects (IllegalArgumentException, so DLT) a SALE that belongs to another order; LEDGER ALERT logs presence flags only, and non-positive items value / non-UUID ids are malformed; MISSING_SALE repair needs an admin-entered `?amount=` (409 without it; order-service does not confirm amounts), logged with the actor and kept in detail; V4 index on ledger_entries(shipment_id); "cannot deadlock" corrected to single-shipment (ADR 0012, td-p9-22 deadlock note); permutation test has a return-before-DELIVERED case (reported, then converges to 48.35 after repair); negative-balance test renamed; env-descriptions.json has the four RECONCILE variables (md regenerated).
- Part B production lines (c316236..HEAD, adds plus deletes, excluding src/test, docs, .md): 392. Whole branch 820e5a8..HEAD: 666 (A 298 + B 392 less overlap), the split being the CTO ruling.
- Repair runbook change: ops must pass the verified amount when repairing MISSING_SALE.
