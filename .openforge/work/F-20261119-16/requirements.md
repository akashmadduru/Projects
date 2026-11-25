# F-20261119-16 Requirements: seller ledger treats a cancelled shipment as terminal

## 1. Problem and users affected
order-service can now cancel a single shipment (F-14). seller-service books SALE, COMMISSION and FEE from a DELIVERED shipment part. Today a stale or replayed DELIVERED can still book money for a shipment that was cancelled, if the event arrives or is retried in the wrong order. Affected: sellers (wrong payout), finance admins (wrong ledger and false reconcile noise), the Owner (money-path risk). This is the deferred F-14 #4 guard, stacked on F-13 (needs `ledger_shipments` and the reconcile).

## 2. Scope and non-goals
In scope: seller-service only. Record CANCELLED per shipment in the register from SHIPMENT_STATUS_CHANGED and ORDER_CANCELLED parts; keep it terminal; stop delivery booking for a cancelled shipment; keep the F-13 reconcile quiet for cancelled shipments.
Non-goals: no reversal or clawback of any entry (including a SALE booked before the cancel); no change to FEE, COMMISSION or return-reversal policy; no new table, column, service, datastore or vendor; no change to order-service or its event shape; no new endpoint; no repair path for cancelled shipments.

## 3. Acceptance criteria
- AC-1 Given an event of type SHIPMENT_STATUS_CHANGED whose part for shipment S has status CANCELLED, when seller-service consumes it, then the register row for S has status CANCELLED (created if S was unknown) and no ledger entry is written.
- AC-2 Given an event of type ORDER_CANCELLED whose parts carry status CANCELLED for shipments S1 and S2, when consumed, then both register rows are CANCELLED. A part of the same event that is not CANCELLED (for example an already DELIVERED shipment) is not forced to CANCELLED by this event.
- AC-3 Given S is CANCELLED in the register, when a DELIVERED part for S arrives (live, replayed or out of order), then no SALE, COMMISSION, FEE or sale_lines row exists for S afterwards, the register stays CANCELLED, and one WARN is logged naming the order and shipment ids only. The consumer does not throw and does not dead-letter the event.
- AC-4 Given S was DELIVERED and its SALE, COMMISSION and FEE are booked, when CANCELLED for S arrives later, then behaviour is exactly as before this change: the existing entries are unchanged, nothing is reversed or added, and the event is not dead-lettered. (The register ends CANCELLED, as the existing status ranking already does.)
- AC-5 Given S is CANCELLED, when any later event for S carries an earlier status (PLACED, CONFIRMED, PACKED, SHIPPED, OUT_FOR_DELIVERY) or the same event again, then the register row stays CANCELLED and no other register field is changed by it, including amount and seller.
- AC-6 Given all permutations of the events {SHIPPED, DELIVERED, CANCELLED} for one shipment, then the end state is CANCELLED, and entries exist only if DELIVERED was processed before CANCELLED (and then exactly one SALE, one COMMISSION and at most one FEE).
- AC-7 Given the same CANCELLED or ORDER_CANCELLED event consumed twice, when the second delivery is processed, then no error occurs, no duplicate row appears, and the register and ledger are identical to after the first delivery.
- AC-8 Given a shipment CANCELLED in the register and in order-service with no SALE, when the F-13 reconcile runs (including past the grace window), then it reports no discrepancy of any kind (no MISSING_SALE, no STATE_MISMATCH) for it, with or without a reachable order-service.
- AC-9 Given a shipment that is CANCELLED in the register but has a SALE booked (the AC-4 case), when the reconcile runs, then it keeps its current F-13 behaviour (STATE_MISMATCH while the SALE stands and order-service says CANCELLED), so the money anomaly stays visible.
- AC-10 Given an event that mixes shipments (one CANCELLED, one DELIVERED, one in transit), when consumed, then only the DELIVERED one books entries, the CANCELLED one books none, and an error on one part does not undo the CANCELLED register write (the register still commits first and on its own).
- AC-11 Given a reconcile admin repair of a MISSING_SALE discrepancy for a shipment that is CANCELLED in the register, when the admin repairs it, then no SALE is booked and the response is 409 with the discrepancy left open (or resolved without a booking, see open question 2).
- AC-12 Given the whole change, then no FEE amount, commission rule or reversal behaviour differs from the F-13 head for any shipment that is not CANCELLED, and the existing seller-service tests still pass.

## 4. Edge cases and abuse cases
- Legacy event with no `shipments` array (shipmentId = orderId, platform seller): a status CANCELLED event for it must not book, and a later DELIVERED must book nothing.
- Part with null shipmentId or null status is skipped, as today; unknown status text must not unseat CANCELLED.
- CANCELLED arrives for a shipment never seen before, then DELIVERED: books nothing (AC-3).
- Redelivery after consumer-group reset (earliest offset): the full history replays; the end state must equal the live run.
- Forged or stale DELIVERED with an inflated amount after CANCELLED: books nothing, amount in the register is not overwritten.
- Concurrent consumption of CANCELLED and DELIVERED for one shipment: never both end CANCELLED-and-booked-after-cancel; the loser either books before the cancel (AC-4) or books nothing (AC-3).
- Log hygiene: the WARN carries ids only, no amounts or free text from the event.

## 5. Open questions (none block work; defaults apply)
1. DELIVERED after CANCELLED logs WARN, not ERROR, and is not dead-lettered. Default: yes (Owner brief).
2. Repair of a MISSING_SALE for a CANCELLED shipment (AC-11): default is refuse with 409 and keep the discrepancy open for manual review. Owner may prefer auto-resolve.
3. A SALE already booked before a CANCELLED stays (AC-4, AC-9); reversal is a separate objective if wanted. Default: out of scope.
