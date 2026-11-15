"""Sizing: how many customers produce the requested database size?

The plan is *measured*, not guessed: a small world (1,500 customers) is generated in memory through the exact same
generators and the real schema catalog; every table's row count and per-column byte lengths are recorded, converted to
an on-disk estimate (heap tuple + every index from the catalog), and the customer count is solved so that the total
matches the target.  Everything scales linearly with customers (products = 0.3 x, sellers = 1 % x).
"""
from __future__ import annotations

import re
import time
import uuid
from pathlib import Path

import numpy as np

from . import world as W
from .catalog import Catalog, Table
from .config import Settings
from .emitter import Emitter
from .sizes import fmt_size
from .vocab import load_vocab

CAL_USERS = 1500
MIN_USERS = 600


def load_catalog(settings: Settings) -> Catalog:
    p = settings.inventory_dir / "schema_inventory.json"
    if not p.exists():
        raise SystemExit("inventory/schema_inventory.json not found - run `python main.py inspect` first.")
    return Catalog.load(p)


def _col_width(typ: str, frac: float, mean_len: float) -> float:
    t = typ.lower()
    if t == "uuid": w = 16
    elif t.startswith("timestamp"): w = 8
    elif t == "date": w = 4
    elif t in ("smallint",): w = 2
    elif t in ("integer", "int", "serial"): w = 4
    elif t in ("bigint", "bigserial"): w = 8
    elif t == "boolean": w = 1
    elif t.startswith("numeric"): w = 8 + mean_len / 4
    elif t.endswith("[]"): w = mean_len + 8
    elif t == "inet": w = 8
    elif t == "bytea": w = mean_len + 4
    elif t == "jsonb": w = mean_len + 4
    elif t == "tsvector": w = mean_len * 0.5
    else: w = mean_len + (1 if mean_len < 127 else 4)         # text / varchar / char
    return frac * w


def disk_row_bytes(tb: Table, colstats: dict | None, rows: int) -> tuple[float, float]:
    """(heap bytes per row, index bytes per row) for a table, from measured per-column lengths."""
    widths = {}
    for c in tb.columns:
        if colstats and c.name in colstats["cols"] and colstats["rows"]:
            nn, tot = colstats["cols"][c.name]
            frac = nn / colstats["rows"]
            mean = tot / nn if nn else 0
        else:                                              # not emitted (generated/defaulted): a small default
            frac, mean = (1.0, 8.0) if c.generated else (0.0, 0.0)
        widths[c.name] = _col_width(c.type, frac, mean)
        if c.generated and c.type.lower() == "tsvector" and colstats and "description" in colstats["cols"]:
            nn, tot = colstats["cols"]["description"]
            widths[c.name] = 0.35 * (tot / max(nn, 1))
    heap = 24 + 4 + sum(widths.values()) * 1.08 + 4
    idx = 0.0
    key_cols = [tb.pk] if tb.pk else []
    for ix in tb.indexes:
        m = re.search(r"\((.*)\)", ix["def_"])
        cols = [x.strip().strip('"') for x in (m.group(1).split(",") if m else [])]
        key_cols.append([c.split()[0] for c in cols])
        if "gin" in ix["def_"].lower():
            idx += heap * 0.15
            key_cols.pop()
    for kc in key_cols:
        w = sum(widths.get(c, 24) for c in kc)
        idx += (w + 12) * 1.12
    return heap, idx


def calibrate(settings: Settings, cat: Catalog, seed: int, as_of: int) -> dict:
    from .engine import run_stage_chunk
    voc = load_vocab(settings.cache_dir, seed, (settings.cache_dir / "llm_pools.json") if settings.llm_provider != "none" else None)
    w = W.World(seed=seed, as_of=as_of, U=CAL_USERS, orders_mean=settings.orders_per_user)
    stats: dict[str, dict] = {}
    text_bytes = 0
    dbs: dict[str, set] = {"static": set(), "customer": set(), "catalog": set()}
    t0 = time.time()
    jobs = [("static", 0, 0, 0)] + [("customer", i, lo, min(lo + 500, w.U)) for i, lo in enumerate(range(0, w.U, 500))] + [("catalog", 0, 0, w.P)]
    agg: dict = {}
    for stage, i, lo, hi in jobs:
        em, ctx = run_stage_chunk(stage, i, lo, hi, w, voc, cat, settings, {"product_views": 1, "chat_messages": 1}, agg, dry=True, collect=True)
        for k, v in em.colstats.items():
            s = stats.setdefault(k, {"rows": 0, "cols": {c: [0, 0] for c in v["cols"]}})
            s["rows"] += v["rows"]
            for c, (nn, tot) in v["cols"].items():
                s["cols"].setdefault(c, [0, 0])
                s["cols"][c][0] += nn
                s["cols"][c][1] += tot
        text_bytes += sum(em.bytes.values())
        dbs[stage] |= em.written_dbs()
    per_table = {}
    for key, s in stats.items():
        db, fq = key.split(":", 1)
        tb = cat.tables[(db, fq)]
        heap, idx = disk_row_bytes(tb, s, s["rows"])
        per_table[key] = {"rows": s["rows"], "heap_row": heap, "idx_row": idx * settings.index_overhead}
    return {"users": CAL_USERS, "per_table": per_table, "text_bytes": text_bytes, "stage_dbs": {k: sorted(v) for k, v in dbs.items()}, "secs": time.time() - t0}


def make_plan(settings: Settings, size_bytes: int, users: int | None = None, catalog: Catalog | None = None) -> dict:
    cat = catalog or load_catalog(settings)
    seed = settings.seed
    if settings.as_of:
        as_of = int(time.mktime(time.strptime(settings.as_of, "%Y-%m-%d")))
    else:
        as_of = int(time.time()) // 86400 * 86400
    cal = calibrate(settings, cat, seed, as_of)
    disk_per_user = sum(t["rows"] * (t["heap_row"] + t["idx_row"]) for t in cal["per_table"].values()) / cal["users"]
    text_per_user = cal["text_bytes"] / cal["users"]
    U = users if users else max(MIN_USERS, int(size_bytes / disk_per_user))
    if users:
        size_bytes = int(U * disk_per_user)
    scale = U / cal["users"]
    per_table = {k: {"rows": int(v["rows"] * scale), "est_bytes": int(v["rows"] * scale * (v["heap_row"] + v["idx_row"]))} for k, v in cal["per_table"].items()}
    # memory per worker ~ 6x the chunk's COPY text; keep chunks around 150 MB of text
    chunk_users = int(np.clip(80e6 / max(text_per_user, 1), 150, 3000))    # ~80 MB of COPY text per chunk keeps a worker near 100-200 MB
    chunk_users = min(chunk_users, max(50, U // 8 or 50))
    P = W.World(seed=seed, as_of=as_of, U=U).P
    plan = dict(
        run_id=uuid.uuid4().hex[:8], created=time.strftime("%Y-%m-%d %H:%M:%S"), target_bytes=int(size_bytes),
        world=W.World(seed=seed, as_of=as_of, U=U, orders_mean=settings.orders_per_user).dict(), chunk_users=chunk_users, chunk_products=5000,
        est_disk_bytes_total=int(disk_per_user * U), est_text_bytes_total=int(text_per_user * U), disk_bytes_per_user=disk_per_user,
        per_table=per_table, stage_dbs={**cal["stage_dbs"], "all": sorted(set().union(*map(set, cal["stage_dbs"].values())))}, calibration_secs=round(cal["secs"], 1))
    return plan


def print_plan(plan: dict) -> None:
    w = plan["world"]
    print(f"\nGeneration plan {plan['run_id']}  (seed {w['seed']}, as-of {time.strftime('%Y-%m-%d', time.gmtime(w['as_of']))})")
    print(f"  target size            {fmt_size(plan['target_bytes'])}")
    print(f"  estimated on-disk size {fmt_size(plan['est_disk_bytes_total'])}  (heap + indexes, measured from the real schema)")
    print(f"  COPY text to stream    {fmt_size(plan['est_text_bytes_total'])}")
    print(f"  customers {w['U']:,} | products {w['P']:,} | sellers {w['S']:,} | coupons {w['n_coupons']} | deals {w['n_deals']:,}")
    n_c = -(-w["U"] // plan["chunk_users"])
    n_p = -(-w["P"] // plan["chunk_products"])
    print(f"  chunks                 {n_c} customer chunks of {plan['chunk_users']:,} users, {n_p} catalog chunks of {plan['chunk_products']:,} products")
    print("  order: static -> customer chunks (parallel) -> catalog chunks (parallel) -> finalize\n")
    by_db: dict[str, list] = {}
    for k, v in plan["per_table"].items():
        db, fq = k.split(":", 1)
        by_db.setdefault(db, []).append((fq, v))
    print(f"  {'table':<44}{'rows':>14}{'est. size':>12}")
    for db in sorted(by_db):
        tot = sum(v["est_bytes"] for _, v in by_db[db])
        print(f"  {db}  ({fmt_size(tot)})")
        for fq, v in sorted(by_db[db], key=lambda x: -x[1]["est_bytes"]):
            print(f"    {fq:<42}{v['rows']:>14,}{fmt_size(v['est_bytes']):>12}")
