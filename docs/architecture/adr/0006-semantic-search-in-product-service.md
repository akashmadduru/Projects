# ADR 0006: Semantic product search inside product-service, on pgvector and Bedrock

Status: accepted, 2026-11-15. Implemented (Cartwise AI-1). Unit-tested; the pgvector integration tests
are written but were not run when this was recorded (Docker unavailable).

## Context
Keyword search (`ProductNativeQueries.rankedIds`: full-text plus trigram, capped at 2000 ids and
ranked in memory, TD-P7-03) cannot answer "lightweight laptop for travel under 60000". The AI roadmap
(see [HLD-ai-platform](../design/HLD-ai-platform.md)) starts with semantic discovery, and later phases
(assistant, recommendations) reuse the same vectors.

## Decision
Product embeddings live in product-service's own database, in `product.product_embeddings`
(`vector(1024)`, HNSW cosine), and one SQL statement fuses a keyword leg and a vector leg with
Reciprocal Rank Fusion. A new endpoint, `GET /api/products/search`, serves it. The old
`GET /api/products?q=` is untouched.

| Choice | Why |
|---|---|
| Vectors in `product_db`, not a new search service | One query can combine full-text, trigram, vector and the catalog filters with consistent facets. A search service fed by `product.events` would drift: there is no outbox (TD-P6-01) and some listeners swallow errors (TD-BE-17). |
| A separate table, not a column on `products` | Keeps the 4 KB vector out of every catalog read; re-embedding never bumps `products.version` or `updated_at`. |
| A polling reconciler (under `JobLock`), not Kafka | Covers every write path (catalog, seller, seed) with no event to lose. A content hash skips unchanged products; a daily full sweep catches category renames that do not touch `updated_at`. |
| AWS Bedrock, Amazon Titan Text Embeddings V2 at 1024 dimensions | Fits EKS with IRSA (no API key) and keeps data in-region. 1024 is fixed in the column type: a different model or width is a new table plus a cutover, never an `ALTER`. |
| AWS SDK v2 `bedrockruntime`, not Spring AI | The day-1 spike found that spring-ai 2.0.2's Bedrock module declares spring-boot-autoconfigure 4.1.2, spring-messaging 7.0.10 and Jackson 3.1.7, which would lift the Boot platform off 4.0.7 (the springdoc failure mode recorded in `libs.versions.toml`). The SDK has no Spring dependency. It sits behind `EmbeddingPort`, so swapping is local. |
| `vector` extension in schema `public`, created by the bootstrap scripts | pgvector is not a trusted extension: the `product_service` role gets "permission denied". The superuser bootstrap creates it, and it cannot go in `product` because Flyway refuses to start in a non-empty schema with no history table. Every type, operator class and operator is schema-qualified (`public.vector`, `OPERATOR(public.<=>)`), because Flyway runs with `search_path=product`. |
| Provider down or not configured: keyword results with `degraded=true` | Search must never fail because of an optional provider. The fake provider exists only for tests and the opt-in `dev-fake` profile, never as a silent fallback. |
| Natural language through small deterministic extractors ("under 3000", "4 stars and above", "from Acme") | product-service needs no chat model. LLM query parsing is deferred to the assistant (AI-3). |
| Each leg keeps its top 100, so a search returns at most 200 products | An explicit, documented cap that replaces the legacy hidden 2000-id cap on this path. Deep paging is not a search goal. |

## Consequences
- product-service now egresses to Bedrock. The Helm NetworkPolicy and IAM role need `bedrock:InvokeModel`
  for this model (follow-up, see [techdebt/ai.md](../../techdebt/ai.md)).
- Every product-service integration test now runs on the `pgvector/pgvector:0.8.7-pg17` image.
- An existing local volume needs `deploy/local/scripts/create-customer-dbs.sql` re-run (as the superuser) to
  create the extension before V10 can apply.
- Extraction into a search service later remains possible: the port, the SQL builder and the response
  shape do not depend on where the table lives.
