"""Bulk-push output/images/*.jpg to the image server and rewrite the DB URLs.

  set IMAGE_SERVER_TOKEN=<token from deploy/local/.env>   (and SDAI_PG_* if not the scratch DB)
  .venv-images\Scripts\python.exe tools\push_images.py [--dir output/images] [--limit N]
Idempotent: a product already pointing at the image server is overwritten in place.
"""
import argparse
import sys
from pathlib import Path

import psycopg

sys.path.insert(0, str(Path(__file__).parent))
import image_store
import os
DB_CONFIG = {
    "host": os.environ.get("SDAI_PG_HOST", "127.0.0.1"),
    "port": int(os.environ.get("SDAI_PG_PORT", "55432")),
    "dbname": "product_db",
    "user": os.environ.get("SDAI_PG_USER", "postgres"),
    "password": os.environ.get("SDAI_PG_PASSWORD", "sdai_scratch"),
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default="output/images")
    ap.add_argument("--limit", type=int, default=0)
    a = ap.parse_args()
    if not image_store.TOKEN:
        sys.exit("IMAGE_SERVER_TOKEN is not set")
    files = sorted(Path(a.dir).glob("*.jpg"))
    if a.limit:
        files = files[: a.limit]
    ok = bad = 0
    with psycopg.connect(**DB_CONFIG) as conn:
        for f in files:
            try:
                with conn.cursor() as cur:
                    image_store.push(cur, f.stem, f.read_bytes())
                conn.commit()
                ok += 1
            except Exception as e:  # keep going; report at the end
                conn.rollback()
                bad += 1
                print(f"FAILED {f.name}: {e}")
    print(f"pushed {ok}, failed {bad}")


if __name__ == "__main__":
    main()
