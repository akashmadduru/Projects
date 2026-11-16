# Other hosted image-generation APIs: price per image (research date 2026-11-15)

## 1. Price per image by provider/model (about 1024x1024)

### Takeaway
Cheapest reputable hosted options at about 1MP are fal.ai FLUX.2 (about $0.012) and FLUX.2 Flash (about $0.005), FLUX.2 klein via BFL ($0.014-0.015), Replicate FLUX schnell (about $0.003, lower quality), and Google Nano Banana 2.1 / 2 Lite at batch rates ($0.0168 at 1K). Mid-tier quality options are about $0.03-0.04: FLUX.2 pro ($0.03), Stability Core ($0.03) and SD3.5 Large ($0.065). Midjourney has no official API.

### Cited Findings
Google (Gemini API / AI Studio; page fetched 2026-11-15; I could not read the Vertex page, its fetch was truncated):
- Nano Banana 2.1 standard: $0.0336 (1K), $0.0504 (2K), $0.113 (4K). Batch: $0.0168 (1K), $0.0252 (2K), $0.0567 (4K). — [AI Studio pricing](https://aistudio.google.com/docs/pricing)
- Nano Banana 2 (3.1 Flash Image): $0.045 (0.5K), $0.067 (1K), $0.101 (2K), $0.151 (4K). No batch price listed. — [AI Studio pricing](https://aistudio.google.com/docs/pricing)
- Nano Banana 2 Lite (3.1 Flash Lite Image): $0.0336 (1K) standard, $0.0168 batch. — [AI Studio pricing](https://aistudio.google.com/docs/pricing)
- Nano Banana Pro (3 Pro Image): $0.134 (1K/2K), $0.24 (4K). — [AI Studio pricing](https://aistudio.google.com/docs/pricing)
- Nano Banana (2.5 Flash Image): $0.039, batch $0.0195. It is deprecated, with shutdown scheduled for 2027-03-15. — [AI Studio pricing](https://aistudio.google.com/docs/pricing)
- None of these image models has a free tier. Batch is 50% of standard. — [AI Studio pricing](https://aistudio.google.com/docs/pricing)
- Imagen 4 was $0.02 (Fast), $0.04 (Standard) and $0.06 (Ultra) per image, per aggregators and the Google blog (Fast only). — [Google Developers Blog](https://developers.googleblog.com/en/announcing-imagen-4-fast-and-imagen-4-family-generally-available-in-the-gemini-api/?asuniq=b91b7682); [pricepertoken](https://pricepertoken.com/imagen-pricing). Imagen is likely retired or deprecated: sources conflict, with a Vertex deprecation on 2026-03-24 and a Gemini API shutdown on 2026-08-17 claimed by an aggregator search summary. Unverified; do not plan on Imagen.
- fal.ai resells Nano Banana at $0.0398/image, Nano Banana 2 at $0.08 and Nano Banana Pro at $0.15. — [fal.ai pricing](https://fal.ai/pricing)

Stability AI (direct API; 1 credit = $0.01; fetched 2026-11-15):
- Stable Image Ultra $0.08; SD3.5 Large $0.065; SD3.5 Large Turbo $0.04; SD3.5 Medium $0.035; SD3.5 Flash $0.025; Stable Image Core $0.03; SDXL 1.0 from $0.009. — [Stability pricing](https://docs.stability.ai/pricing)
- Edit/background tools: Remove Background $0.05; Replace Background & Relight $0.08; Inpaint $0.05; Fast Upscaler $0.02; Conservative Upscaler $0.40; Creative Upscaler $0.60. These are useful for product photos. — [Stability pricing](https://docs.stability.ai/pricing)
- Stability on Bedrock (older models): SDXL 1.0 $0.08 per 1024x1024 at premium quality (70 steps), and about $0.036 for 512x512. — [AWS Bedrock pricing](https://aws.amazon.com/bedrock/pricing/)

Black Forest Labs (direct; 1 credit = $0.01; fetched 2026-11-15):
- FLUX.2 is billed per megapixel (first MP / each additional MP): max $0.07/$0.03; pro $0.03/$0.015; flex $0.05/$0.05; klein 9B $0.015/$0.002; klein 4B $0.014/$0.001. Each reference image is billed separately. — [BFL pricing](https://bfl.ai/pricing)
- FLUX.1 per image: Kontext max $0.08; Kontext pro $0.04; 1.1 pro Ultra $0.06; 1.1 pro $0.04; FLUX.1 pro $0.05; dev $0.025. — [BFL pricing](https://bfl.ai/pricing)
- A "FLUX 3 Image" tier exists (billed per image by resolution tier, 768x768 up to 4k). Dollar amounts were not retrievable. — [BFL docs](https://docs.bfl.ai/quick_start/pricing.md)

fal.ai (fetched 2026-11-15):
- FLUX.1 schnell $0.003/MP; FLUX.1 dev $0.025/MP; FLUX.1 dev LoRA $0.035/MP; FLUX 2 $0.012/MP; FLUX 2 Edit $0.012/processed MP; FLUX 2 Flash $0.005/MP; Z Image Turbo $0.005/MP; Sana $0.001/MP; Seedream V4.5 $0.04/image; Flux 3 Image edit $0.048/MP. — [fal.ai pricing](https://fal.ai/pricing)

Replicate (fetched 2026-11-15):
- FLUX 1.1 Pro $0.04/image; FLUX Dev $0.025/image; FLUX Schnell $3.00 per 1,000 images; Recraft V3 $0.04; Ideogram V3 Quality $0.09. — [Replicate pricing](https://replicate.com/pricing)
- FLUX.2 pro on Replicate: aggregators say $0.015 + $0.015 per MP, about $0.055 at 1MP with one input image. They disagree with each other and I found no official figure. — [search results summary](https://www.cometapi.com/models/replicate/black-forest-labsflux-2-pro/)

Together AI (fetched 2026-11-15):
- FLUX.2 pro $0.03/image; FLUX.2 flex $0.03/image; FLUX.2 dev $0.0154/image; FLUX.2 max $0.07/MP; FLUX.1.1 pro $0.04/MP; Kontext pro $0.04/MP; Kontext max $0.08/MP. — [Together pricing](https://www.together.ai/pricing)

Amazon Bedrock:
- Titan Image Generator: $0.01 per 1024x1024 standard-quality image on-demand (AWS pricing example). — [AWS Bedrock pricing](https://aws.amazon.com/bedrock/pricing/)
- Nova Canvas: $0.04 (standard) and $0.06 (premium) at up to 1024x1024, per secondary sources only (AWS page not retrievable). — [Developers Digest](https://www.developersdigest.tech/models/image-generation/amazon-bedrock); [MindStudio](https://mindstudio.ai/blog/amazon-nova-canvas-vs-stable-image-core-budget-comparison). Reseller EmpirioLabs lists $0.12/$0.18, which is a markup, not AWS pricing.
- One aggregator reports nova-canvas-v1:0 was retired on 2026-09-30. Unverified.

Midjourney:
- No official public API as of the latest (vendor-written) sources. Third-party wrappers are unofficial, and sources claim the ToS prohibit automated access. — [Apiframe](https://apiframe.ai/blog/best-midjourney-apis); [Unifically](https://unifically.com/blogs/midjourney-api). Not viable for 100k bulk generation.

### Inferences
- Estimated cost for 100,000 images at about 1MP: fal FLUX.2 Flash about $500; fal FLUX.2 about $1,200; BFL klein 4B about $1,400; Nano Banana 2.1 batch 1K about $1,680; BFL/Together FLUX.2 pro about $3,000; Stability Core about $3,000; Nova Canvas standard about $4,000 (unverified); Nano Banana 2 about $6,700.
- The cheapest reputable option with strong text and product fidelity is likely Nano Banana 2.1 or 2 Lite at batch rates, or fal FLUX.2. Quality for product photos was not benchmarked here.
- The Titan $0.01 price is cheap, but its quality is generally lower and I did not verify its current availability.

### Gaps
- I could not fetch Vertex AI's own pricing page (truncated), the official Nova Canvas rates, the official Imagen 4 status, or the BFL FLUX 3 dollar prices.
- I did not verify Replicate's FLUX.2 pricing.

## 2. Batch and volume discounts, rate limits

### Takeaway
Google gives a clear 50% batch discount on Gemini image models. BFL, Stability, fal and Replicate offer only negotiated enterprise discounts with no public tiers. Together shows no image batch discount.

### Cited Findings
- Google batch is 50% of standard on listed Nano Banana models. — [AI Studio pricing](https://aistudio.google.com/docs/pricing)
- BFL: enterprise plan offers custom pricing and volume discounts via sales. — [BFL pricing](https://bfl.ai/pricing)
- BFL concurrency: maximum 24 concurrent requests on most endpoints, 6 on flux-kontext-max, with exponential backoff on 429. — [BFL integration guidelines](https://docs.bfl.ai/api_integration/integration_guidelines)
- Replicate: volume discounts only via the enterprise program, and no rate limits listed on the pricing page. — [Replicate pricing](https://replicate.com/pricing)
- fal.ai: no volume discounts or rate limits stated; custom deployments via sales. — [fal.ai pricing](https://fal.ai/pricing)
- Stability: no discount or rate-limit info on the pricing page. — [Stability pricing](https://docs.stability.ai/pricing)
- Together: batch API price labels appear only for chat and audio, not image. — [Together pricing](https://www.together.ai/pricing)
- AWS: batch inference at 50% off applies to select Anthropic, Meta, Mistral and Amazon models, and image models are not confirmed. Provisioned Throughput exists, for example Titan Image at about $16.20 per model unit per hour on a 1-month commitment. — [AWS Bedrock pricing](https://aws.amazon.com/bedrock/pricing/)

### Inferences
- At 24 concurrent requests and an assumed 5 to 10 seconds per image, BFL would give about 2.4-4.8 images per second, so 100k images would take about 6-12 hours. This is my calculation, not a sourced figure.

### Gaps
- Google API quotas (RPM/IPM) for image models, and Bedrock Nova Canvas quotas, were not found.

## 3. Licensing, commercial use and watermarking

### Takeaway
Google outputs carry SynthID. BFL's open-weight models need a commercial license, but the API route has no stated restriction I could confirm.

### Cited Findings
- Imagen 4 outputs carry an invisible SynthID watermark. A single third-party claim says Vertex allows disabling it (addWatermark) while the Gemini API does not. — [Coinis](https://coinis.com/models/google/imagen-4) (unverified). Gemini image outputs also carry SynthID, which I assumed from Google's general practice and did not verify in this research.
- BFL commercial licensing for self-hosted open weights is tiered: Builder 10K images/month; Platform 100K/month; Professional 100K/month on up to 3 domains; Enterprise custom. A separate Synthetic Data tier covers use of outputs as training data. — [BFL pricing](https://bfl.ai/pricing)
- BFL docs do not address commercial use of API outputs. — [BFL docs](https://docs.bfl.ai/quick_start/pricing.md)

### Inferences
- SynthID is invisible, so it should not matter for product listings, but marketplace AI-disclosure policies are outside this research.

### Gaps
- I did not read the Stability, AWS, fal or Replicate commercial-use terms, nor the Gemini terms on output ownership.
