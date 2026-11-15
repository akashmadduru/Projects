"""`stats`: what is actually in the databases vs. the plan (read-only)."""
from __future__ import annotations

import json

from .config import Settings
from .db import Target
from .planner import load_catalog
from .sizes import fmt_size


def run_stats(settings: Settings) -> int:
    cat = load_catalog(settings)
    plan = None
    p = settings.state_dir / "plan.json"
    if p.exists():
        plan = json.loads(p.read_text())
    total_actual = 0
    print(f"{'database / table':<46}{'rows':>14}{'size':>12}{'planned rows':>16}")
    for db in cat.dbs():
        try:
            t = Target(settings, db, autocommit=True)
            t.conn.execute("SET default_transaction_read_only = on")
        except Exception as e:
            print(f"{db:<46}  unreachable: {str(e).splitlines()[0]}")
            continue
        rows = t.q("select pg_database_size(current_database())")
        dbsize = rows[0][0]
        print(f"{db:<46}{'':>14}{fmt_size(dbsize):>12}")
        for tb in sorted(cat.by_db(db), key=lambda x: x.fq):
            n, sz = t.q(f"select (select count(*) from {tb.fq}), pg_total_relation_size('{tb.fq}'::regclass)")[0]
            total_actual += sz
            planned = plan["per_table"].get(f"{db}:{tb.fq}", {}).get("rows") if plan else None
            print(f"  {tb.fq:<44}{n:>14,}{fmt_size(sz):>12}{(f'{planned:,}' if planned else ''):>16}")
        t.close()
    print(f"\ntotal table+index size across the {len(cat.dbs())} databases: {fmt_size(total_actual)}")
    if plan:
        tgt, est = plan["target_bytes"], plan["est_disk_bytes_total"]
        print(f"plan target {fmt_size(tgt)}, planner estimate {fmt_size(est)}; actual includes pre-existing migration seed rows")
    return 0
