"""synthetic-db-ai command line.

  python main.py inspect                 discover services/tables/constraints -> inventory/
  python main.py plan --size 10GB        row budgets + estimated size + generation order
  python main.py generate --size 10GB    stream synthetic data into the databases (resumable)
  python main.py validate                FK, constraint and business-rule checks
  python main.py stats                   actual row counts / sizes vs plan
  python main.py scratch                 (dev) create service DBs + apply migrations on a SCRATCH server
"""
from __future__ import annotations

import argparse
import sys

from sdai import config as cfg
from sdai.sizes import parse_size


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="synthetic-db-ai", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--config", help="path to a synthetic.yaml override")
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("inspect", help="discover schemas (read-only) and write inventory/")
    sp = sub.add_parser("plan", help="compute the generation plan")
    sp.add_argument("--size", default="1GB", help="100MB | 1GB | 5GB | 10GB | any custom size, e.g. 2.5GB, 750MB")
    sp.add_argument("--users", type=int, help="override: number of customers instead of --size")
    g = sub.add_parser("generate", help="generate and bulk-load data")
    g.add_argument("--size", default="1GB")
    g.add_argument("--users", type=int)
    g.add_argument("--workers", type=int)
    g.add_argument("--seed", type=int)
    g.add_argument("--llm", choices=["none", "anthropic", "openai"], help="optional LLM text enrichment")
    g.add_argument("--fresh", action="store_true", help="start a new run instead of resuming (never deletes DB rows)")
    g.add_argument("--dry-run", action="store_true", help="generate in memory only; no database writes")
    g.add_argument("--no-analyze", action="store_true")
    g.add_argument("--yes", action="store_true", help="skip the interactive confirmation (required when not on a TTY)")
    v = sub.add_parser("validate", help="validate loaded data")
    v.add_argument("--sample", type=int, default=20000, help="rows sampled per cross-service reference check")
    sub.add_parser("stats", help="show row counts and sizes")
    sub.add_parser("scratch", help="(dev) build the schemas on a scratch Postgres from the repo's migrations")
    a = p.parse_args(argv)
    s = cfg.load(a.config)

    if a.cmd == "scratch":
        from sdai.discovery import build_scratch
        build_scratch(s)
        print("scratch databases ready on", s.host, s.port)
        return 0
    if a.cmd == "inspect":
        from sdai.discovery import run_inspect
        inv = run_inspect(s)
        print(f"inspected {len(inv['repo'])} services, {len(inv['tables'])} tables, "
              f"{sum(len(t['fks']) for t in inv['tables'])} FKs, {len(inv['logical_relations'])} logical refs")
        print(f"wrote {s.inventory_dir / 'schema_inventory.json'} and SCHEMA_INVENTORY.md")
        return 0
    if a.cmd == "plan":
        from sdai.planner import make_plan, print_plan
        pl = make_plan(s, parse_size(a.size), users=a.users)
        print_plan(pl)
        return 0
    if a.cmd == "generate":
        from sdai.engine import run_generate
        if a.workers:
            s.workers = a.workers
        if a.seed is not None:
            s.seed = a.seed
        if a.llm:
            s.llm_provider = a.llm
        return run_generate(s, parse_size(a.size), users=a.users, fresh=a.fresh, dry_run=a.dry_run, analyze=not a.no_analyze, assume_yes=a.yes)
    if a.cmd == "validate":
        from sdai.validate import run_validate
        return run_validate(s, sample=a.sample)
    if a.cmd == "stats":
        from sdai.stats import run_stats
        return run_stats(s)
    return 2


if __name__ == "__main__":
    sys.exit(main())
