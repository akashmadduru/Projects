# Future work / known gaps
* `PENDING_PAYMENT` orders and `REQUIRES_PAYMENT / PROCESSING / FAILED` payments (an order row needs a payment attempt that never completed).
* Exchange quotes linked to orders (`APPLIED` / `PICKED_UP`, `orders.exchange_quote_id`, shortfall) and `orders.subscription_id` ← subscription runs.
* Coin `EXPIRE` events and partial `REVERSE_ORDER`; wallet `CASHBACK`/`ADJUSTMENT`/`REFERRAL` credits.
* Review authors for Q&A answers are random users (`verified_buyer = false`); link answers to purchasers.
* Payouts are one per (seller, customer-chunk) rather than per calendar period.
* Optional `--defer-indexes` mode for scratch servers (drop/recreate secondary indexes around the load) — deliberately not done by default.
* Kafka/Redis state and binary `export_jobs.content` are out of scope.
* A static (no-DB) migration parser as a fallback for `inspect` when no migrated database is reachable.
