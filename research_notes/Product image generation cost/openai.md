# OpenAI image generation API pricing (as of 2026-11-15), for ~100k e-commerce product photos

All prices USD, read from OpenAI developer docs on 2026-11-15. The old platform.openai.com/docs/pricing URL redirects to developers.openai.com/api/docs/pricing. openai.com/api/pricing returned HTTP 403.

## What is the cost per image at low/medium/high quality for square product shots?

### Takeaway
OpenAI now bills image models by token, and per-image figures are derived from fixed output-token counts. For the latest families (gpt-image-2.5 and 2.6, sunburst and flare) OpenAI publishes no per-image dollar table, only token rates. The only current-generation per-image table is for gpt-image-2, which shares those token rates and is marked Deprecated. At 1024x1024 it costs $0.006 (low), $0.053 (medium) and $0.211 (high). Cheapest option: gpt-image-1-mini at $0.005 / $0.011 / $0.036, also marked Deprecated.

### Cited Findings
Per-image price, standard (non-batch), from OpenAI's image generation guide cost table:

| Model | Quality | 1024x1024 | 1024x1536 | 1536x1024 |
|---|---|---|---|---|
| gpt-image-2 | Low | $0.006 | $0.005 | $0.005 |
| gpt-image-2 | Medium | $0.053 | $0.041 | $0.041 |
| gpt-image-2 | High | $0.211 | $0.165 | $0.165 |
| gpt-image-1.5 | Low | $0.009 | $0.013 | $0.013 |
| gpt-image-1.5 | Medium | $0.034 | $0.05 | $0.05 |
| gpt-image-1.5 | High | $0.133 | $0.20 | $0.20 |
| gpt-image-1-mini | Low | $0.005 | $0.006 | $0.006 |
| gpt-image-1-mini | Medium | $0.011 | $0.015 | $0.015 |
| gpt-image-1-mini | High | $0.036 | $0.052 | $0.052 |
| gpt-image-1 (SHUT DOWN 2026-10-23) | Low / Med / High | $0.011 / $0.042 / $0.167 | $0.016 / $0.063 / $0.25 | same as 1024x1536 |

Source for the table: [Image generation guide](https://developers.openai.com/api/docs/guides/image-generation) (the page text said the gpt-image-2 row is for "earlier models"). The gpt-image-1.5, gpt-image-1-mini and gpt-image-1 rows are corroborated by the model pages: [gpt-image-1.5](https://developers.openai.com/api/docs/models/gpt-image-1.5), [gpt-image-1-mini](https://developers.openai.com/api/docs/models/gpt-image-1-mini), [gpt-image-1](https://developers.openai.com/api/docs/models/gpt-image-1).

- Output tokens per image for the earlier models: low 272 / 408 / 400, medium 1056 / 1584 / 1568, high 4160 / 6240 / 6208, for 1024x1024 / 1024x1536 / 1536x1024. — [Image generation guide](https://developers.openai.com/api/docs/guides/image-generation)
- Token rates per 1M tokens, standard — [Pricing](https://developers.openai.com/api/docs/pricing):
  - gpt-image-2, 2.5-sunburst, 2.5-flare, 2.6-sunburst, 2.6-flare: text input $5 (cached $1.25), image input $8 (cached $2), image output $30.
  - gpt-image-1.5 and chatgpt-image-latest: text input $5, image input $8, image output $32, text output $10.
  - gpt-image-1-mini: text input $2, image input $2.50, image output $8.
- gpt-image-2.5 (sunburst, flare) and gpt-image-2.6 (sunburst, flare) have no per-image price table. Their model pages give token pricing only. Sources: [2.5-sunburst](https://developers.openai.com/api/docs/models/gpt-image-2.5-sunburst), [2.5-flare](https://developers.openai.com/api/docs/models/gpt-image-2.5-flare), [2.6-sunburst](https://developers.openai.com/api/docs/models/gpt-image-2.6-sunburst), [2.6-flare](https://developers.openai.com/api/docs/models/gpt-image-2.6-flare). The 2.5 pages say their token rates match GPT Image 2 and that the GPT Image 2 calculator does not estimate their token usage.
- gpt-image-2.5 supports quality low, medium, high, xhigh, max and auto. 2.5-flare is described as "the fastest model for high-quality, everyday image generation". 2.5-sunburst is described as "our most capable model" and aimed at editing precision. Default snapshots are dated 2026-09-08. — model pages above.
- gpt-image-2.6 models default to `high` quality if `complexity` is omitted. "Adjusting complexity has no separate fee." — [Image generation guide](https://developers.openai.com/api/docs/guides/image-generation)
- Status as shown on the model pages: gpt-image-1.5, gpt-image-2 and gpt-image-1-mini are labelled Deprecated. gpt-image-1 was shut down 2026-10-23 and the page recommends GPT-Image-2.5 Sunburst as the replacement. Pages for 2.5 and 2.6 show no status label. No shutdown date was found for the deprecated models other than gpt-image-1.
- DALL-E 3 is no longer offered ("deprecated and removed from the API"), and the page recommends GPT-Image-2.5 Sunburst. — [dall-e-3 model page](https://developers.openai.com/api/docs/models/dall-e-3)
- Regional processing (data residency) endpoints carry a 10% uplift for models released on or after 2026-03-05, and FedRAMP endpoints carry a 10% uplift. — [Pricing](https://developers.openai.com/api/docs/pricing)
- A third-party aggregator (pricepertoken.com) shows matching 1.5 and mini figures, but I relied on OpenAI pages.

### Inferences
- Implied output tokens for gpt-image-2 at $30/M: about 200 (low), 1,767 (medium) and 7,033 (high) at 1024x1024. This is arithmetic from the prices above, not a published figure.
- gpt-image-2.5 and 2.6 per-image cost is likely in the same range as gpt-image-2, since the token rates are the same. The token counts per image are unconfirmed, and 2.5 has xhigh and max tiers with unknown cost. Treat the gpt-image-2 figures as a proxy only.
- The gpt-image-2 table is odd: 1024x1536 is cheaper than 1024x1024. It is copied as published and I did not verify it further.

### Gaps
- No official per-image dollar table for gpt-image-2.5 and 2.6, and no token counts per quality tier. OpenAI points to a calculator in the guide, which I could not run.
- Cost of the xhigh and max quality tiers is unknown.
- Deprecation dates for gpt-image-2, 1.5 and 1-mini were not found. This matters if the project runs for months.
- Page openai.com/api/pricing returned 403, so the marketing page was not checked.

## Is there a Batch API discount and what is it?

### Takeaway
Yes. The discount is 50% on both input and output tokens, with a 24-hour completion window. Batch covers `/v1/images/generations` and `/v1/images/edits`.

### Cited Findings
- Batch API is a "50% cost discount compared to synchronous APIs". Supported endpoints include `/v1/images/generations` and `/v1/images/edits`. Limits are 50,000 requests per batch and a 200 MB input file. Each model has a cap on enqueued prompt tokens, set on the Platform Settings page. The window is 24 hours, the only value supported, and batches usually finish sooner. — [Batch guide](https://developers.openai.com/api/docs/guides/batch)
- Batch token rates per 1M tokens — [Pricing](https://developers.openai.com/api/docs/pricing):
  - gpt-image-2, 2.5 and 2.6: image input $4, image output $15, text input $2.50.
  - gpt-image-1.5: image output $16, image input $4, text input $2.50.
  - gpt-image-1-mini: image output $4, image input $1.25, text input $1.00.
- gpt-image-2.5 pages state batch is supported on `v1/batch` and show image output at $15 batch. — [2.5-sunburst](https://developers.openai.com/api/docs/models/gpt-image-2.5-sunburst), [2.5-flare](https://developers.openai.com/api/docs/models/gpt-image-2.5-flare)
- Conflict: the 2.6-sunburst and 2.6-flare model pages mark `v1/batch` as "Not supported", while the pricing page lists Batch rates for the 2.6 models. — [2.6-sunburst](https://developers.openai.com/api/docs/models/gpt-image-2.6-sunburst) versus [Pricing](https://developers.openai.com/api/docs/pricing). Treat 2.6 batch as unconfirmed.
- Batch is supported for gpt-image-2, 1.5, 1-mini and 1 per their model pages.

### Inferences
Batch per-image price is half the standard price, so gpt-image-2 batch at 1024x1024 is about $0.003 (low), $0.0265 (medium) and $0.1055 (high). gpt-image-1-mini batch is $0.0025 / $0.0055 / $0.018.

Estimated cost for 100,000 square images (gpt-image-2 prices as proxy for 2.5):

| Model | Quality | Standard | Batch |
|---|---|---|---|
| gpt-image-2 | Low | $600 | $300 |
| gpt-image-2 | Medium | $5,300 | $2,650 |
| gpt-image-2 | High | $21,100 | $10,550 |
| gpt-image-1.5 | Low | $900 | $450 |
| gpt-image-1.5 | Medium | $3,400 | $1,700 |
| gpt-image-1.5 | High | $13,300 | $6,650 |
| gpt-image-1-mini | Low | $500 | $250 |
| gpt-image-1-mini | Medium | $1,100 | $550 |
| gpt-image-1-mini | High | $3,600 | $1,800 |

These are my arithmetic (price x 100,000). Edit-request input tokens and any prompt text are extra.

### Gaps
- The enqueued-token limit for image models is not published in the docs and is only shown in account settings.
- The 2.6 batch conflict is unresolved.

## Image-to-image/edit pricing (if product photo reference is supplied)?

### Takeaway
Edits use the same output-token pricing plus input tokens: text at $5/M and image at $8/M (batch half). I found no published per-edit token count for a reference photo, so edit cost per image cannot be pinned down from the docs.

### Cited Findings
- Total cost is the sum of input text tokens, input image tokens (edits endpoint) and image output tokens. — [Image generation guide](https://developers.openai.com/api/docs/guides/image-generation)
- For gpt-image-2, image inputs are always processed at high fidelity, so edit requests with reference images can use more input tokens. The page refers to the vision costs section. — same source
- Markup-guided edits use the same token rates as other edits, with no extra fee. — same source
- Cached input discount ($2/M for image) applies only to the Responses API image generation tool, not to direct Images API calls such as `/v1/images/edits`. — same source
- gpt-image-2.5 supports edits via `v1/images/edits` and batch. SVG output is not supported by the edit endpoint. — [2.5-sunburst](https://developers.openai.com/api/docs/models/gpt-image-2.5-sunburst), [Image generation guide](https://developers.openai.com/api/docs/guides/image-generation)

### Inferences
Each reference image adds input cost at $8 per 1M tokens ($4 in batch). For example, if a reference photo cost about 1,000 image tokens, that would add about $0.008 standard or $0.004 batch. The 1,000 figure is an illustration, not a sourced number.

### Gaps
- Image input token count for a typical product photo was not found (the vision costs section was not fetched).

## Throughput/rate limits and practical time to generate 100k images?

### Takeaway
Default limits by tier are 20 IPM (Build), 150 IPM (Launch) and 250 IPM (Grow), plus TPM caps. At 250 IPM, 100k images takes about 6.7 hours. At 20 IPM it takes about 3.5 days. Batch avoids IPM limits in practice but is bounded by enqueued-token limits and the 24-hour window.

### Cited Findings
- Default rate limits for gpt-image-2, 2.5, 1.5, 1-mini and 1: Build 250,000 TPM / 20 IPM; Launch 3,000,000 TPM / 150 IPM; Grow 8,000,000 TPM / 250 IPM. Tiers rise automatically with usage and spend. — [gpt-image-1.5](https://developers.openai.com/api/docs/models/gpt-image-1.5), [gpt-image-2](https://developers.openai.com/api/docs/models/gpt-image-2), [2.5-sunburst](https://developers.openai.com/api/docs/models/gpt-image-2.5-sunburst)
- The 2.6 pages give no tier limits and say limits vary by usage tier and account. — [2.6-sunburst](https://developers.openai.com/api/docs/models/gpt-image-2.6-sunburst)
- Batch: up to 50,000 requests per batch file, a 24-hour completion window, and no output-token limit. — [Batch guide](https://developers.openai.com/api/docs/guides/batch)

### Inferences
- Synchronous time for 100,000 images: 20 IPM is about 83 hours; 150 IPM is about 11 hours; 250 IPM is about 6.7 hours. This assumes IPM is the binding limit. Check TPM too: at about 1,767 output tokens per medium image (implied), 8M TPM would be the tighter limit only if TPM counts output tokens, which I could not confirm.
- Batch needs at least two batch files (100,000 / 50,000). Each should finish within 24 hours, subject to enqueued-token caps.

### Gaps
- Whether IPM or TPM counts against batch jobs, and the actual enqueued-token cap for image models.
- Real-world latency per image was not found.

## Restrictions for product imagery

### Takeaway
All prompts and images pass content-policy filtering. A blocked request returns a `moderation_blocked` error. A `moderation: low` setting is available. I found no product-specific restriction in the pages I read.

### Cited Findings
- `moderation` accepts `auto` (default, standard filtering) or `low` (less restrictive). Blocked requests return `error.code = "moderation_blocked"` and may include `moderation_details` with stage (input, output, unknown) and coarse categories. — [Image generation guide](https://developers.openai.com/api/docs/guides/image-generation)
- The guide lists supported sizes. For 2.6, recommended sizes are 1024x1024, 1536x1024 and 1024x1536. Custom edges must be multiples of 16, aspect ratio between 1:3 and 3:1, no edge above 3840 px, total pixels 655,360 to 8,294,400, and sizes above 2560x1440 are experimental. gpt-image-2 accepts any size meeting those constraints. — same source

### Inferences
Branded products, people and apparel may trigger moderation blocks. This is not confirmed in the docs I read, so plan for a retry or fallback path.

### Gaps
- OpenAI usage policy text on trademarked or branded products and on photo-accuracy limits for product fidelity was not reviewed.
