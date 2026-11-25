# HLD: Cartwise AI platform (AI-1 to AI-5)

Last updated: 2026-11-15. Status: **AI-1 built** ([LLD](LLD-ai-1-semantic-search.md), [ADR 0006](../adr/0006-semantic-search-in-product-service.md)).
AI-2 to AI-5 are approved designs, not code.

The phases are called AI-1..AI-5 to avoid colliding with the product Phases 1–10 in [phases/](../../phases/README.md).

## 1. Goals and non-goals

**Goals**
- AI features are additive, and every AI path degrades to today's behaviour.
- All data access by AI components goes through the **end user's token**; the domain services re-authorize.
- Nothing changes state without a human click.

**Non-goals**
- No checkout, payment, address, profile or order-mutating tools, ever.
- No anonymous chat in v1 (public AI is limited to search and review-summary reads).
- No new vector database, no schema registry, no exactly-once delivery, no external MCP clients in v1.

## 2. Phases

| Phase | Size | Outcome | Depends on |
|---|---|---|---|
| AI-1 Semantic search (**built**) | L | pgvector embeddings in product-service, RRF hybrid search, deterministic NL filters | spikes |
| AI-2 MCP | L | read-only `mcp-service` tool server (product, inventory, rating, promotion), per-tool authz, audit; `assistant-service` skeleton | AI-1 (search tool) |
| AI-3 Shopping assistant | XL | chat panel, comparisons, cart changes only after a browser-side confirm, context management | AI-2; fix TD-BE-09 first |
| AI-4 RAG knowledge | L | `knowledge.events` from a support-service outbox, ACL-filtered hybrid retrieval, cited answers or refusal | none (parallel with AI-2/3) |
| AI-5 Advanced | XL | five independent tracks: recommendations, review summaries, seller analytics Q&A, visual search, support automation | varies |

Overall estimate: 110–150 person-days.

## 3. Services after AI-5

| Service | Status | Responsibility | Data | Provider egress |
|---|---|---|---|---|
| gateway | modified | `assistant` route (SSE-safe breaker), stricter limiter on `/api/products/search*` and `/api/assistant/**` | none | no |
| product-service | **modified (AI-1)** | embeddings, hybrid search, reconciler, admin coverage; later `similar-to` and visual search | `product_embeddings`, later `product_image_embeddings` | embeddings |
| mcp-service :8094 | new (AI-2) | **read-only for its whole life**; tool registry, per-call authz, user-token relay, audit. Not routed by the gateway. | none | **no** |
| assistant-service :8093 | new (AI-2/3) | agent loop, conversations, pending actions, confirm and cart execution, knowledge index, review summaries, budgets | `assistant_db` (pgvector) | chat, embeddings |
| support-service | modified (AI-4/5) | FAQ/policy source of truth, knowledge outbox and republish, chat delegation with rule-based fallback | `support_db` | no |
| cart, inventory, rating, promotion, seller, order | unchanged | re-authorize every relayed call | own | no |

## 4. Trust boundaries

| Boundary | Control |
|---|---|
| Browser to gateway | JWT validation, rate limits, bearer token sent to the own origin only (TD-SEC-11, fixed 2026-11-18) |
| assistant-service to mcp-service to domain services | the **user's** JWT on every call, passed as a method parameter (no thread-local relay, so it cannot silently go missing on a worker thread); deny-by-default tool registry; downstream `@PreAuthorize` |
| Egress to Bedrock | only product-service and assistant-service; IRSA, no keys; PII redacted; never inside a DB transaction |
| Untrusted text into prompts (seller products, reviews, documents) | delimited and labelled; fixed tool allow-list; no tools on the RAG answer path; ids, prices and quantities re-resolved server-side |

AI services never hold `INTERNAL_API_TOKEN` (TD-P6-12) and never reach payment or checkout endpoints.

## 5. Communication and consistency

| Interaction | Style |
|---|---|
| Product writes to embeddings | **Neither sync nor events**: a polling reconciler reads the database (product-service has no outbox, TD-P6-33) |
| Search, tools, confirm | Sync HTTP with the user token |
| support-service to knowledge index | Async `knowledge.events` from a support-service outbox, with republish |
| `rating.events` to review summaries | Async trigger only; the summary re-reads the public rating API |

Embeddings and the knowledge index are eventually consistent and advisory; pending cart actions are strongly
consistent (single-row compare-and-set).

## 6. Failure modes

| Failure | Behaviour |
|---|---|
| Embedding provider down or not configured | `/search` returns keyword results with `degraded=true`; the reconciler backs off per product |
| Chat provider down | assistant shows an unavailable state; support chat falls back to rules |
| Downstream outage | tools return a structured `UNKNOWN`/`ERROR`, never a false "in stock" |
| Kafka down | the outbox accumulates; answers come from the existing index |
| pgvector missing | V10 fails fast at startup with a hint to run the bootstrap |

## 7. Decisions taken at the approval gate (2026-11-15)

- Provider: **AWS Bedrock**; embeddings Titan Text V2 at **1024** dimensions.
- AI-1 implemented first; later phases in separate runs.
- Defaults accepted: authenticated-only chat, read-only MCP, 30-day conversation retention, review summaries
  public, no linking of anonymous browsing to accounts, ticket duplicate detection dropped, policies as FAQ
  entries with `kind=POLICY`, search degrades rather than fails.

## 8. Open items

See [techdebt/ai.md](../../techdebt/ai.md) for AI-1 follow-ups and the risks carried into AI-2+.
