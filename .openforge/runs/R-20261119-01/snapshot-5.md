# Snapshot 5 (2026-11-23)

## Gate-passed, PR text written, NOT pushed (15)
F-01, F-03, F-04 (2 parts), F-05, F-06, F-07, F-08, F-09, F-10, F-11 (3 parts), F-12, F-13 (2 parts), F-15 (2 parts), F-14 (2 parts), F-16.
- Push pre-approved by the Owner, but the guard blocks agents, so the Owner runs them: F-01, F-03, F-04, F-05, F-06, F-07, F-08.
- Each needs its own Owner yes: F-09, F-10, F-11, F-12, F-13, F-14, F-15, F-16.

## Stacked chains (merge order)
- order-service: F-11 -> F-15 -> F-14 (A and B together).
- seller-service: F-10 -> F-13 -> F-16.

## This period
- F-14: 3 review rounds on A and 2 on B. 4 real money majors were found and fixed: the note overflowing after a refund, shipping refunded early in two orderings, and shipping lost on a return during an in-flight cancel. Gate PASS at 57e20e6.
- F-16 (new, class S): approved by both reviewers in round 1, gate PASS at 3e34923.
- Docker Desktop hung for several hours. Reviews, the PR draft and F-16's planning and code went on meanwhile. The Owner allowed a restart, done with the `docker desktop` CLI.

## Not started (needs Owner input or merges first)
- SMS caps (needs F-06 and F-07 merged); O-02 internal token (touches every service).
- The backlog money follow-ups from F-14 and F-16: the COD due amount after a partial cancel, a settle watch for PENDING cancel refunds, an admin refund-to-wallet action, and the cross-order check on the ledger register.
- CEO judgement: further stacking adds to the Owner's merge burden more than to the value delivered. The bottleneck is now Owner pushes and merges.
