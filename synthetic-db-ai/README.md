# synthetic-db-ai

Schema-driven synthetic data generator for the e-commerce microservices in this repository.
It discovers the **real** schemas (12 services, 12 PostgreSQL databases, 88 tables, 41 foreign keys), then
streams realistic, internally consistent data — sized from 100 MB to 10 GB or more — into those databases with
PostgreSQL `COPY`.

It is a standalone project: it only *reads* the repository (migration files, entities, config) and never
modifies application code or migrations. It only ever **appends** data, and refuses to touch anything that
looks like production.

```
python main.py inspect                    # discover services / tables / constraints  -> inventory/
python main.py plan --size 10GB           # row budgets + estimated on-disk size (measured, not guessed)
python main.py generate --size 10GB       # generate + bulk-load (parallel, resumable)
python main.py validate                   # FK, cross-service and business-rule checks
python main.py stats                      # actual rows / sizes vs the plan
```

## Contents
1. [Requirements](#requirements) · 2. [Quick start](#quick-start) · 3. [Configuration](#configuration)
4. [Commands](#commands) · 5. [What gets generated](#what-gets-generated) · 6. [How it works](#how-it-works)
7. [Safety](#safety) · 8. [Optional LLM text](#optional-llm-text) · 9. [Tests](#tests) · 10. [Troubleshooting](#troubleshooting) · 11. [Limitations](#limitations)

## Requirements
* Python 3.11+ and `pip install -r requirements.txt` (psycopg 3, NumPy, Faker, PyYAML, pytest; `anthropic` / `openai` are only imported if you use `--llm`)
* A PostgreSQL 14+ server that already has the service databases **with the Flyway migrations applied** (i.e. the
  services have started at least once, or `python main.py scratch` was used).
* No Python installed? Use Docker: `docker build -t synthetic-db-ai .` then
  `docker run --rm --network <net> -v <repo>:/repo -w /repo/synthetic-db-ai -e SDAI_REPO_ROOT=/repo -e SDAI_PG_HOST=<host> -e SDAI_PG_PASSWORD=<pw> -e SDAI_STATE_DIR=/repo/synthetic-db-ai/state synthetic-db-ai generate --size 1GB --yes`

## Quick start
The recommended first run is against a **throw-away** Postgres, not your dev databases:

```bash
docker run -d --name sdai-pg -e POSTGRES_PASSWORD=sdai -p 127.0.0.1:55432:5432 postgres:17 -c fsync=off -c synchronous_commit=off -c max_connections=200
export SDAI_PG_HOST=127.0.0.1 SDAI_PG_PORT=55432 SDAI_PG_PASSWORD=sdai
pip install -r requirements.txt

python main.py scratch                 # creates the 12 databases and applies the repo's Flyway migrations (read-only use of the files)
python main.py inspect                 # writes inventory/schema_inventory.json + SCHEMA_INVENTORY.md
python main.py plan --size 1GB
python main.py generate --size 1GB     # asks you to type the host name to confirm; add --yes in scripts/CI
python main.py validate
python main.py stats
```

To load the local docker-compose stack instead, point at it (`SDAI_PG_HOST=127.0.0.1 SDAI_PG_PORT=5432`,
superuser credentials from `deploy/local/secrets`). Data is *added next to* whatever is there.

## Configuration
`config/synthetic.yaml`, overridable by environment variables:

| setting | env | default | meaning |
|---|---|---|---|
| target.host / port / user / password | `SDAI_PG_HOST` `SDAI_PG_PORT` `SDAI_PG_USER` `SDAI_PG_PASSWORD` | 127.0.0.1 / 5432 / postgres / – | one server hosting the 12 service DBs |
| target.allowed_hosts | `SDAI_ALLOWED_HOSTS` (comma list, appended) | localhost, 127.0.0.1, ::1, host.docker.internal, postgres, sdai-pg | **only** these hosts may be written to |
| generation.seed | `--seed` | 20260101 | same seed ⇒ byte-identical data; use a different seed to add an independent second dataset |
| generation.workers | `SDAI_WORKERS`, `--workers` | CPU-1, capped by free RAM | parallel chunk workers |
| generation.orders_per_user | – | 5.5 | mean orders per customer (zero-inflated, heavy tail) |
| generation.as_of | – | today | the synthetic "now"; stored in the plan so resumes agree |
| llm.provider | `SDAI_LLM`, `--llm` | none | `none` \| `anthropic` \| `openai` |
| – | `SDAI_REPO_ROOT`, `SDAI_STATE_DIR` | parent dir / `state/` | where the repo and run state live |

## Commands
**`inspect`** — scans `services/*/*-app` for Flyway migrations, JPA `@Entity` classes and datasource config, then reads
the live catalog (`pg_catalog`, read-only transaction) of every service database. Writes
`inventory/schema_inventory.json` (machine-readable: columns, types, defaults, generated columns, PK/unique/FK/CHECK,
indexes, Flyway history) and `inventory/SCHEMA_INVENTORY.md` (human-readable), plus **111 cross-service logical
references** — the microservices have no cross-database FKs, so integrity across services is an application contract
that this tool both honours and later verifies.

**`plan --size N`** — `N` is `100MB`, `1GB`, `5GB`, `10GB` or any custom value (`750MB`, `2.5GB`; `--users K` instead
fixes the customer count). The planner generates a 1,500-customer world in memory through the *same generators* and
the *real catalog*, measures every column's byte length, converts that to heap + index bytes per table, and solves for
the customer count. It prints rows and estimated size per table. Typical result: ≈ 48 KB of database per customer, so
10 GB ≈ 215k customers, 65k products, 2.1k sellers.

**`generate --size N`** — runs the stages below. Re-running the same command **resumes**. Flags: `--yes`, `--seed`,
`--workers`, `--llm`, `--users`, `--fresh` (new run; previous plan/state files are renamed, never deleted),
`--dry-run` (generate + encode in memory, no DB), `--no-analyze`.

**`validate`** — read-only; ~210 checks: every physical FK (anti-join), every cross-service reference (sampled by
id lookup in the parent DB), and ~50 business rules written as SQL (see below). Exit code 1 if anything fails.

**`stats`** — rows and `pg_total_relation_size` per table and database next to the planned rows.

## What gets generated
Generation is **customer-centric**: a chunk of customers is simulated as a whole, so an order, its shipments, payment,
ledger rows, stock reservations, review and notifications are produced together and agree with each other, even though
they land in different databases. Nothing is invented: the emitter checks every column against the catalog (unknown
column, missing NOT NULL column, NULL in NOT NULL, varchar overflow, generated column ⇒ hard error).

| service DB | tables generated | notable business rules honoured |
|---|---|---|
| auth_db | users, user_credentials, federated_identities, user_roles, refresh_tokens (rotation chains), one_time_tokens, user_totp, recovery_codes, mfa_challenges, known_devices | roles 1–6 from the seed; sellers get `ROLE_SELLER`; passwords are unusable Argon2id-shaped placeholders; token family expiry = root + 30 d |
| user_db | user_profiles, addresses (exactly one default), recently_viewed, memberships, membership_payments, user_preferences, referral_codes/claims, export_jobs, deletion_requests, delete_codes | membership price table (149/999 INR …); referral code = referrer's code; reward when referee's first order is delivered |
| product_db | categories (1 level), products (all 40 columns), product_images, product_variants, offers, product_views | `average_rating`, `rating_count`, `review_count`, `rating_distribution`, `seller_rating` equal the generated reviews/feedback exactly; discount % matches list/sale price; slug/sku unique |
| inventory_db | warehouses, stock_levels, variant_stock, warehouse_stock, reservations, reservation_releases | `reserved ≤ quantity`; status from availability vs `low_stock_threshold`; warehouse stock sums to stock level; cancelled/returned orders release stock |
| cart_db | carts, cart_items, wishlist_items, saved_items, alerts, lists, list_items | one default list; product unique per cart; alert targets below last price |
| order_db | orders, order_items, order_shipments, order_status_history, order_returns, order_return_items, subscriptions | `total = subtotal − discount − coins + shipping`; INR GST 18 % inclusive split CGST+SGST (Karnataka) or IGST; free shipping ≥ 499 INR / 25 other (fee 40 / 4.99), always free for members; status derived from per-seller shipment timeline; COD only in India under the COD limit; returns only on delivered orders |
| payment_db | payments, refunds, wallets, wallet_txns, gift_cards, processed_events | one payment per CARD/COD order (WALLET orders have none); source refunds only for CARD; wallet balance is a true running sum and never negative |
| promotion_db | coupons, bank_offers, deals, deal_claims, redemptions, coin_ledger, exchange_programs, exchange_quotes | one redemption per (user, coupon); first-order-only, window and currency rules; coins earn 1/100 INR or 1/unit (members ×2); `deals.claimed` equals unreleased claims |
| rating_db | ratings, rating_votes, review_media, questions, answers, qa_votes | one review per (product, user); verified ⇒ the user has that product in a delivered order; helpful/unhelpful, answer_count and upvotes equal their child rows |
| seller_db | sellers, seller_feedback, payouts, ledger_entries, sale_lines, ad_campaigns | SALE / −COMMISSION % / −FEE (10 INR, 0.25; none for the 0 % platform seller); partial refunds post REFUND_REVERSAL and COMMISSION_REVERSAL by `return_id`; payout = sum of its entries |
| support_db | faq_articles, tickets (SLA dates), ticket_messages, chat_sessions, chat_messages, audit_log | resolved ⇒ `resolved_at`; CSAT only on resolved; first response from an agent message |
| notification_db | notifications (deduped by event), push_subscriptions, campaigns | one notification per order milestone, `dedupe_key` unique |

Seed rows shipped inside the migrations (roles, plans, FX rates, the "Acme Retail" platform seller and its 3
warehouses, demo coupons…) are left untouched and referenced where the business logic does (platform-listed products).

## How it works
```
inspect ──► inventory/schema_inventory.json ──► plan (calibrate on 1,500 customers → customers for target size)
                                                    │
 generate:   static  ─►  customer chunks (parallel) ─►  catalog chunks (parallel) ─►  finalize
             categories      users → identity, carts,       products, offers, variants,    sellers.rating_*,
             coupons, deals  orders, payments, ledger,      images, stock, warehouse       deals.claimed, serial
             sellers, WH     reviews, tickets, alerts…      stock (exact tallies of        sequences, ANALYZE
                              + tallies (npz per chunk)     ratings / reservations)
```
* **Index-addressable world** — every entity is a pure function of `(seed, kind, index)`: UUIDs, signup dates, product
  prices, seller commission… A worker can recompute any parent row, so there is no shared lookup table and memory is flat
  (a worker holds ≈ 50 MB + ≈ 50 KB per customer in its chunk). Different services agree on ids without talking.
* **Vectorised generation** — NumPy hashing/RNG, Faker-built vocabulary pools, fancy-indexing and vectorised hex/UUID/
  money/timestamp encoders; rows are assembled once, as COPY text.
* **Bulk load** — one connection per database per worker, `COPY … FROM STDIN` per table inside **one transaction per
  (chunk, database)**; `synchronous_commit=off` for the session. Existing indexes/constraints stay on (the schema is never
  altered), so the load is correct first and as fast as Postgres allows with live indexes.
* **Resumable** — `state/state.db` (SQLite) records each committed `(stage, chunk, database)`; `plan.json` pins the world
  (seed, as-of date, sizes). A crash between `COMMIT` and the state write is detected by probing the chunk's first primary
  key. Tallies are per chunk; if one is lost the chunk is regenerated (deterministically) without writing, only to rebuild it.
* **Progress** — rows, bytes, MB/s, elapsed and ETA per stage; failed chunks are reported and retried on the next run.
* **Throughput** (Docker Desktop, 8 GB shared with the full microservice stack, 9 workers): ≈ 36 MB/s of COPY text →
  1 GB target in ≈ 30 s, extrapolating to roughly 5–10 min for 10 GB depending on index maintenance and disk.

## Safety
* **Never production** — `assert_safe_target` refuses hosts not in `target.allowed_hosts`, any host/db name containing
  `prod|production|prd|live`, and managed-cloud hostnames (RDS, Azure, GCP, Supabase, Neon, Aiven).
* **Append-only** — the only SQL the tool can run is `SELECT`/`WITH`/`SHOW`, `SET`, `ANALYZE` and `COPY … FROM STDIN`;
  `DELETE/DROP/TRUNCATE/ALTER/CREATE/GRANT/VACUUM/INSERT/UPDATE` are rejected by a guard in the DB layer. The single
  exception is a key-restricted `UPDATE` of `sellers.rating_avg/rating_count` and `deals.claimed` for this run's own
  ids (denormalised counters that can only be known after all rows exist).
* **Discovery is read-only** (`default_transaction_read_only = on`). `validate`/`stats` too.
* **Confirmation** — `generate` prints the target and whether tables already hold rows, and requires typing the host
  name (or `--yes`).
* **Secrets** — credentials come from environment variables, never from files in the repo; the LLM prompts contain no data
  from your databases.
* Killed runs cannot leave zombie sessions holding locks (server-side TCP keepalives, 5-minute `lock_timeout`).

## Optional LLM text
`python main.py generate --llm anthropic` (needs `ANTHROPIC_API_KEY`; `--llm openai` needs `OPENAI_API_KEY`).
The model is asked **once** for a few hundred varied passages per pool — product descriptions per category, reviews per
sentiment, support replies, marketing copy, shopper personas — capped by `llm.max_calls` and cached in
`cache/llm_pools.json`. Generators then sample and recombine them with row facts (product noun, rating, order id), so
10 GB of text costs a few cents rather than millions of API calls. With no key, no network or any API error it
silently falls back to the built-in Faker/template pools.

## Tests
```bash
pytest -q                                   # unit + schema-contract + business-rule tests (no DB needed)
SDAI_PG_PASSWORD=... pytest -q              # also runs the end-to-end generate → resume → validate test on a scratch Postgres
```
The suite generates a world in memory through the real catalog and asserts: every non-seed table has a generator,
PK/unique/intra-service FK integrity, the pricing/GST/shipping formulas, order↔item↔shipment roll-ups, lifecycle
consistency, one payment per CARD/COD order, wallet non-negativity, exact rating counters, determinism, schema-drift
detection, the production/SQL guards, planner scaling and the LLM pool builder (with a fake provider).

## Troubleshooting
* *`inventory/schema_inventory.json not found`* → run `inspect`.
* *`NOT NULL columns without default not supplied`* → a migration added a column; re-run `inspect`, then extend the
  relevant `sdai/gen_*.py` (the error names table and columns). This is deliberate: no silent bad data.
* *Out of memory / swapping* → lower `--workers` (the default already caps workers at 60 % of free RAM).
* *Adding a second dataset* → use a different `--seed` (ids, emails, SKUs, slugs, codes are seed-salted).
* *Duplicate key on a re-run with the same seed* → the chunk already exists; this is detected and skipped.

## Limitations
* Loads with the schema's indexes live (never drops or alters them); expect the 10 GB load to be bounded by Postgres
  index maintenance. Use a fast local disk and `fsync=off` on scratch servers.
* Sizes are estimates (±~10–15 %): `stats` shows the actual size; re-plan with `--users` to adjust.
* Not generated: `PENDING_PAYMENT` orders and failed/processing payments, exchange quotes linked to orders, Kafka/Redis
  state, binary export-job content, FAQ/theme/notification-settings seed content. See `docs/FutureWork.md`.
