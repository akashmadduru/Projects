"""Orchestration: stages, parallel chunk workers, resumable state, progress, finalize.

Stages (each stage finishes before the next starts):
  static    reference rows other tables point at (categories, coupons, deals, sellers, warehouses, FAQ, campaigns)
  customer  chunks of users -> every row that hangs off those users, across all 12 service databases
  catalog   chunks of products -> products/offers/variants/images + inventory, using exact tallies from `customer`
  finalize  small key-restricted updates of denormalised aggregates, sequences, ANALYZE
Each (stage, chunk, database) is committed in its own transaction and recorded in a SQLite state file, so an
interrupted run resumes without redoing or duplicating finished work (and a crash between COMMIT and the state
write is detected by probing for the chunk's first primary key).
"""
from __future__ import annotations

import json
import multiprocessing as mp
import os
import sqlite3
import sys
import time
import traceback
from concurrent.futures import ProcessPoolExecutor, as_completed
from pathlib import Path

import numpy as np

from . import world as W
from .catalog import Catalog
from .config import Settings, available_memory
from .db import Target, target
from .emitter import Emitter
from .gen_util import Ctx
from .sizes import fmt_size
from .vocab import load_vocab

STAGE_CODE = {"static": 1, "customer": 2, "catalog": 3}


# ---------------------------------------------------------------------------------------------- state
class State:
    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.c = sqlite3.connect(path, timeout=30)
        self.c.execute("create table if not exists parts(stage text, chunk int, db text, rows int, bytes int, ts real, primary key(stage, chunk, db))")
        self.c.execute("create table if not exists meta(k text primary key, v text)")
        self.c.commit()

    def done_dbs(self, stage, chunk) -> set[str]:
        return {r[0] for r in self.c.execute("select db from parts where stage=? and chunk=?", (stage, chunk))}

    def mark(self, stage, chunk, db, rows, nbytes):
        self.c.execute("insert or replace into parts values (?,?,?,?,?,?)", (stage, chunk, db, rows, nbytes, time.time()))
        self.c.commit()

    def totals(self):
        return self.c.execute("select coalesce(sum(rows),0), coalesce(sum(bytes),0) from parts").fetchone()

    def get(self, k, default=None):
        r = self.c.execute("select v from meta where k=?", (k,)).fetchone()
        return r[0] if r else default

    def put(self, k, v):
        self.c.execute("insert or replace into meta values (?,?)", (k, v))
        self.c.commit()


# ---------------------------------------------------------------------------------------------- worker side
_G: dict = {}


def _init_worker(settings: Settings, wdict: dict, inv_path: str):
    from .catalog import Catalog
    _G["settings"] = settings
    _G["w"] = W.World(**wdict)
    _G["cat"] = Catalog.load(Path(inv_path))
    llm = settings.cache_dir / "llm_pools.json"
    _G["voc"] = load_vocab(settings.cache_dir, settings.seed, llm if settings.llm_provider != "none" else None)


def make_ctx(w, voc, stage, chunk, serial_base, agg=None) -> Ctx:
    rng = np.random.default_rng([w.seed & 0x7FFFFFFF, STAGE_CODE[stage], chunk])
    return Ctx(w=w, voc=voc, rng=rng, chunk=chunk, serial_base=serial_base, agg=agg or {})


def run_stage_chunk(stage, chunk, lo, hi, w, voc, catalog, settings, serial_base, agg, skip_dbs=(), dry=False, collect=False):
    """Generate one chunk and (unless dry) load it.  Returns (emitter, ctx)."""
    from .gen_catalog import catalog_chunk
    from .gen_customer import customer_chunk
    from .gen_static import emit_static
    ctx = make_ctx(w, voc, stage, chunk, serial_base, agg)
    em = Emitter(settings, catalog, dry=dry, skip_dbs=skip_dbs, collect_stats=collect)
    try:
        if stage == "static":
            emit_static(ctx, em)
        elif stage == "customer":
            customer_chunk(ctx, lo, hi, em)
        else:
            catalog_chunk(ctx, lo, hi, em)
        if not dry:
            em.commit()
    except BaseException:
        em.rollback()
        raise
    finally:
        if not dry:
            em.close()
    return em, ctx


def run_task(task: dict) -> dict:
    t0 = time.time()
    st, chunk = task["stage"], task["chunk"]
    agg = {}
    if st == "catalog":
        with np.load(task["agg_path"]) as z:
            agg = {k: z[k] for k in z.files}
    em, ctx = run_stage_chunk(st, chunk, task["lo"], task["hi"], _G["w"], _G["voc"], _G["cat"], _G["settings"], task["serial_base"], agg, task["skip"], dry=task.get("dry", False))
    if st == "customer" and ctx.tally:
        tdir = Path(task["tally_dir"])
        tdir.mkdir(parents=True, exist_ok=True)
        tmp = tdir / f"customer_{chunk}.tmp.npz"
        np.savez(tmp, **{k: v.astype(np.int32) if v.max(initial=0) < 2 ** 31 else v for k, v in ctx.tally.items()})
        os.replace(tmp, tdir / f"customer_{chunk}.npz")
    per_db: dict[str, list[int]] = {}
    for k, r in em.rows.items():
        d = k.split(":", 1)[0]
        per_db.setdefault(d, [0, 0])
        per_db[d][0] += r
        per_db[d][1] += em.bytes[k]
    return {"stage": st, "chunk": chunk, "per_db": per_db, "present": sorted(em.already_present), "secs": time.time() - t0, "tables": em.rows}


# ---------------------------------------------------------------------------------------------- driver
def _fmt_eta(s: float) -> str:
    s = int(s)
    return f"{s // 3600}h{(s % 3600) // 60:02d}m" if s >= 3600 else f"{s // 60}m{s % 60:02d}s"


def _serial_bases(settings: Settings, cat: Catalog) -> dict:
    """First free value of each bigserial PK (we write explicit ids; the sequence is advanced in finalize)."""
    out = {}
    for db, name in (("product_db", "product_views"), ("support_db", "chat_messages")):
        tb = cat.find(db, name)
        try:
            with target(settings, db) as t:
                r = t.q(f"SELECT coalesce(max(id),0)+1 FROM {tb.fq}")
            out[name] = int(r[0][0])
        except Exception:
            out[name] = 1
    return out


def _merge_agg(tally_dir: Path, out: Path, w: W.World):
    agg: dict[str, np.ndarray] = {}
    for f in sorted(tally_dir.glob("customer_*.npz")):
        if ".tmp" in f.name:
            continue
        with np.load(f) as z:
            for k in z.files:
                a = z[k].astype(np.int64)
                agg[k] = a if k not in agg else agg[k] + a
    np.savez(out, **agg)


def run_generate(settings: Settings, size_bytes: int, users: int | None = None, fresh: bool = False, dry_run: bool = False, analyze: bool = True, assume_yes: bool = False) -> int:
    from .planner import load_catalog, make_plan, print_plan
    cat = load_catalog(settings)
    sd = settings.state_dir
    plan_path = sd / "plan.json"
    new_plan = False
    sd.mkdir(parents=True, exist_ok=True)
    if plan_path.exists() and not fresh and not dry_run:
        plan = json.loads(plan_path.read_text())
        print(f"Resuming run {plan['run_id']} ({plan['world']['U']:,} customers, target {fmt_size(plan['target_bytes'])}); use --fresh to start a new run.")
        if abs(plan["target_bytes"] - size_bytes) > 0.02 * size_bytes and not users:
            print(f"  note: ignoring --size {fmt_size(size_bytes)}; the saved plan is authoritative (use --fresh to change it)")
    else:
        plan = make_plan(settings, size_bytes, users=users, catalog=cat)
        plan["serial_base"] = _serial_bases(settings, cat) if not dry_run else {"product_views": 1, "chat_messages": 1}
        print_plan(plan)
        new_plan = True
    w = W.World(**plan["world"])
    from .llm import build_pools
    build_pools(settings)                                   # optional; no-op unless --llm / llm.provider is set
    load_vocab(settings.cache_dir, settings.seed, (settings.cache_dir / "llm_pools.json") if settings.llm_provider != "none" else None)  # build cache once, before workers start
    if not dry_run and not _confirm(settings, cat, plan, assume_yes):
        return 2
    if new_plan and not dry_run:
        stamp = int(time.time())
        for f, nm in ((plan_path, f"plan.{stamp}.old.json"), (sd / "state.db", f"state.{stamp}.old.db")):
            if f.exists():                                  # keep the previous run's files; nothing is deleted
                f.rename(sd / nm)
        plan_path.write_text(json.dumps(plan, indent=1))
    state = State(sd / "state.db") if not dry_run else None
    mem_per_worker = int(80e6 + 100e3 * plan["chunk_users"])        # measured: ~50 MB base + ~50 KB per customer in the chunk, x2 headroom
    workers = settings.n_workers(mem_per_worker)
    print(f"workers: {workers} (cpu {os.cpu_count()}, ~{fmt_size(mem_per_worker)} each, {fmt_size(available_memory() or 0)} RAM free)")
    inv_path = str(settings.inventory_dir / "schema_inventory.json")
    ctx_mp = mp.get_context("spawn")
    tally_dir = sd / "tallies" / plan["run_id"]
    agg_path = sd / f"agg-{plan['run_id']}.npz"
    stages = [("static", [(0, 0, 0)]),
              ("customer", [(i, lo, min(lo + plan["chunk_users"], w.U)) for i, lo in enumerate(range(0, w.U, plan["chunk_users"]))]),
              ("catalog", [(i, lo, min(lo + plan["chunk_products"], w.P)) for i, lo in enumerate(range(0, w.P, plan["chunk_products"]))])]
    t_start = time.time()
    total_rows = total_bytes = 0
    expected_bytes = plan["est_text_bytes_total"]
    for stage, chunks in stages:
        if stage == "catalog":
            _merge_agg(tally_dir, agg_path, w)
        todo = []
        for (i, lo, hi) in chunks:
            done = state.done_dbs(stage, i) if state else set()
            need = {d for d in plan["stage_dbs"][stage]} - done
            lost_tally = stage == "customer" and bool(state) and not (tally_dir / f"customer_{i}.npz").exists()
            if lost_tally and not need:
                done = set(plan["stage_dbs"][stage])         # chunk is loaded; regenerate (deterministically) only to rebuild its tally
            if need or lost_tally or dry_run:
                todo.append((i, lo, hi, done))
        print(f"\n== stage {stage}: {len(todo)}/{len(chunks)} chunks to run on {min(workers, max(1, len(todo)))} worker(s)")
        if not todo:
            continue
        failures = []
        last_print = 0.0
        pool_n = 1 if stage == "static" else min(workers, len(todo))
        with ProcessPoolExecutor(max_workers=pool_n, mp_context=ctx_mp, initializer=_init_worker, initargs=(settings, plan["world"], inv_path)) as ex:
            futs = {}
            for (i, lo, hi, done) in todo:
                task = dict(stage=stage, chunk=i, lo=lo, hi=hi, skip=sorted(done), serial_base=plan["serial_base"], tally_dir=str(tally_dir), agg_path=str(agg_path))
                task["dry"] = dry_run                                 # dry-run: generate + encode, never connect
                futs[ex.submit(run_task, task)] = (i, lo, hi, done)
            n_done = 0
            for f in as_completed(futs):
                i, lo, hi, done = futs[f]
                try:
                    r = f.result()
                except Exception as e:  # keep going; report at the end, resume will retry only the failed chunks
                    failures.append((stage, i, repr(e)))
                    print(f"  !! {stage} chunk {i} failed: {e}", file=sys.stderr)
                    traceback.print_exc()
                    continue
                n_done += 1
                for db, (rows, nb) in r["per_db"].items():
                    total_rows += rows
                    total_bytes += nb
                if state:
                    for db in plan["stage_dbs"][stage]:
                        rows, nb = r["per_db"].get(db, [0, 0])
                        state.mark(stage, i, db, rows, nb)
                now = time.time()
                if now - last_print > 2 or n_done == len(todo):
                    last_print = now
                    el = now - t_start
                    rate = total_bytes / max(el, 1e-9)
                    eta = (expected_bytes - total_bytes) / rate if rate > 0 and total_bytes < expected_bytes else 0
                    print(f"  [{stage}] {n_done}/{len(todo)} chunks | {total_rows:,} rows | {fmt_size(total_bytes)} | {fmt_size(rate)}/s | elapsed {_fmt_eta(el)} | ETA ~{_fmt_eta(eta)}", flush=True)
        if failures:
            print(f"\n{len(failures)} chunk(s) failed in stage {stage}; completed chunks are committed. Fix the cause and re-run `generate` to resume.", file=sys.stderr)
            return 1
    if not dry_run:
        _finalize(settings, cat, w, agg_path, plan, analyze)
        state.put("finished", str(time.time()))
    print(f"\nDone in {_fmt_eta(time.time() - t_start)}: {total_rows:,} rows, {fmt_size(total_bytes)} of COPY text.")
    return 0


def _finalize(settings: Settings, cat: Catalog, w: W.World, agg_path: Path, plan: dict, analyze: bool):
    print("\n== stage finalize")
    with np.load(agg_path) as z:
        agg = {k: z[k].astype(np.int64) for k in z.files}
    # 1. sellers.rating_avg/rating_count (set from the exact seller_feedback tally; only this run's seller ids)
    sf = agg.get("seller_fb")
    if sf is not None:
        s = np.flatnonzero(sf[: w.S, 1] > 0)
        ids = W.uid_s(w, "seller", s)
        rows = [(ids[k].decode(), f"{(int(sf[s[k], 0]) * 200 + int(sf[s[k], 1])) // (2 * int(sf[s[k], 1])) / 100:.2f}", int(sf[s[k], 1])) for k in range(s.size)]
        tb = cat.find("seller_db", "sellers")
        with target(settings, "seller_db") as t:
            t.conn.autocommit = False
            t.update_own_rows(tb.fq, "id", ["rating_avg", "rating_count"], rows)
            t.commit()
        print(f"  sellers: rating aggregates set on {len(rows)} sellers")
    dc = agg.get("deal_claimed")
    if dc is not None:
        d = np.flatnonzero(dc > 0)
        ids = W.uid_s(w, "deal", d)
        tb = cat.find("promotion_db", "deals")
        with target(settings, "promotion_db") as t:
            t.conn.autocommit = False
            t.update_own_rows(tb.fq, "id", ["claimed"], [(ids[k].decode(), int(dc[d[k]])) for k in range(d.size)])
            t.commit()
        print(f"  deals: claimed counters set on {d.size} deals")
    for db, name in (("product_db", "product_views"), ("support_db", "chat_messages")):
        tb = cat.find(db, name)
        with target(settings, db) as t:
            seq = t.q(f"SELECT pg_get_serial_sequence('{tb.fq}', 'id')")[0][0]
            if seq:
                t.q(f"SELECT setval('{seq}', (SELECT max(id) FROM {tb.fq}))")
        print(f"  {tb.fq}: sequence advanced")
    if analyze:
        for db in sorted(plan["stage_dbs"]["all"]):
            with target(settings, db) as t:
                for tb in cat.by_db(db):
                    t.exec_safe(f"ANALYZE {tb.fq}")
        print("  ANALYZE done")


def _confirm(settings: Settings, cat: Catalog, plan: dict, assume_yes: bool) -> bool:
    """Show exactly where data will be appended and make the human say so (append-only; nothing is ever deleted)."""
    from .safety import assert_safe_target
    dbs = plan["stage_dbs"]["all"]
    existing = 0
    for db in dbs:
        assert_safe_target(settings.host, db, settings.allowed_hosts)
        try:
            with target(settings, db) as t:
                existing += sum(int(t.q(f"select reltuples::bigint from pg_class where oid='{tb.fq}'::regclass")[0][0] > 0) for tb in cat.by_db(db))
        except Exception as e:
            print(f"Cannot reach {db} on {settings.host}:{settings.port}: {str(e).splitlines()[0]}", file=sys.stderr)
            return False
    print(f"\nTarget: {settings.user}@{settings.host}:{settings.port}  databases: {', '.join(dbs)}")
    print("Mode: APPEND ONLY - rows are inserted with COPY; nothing is deleted, truncated or altered (only this run's own seller/deal aggregates are set).")
    if existing:
        print(f"Note: {existing} table(s) already contain rows; new rows are added next to them.")
    if assume_yes:
        return True
    if not sys.stdin.isatty():
        print("Refusing to write without confirmation: pass --yes in non-interactive mode.", file=sys.stderr)
        return False
    ans = input(f"Type the host name '{settings.host}' to continue: ").strip()
    return ans == settings.host
