"""Point products at their generated images (product-images/images/<product_id>.jpg).

  .venv-images\Scripts\python.exe tools\push_images.py [--dir product-images/images] [--limit N] [--upload]
--upload also PUTs each file to static-image-server (needs IMAGE_SERVER_TOKEN); without it the server's
own startup copy of the folder is relied on. DB: SDAI_PG_* (default: the scratch DB). Idempotent.
"""
import argparse
import os
import sys
from pathlib import Path

import psycopg

sys.path.insert(0, str(Path(__file__).parent))
import image_store

DB_CONFIG = {
    "host": os.environ.get("SDAI_PG_HOST", "127.0.0.1"),
    "port": int(os.environ.get("SDAI_PG_PORT", "55432")),
    "dbname": os.environ.get("SDAI_PG_DB", "product_db"),
    "user": os.environ.get("SDAI_PG_USER", "postgres"),
    "password": os.environ.get("SDAI_PG_PASSWORD", "sdai_scratch"),
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default="product-images/images")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--upload", action="store_true")
    a = ap.parse_args()
    if a.upload and not image_store.TOKEN:
        sys.exit("--upload needs IMAGE_SERVER_TOKEN")
    files = sorted(Path(a.dir).glob("*.jpg"))[: a.limit or None]
    ok = skipped = bad = 0
    with psycopg.connect(**DB_CONFIG) as conn:
        for f in files:
            try:
                with conn.cursor() as cur:
                    cur.execute("SELECT 1 FROM product.products WHERE id=%s", (f.stem,))
                    if cur.fetchone() is None:
                        skipped += 1
                        continue
                    image_store.link(cur, f.stem, f.read_bytes() if a.upload else None)
                conn.commit()
                ok += 1
            except Exception as e:  # keep going; report at the end
                conn.rollback()
                bad += 1
                print(f"FAILED {f.name}: {e}")
    print(f"linked {ok}, no such product {skipped}, failed {bad}")


if __name__ == "__main__":
    main()
