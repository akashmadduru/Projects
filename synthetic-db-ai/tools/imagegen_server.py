"""Local product-image generator: SDXL on the GPU, a job queue, and a browser control panel.

    .venv-images\\Scripts\\python.exe tools\\imagegen_server.py        ->  http://127.0.0.1:8765

Reads output/products.jsonl (written once by tools/export_products.py); needs no database or Docker.
Writes output/images/<product_id>.jpg + manifest.jsonl; an existing .jpg means the product is done.
"""
from __future__ import annotations

import argparse
import gc
import hashlib
import json
import os
import random
import re
import shutil
import string
import subprocess
import sys
import threading
import time
import traceback
import webbrowser
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

ROOT = Path(__file__).resolve().parent.parent
os.environ.setdefault("HF_HOME", str(ROOT / ".hf"))
if (Path(os.environ["HF_HOME"]) / "hub" / "models--stabilityai--stable-diffusion-xl-base-1.0").exists():
    os.environ.setdefault("HF_HUB_OFFLINE", "1")

OUT = ROOT / "output" / "images"
PREVIEW = ROOT / "output" / "preview"
PRODUCTS = ROOT / "output" / "products.jsonl"
SETTINGS_FILE = OUT / "settings.json"
FAILED_FILE = OUT / "failed.json"
UI = Path(__file__).with_name("imagegen_ui.html")

STATIC = ROOT / "static"
STATIC_IMAGES = STATIC / "images"
STATIC_META = STATIC / "metadata"
UPLOADS_FILE = STATIC / "uploads.jsonl"
UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
FIELDS = ("name", "brand", "color", "material", "category")
SIZES = (512, 640, 768, 896, 1024)

DEFAULTS = {
    "steps": 20, "guidance": 6.5, "size": 1024, "quality": 90,
    "prompt": ("studio packshot photo of a {color} {name}, made of {material}, {category}, single product isolated on a plain "
               "pure white background, centered, no props, no scenery, soft even studio lighting, sharp focus, high detail, "
               "e-commerce catalog image"),
    "negative": ("room, interior, furniture, plants, table, shelf, scenery, landscape, people, hands, text, watermark, logo, "
                 "caption, multiple objects, blurry, low quality, deformed, cropped"),
}

DB_CONFIG = {
    "host": os.environ.get("SDAI_PG_HOST", "127.0.0.1"),
    "port": int(os.environ.get("SDAI_PG_PORT", "55432")),
    "dbname": "product_db",
    "user": os.environ.get("SDAI_PG_USER", "postgres"),
    "password": os.environ.get("SDAI_PG_PASSWORD", "sdai_scratch"),
}


def _read_json(path: Path, default):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return default


def _write_json(path: Path, obj):
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(obj, indent=1), encoding="utf-8")
    tmp.replace(path)


def render_prompt(template: str, product: dict) -> str:
    vals = {k: (product.get(k) or "").strip() for k in FIELDS}
    vals["material"] = vals["material"].lower()
    vals["category"] = vals["category"].lower()
    text = template.format(**vals)
    return re.sub(r"\s+,", ",", re.sub(r"\s{2,}", " ", text)).strip(" ,")


def validate_template(template: str):
    for _, field, spec, conv in string.Formatter().parse(template):
        if field is None:
            continue
        if field not in FIELDS or spec or conv:
            raise ValueError(f"unknown placeholder {{{field}}}; allowed: " + ", ".join("{%s}" % f for f in FIELDS))


def seed_for(pid: str, variant: int) -> int:
    base = int.from_bytes(hashlib.sha256(pid.encode()).digest()[:4], "big")
    return (base + variant * 7919) % 2**32


class Busy(RuntimeError):
    pass


class Engine:
    def __init__(self):
        self.lock = threading.RLock()
        self.products = [json.loads(line) for line in PRODUCTS.open(encoding="utf-8") if line.strip()]
        self.index = {p["id"]: i for i, p in enumerate(self.products)}
        OUT.mkdir(parents=True, exist_ok=True)
        PREVIEW.mkdir(parents=True, exist_ok=True)
        jpgs = sorted((f for f in OUT.glob("*.jpg") if UUID_RE.match(f.stem)), key=lambda f: f.stat().st_mtime)
        self.done = {f.stem for f in jpgs}
        self.recent = deque((f.stem for f in jpgs[-24:] if f.stem in self.index), maxlen=24)
        self.failed: dict = _read_json(FAILED_FILE, {})
        self.settings = {**DEFAULTS, **_read_json(SETTINGS_FILE, {})}
        self.queue: deque = deque()
        self.queued: set = set()
        self.current = None
        self.state = "idle"
        self.run = threading.Event()
        self.run.set()
        self.abort = None
        self.pipe = None
        self.thread = None
        self.times = deque(maxlen=10)
        self.log = deque(maxlen=100)
        self.last_preview = None
        self.session_done = 0
        self.fail_streak = 0
        self._gpu_cache = (0.0, None)
        self.db_written: set = set()
        self.db_failed: dict = {}
        self._load_db_state()
        self.s3_uploaded: set = set()
        self.s3_failed: dict = {}
        self._init_static_folder()
        self._load_s3_state()

    # ---- db state ---------------------------------------------------------------------------------
    def _load_db_state(self):
        db_state_file = OUT / "db_state.json"
        try:
            state = json.loads(db_state_file.read_text(encoding="utf-8"))
            self.db_written = set(state.get("written", []))
            self.db_failed = state.get("failed", {})
        except (OSError, ValueError):
            self.db_written = set()
            self.db_failed = {}

    def _save_db_state(self):
        db_state_file = OUT / "db_state.json"
        tmp = db_state_file.with_suffix(".tmp")
        tmp.write_text(json.dumps({
            "written": sorted(self.db_written),
            "failed": self.db_failed
        }, indent=1), encoding="utf-8")
        tmp.replace(db_state_file)

    def _init_static_folder(self):
        STATIC_IMAGES.mkdir(parents=True, exist_ok=True)
        STATIC_META.mkdir(parents=True, exist_ok=True)

    def _load_s3_state(self):
        try:
            with UPLOADS_FILE.open(encoding="utf-8") as f:
                for line in f:
                    if line.strip():
                        rec = json.loads(line)
                        pid = rec.get("product_id")
                        if rec.get("uploaded"):
                            self.s3_uploaded.add(pid)
                        else:
                            self.s3_failed[pid] = rec.get("error", "unknown")
        except (OSError, ValueError):
            pass

    def _save_to_static(self, product_id: str, image_path: Path, product: dict, settings: dict) -> bool:
        """Copy image to static folder and save metadata."""
        try:
            if not isinstance(image_path, Path):
                image_path = Path(image_path)
            if not image_path.exists():
                self._say(f"image not found for static save: {image_path}")
                return False

            STATIC_IMAGES.mkdir(parents=True, exist_ok=True)
            STATIC_META.mkdir(parents=True, exist_ok=True)

            dest = STATIC_IMAGES / f"{product_id}.jpg"
            shutil.copy2(str(image_path), str(dest))

            meta = {
                "product_id": product_id,
                "name": product.get("name"),
                "file_path": str(dest.relative_to(ROOT)),
                "file_size": dest.stat().st_size,
                "prompt": settings.get("prompt"),
                "negative": settings.get("negative"),
                "seed": product.get("seed"),
                "steps": settings.get("steps"),
                "guidance": settings.get("guidance"),
                "size": settings.get("size"),
                "created_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
                "uploaded": False,
                "s3_url": None,
            }
            meta_file = STATIC_META / f"{product_id}.json"
            meta_file.write_text(json.dumps(meta, indent=1), encoding="utf-8")
            with self.lock:
                self.s3_failed.pop(product_id, None)
            self._say(f"saved to static: {product_id}")
            return True
        except Exception as e:
            msg = str(e)[:100]
            self._say(f"failed to save to static: {product_id}: {msg}")
            return False

    def _write_to_db(self, product_id: str, image_path: str = None):
        if not image_path:
            image_path = f"output/images/{product_id}.jpg"
        try:
            import psycopg
            import image_store  # tools/image_store.py: PUT to the image server, then write the URL
            data = Path(image_path).read_bytes()
            with psycopg.connect(**DB_CONFIG) as conn:
                with conn.cursor() as cur:
                    image_store.push(cur, product_id, data)
                    conn.commit()
            with self.lock:
                self.db_written.add(product_id)
                self.db_failed.pop(product_id, None)
            self._say(f"image written to DB: {product_id}")
            return True
        except Exception as e:
            msg = f"{type(e).__name__}: {e}"[:200]
            with self.lock:
                self.db_failed[product_id] = {"error": msg, "at": time.strftime("%Y-%m-%d %H:%M:%S")}
            self._say(f"DB write failed for {product_id}: {msg}")
            return False

    # ---- queue control -------------------------------------------------------------------------
    def _say(self, msg):
        line = time.strftime("%H:%M:%S ") + msg
        self.log.append(line)
        print(line, flush=True)

    def _enqueue(self, tasks, front=False):
        with self.lock:
            if front:
                for t in reversed(tasks):
                    self.queue.appendleft(t)
            else:
                self.queue.extend(tasks)
            self.queued.update(t["id"] for t in tasks if not t.get("preview"))
            if self.thread is None or not self.thread.is_alive():
                self.thread = threading.Thread(target=self._worker, daemon=True)
                self.thread.start()
        return len(tasks)

    def queue_next(self, count):
        with self.lock:
            ids = [p["id"] for p in self.products if p["id"] not in self.done and p["id"] not in self.queued][:count]
            n = self._enqueue([{"id": i, "variant": 0} for i in ids])
        self._say(f"queued next {n} pending products")
        return n

    def queue_range(self, start, count, overwrite):
        with self.lock:
            sel = self.products[max(start, 0):max(start, 0) + count]
            ids = [p["id"] for p in sel if (overwrite or p["id"] not in self.done) and p["id"] not in self.queued]
            n = self._enqueue([{"id": i, "variant": 0, "overwrite": overwrite} for i in ids])
        self._say(f"queued {n} products from #{start} (count {count}{', overwrite' if overwrite else ''})")
        return n

    def queue_failed(self):
        with self.lock:
            ids = [i for i in self.failed if i in self.index and i not in self.queued]
            n = self._enqueue([{"id": i, "variant": 0} for i in ids])
        self._say(f"queued {n} failed products for retry")
        return n

    def redo(self, pid):
        self._require(pid)
        self._enqueue([{"id": pid, "variant": random.randint(1, 10**6), "overwrite": True}], front=True)
        self._say(f"regenerate (new seed): {self.products[self.index[pid]]['name']}")

    def generate_one(self, pid):
        self._require(pid)
        self._enqueue([{"id": pid, "variant": 0, "overwrite": True}], front=True)

    def preview(self, pid):
        self._require(pid)
        self._enqueue([{"id": pid, "variant": random.randint(1, 10**6), "preview": True}], front=True)
        self._say(f"preview queued: {self.products[self.index[pid]]['name']}")

    def _require(self, pid):
        if pid not in self.index:
            raise ValueError("unknown product id")

    def pause(self):
        self.run.clear()
        self._say("paused")

    def resume(self):
        self.run.set()
        self.fail_streak = 0
        self._say("resumed")

    def skip(self):
        with self.lock:
            if self.current:
                self.abort = "skip"

    def stop(self):
        with self.lock:
            self.queue.clear()
            self.queued.clear()
            if self.current:
                self.abort = "stop"
            self.run.set()
        self._say("stopped: queue cleared")

    def clear_queue(self):
        with self.lock:
            n = len(self.queue)
            self.queue.clear()
            self.queued.clear()
        self._say(f"cleared {n} queued items")

    def unload(self):
        with self.lock:
            if self.current or self.queue:
                raise Busy("stop the queue before unloading the model")
            self.pipe = None
        gc.collect()
        import torch
        torch.cuda.empty_cache()
        self._say("model unloaded, GPU memory released")

    def update_settings(self, new: dict):
        s = dict(self.settings)
        for k in ("steps", "quality", "size"):
            if k in new:
                s[k] = int(new[k])
        if "guidance" in new:
            s["guidance"] = round(float(new["guidance"]), 2)
        for k in ("prompt", "negative"):
            if k in new:
                s[k] = str(new[k]).strip()[:2000]
        if not 4 <= s["steps"] <= 60:
            raise ValueError("steps must be 4-60")
        if not 1.0 <= s["guidance"] <= 15.0:
            raise ValueError("guidance must be 1-15")
        if s["size"] not in SIZES:
            raise ValueError(f"size must be one of {SIZES}")
        if not 60 <= s["quality"] <= 100:
            raise ValueError("jpeg quality must be 60-100")
        if not s["prompt"]:
            raise ValueError("prompt is empty")
        validate_template(s["prompt"])
        with self.lock:
            self.settings = s
            _write_json(SETTINGS_FILE, s)
        self._say(f"settings saved: {s['steps']} steps, guidance {s['guidance']}, {s['size']}px (applies from the next image)")

    def reset_settings(self):
        with self.lock:
            self.settings = dict(DEFAULTS)
            _write_json(SETTINGS_FILE, self.settings)
        self._say("settings reset to defaults")

    # ---- worker --------------------------------------------------------------------------------
    def _load(self):
        self._say("loading SDXL onto the GPU (about 30-60 s the first time)...")
        import torch
        from diffusers import AutoencoderKL, DPMSolverMultistepScheduler, StableDiffusionXLPipeline
        vae = AutoencoderKL.from_pretrained("madebyollin/sdxl-vae-fp16-fix", torch_dtype=torch.float16)
        pipe = StableDiffusionXLPipeline.from_pretrained("stabilityai/stable-diffusion-xl-base-1.0", vae=vae,
                                                         torch_dtype=torch.float16, variant="fp16", use_safetensors=True)
        pipe.scheduler = DPMSolverMultistepScheduler.from_config(pipe.scheduler.config, use_karras_sigmas=True)
        pipe.enable_model_cpu_offload()
        pipe.enable_vae_slicing()
        pipe.set_progress_bar_config(disable=True)
        self.pipe = pipe
        self._say("model ready on " + torch.cuda.get_device_name(0))

    def _worker(self):
        while True:
            self.run.wait()
            with self.lock:
                self.abort = None
                if not self.queue:
                    self.state, self.current, self.thread = "idle", None, None
                    return
                task = self.queue.popleft()
                self.queued.discard(task["id"])
                p = self.products[self.index[task["id"]]]
                self.current = {"id": p["id"], "idx": self.index[p["id"]], "name": p["name"], "step": 0,
                                "steps": self.settings["steps"], "started": time.time(), "preview": bool(task.get("preview"))}
                self.state = "loading" if self.pipe is None else "running"
            if self.pipe is None:
                try:
                    self._load()
                except Exception as e:
                    self._say(f"model load failed: {e}")
                    traceback.print_exc()
                    with self.lock:
                        self.queue.clear()
                        self.queued.clear()
                        self.state, self.current, self.thread = "idle", None, None
                    return
                with self.lock:
                    self.state = "running"
                    self.current["started"] = time.time()
            self._generate(task, p)

    def _generate(self, task, p):
        import torch
        s = dict(self.settings)
        cur = self.current
        cur["steps"] = s["steps"]
        pid = p["id"]
        seed = seed_for(pid, task.get("variant", 0))
        prompt = render_prompt(s["prompt"], p)
        paused = [0.0]

        def on_step(pipe, step, timestep, kw):
            cur["step"] = step + 1
            if not self.run.is_set():
                t = time.time()
                while not self.run.wait(0.2) and not self.abort:
                    pass
                paused[0] += time.time() - t
            if self.abort:
                pipe._interrupt = True
            return kw

        t0 = time.time()
        try:
            img = self.pipe(prompt=prompt, negative_prompt=s["negative"], num_inference_steps=s["steps"],
                            guidance_scale=s["guidance"], width=s["size"], height=s["size"],
                            generator=torch.Generator("cpu").manual_seed(seed), callback_on_step_end=on_step).images[0]
        except Exception as e:
            msg = f"{type(e).__name__}: {e}"[:300]
            torch.cuda.empty_cache()
            with self.lock:
                self.failed[pid] = {"error": msg, "at": time.strftime("%Y-%m-%d %H:%M:%S")}
                _write_json(FAILED_FILE, self.failed)
                self.fail_streak += 1
            self._say(f"FAILED {p['name']}: {msg}")
            if self.fail_streak >= 3:
                self.run.clear()
                self._say("paused after 3 failures in a row - check the log, then Resume")
            return
        if self.abort:
            self._say(("skipped " if self.abort == "skip" else "stopped during ") + p["name"])
            return
        took = time.time() - t0 - paused[0]
        if task.get("preview"):
            name = f"{pid}-{int(time.time() * 1000)}.jpg"
            img.save(PREVIEW / name, quality=s["quality"])
            self.last_preview = {"file": name, "id": pid, "name": p["name"], "prompt": prompt, "seed": seed,
                                 "steps": s["steps"], "size": s["size"], "seconds": round(took, 1)}
            self._say(f"preview ready: {p['name']} ({took:.1f}s)")
        else:
            tmp = OUT / f"{pid}.tmp.jpg"
            img.save(tmp, quality=s["quality"])
            tmp.replace(OUT / f"{pid}.jpg")
            rec = {"product_id": pid, "name": p["name"], "prompt": prompt, "negative": s["negative"], "seed": seed,
                   "steps": s["steps"], "guidance": s["guidance"], "size": s["size"], "file": f"{pid}.jpg",
                   "seconds": round(took, 1), "created_at": time.strftime("%Y-%m-%dT%H:%M:%S")}
            with self.lock:
                with (OUT / "manifest.jsonl").open("a", encoding="utf-8") as m:
                    m.write(json.dumps(rec) + "\n")
                self.done.add(pid)
                if self.failed.pop(pid, None) is not None:
                    _write_json(FAILED_FILE, self.failed)
                if pid in self.recent:
                    self.recent.remove(pid)
                self.recent.append(pid)
                self.session_done += 1
            self._say(f"done #{self.index[pid]} {p['name']} ({took:.1f}s)")
            self._write_to_db(pid)
            p["seed"] = seed
            self._save_to_static(pid, OUT / f"{pid}.jpg", p, s)
            with self.lock:
                self._save_db_state()
        self.times.append(took)
        self.fail_streak = 0

    # ---- read side -----------------------------------------------------------------------------
    def _gpu(self):
        t, cached = self._gpu_cache
        if time.time() - t < 2.5:
            return cached
        info = None
        smi = shutil.which("nvidia-smi")
        if smi:
            try:
                out = subprocess.run([smi, "--query-gpu=name,memory.used,memory.total,utilization.gpu,temperature.gpu",
                                      "--format=csv,noheader,nounits"], capture_output=True, text=True, timeout=3,
                                     creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0)).stdout.strip().splitlines()[0]
                name, used, total, util, temp = [x.strip() for x in out.split(",")]
                info = {"name": name, "used_gb": round(int(used) / 1024, 1), "total_gb": round(int(total) / 1024, 1),
                        "util": int(util), "temp": int(temp)}
            except Exception:
                info = None
        self._gpu_cache = (time.time(), info)
        return info

    def _status_of(self, pid):
        if self.current and self.current["id"] == pid and not self.current["preview"]:
            return "current"
        if pid in self.done:
            return "done"
        if pid in self.queued:
            return "queued"
        if pid in self.failed:
            return "failed"
        return "pending"

    def _ver(self, pid):
        try:
            return int((OUT / f"{pid}.jpg").stat().st_mtime)
        except OSError:
            return 0

    def status(self):
        try:
            import psutil
            vm = psutil.virtual_memory()
            ram = {"used_gb": round((vm.total - vm.available) / 2**30, 1), "total_gb": round(vm.total / 2**30, 1)}
        except Exception:
            ram = None
        with self.lock:
            avg = sum(self.times) / len(self.times) if self.times else None
            todo = len(self.queue) + (1 if self.current else 0)
            done = sum(1 for i in self.done if i in self.index)
            cur = dict(self.current) if self.current else None
            if cur:
                cur["elapsed"] = round(time.time() - cur.pop("started"), 1)
            paused = not self.run.is_set()
            return {
                "state": ("paused" if paused and (cur or self.queue) else self.state),
                "model_loaded": self.pipe is not None,
                "current": cur,
                "queue": len(self.queue),
                "total": len(self.products), "done": done, "failed": len(self.failed),
                "pending": len(self.products) - done,
                "session_done": self.session_done,
                "avg_sec": round(avg, 1) if avg else None,
                "eta_sec": int(avg * todo) if avg else None,
                "settings": self.settings,
                "recent": [{"id": i, "name": self.products[self.index[i]]["name"], "v": self._ver(i)} for i in reversed(self.recent)],
                "preview": self.last_preview,
                "db_written": len(self.db_written), "db_failed": len(self.db_failed),
                "log": list(self.log)[-40:],
                "gpu": self._gpu(), "ram": ram,
            }

    def list_products(self, flt, q, offset, limit):
        q = q.lower().strip()
        with self.lock:
            rows = []
            for i, p in enumerate(self.products):
                st = self._status_of(p["id"])
                if flt != "all" and st != flt and not (flt == "queued" and st == "current"):
                    continue
                if q and q not in p["name"].lower() and q not in (p.get("category") or "").lower():
                    continue
                rows.append((i, p, st))
            page = rows[offset:offset + limit]
            return {"matches": len(rows), "rows": [
                {"idx": i, "id": p["id"], "name": p["name"], "category": p.get("category"), "color": p.get("color"),
                 "status": st, "v": self._ver(p["id"]) if st == "done" else 0,
                 "error": self.failed.get(p["id"], {}).get("error")} for i, p, st in page]}

    # ---- static / S3 uploads -------------------------------------------------------------------
    def get_static_status(self):
        """Get status of static folder and S3 uploads."""
        static_count = len(list(STATIC_IMAGES.glob("*.jpg"))) if STATIC_IMAGES.exists() else 0
        with self.lock:
            return {
                "static_images": static_count,
                "s3_uploaded": len(self.s3_uploaded),
                "s3_failed": len(self.s3_failed),
                "s3_pending": static_count - len(self.s3_uploaded) - len(self.s3_failed),
            }

    def list_static_images(self, offset=0, limit=50):
        """List images in static folder with metadata."""
        rows = []
        if STATIC_META.exists():
            meta_files = sorted(STATIC_META.glob("*.json"))
            for meta_file in meta_files[offset:offset+limit]:
                try:
                    meta = json.loads(meta_file.read_text(encoding="utf-8"))
                    pid = meta.get("product_id")
                    meta["status"] = "uploaded" if pid in self.s3_uploaded else ("failed" if pid in self.s3_failed else "pending")
                    rows.append(meta)
                except (OSError, ValueError):
                    pass
        return {
            "total": len(list(STATIC_META.glob("*.json"))) if STATIC_META.exists() else 0,
            "rows": rows
        }

    def upload_to_s3(self, s3_bucket: str, s3_prefix: str = "products/", aws_access_key: str = None, aws_secret_key: str = None, region: str = "us-east-1"):
        """Upload all pending images to S3."""
        try:
            import boto3
        except ImportError:
            return {"error": "boto3 not installed - run: pip install boto3"}

        if not aws_access_key or not aws_secret_key:
            aws_access_key = os.environ.get("AWS_ACCESS_KEY_ID")
            aws_secret_key = os.environ.get("AWS_SECRET_ACCESS_KEY")
            region = os.environ.get("AWS_DEFAULT_REGION", region)

        if not aws_access_key or not aws_secret_key:
            return {"error": "AWS credentials not provided and not in environment"}

        try:
            s3 = boto3.client("s3",
                            aws_access_key_id=aws_access_key,
                            aws_secret_access_key=aws_secret_key,
                            region_name=region)
            uploaded = 0
            failed = 0

            if STATIC_META.exists():
                for meta_file in STATIC_META.glob("*.json"):
                    try:
                        meta = json.loads(meta_file.read_text(encoding="utf-8"))
                        pid = meta.get("product_id")
                        img_file = STATIC_IMAGES / f"{pid}.jpg"

                        if pid in self.s3_uploaded or not img_file.exists():
                            continue

                        s3_key = f"{s3_prefix}{pid}.jpg"
                        s3.upload_file(str(img_file), s3_bucket, s3_key, ExtraArgs={"ContentType": "image/jpeg"})

                        meta["uploaded"] = True
                        meta["s3_url"] = f"s3://{s3_bucket}/{s3_key}"
                        meta["s3_bucket"] = s3_bucket
                        meta["s3_key"] = s3_key
                        meta_file.write_text(json.dumps(meta, indent=1), encoding="utf-8")

                        with self.lock:
                            self.s3_uploaded.add(pid)
                            self.s3_failed.pop(pid, None)
                        uploaded += 1
                        self._say(f"uploaded to S3: {pid}")
                    except Exception as e:
                        with self.lock:
                            self.s3_failed[pid] = str(e)[:200]
                        self._say(f"S3 upload failed for {pid}: {e}")
                        failed += 1

            self._save_uploads_log(s3_bucket, s3_prefix)
            return {"uploaded": uploaded, "failed": failed, "s3_bucket": s3_bucket}
        except Exception as e:
            return {"error": f"S3 upload error: {str(e)[:200]}"}

    def _save_uploads_log(self, bucket: str, prefix: str):
        """Log upload details for reference."""
        with UPLOADS_FILE.open("a", encoding="utf-8") as f:
            rec = {"bucket": bucket, "prefix": prefix, "at": time.strftime("%Y-%m-%d %H:%M:%S")}
            f.write(json.dumps(rec) + "\n")

    # ---- database writes -----------------------------------------------------------------------
    def write_all_done(self):
        """Write all completed images to the database."""
        with self.lock:
            to_write = [pid for pid in self.done if pid not in self.db_written and pid not in self.db_failed]
        count = 0
        for pid in to_write:
            if self._write_to_db(pid):
                count += 1
        with self.lock:
            self._save_db_state()
        self._say(f"wrote {count} images to DB")
        return {"written": count}

    def retry_failed_writes(self):
        """Retry failed database writes."""
        with self.lock:
            failed_ids = list(self.db_failed.keys())
        count = 0
        for pid in failed_ids:
            if self._write_to_db(pid):
                count += 1
        with self.lock:
            self._save_db_state()
        self._say(f"retried {len(failed_ids)} failed writes, succeeded: {count}")
        return {"attempted": len(failed_ids), "succeeded": count}

    def get_db_status(self):
        """Get database write statistics."""
        with self.lock:
            return {
                "written": len(self.db_written),
                "failed": len(self.db_failed),
                "failed_list": list(self.db_failed.keys())[:10]
            }


class Handler(BaseHTTPRequestHandler):
    engine: Engine = None
    port = 0

    def log_message(self, *args):
        pass

    def _host_ok(self):
        host = (self.headers.get("Host") or "").split(":")[0]
        return host in ("127.0.0.1", "localhost")

    def _send(self, code, body: bytes, ctype, cache="no-store"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", cache)
        self.end_headers()
        self.wfile.write(body)

    def _json(self, obj, code=200):
        self._send(code, json.dumps(obj).encode(), "application/json")

    def _jpg(self, path: Path):
        try:
            self._send(200, path.read_bytes(), "image/jpeg", "max-age=86400")
        except OSError:
            self._send(404, b"not found", "text/plain")

    def do_GET(self):
        if not self._host_ok():
            return self._send(403, b"forbidden", "text/plain")
        u = urlparse(self.path)
        if u.path in ("/", "/index.html"):
            return self._send(200, UI.read_bytes(), "text/html; charset=utf-8")
        if u.path == "/api/status":
            return self._json(self.engine.status())
        if u.path == "/api/db/status":
            return self._json(self.engine.get_db_status())
        if u.path == "/api/static/status":
            return self._json(self.engine.get_static_status())
        if u.path == "/api/static/images":
            qs = parse_qs(u.query)
            g = lambda k, d: qs.get(k, [d])[0]
            try:
                offset, limit = max(int(g("offset", "0")), 0), min(max(int(g("limit", "50")), 1), 200)
            except ValueError:
                offset, limit = 0, 50
            return self._json(self.engine.list_static_images(offset, limit))
        if u.path == "/api/products":
            qs = parse_qs(u.query)
            g = lambda k, d: qs.get(k, [d])[0]
            flt = g("filter", "all")
            if flt not in ("all", "done", "pending", "failed", "queued"):
                flt = "all"
            try:
                offset, limit = max(int(g("offset", "0")), 0), min(max(int(g("limit", "48")), 1), 200)
            except ValueError:
                offset, limit = 0, 48
            return self._json(self.engine.list_products(flt, g("q", ""), offset, limit))
        m = re.fullmatch(r"/img/([0-9a-f-]{36})\.jpg", u.path)
        if m and UUID_RE.match(m.group(1)):
            return self._jpg(OUT / f"{m.group(1)}.jpg")
        m = re.fullmatch(r"/preview/([0-9a-f-]{36}-\d+\.jpg)", u.path)
        if m:
            return self._jpg(PREVIEW / m.group(1))
        self._send(404, b"not found", "text/plain")

    def do_POST(self):
        # JSON-only + host check: a page on another site cannot drive this server
        if not self._host_ok() or not (self.headers.get("Content-Type") or "").startswith("application/json"):
            return self._send(403, b"forbidden", "text/plain")
        n = int(self.headers.get("Content-Length") or 0)
        if n > 100_000:
            return self._send(413, b"too large", "text/plain")
        try:
            body = json.loads(self.rfile.read(n) or b"{}")
        except ValueError:
            return self._json({"error": "bad json"}, 400)
        E = self.engine
        routes = {
            "/api/queue/next": lambda: {"queued": E.queue_next(int(body.get("count", 100)))},
            "/api/queue/range": lambda: {"queued": E.queue_range(int(body.get("start", 0)), int(body.get("count", 100)), bool(body.get("overwrite")))},
            "/api/queue/failed": lambda: {"queued": E.queue_failed()},
            "/api/pause": E.pause, "/api/resume": E.resume, "/api/skip": E.skip, "/api/stop": E.stop,
            "/api/clear": E.clear_queue, "/api/unload": E.unload,
            "/api/settings": lambda: E.update_settings(body),
            "/api/settings/reset": E.reset_settings,
            "/api/redo": lambda: E.redo(str(body.get("id"))),
            "/api/generate": lambda: E.generate_one(str(body.get("id"))),
            "/api/preview": lambda: E.preview(str(body.get("id"))),
            "/api/db/write-all": E.write_all_done,
            "/api/db/retry-failed": E.retry_failed_writes,
            "/api/s3/upload": lambda: E.upload_to_s3(
                str(body.get("bucket", "")),
                str(body.get("prefix", "products/")),
                str(body.get("aws_access_key", "")),
                str(body.get("aws_secret_key", "")),
                str(body.get("region", "us-east-1"))
            ),
        }
        fn = routes.get(urlparse(self.path).path)
        if fn is None:
            return self._json({"error": "not found"}, 404)
        try:
            return self._json(fn() or {"ok": True})
        except Busy as e:
            return self._json({"error": str(e)}, 409)
        except (ValueError, TypeError, KeyError) as e:
            return self._json({"error": str(e)}, 400)


def main():
    ap = argparse.ArgumentParser(description="Local SDXL product-image generator with a browser control panel")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--no-browser", action="store_true")
    a = ap.parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if not PRODUCTS.exists():
        sys.exit(f"{PRODUCTS} not found - run tools/export_products.py once while the database is up")
    Handler.engine = Engine()
    srv = ThreadingHTTPServer(("127.0.0.1", a.port), Handler)
    url = f"http://127.0.0.1:{a.port}"
    e = Handler.engine
    print(f"{len(e.products)} products, {len(e.done)} images already on disk. Control panel: {url}  (Ctrl+C to quit)", flush=True)
    if not a.no_browser:
        threading.Timer(1.0, webbrowser.open, (url,)).start()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("shutting down", flush=True)


if __name__ == "__main__":
    main()
