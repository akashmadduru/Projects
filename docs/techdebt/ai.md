# Tech debt: Cartwise AI (AI-1 semantic search)

Checked against the code on 2026-11-15. Design: [HLD-ai-platform](../architecture/design/HLD-ai-platform.md),
[LLD-ai-1](../architecture/design/LLD-ai-1-semantic-search.md).

## TD-AI-01
**pgvector integration tests written but never run.** Severity: High. Effort: S.
`HybridSearchIntegrationTest`, `HybridSearchProviderDownIntegrationTest` and `HybridSearchDisabledIntegrationTest`
(Testcontainers, `pgvector/pgvector:0.8.7-pg17`) compile, but Docker was unavailable when AI-1 was finished, so the
real SQL (schema-qualified operators, RRF, facets, iterative scans) has only been exercised by unit tests and an
earlier partial run. Run `./gradlew :services:product-service:product-service-app:test` with Docker before
enabling the feature anywhere. Unit tests (`EmbeddingTextTest`, `FakeEmbeddingPortTest`, `QueryEmbeddingCacheTest`,
`QueryUnderstandingTest`) pass.

## TD-AI-02
**No IRSA role or NetworkPolicy egress for Bedrock yet.** Severity: Med. Effort: S.
Helm sets `EMBEDDING_PROVIDER: "none"` and `AWS_REGION`, but the product-service ServiceAccount has no IAM role
with `bedrock:InvokeModel`, and `templates/network.yaml` (default deny) has no egress to the Bedrock endpoint.
NetworkPolicy cannot allow by FQDN, so the intended route is a Bedrock VPC interface endpoint. Until then the
feature stays off on AWS (keyword-only, `degraded=true`). RDS pgvector support for the deployed engine minor
version is also unverified (`db-bootstrap.yaml` comment).

## TD-AI-03
**No search-specific rate limit.** Severity: Med. Effort: M.
`GET /api/products/search` is public and each uncached query is one Bedrock call. The gateway's
`RateLimitWebFilter` only has the global per-client buckets (300 reads/min). Mitigated by the 5-minute query
cache, the 2..200 character limit, the `embeddingCb` breaker and Titan V2's low per-call cost. Add a stricter
bucket for `/api/products/search*` (planned for AI-3 together with `/api/assistant/**`).

## TD-AI-04
**Relevance never evaluated.** Severity: Med. Effort: M.
The plan's exit criterion (hybrid beats keyword on nDCG@10 and Recall@10 over a 50–100 query judged set) is not
done, and `maxDistance=0.65` is a guess for Titan V2. The storefront already sends searches to hybrid mode, which
degrades to keyword while the provider is `none`. Do not set `EMBEDDING_PROVIDER=bedrock` in a shared environment
before the evaluation; if hybrid loses, change `productApi.ts` to `mode=keyword` rather than disabling the endpoint.

## TD-AI-05
**Search and facets each run retrieval.** Severity: Low. Effort: S.
The storefront calls `/search` and `/search/facets` for the same query; the second embed is a cache hit but the
fused SQL runs twice. Acceptable at 200 candidates; return facets in the search response if it shows up in p95.

## TD-AI-06
**Formatting debt across product-service is wider than TD-TEST-07 says.** Severity: Low. Effort: S.
`spotlessApply` on product-service reformats 27 files untouched by AI-1 (clients, config, repositories, services,
controllers). They were left unformatted to keep the AI-1 change reviewable. Fix in a formatting-only commit.

## TD-AI-07
**Product-service unit tests had rotted before AI-1.** Severity: Low. Effort: done.
`ProductCatalogServiceTest` and `CreateProductRequestTest` did not compile after the Nov 12–14 DTO and constructor
changes (brand, attributes, four new collaborators); repaired alongside AI-1. There is no CI gate that compiles
product-service tests on every push, so this will recur (see the audit's CI trigger item).

## TD-AI-08
**The env-reference generator drops unknown groups silently.** Severity: Low. Effort: S.
`deploy/local/scripts/env-reference.mjs` renders only groups listed in `GROUP_ORDER`; a description with any other
`group` vanishes from the generated page while `--check` still passes. "AI and search" was added to the list.
Make unknown groups fail `--check`. Separately, `VITE_STOREFRONT_ORIGIN` (admin `ThemeView.vue`) is undescribed,
so `--check` fails today independently of AI-1.

## TD-AI-09
**Node 26 breaks admin auth-store and route-guard tests.** Severity: Low. Effort: S.
`auth.storage.spec.ts` and `guards.spec.ts` fail with `localStorage.clear` undefined: Node 26 ships a global
`localStorage` that shadows jsdom's. Same family as TD-FE-12. Not caused by AI-1; the admin suite otherwise passes.
