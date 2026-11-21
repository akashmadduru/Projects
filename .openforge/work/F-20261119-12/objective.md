# F-20261119-12 Objective (portfolio O-08, run R-20261119-01)

Use a transactional outbox for the remaining Kafka publishers in payment-service, promotion-service, seller-service and user-service (TD-P6-33). Today these services publish best effort, outside the database transaction. Reuse order-service's existing outbox pattern. Use additive migrations only.

Scope (Owner): security and money paths only. Prioritise money-relevant events (payment, promotion/coins, seller ledger). If all four services do not fit within class M/L production-line limits, split this into stacked parts or later objectives. A user-service part may be deferred if its events are not money or security relevant. No new service, datastore or vendor. A shared lib is allowed only if the architect justifies it and it is minimal.

Base: the head of F-20261119-10 (820e5a8), stacked on the unmerged chain. User-service has unmerged changes on F-06, F-07 and F-08, which are based on F-03. Note any conflict risk.

Register entry: docs/techdebt/phase6.md#td-p6-33.

## Blocking questions
None.
