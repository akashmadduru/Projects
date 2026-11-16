# 20. Hybrid product search (AI-1)

Last checked against the code: 2026-11-15. Source: `product/search/HybridSearchService.java`, `HybridSearchSql.java`,
`product/embedding/QueryEmbeddingService.java`. Decision: [ADR 0006](../adr/0006-semantic-search-in-product-service.md).

```mermaid
sequenceDiagram
    autonumber
    participant SPA as Storefront
    participant GW as Gateway
    participant PS as product-service (8082)
    participant C as Query cache
    participant BR as AWS Bedrock (Titan V2)
    participant DB as product_db (pgvector)

    SPA->>GW: GET /api/products/search?q=...&mode=hybrid
    GW->>PS: forward (public read, global rate limit)
    PS->>PS: validate, normalise, extract filters ("under 3000")
    PS->>C: get(model | text)
    alt cache miss
        PS->>BR: InvokeModel (800 ms timeout, embeddingCb breaker)
        alt provider ok
            BR-->>PS: vector[1024]
            PS->>C: put
        else timeout / error / breaker open / provider "none"
            PS->>PS: drop vector leg, degraded = true
        end
    end
    PS->>DB: BEGIN READ ONLY; set_config(trgm threshold, hnsw.iterative_scan, ef_search)
    PS->>DB: WITH kw (top 100), vec (top 100, dist <= 0.65), fused = RRF(k=60)
    DB-->>PS: ranked ids (<= 200)
    PS->>DB: findAllById(page ids), keep rank order
    PS-->>SPA: {content, page, effectiveMode, degraded, interpretedFilters, matchedBy}
```

## Rules that are not obvious
- The provider call happens before the transaction opens, so a slow provider never holds a pooled connection.
- A degraded answer is a 200, not an error. `degraded` is only true when semantic ranking was asked for.
- Facets (`/search/facets`) run the same retrieval and count over the same id set, so they agree with the list.
- pgvector names are qualified with `public.`, pg_trgm names with `product.`.
