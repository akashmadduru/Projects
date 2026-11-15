# 21. Product embedding pipeline (AI-1)

Last checked against the code: 2026-11-15. Source: `product/embedding/EmbeddingReconciler.java`, `EmbeddingRepository.java`,
`EmbeddingText.java`. Decision: [ADR 0006](../adr/0006-semantic-search-in-product-service.md).

```mermaid
flowchart TD
    W[Catalog, seller and seed writes<br/>update products] -.no event, no hook.-> P[(product.products)]
    T([Every 1 min, one replica<br/>JobLock embedding-reconciler]) --> S1
    S1[TX 1, read-only:<br/>up to 64 active products with no vector,<br/>other model, updated_at > checked_at,<br/>or daily full sweep;<br/>skip failures still backing off] --> H
    P --> S1
    H[No TX: EmbeddingText = name, brand, category,<br/>description, specifications<br/>hash = sha256 text + model] --> D{hash changed?}
    D -- no --> K[touch checked_at]
    D -- yes --> E[Bedrock InvokeModel<br/>10 s timeout, 4 concurrent]
    E -- ok --> U[TX 2: upsert product_embeddings,<br/>touch checked_at, clear failure]
    E -- error --> F[TX 2: embedding_failures<br/>backoff 1 min .. 6 h]
    A[Admin: POST .../embeddings/reindex] -.marks stale.-> S1
    U --> V[(product.product_embeddings<br/>vector 1024, HNSW cosine)]
```

## Rules that are not obvious
- Price, stock and ratings are deliberately not embedded: they change often and are filters, not meaning.
- Rating updates bump `products.updated_at`; the content hash makes those cheap no-ops.
- A category rename does not touch `products.updated_at`; the daily full sweep catches it.
- Up to 50 batches per run; an edit racing a run is corrected on the next run.
