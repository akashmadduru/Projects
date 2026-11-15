# Decision log

| # | decision | why | alternatives rejected |
|---|---|---|---|
| 1 | Discover schemas by introspecting a migrated database, not by parsing SQL | migrations ALTER/DROP constraints, add generated columns and partial/expression indexes; the catalog is the exact end state | custom SQL parser (brittle), JPA-only (misses constraints) |
| 2 | Customer-centric generation (all services for a customer range in one worker) | cross-service data (order ↔ payment ↔ ledger ↔ stock ↔ review) stays coherent; per-user rules (wallet, coins, coupon limits, one review per product) are local | table-by-table generation with global id lists (memory-heavy, hard to keep consistent) |
| 3 | IDs and entity attributes are pure functions of `(seed, kind, index)` | no lookup tables ⇒ flat memory; any worker recomputes any parent; resumable by construction | random UUIDs + in-memory maps |
| 4 | Denormalised aggregates (product ratings, stock reserved, seller rating, deals.claimed) come from exact per-chunk tallies | they must equal child rows; tallies are small arrays | approximating them, or querying other databases while loading |
| 5 | One transaction per (stage, chunk, database); SQLite state; probe-on-resume | atomic chunks, resume without duplicates even after a crash between COMMIT and the state write | per-table commits (partial chunks), DB-side progress tables (would add objects to app DBs) |
| 6 | Keep indexes and constraints live during load | the tool must not alter schemas; FK/unique/CHECK enforcement doubles as a correctness check | dropping/recreating indexes (alters app DBs, risky) |
| 7 | Planner measures the real generators on a 1,500-customer world | accuracy follows schema changes automatically | fixed bytes/row constants |
| 8 | LLM text is pooled, cached and capped | millions of fields; per-row calls are unaffordable; no DB data in prompts | per-row generation |
| 9 | Guards: allow-listed hosts + SQL allow-list + confirmation | "never production, never delete" must hold even with a mis-set env var | trusting config |
| 10 | Server-side TCP keepalives + lock_timeout | a killed generator left COPY sessions holding row locks and blocked the resume (observed) | manual `pg_terminate_backend` |
| 11 | Seed-salted natural keys (email, sku, slug, codes, phone band) | a second dataset (new `--seed`) can be appended to the same databases | single dataset per database |
| 12 | Worker count capped by free RAM | the dev laptop also runs the whole microservice stack; unconstrained workers swapped | CPU-count only |
