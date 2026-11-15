# Self-hosting open-weight image generation (SDXL, SD 3.5, FLUX.1 schnell/dev) on rented GPUs for ~100k product images at ~1024x1024

Research date: 2026-11-15. All USD. Throughput sources are blogs and vendor guides with unclear methodology and they disagree with each other, so treat every speed as a range. Cost tables in the Inferences sections are my own arithmetic: cost per 1,000 images = seconds per image x 1000 / 3600 x $/hr. Multiply by 100 for 100k images. Ignored: startup/model-load time, idle time, and rejects (see Q4).

## Q1. Throughput (images/sec and images/hour) per GPU and model

### Takeaway
Published numbers are inconsistent, but a usable working range exists. FLUX schnell (4 steps) runs about 1-4 s/image on 4090, A100 and H100 class cards and about 6-10 s on an L4. FLUX dev (20 steps) runs about 5-10 s on an H100, 10-18 s on an A100, 15-30 s on a 4090 and 35-60 s on an L4. SDXL on a 4090 is about 2.6 s/image at 20 steps. I found no solid SD 3.5 per-GPU benchmark and no reliable A10G or L4 SDXL figure.

### Cited Findings
- JarvisLabs table (1024x1024, batch 1, FP16, approximate, step counts schnell 4 / dev 20): H100 schnell ~1-2 s, dev ~5-10 s; A100 80GB schnell ~2-4 s, dev ~10-18 s; RTX 4090 schnell ~2-4 s, dev ~15-30 s; L4 schnell ~6-10 s, dev ~35-60 s. It also lists those GPUs at $3.49, $1.49, $0.59 and $0.44/hr. — [JarvisLabs, Best GPU for FLUX](https://jarvislabs.ai/ai-faqs/best-gpu-for-flux)
- Gigagpu 4090 benchmarks (as reported by search snippet; the pages returned HTTP 403 on direct fetch, so I could not verify): schnell 4 steps ~1.8 s FP8 / ~2.6 s FP16; dev 30 steps ~4.0 s FP8 and ~6.2 s FP16 (22 GB peak VRAM). The dev figure looks too fast against other sources. — [gigagpu schnell](https://gigagpu.com/rtx-4090-24gb-flux-schnell-benchmark/), [gigagpu dev](https://gigagpu.com/rtx-4090-24gb-flux-dev-benchmark/)
- RTX 4090 per dev.to (posted June 2, 2026; consumer-card measurements): SDXL 1.0 at 1024x1024, 20 steps ~2.6 s/image; FLUX schnell 4 steps FP8 ~4-5 s; FLUX dev 20 steps FP8 with `--fast` 9-10 s, Q8 GGUF 15-17 s, FP16 18-41 s. This contradicts gigagpu's 1.8 s schnell figure. — [dev.to / runaihome](https://dev.to/jovan_chan_9500711396d4e6/flux-vs-sdxl-vs-sd-15-real-cost-per-image-across-gpus-2026-44f8)
- packet.ai (Aug 2026) estimates an RTX 4090 at ~300 schnell images/hour (~12 s/image, which conflicts with the other sources and is probably a loose estimate), an RTX 6000 Pro at ~100 dev images/hour, and an L40S at 5-10 s schnell / 20-40 s dev. — [packet.ai](https://packet.ai/blog/flux-vs-sdxl-image-generation-cost)
- Replicate's flux-is-fast post (hardware not stated in the snippet I saw): schnell 4 steps 0.72 s, dev 28 steps 3.03 s. — [Replicate](https://replicate.com/blog/flux-is-fast-and-open-source)
- HF/PyTorch "flux-fast" gets ~2.5x speedup on FLUX schnell and dev on an H100 700W using torch.compile, FP8, FA3 and CUDA graphs. Per-image times are only in charts I could not read. — [PyTorch blog](https://pytorch.org/blog/presenting-flux-fast-making-flux-go-brrr-on-h100s/)
- Baseten: optimized SDXL takes 4-6 s on an A10G and an unoptimized A100 takes 8-10 s (latency figures, not throughput). TensorRT gives up to 30% (A100) and 40% (H100) faster SDXL. — [Baseten](https://www.baseten.co/blog/sdxl-inference-in-under-2-seconds-the-ultimate-guide-to-stable-diffusion-optimiza), [Baseten TensorRT](https://baseten.co/blog/40-faster-stable-diffusion-xl-inference-with-nvidia-tensorrt)
- SD 3.5: Large is ~8.1B params and Medium ~2.5B. Stability says TensorRT makes Medium ~1.7x faster on RTX GPUs. A Clore.ai guide claims Large takes ~3 s at 1024x1024 on a 4090 with no steps or precision given, which I consider unverified. — [Stability TensorRT post](https://stability.ai/news/stable-diffusion-35-models-optimized-with-tensorrt-deliver-2x-faster-performance-and-40-less-memory-on-nvidia-rtx-gpus), [Clore SD3.5](https://docs.clore.ai/guides/image-generation/stable-diffusion-3-5)
- A Valebyte page (dated Apr 24, 2026) lists SDXL (50 steps) at 12.5 img/s on H100, 7.8 on A100 and 2.8 on 4090. Not credible: its own cost column is ~10x off and the H100 figure is implausible. I exclude it. — [Valebyte](https://www.valebyte.com/en/guides/gpu-cloud-benchmarks-2025-stable-diffusion-performance-value/)

### Inferences
Working seconds-per-image values used below (midpoints of the ranges above):

| Model | 4090 | L4 | A10G | A100 | H100 |
|---|---|---|---|---|---|
| FLUX schnell, 4 steps | 2.5 s (~1,440/hr) | 8 s (~450/hr) | ~5 s (my guess, unsourced) | 3 s (~1,200/hr) | 1.5 s (~2,400/hr) |
| FLUX dev, 20 steps | 10-22 s (160-360/hr) | 47 s (~77/hr) | n/a | 14 s (~257/hr) | 7.5 s (~480/hr) |
| SDXL, 20-30 steps | 2.6 s (~1,380/hr) | n/a | ~5 s (~720/hr) | ~1.5 s (inferred) | ~1 s (inferred) |

- SD 3.5 Large should land between SDXL and FLUX dev in cost (8B params, similar to FLUX's 12B but fewer steps, usually 28-40). SD 3.5 Medium should be close to SDXL. No sourced numbers.
- Batching and compile and FP8 optimizations can give 1.3-2.5x on top of these naive numbers, so the figures are conservative for a tuned pipeline.

### Gaps
- No credible published SDXL or SD 3.5 numbers for L4, A10G, A100 or H100 at stated step counts and batch sizes. Run a 200-image pilot on the chosen GPU before committing.
- Gigagpu pages could not be fetched (403), so the 1.8 s / 4.0 s numbers are snippet-only.

## Q2. Current GPU rental prices

### Takeaway
RunPod Secure is about $0.89 (4090), $0.69 (L4), $2.09 (A100 PCIe), $2.89 (H100 PCIe) and $4.39 (H100 SXM). Community and Vast.ai pricing is 40-60% lower. Hyperscalers cost more per unit of performance: AWS g5.xlarge (A10G) is $1.006/hr and GCP an A100 40GB is about $3.67/hr.

### Cited Findings
- RunPod pricing page, "Updated November 12, 2026" (Secure / Community): RTX 4090 $0.89 / $0.34; L4 $0.69 / $0.44; A100 PCIe $2.09 / $1.19; A100 SXM $2.19 / $1.39; H100 PCIe $2.89 / $1.99; H100 SXM $4.39 / $2.69; A40 $0.59 / $0.35; L40S $1.09 / $0.79; A10G not listed. Community figures came from structured data/FAQ rather than the visible table. — [RunPod pricing](https://www.runpod.io/gpu-cloud/pricing)
- Lambda on-demand, effective Nov 16, 2026: H100 SXM $4.89/hr (1x; $4.59 at 8x), H100 PCIe $3.79 (1x), A100 80GB $3.19 (8x node only), A100 40GB $1.99, A10 $1.29, GH200 $2.59, B200 $7.99 (1x). — [Lambda pricing](https://lambda.com/pricing)
- Vast.ai (marketplace, varies by host): cheapest offers ~$0.36 (4090), ~$0.34 (A100), ~$1.79 (H100) per Activepieces, Nov 2, 2026; realistic working range ~$0.30-0.40 (4090), $0.40-1.50 (A100 80GB), $1.50-2.80 (H100). Thunder Compute (Oct 23, 2026) lists Vast on-demand at 4090 $0.71, A100 80GB $1.44, H100 $6.43, an outlier. — [Activepieces](https://www.activepieces.com/blog/vast-ai-gpu-pricing-per-hour-in-2026-and-extra-fees.md), [Thunder Compute](https://www.thundercompute.com/blog/vast-ai-vs-thunder-compute), [usagepricing.com](https://usagepricing.com/blueprint/vast-ai)
- AWS us-east-1 on-demand: g5.xlarge (1x A10G) $1.006/hr, g6.xlarge (1x L4) $0.8048/hr. g5.xlarge spot ~$0.51/hr at one snapshot. These are aggregator figures, not the AWS page. One aggregator listing both at $0.18/hr is an outlier and I disregard it. — [DoiT g5.xlarge](https://www.doit.com/compute/spot/us-east-1/g5.xlarge), [Holori g6.xlarge](https://calculator.holori.com/aws/ec2/g6.xlarge/us-east-1)
- GCP us-central1: g2-standard-4 (1x L4) ~$0.71/hr; a2-highgpu-1g (1x A100 40GB) ~$3.67/hr on-demand. Spot figures conflict across aggregators (A100 $1.84 vs $3.30). Aggregator figures, official page not extractable. — [GCP accelerator pricing](https://cloud.google.com/products/compute/pricing/accelerator-optimized), [Holori a2-highgpu-1g](https://calculator.holori.com/gcp/vm/a2-highgpu-1g)
- Other snapshots for cross-checking: packet.ai (Aug 2026) 4090 $0.39, A100 80GB $0.89; JarvisLabs H100 $3.49, A100 $1.49, 4090 $0.59, L4 $0.44. — [packet.ai](https://packet.ai/blog/flux-vs-sdxl-image-generation-cost), [JarvisLabs](https://jarvislabs.ai/ai-faqs/best-gpu-for-flux)

### Inferences
- Marketplace and Community tiers have no SLA and can disappear mid-job. For a 100k batch, use checkpointed, idempotent job chunks so interruptions cost little.
- The cheapest throughput-per-dollar is usually a 4090 on community/marketplace for schnell/SDXL, and an H100 for dev or heavy models.

### Gaps
- Official AWS and GCP pricing pages were not read directly. Lambda's page shows prices effective Nov 16, 2026 (tomorrow), not necessarily today's.

## Q3. Cost per 1,000 images and per 100k images, plus wall-clock time

### Takeaway
At naive settings, FLUX schnell or SDXL on a 4090 costs about $0.25-0.65 per 1,000 images, or $25-65 per 100k, which is the cheapest option. The H100 and A100 land at about $1-2 per 1,000 for schnell. FLUX dev costs $1-10 per 1,000 ($100-1,000 per 100k) and is the least efficient. Compute is a small part of the total (see Q4). 100k images take about 70 hours on one 4090 (schnell) versus about 9 hours on 8.

### Cited Findings
- Existing published estimates agree in order of magnitude: packet.ai estimates ~$3.90 per 1,000 for an RTX 4090 and ~$7.60 per 1,000 for an RTX 6000 Pro, but they assume a loose ~100-300 images/hr. JarvisLabs derives 4090 dev ~$0.003/image and schnell ~$0.0005/image ($0.50 per 1,000 images). Electricity-only cost on a home 4090 is ~$0.10 per 1,000 for schnell and ~$0.23 for FLUX dev FP8. — [packet.ai](https://packet.ai/blog/flux-vs-sdxl-image-generation-cost), [JarvisLabs](https://jarvislabs.ai/ai-faqs/best-gpu-for-flux), [dev.to](https://dev.to/jovan_chan_9500711396d4e6/flux-vs-sdxl-vs-sd-15-real-cost-per-image-across-gpus-2026-44f8)
- For reference, API prices: BFL FLUX.1 Pro API ~$0.055/image, schnell API ~$0.003/image, DALL-E 3 HD $0.08 (packet.ai, Aug 2026). Self-hosted schnell at ~$0.0003-0.0020/image is well below this. — [packet.ai](https://packet.ai/blog/flux-vs-sdxl-image-generation-cost)

### Inferences
Cost per 1,000 images (my calculation from the Q1 midpoint speeds and Q2 prices; excludes rejects, boot and idle):

| Model / GPU (price used) | s/img | $ per 1,000 | $ per 100k |
|---|---|---|---|
| FLUX schnell / 4090 RunPod Community $0.34 | 2.5 | $0.24 | $24 |
| FLUX schnell / 4090 RunPod Secure $0.89 | 2.5 | $0.62 | $62 |
| FLUX schnell / L4 RunPod Secure $0.69 | 8 | $1.53 | $153 |
| FLUX schnell / L4 GCP $0.71 | 8 | $1.58 | $158 |
| FLUX schnell / L4 AWS g6 $0.8048 | 8 | $1.79 | $179 |
| FLUX schnell / A10G AWS g5 $1.006 | ~5 (guess) | ~$1.40 | ~$140 |
| FLUX schnell / A100 PCIe RunPod Community $1.19 | 3 | $0.99 | $99 |
| FLUX schnell / A100 PCIe RunPod Secure $2.09 | 3 | $1.74 | $174 |
| FLUX schnell / H100 SXM RunPod Community $2.69 | 1.5 | $1.12 | $112 |
| FLUX schnell / H100 PCIe RunPod Secure $2.89 | 1.5 | $1.20 | $120 |
| FLUX schnell / H100 SXM Lambda $4.89 | 1.5 | $2.04 | $204 |
| FLUX dev / 4090 Community $0.34 (10-22 s) | 10-22 | $0.94-2.08 | $94-208 |
| FLUX dev / 4090 Secure $0.89 | 10-22 | $2.47-5.44 | $247-544 |
| FLUX dev / L4 RunPod Secure $0.69 | 47 | $9.01 | $901 |
| FLUX dev / A100 PCIe Community $1.19 | 14 | $4.63 | $463 |
| FLUX dev / H100 PCIe Secure $2.89 | 7.5 | $6.02 | $602 |
| FLUX dev / H100 SXM Community $2.69 | 7.5 | $5.60 | $560 |
| SDXL / 4090 Community $0.34 | 2.6 | $0.25 | $25 |
| SDXL / 4090 Secure $0.89 | 2.6 | $0.64 | $64 |
| SDXL / A10G AWS g5 $1.006 | ~5 | ~$1.40 | ~$140 |
| SDXL / H100 PCIe Secure $2.89 (1 s, inferred) | ~1 | ~$0.80 | ~$80 |

SD 3.5 Large is estimated to cost between the SDXL and FLUX dev rows on the same GPU, and SD 3.5 Medium close to SDXL; no sourced figures.

Wall-clock for 100k images (pure generation, ideal scaling, no rejects):

| Case | 1 GPU | 8 GPUs |
|---|---|---|
| FLUX schnell, 4090 (2.5 s) | ~69 h (2.9 days) | ~8.7 h |
| FLUX schnell, H100 (1.5 s) | ~42 h | ~5.2 h |
| FLUX schnell, L4 (8 s) | ~222 h (9.3 days) | ~27.8 h |
| FLUX dev, 4090 (10-22 s) | ~278-611 h (12-25 days) | ~35-76 h |
| FLUX dev, H100 (7.5 s) | ~208 h (8.7 days) | ~26 h |
| SDXL, 4090 (2.6 s) | ~72 h | ~9 h |

- The 8-GPU totals cost the same as 1 GPU (same GPU-hours); only time shrinks. Embarrassingly parallel, so scaling is near-linear if prompts are sharded.
- A tuned pipeline (FP8, torch.compile, batching) could cut GPU time by up to ~2x, per the flux-fast claim above.

### Gaps
- No sourced real-world 100k-image production case study with end-to-end cost.
- Throughput inputs are the weak link: a 2x error in s/img doubles cost.

## Q4. Licensing, commercial usability, and overhead (setup, upscaling, QA)

### Takeaway
FLUX.1 schnell (Apache-2.0) is the clean choice for commercial product images. SD 3.5 is usable commercially for organizations under $1M annual revenue under the Stability Community License, with registration. FLUX.1 dev is licensed non-commercial and its terms bar revenue-generating use of the model, so bulk production for a business needs a separate BFL commercial license, even though outputs themselves are allowed commercial use. Rejection rates for AI product images are reported anywhere from 10% to 60%, so QA overhead can matter more than GPU cost.

### Cited Findings
- FLUX.1 schnell: "License: apache-2.0"; "the model can be used for personal, scientific, and commercial purposes." Out-of-scope uses (harassment, non-consensual nudity, large-scale disinformation) are listed. Access requires accepting HF conditions. — [HF FLUX.1-schnell](https://huggingface.co/black-forest-labs/FLUX.1-schnell)
- FLUX.1 dev: model card says "falls under the FLUX.1 [dev] Non-Commercial License" while noting "Generated outputs can be used for personal, scientific, and commercial purposes as described in the... License." — [HF FLUX.1-dev](https://huggingface.co/black-forest-labs/FLUX.1-dev)
- The FLUX dev license itself: "We claim no ownership rights in and to the Outputs"; "You may use Output for any purpose (including for commercial purposes), except as expressly prohibited"; no using outputs to train a competing model. But access and use of the model is limited to "Non-Commercial Purposes," which exclude use "for revenue-generating activity" or "in direct interactions with or that has impact on end users"; Section 4(a) bars use "for any commercial or production purposes." It points to bfl.ai for a commercial license. — [BFL non-commercial license](https://bfl.ai/legal/non-commercial-license-terms)
- Stability AI Community License (applies to SD 3.5): free for individuals and organizations under $1M annual revenue (cap includes affiliates; above it you must obtain an Enterprise License); "You own any outputs generated from the Models... to the extent permitted by applicable law"; commercial users must register with Stability AI; when distributing you must keep the notice "This Stability AI Model is licensed under the Stability AI Community License" and prominently display "Powered by Stability AI" on related websites/interfaces/documentation. Hosted services, APIs and internal business operations count as commercial. — [Stability Community License](https://stability.ai/community-license-agreement), [HF SD 3.5 Large](https://huggingface.co/stabilityai/stable-diffusion-3.5-large)
- QA/rejection: sources are vendor/seller blogs and anecdotal. ~23% of AI product images reported as having detectable artifacts needing correction; a seller post reports 40-60% of AI lifestyle images fail at least one QA check (small sample); Amazon rejection claims range ~12-20% with no AI-specific isolation; failure types: garbled text, wrong color/material/accessories, hallucinated parts. Transparent, jewelry, textile, reflective and small-text products fail more. — [Bria](https://bria.ai/blog/wh-ai-product-images-get-rejected), [Indie Hackers](https://www.indiehackers.com/post/what-i-learned-about-ai-product-images-from-rejecting-half-the-output-zXjaoGXp3OK6wpPNfcOd), [Transloadit](https://transloadit.com/guides/ai-product-image-generation/), [Rewarx](https://www.rewarx.com/blogs/ai-product-photos-platform-rejection-issues)

### Inferences
- Commercial usability for product images: schnell, yes (cleanest). SD 3.5 Large/Medium, yes if the company is under $1M revenue and registers; above that, an Enterprise License. SDXL (OpenRAIL++), likely usable but license text not verified here. FLUX dev, no without a BFL commercial license: the "outputs are commercial" statement does not cover running the model for a revenue-generating bulk job. This is not legal advice.
- Rejects multiply generation cost by 1/(1 - r): at r = 25% x1.33, at 50% x2. For schnell on a 4090 this raises 100k images from ~$25-62 to ~$33-124 in GPU cost. Even then GPU cost is small versus human QA labor or engineering time.
- Native 1024x1024 output needs no upscaling. If 2048 px or higher is needed, a separate upscaling pass (e.g. Real-ESRGAN, or a tile diffusion pass) adds GPU time; I found no sourced numbers, so size it in the pilot.
- Engineering/setup is the biggest cost driver but I found no sourced hours/dollars. Typical tasks: Diffusers or ComfyUI pipeline, prompt templating, queue with checkpointing, storage, automated QA (CLIP score, text/OCR check, VLM judge), and manual spot-check sampling. Plausibly a few engineer-days to a couple of weeks at your internal rates; that is my estimate, not a sourced fact.
- Product-image fidelity (exact SKU appearance) usually needs img2img/ControlNet/inpainting or LoRAs rather than pure text-to-image, which raises per-image time (maybe 2-4x) over the plain text-to-image numbers above. Unsourced.

### Gaps
- SDXL license (CreativeML Open RAIL++-M) text not retrieved or verified.
- No sourced engineering-hours, upscaling throughput, or QA-labor cost. No independent, methodology-backed rejection rate.
- Whether Stability's registration requirement and $1M cap apply to your specific entity is a legal question not resolved here.
