"""One-time, read-only export of products to output/products.jsonl so image generation runs without Docker/Postgres.

    python tools/export_products.py --dsn postgresql://postgres:sdai_scratch@127.0.0.1:55432/product_db
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

import psycopg

SQL = """
select p.id::text, p.name, p.brand, p.color, p.material, c.name
from product.products p
left join product.categories c on c.id = p.category_id
where p.sku ~ '^SKU-[A-Z]{3}-[0-9A-Z]{9}$'
order by p.created_at, p.id
"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dsn", default=os.environ.get("SDAI_PRODUCT_DSN", "postgresql://postgres:sdai_scratch@127.0.0.1:55432/product_db"))
    ap.add_argument("--out", default="output/products.jsonl")
    a = ap.parse_args()
    out = Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with psycopg.connect(a.dsn, autocommit=True, connect_timeout=10) as c:
        c.execute("SET default_transaction_read_only = on")
        rows = c.execute(SQL).fetchall()
    tmp = out.with_suffix(".tmp")
    with tmp.open("w", encoding="utf-8") as f:
        for pid, name, brand, color, material, category in rows:
            f.write(json.dumps({"id": pid, "name": name, "brand": brand, "color": color, "material": material, "category": category}) + "\n")
    tmp.replace(out)
    print(f"exported {len(rows)} products to {out}")


if __name__ == "__main__":
    main()
