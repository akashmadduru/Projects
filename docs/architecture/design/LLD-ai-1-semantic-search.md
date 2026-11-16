# LLD: AI-1 Semantic product search (as built)

Last checked against the code: 2026-11-15. Decision record: [ADR 0006](../adr/0006-semantic-search-in-product-service.md).
Platform context: [HLD-ai-platform](HLD-ai-platform.md). Diagrams: [20](../diagrams/20-hybrid-search-sequence.md),
[21](../diagrams/21-embedding-pipeline.md).

## 1. API

| Method and path | Auth | Notes |
|---|---|---|
| `GET /api/products/search` | Public | `q` (2..200 chars, required), `mode=keyword\|semantic\|hybrid` (default `hybrid`), `categoryId` (includes direct children), `minPrice`, `maxPrice`, `minRating` (0..5), `brand` (CSV, OR), `sort=relevance\|price_asc\|price_desc\|newest`, `page`, `size` (1..50, default 20). |
| `GET /api/products/search/facets` | Public | Same parameters. Same shape as `/api/products/facets`, computed over exactly the fused candidates. |
| `GET /api/products/admin/embeddings/status` | `CATALOG_MANAGER` or `ADMIN` | `{enabled, provider, model, dims, active, embedded, stale, failed, lastRunAt, oldestStaleAt}`. |
| `POST /api/products/admin/embeddings/reindex` | `CATALOG_MANAGER` or `ADMIN` | Optional `productId`. `202 {queued}`. The reconciler does the work on its next run; old vectors stay searchable until replaced. |

`SecurityConfig` permits every `GET /api/products/**`, so the `@PreAuthorize` on the admin methods is their only guard.

Search response (`SearchResponse`). `content` and `page` are the same envelope as `GET /api/products`:

```json
{
  "content": [ProductResponse],
  "page": { "size": 20, "number": 0, "totalElements": 37, "totalPages": 2 },
  "effectiveMode": "hybrid",
  "degraded": false,
  "interpretedFilters": { "minPrice": null, "maxPrice": 60000, "minRating": null, "brand": null },
  "matchedBy": { "<productId>": "keyword | semantic | both" }
}
```

Errors: 400 `VALIDATION_FAILED` for bad parameters. A provider that is down or not configured is **not** an error:
the vector leg is dropped, `effectiveMode` becomes `keyword` and `degraded` is `true` (unless `mode=keyword` was
asked for). `AI_UNAVAILABLE` (503, new in `common-core`) is used only where no degraded answer exists.

## 2. Schema (`V10__product_embeddings.sql`)

| Table | Columns | Index |
|---|---|---|
| `product.product_embeddings` | `product_id` PK/FK (cascade), `embedding public.vector(1024)`, `model`, `dims`, `content_hash char(64)`, `embedded_at`, `checked_at` | HNSW `public.vector_cosine_ops` (m=16, ef_construction=64); `checked_at` |
| `product.embedding_failures` | `product_id` PK/FK, `content_hash`, `attempts`, `last_error`, `next_attempt_at` | PK |

The `vector` extension is a **precondition** created by the superuser bootstrap (`01-schemas.sql`,
`create-customer-dbs.sql`, `db-bootstrap.yaml`), in `public`. V10 only re-asserts it and fails with a hint if
it is missing. All pgvector names are schema-qualified because Flyway migrates with `search_path=product`.

## 3. Hybrid query (`HybridSearchSql`, `HybridSearchRepository`)

One statement, run in a read-only transaction after `set_config(..., true)` (transaction-local) for
`pg_trgm.similarity_threshold` (0.25), `hnsw.iterative_scan=relaxed_order` and `hnsw.ef_search` (100).

- **Filter fragment** (`ProductFilterSql`): `p.active` plus category, price, rating and brand. Spliced into the
  keyword leg, the vector leg and the facets, so counts cannot disagree with the list. Only column names are
  concatenated; every value is a bound parameter. Sort clauses come from the `SearchSort` enum.
- **Keyword leg** `kw`: `websearch_to_tsquery('english', :q)` or the indexed trigram operator
  `OPERATOR(product.%)`, ranked by `ts_rank_cd + product.similarity`, top `topN` (100).
- **Vector leg** `vec`: `OPERATOR(public.<=>)` against the query vector (bound as a text literal and cast, so no
  pgvector JDBC type is needed), current model only, top `topN`, then neighbours beyond `maxDistance` (0.65) are
  dropped *after* the index scan so they cannot defeat it.
- **Fusion**: `sum(1 / (k + rank))`, k = 60, grouped by id, with `in_kw`/`in_vec` flags that become `matchedBy`.

At most 200 products per search, by design. Paging, sorting and facets work within that set.

## 4. Query path (`HybridSearchService`, `QueryEmbeddingService`)

1. `SearchParams.toQuery()` validates. `QueryUnderstanding` extracts price, rating and brand filters only where
   the explicit parameter is absent; `q` is never rewritten.
2. Normalised text, then the in-process cache (5 min TTL, 10k entries, keyed by model and text).
3. On a miss: `EmbeddingPort.embed(QUERY)` with an 800 ms timeout through the `embeddingCb` breaker, **outside any
   transaction** (the Hikari pool is 10).
4. Read-only transaction: settings, fused query, then hydrate the page with `findAllById` and reorder.

Query text never reaches a log line: failures log the exception class only.

## 5. Embedding pipeline (`EmbeddingReconciler`)

Every minute (`PT1M`, 30 s initial delay), one replica (advisory `JobLock`):

1. Short read-only transaction: up to 64 active products with no embedding, another model, or
   `products.updated_at > checked_at` (all products on the daily full sweep), skipping products whose
   `embedding_failures.next_attempt_at` is in the future.
2. No transaction: build `EmbeddingText` (name, brand, category, description, flattened specifications;
   never price, stock or ratings), hash it with the model id, and embed only the changed ones (10 s timeout,
   4 concurrent calls).
3. Short write transaction: upsert vectors, touch `checked_at`, clear or record failures with exponential
   backoff (1 min to 6 h).

Up to 50 batches per run. Edits that race a run self-heal on the next run.

## 6. Provider (`EmbeddingPort`)

| Adapter | When | Notes |
|---|---|---|
| `BedrockEmbeddingPort` | `EMBEDDING_PROVIDER=bedrock` | Titan V2 `InvokeModel` with `dimensions=1024, normalize=true`. Credentials from the SDK default chain (IRSA on EKS). Validates the returned length and rejects non-finite values. |
| `FakeEmbeddingPort` | `fake`, only with the `test` or `dev-fake` profile | Deterministic, lexical (not semantic) vectors. |
| none | `none` (default) | Feature off; search is keyword-only with `degraded=true`. |

## 7. Clients

- Storefront: `productApi.list` sends a query to `/search` (hybrid) when it has 2+ characters of text and uses only
  filters the endpoint supports; attribute, seller, stock, sale and rating/discount sorts stay on the catalog
  listing. A failed search call falls back to the listing. Facets follow the same rule. The results header shows a
  "Smart search" tag when semantic ranking was actually used.
- Admin: `ProductsView` shows index coverage (or "Semantic search off") and a "Reindex search" button for
  catalog managers.

## 8. Configuration

`product.embedding.*` in product-service `application.yml` (`EmbeddingProperties`). Environment variables are in
[environment-variables.md](../../reference/environment-variables.md) under "AI and search".
