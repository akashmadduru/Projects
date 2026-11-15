"""Generate product photos locally (SDXL, fp16, fits an 8 GB GPU such as an RTX 2070) for the first N products.

Read-only against product_db; writes output/images/<product_id>.jpg + output/images/manifest.jsonl.
Resumable (existing files are skipped); deterministic per product (seed derived from the product id).
Does NOT modify any database. Use --update-db later (not implemented) if you want product_images/thumbnail URLs rewritten.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import time
from pathlib import Path

import psycopg

SQL = """
select p.id, p.name, p.brand, p.color, p.material, c.name as category, left(p.description, 220)
from product.products p
left join product.categories c on c.id = p.category_id
where p.sku ~ '^SKU-[A-Z]{3}-[0-9A-Z]{9}$'
order by p.created_at, p.id
limit %s offset %s
"""


def prompt_for(name, brand, color, material, category):
    bits = [f"{color or ''} {name}".strip()]
    if material:
        bits.append(f"made of {material.lower()}")
    if category:
        bits.append(f"({category.lower()})")
    return (f"professional e-commerce product photograph of {', '.join(bits)}, single product centered, "
            "pure white seamless background, soft studio lighting, sharp focus, high detail, catalog style")


NEGATIVE = "text, watermark, logo, caption, people, hands, multiple objects, blurry, low quality, deformed, cropped"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--count", type=int, default=1000)
    ap.add_argument("--offset", type=int, default=0)
    ap.add_argument("--out", default="output/images")
    ap.add_argument("--steps", type=int, default=20)
    ap.add_argument("--size", type=int, default=1024)
    ap.add_argument("--dsn", default=os.environ.get("SDAI_PRODUCT_DSN", "postgresql://postgres:sdai_scratch@sdai-pg:5432/product_db"))
    a = ap.parse_args()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    with psycopg.connect(a.dsn, autocommit=True) as c:
        c.execute("SET default_transaction_read_only = on")
        rows = c.execute(SQL, (a.count, a.offset)).fetchall()
    todo = [r for r in rows if not (out / f"{r[0]}.jpg").exists()]
    print(f"{len(rows)} products selected, {len(rows) - len(todo)} already done, {len(todo)} to generate", flush=True)
    if not todo:
        return

    import torch
    from diffusers import AutoencoderKL, DPMSolverMultistepScheduler, StableDiffusionXLPipeline
    vae = AutoencoderKL.from_pretrained("madebyollin/sdxl-vae-fp16-fix", torch_dtype=torch.float16)      # fp16-safe VAE (no black images)
    pipe = StableDiffusionXLPipeline.from_pretrained("stabilityai/stable-diffusion-xl-base-1.0", vae=vae, torch_dtype=torch.float16,
                                                     variant="fp16", use_safetensors=True)
    pipe.scheduler = DPMSolverMultistepScheduler.from_config(pipe.scheduler.config, use_karras_sigmas=True)
    pipe.enable_model_cpu_offload()          # keeps peak VRAM under 8 GB
    pipe.enable_vae_slicing()
    manifest = (out / "manifest.jsonl").open("a", encoding="utf-8")
    t0 = time.time()
    for i, (pid, name, brand, color, material, category, desc) in enumerate(todo, 1):
        seed = int.from_bytes(hashlib.sha256(str(pid).encode()).digest()[:4], "big")
        prompt = prompt_for(name, brand, color, material, category)
        img = pipe(prompt=prompt, negative_prompt=NEGATIVE, num_inference_steps=a.steps, guidance_scale=6.5, width=a.size, height=a.size,
                   generator=torch.Generator("cpu").manual_seed(seed)).images[0]
        tmp = out / f"{pid}.tmp.jpg"
        img.save(tmp, quality=90)
        tmp.replace(out / f"{pid}.jpg")
        manifest.write(json.dumps({"product_id": str(pid), "name": name, "prompt": prompt, "seed": seed, "file": f"{pid}.jpg"}) + "\n")
        manifest.flush()
        el = time.time() - t0
        print(f"[{i}/{len(todo)}] {name[:40]:<40} {el / i:5.1f}s/img  ETA {(len(todo) - i) * el / i / 60:5.1f} min", flush=True)


if __name__ == "__main__":
    main()
