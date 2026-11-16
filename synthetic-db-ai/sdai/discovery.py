"""Repository + live-catalog discovery -> schema inventory (JSON + Markdown).

Sources of truth, in order:
  * the repository: services, their Flyway migrations, JPA @Entity/@Table classes, datasource config
  * the database catalog (pg_catalog) of a database where those migrations have been applied.
    That is the exact, constraint-complete schema (ALTER/DROP CONSTRAINT history already folded
    in), which a static SQL parse cannot reliably give.  Access is strictly read-only.
"""
from __future__ import annotations

import json
import re
import subprocess
import time
from pathlib import Path

import psycopg

from .config import Settings
from .relations import LOGICAL_COLUMNS, SKIP
from .safety import assert_safe_target

_SYS = ("pg_catalog", "information_schema", "pg_toast")


def scan_repo(repo: Path) -> dict:
    """Discover microservices, their databases, migrations and JPA entities."""
    out = {}
    for svc in sorted((repo / "services").glob("*-service")):
        app = next(iter(svc.glob("*-app")), None)
        if not app:
            continue
        mig_dir = app / "src/main/resources/db/migration"
        if not mig_dir.exists():
            continue
        db = None
        yml = app / "src/main/resources/application.yml"
        if yml.exists():
            m = re.search(r"jdbc:postgresql://[^/]+/(\w+)", yml.read_text(encoding="utf-8", errors="ignore"))
            db = m.group(1) if m else None
            fm = re.search(r"flyway:\s*(?:\n\s+[^\n]*)*?\n\s+default-schema:\s*(\w+)", yml.read_text(encoding="utf-8", errors="ignore"))
            default_schema = fm.group(1) if fm else None
        else:
            default_schema = None
        migs = []
        for f in sorted(mig_dir.glob("V*__*.sql"), key=lambda p: int(re.match(r"V(\d+)__", p.name).group(1))):
            txt = f.read_text(encoding="utf-8", errors="ignore")
            migs.append({
                "file": f.name,
                "creates": re.findall(r"CREATE TABLE\s+(?:IF NOT EXISTS\s+)?([\w.]+)", txt, re.I),
                "alters": sorted(set(re.findall(r"ALTER TABLE\s+(?:ONLY\s+)?([\w.]+)", txt, re.I))),
                "seed_rows": len(re.findall(r"^\s*(?:\(|INSERT INTO)", txt, re.M)) if "seed" in f.name else 0,
            })
        entities = []
        for j in (app / "src/main/java").rglob("*.java"):
            t = j.read_text(encoding="utf-8", errors="ignore")
            if "@Entity" in t:
                tm = re.search(r'@Table\(([^)]*)\)', t)
                entities.append({"class": j.stem, "table": re.sub(r"\s+", " ", tm.group(1)) if tm else None})
        out[svc.name] = {"db": db or svc.name.replace("-service", "") + "_db", "default_schema": default_schema,
                         "migrations": migs, "entities": entities}
    return out


_COLS = """
select n.nspname, c.relname, a.attnum, a.attname, format_type(a.atttypid, a.atttypmod),
       a.attnotnull, pg_get_expr(d.adbin, d.adrelid), a.attgenerated <> '', a.attidentity <> '',
       case when a.atttypmod > 0 and format_type(a.atttypid, a.atttypmod) ~ '^(character|char|bpchar)'
            then a.atttypmod - 4 end,
       col_description(c.oid, a.attnum), obj_description(c.oid)
from pg_class c join pg_namespace n on n.oid = c.relnamespace
join pg_attribute a on a.attrelid = c.oid and a.attnum > 0 and not a.attisdropped
left join pg_attrdef d on d.adrelid = c.oid and d.adnum = a.attnum
where c.relkind in ('r','p') and n.nspname not in ('pg_catalog','information_schema','pg_toast')
  and n.nspname not like 'pg_temp%%' and c.relname not in ('flyway_schema_history','flyway_sdai_marker')
order by n.nspname, c.relname, a.attnum"""

_CONS = """
select n.nspname, c.relname, k.contype, k.conname, pg_get_constraintdef(k.oid),
       (select array_agg(a.attname order by x.ord) from unnest(k.conkey) with ordinality x(attnum, ord)
          join pg_attribute a on a.attrelid = k.conrelid and a.attnum = x.attnum),
       fn.nspname, fc.relname,
       (select array_agg(a.attname order by x.ord) from unnest(k.confkey) with ordinality x(attnum, ord)
          join pg_attribute a on a.attrelid = k.confrelid and a.attnum = x.attnum),
       k.confdeltype
from pg_constraint k join pg_class c on c.oid = k.conrelid join pg_namespace n on n.oid = c.relnamespace
left join pg_class fc on fc.oid = k.confrelid left join pg_namespace fn on fn.oid = fc.relnamespace
where n.nspname not in ('pg_catalog','information_schema','pg_toast') and k.contype in ('p','u','f','c')"""

_IDX = """
select n.nspname, t.relname, i.relname, ix.indisunique, pg_get_indexdef(ix.indexrelid), ix.indpred is not null
from pg_index ix join pg_class i on i.oid = ix.indexrelid join pg_class t on t.oid = ix.indrelid
join pg_namespace n on n.oid = t.relnamespace
where n.nspname not in ('pg_catalog','information_schema','pg_toast') and not ix.indisprimary"""


def introspect_db(settings: Settings, db: str, service: str) -> list[dict]:
    dsn = settings.dsn(db)
    from urllib.parse import urlparse
    u = urlparse(dsn)
    assert_safe_target(u.hostname or "", db, settings.allowed_hosts)
    with psycopg.connect(dsn, autocommit=True, connect_timeout=8) as conn:
        conn.execute("SET default_transaction_read_only = on")  # discovery can never write
        tables: dict[tuple, dict] = {}
        for (sch, tn, _n, an, typ, nn, dflt, gen, ident, ml, ccom, tcom) in conn.execute(_COLS).fetchall():
            t = tables.setdefault((sch, tn), dict(db=db, service=service, schema=sch, name=tn, columns=[], pk=[],
                                                  uniques=[], fks=[], checks=[], indexes=[], comment=tcom, live_rows=0))
            t["columns"].append(dict(name=an, type=typ, nullable=not nn, default=dflt, generated=bool(gen),
                                     identity=bool(ident), max_len=ml, comment=ccom))
        for (sch, tn, ct, cn, defn, cols, fs, ft, fcols, dele) in conn.execute(_CONS).fetchall():
            t = tables.get((sch, tn))
            if not t:
                continue
            if ct == "p":
                t["pk"] = list(cols)
            elif ct == "u":
                t["uniques"].append(list(cols))
            elif ct == "f":
                t["fks"].append(dict(name=cn, cols=list(cols), ref_schema=fs, ref_table=ft, ref_cols=list(fcols),
                                     on_delete={"a": "NO ACTION", "c": "CASCADE", "n": "SET NULL", "r": "RESTRICT",
                                                "d": "SET DEFAULT"}.get(dele, dele)))
            elif ct == "c":
                t["checks"].append(defn)
        for (sch, tn, iname, uniq, idef, partial) in conn.execute(_IDX).fetchall():
            t = tables.get((sch, tn))
            if t:
                if uniq and not partial:
                    inner = idef.split("(", 1)[1].rsplit(")", 1)[0]
                    cols = [c.strip().strip('"') for c in inner.split(",")]
                    if all(re.fullmatch(r"\w+", c) for c in cols) and cols not in t["uniques"]:
                        t["uniques"].append(cols)
                t["indexes"].append(dict(name=iname, unique=bool(uniq), partial=bool(partial), def_=idef))
        for (sch, tn), t in tables.items():
            try:
                r = conn.execute(f'select reltuples::bigint from pg_class c join pg_namespace n on n.oid=c.relnamespace '
                                 f"where n.nspname='{sch}' and c.relname='{tn}'").fetchone()
                t["live_rows"] = max(int(r[0]), 0) if r else 0
            except Exception:
                pass
        return list(tables.values())


def flyway_history(settings: Settings, db: str) -> list[dict]:
    try:
        with psycopg.connect(settings.dsn(db), autocommit=True, connect_timeout=8) as conn:
            sch = conn.execute("select table_schema from information_schema.tables where table_name='flyway_schema_history' limit 1").fetchone()
            if not sch:
                return []
            rows = conn.execute(f'select version, description, success from "{sch[0]}".flyway_schema_history order by installed_rank').fetchall()
            return [dict(version=r[0], description=r[1], success=r[2]) for r in rows if r[0]]
    except Exception:
        return []


def logical_relations(tables: list[dict]) -> list[dict]:
    have = {(t["db"], f'{t["schema"]}.{t["name"]}') for t in tables}
    res = []
    for t in tables:
        fq = f'{t["schema"]}.{t["name"]}'
        fk_cols = {c for fk in t["fks"] for c in fk["cols"]}
        for c in t["columns"]:
            ref = LOGICAL_COLUMNS.get(c["name"])
            if not ref or c["name"] in fk_cols or (t["db"], fq, c["name"]) in SKIP:
                continue
            pdb, ptab, pcol = ref
            if pdb == t["db"] and ptab == fq and pcol == c["name"]:
                continue
            # resolve parent table by suffix (seller schema is 'seller' on a migrated db)
            cand = [x for x in have if x[0] == pdb and (x[1] == ptab or x[1].endswith("." + ptab.split(".")[-1]))]
            if not cand:
                continue
            res.append(dict(child_db=t["db"], child_table=fq, child_col=c["name"], nullable=c["nullable"],
                            parent_db=pdb, parent_table=cand[0][1], parent_col=pcol))
    return res


def build_inventory(settings: Settings, dbs: dict[str, str]) -> dict:
    tables: list[dict] = []
    flyway = {}
    for db, svc in dbs.items():
        tables += introspect_db(settings, db, svc)
        flyway[db] = flyway_history(settings, db)
    return dict(generated_at=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), tables=tables,
                flyway=flyway, logical_relations=logical_relations(tables))


def render_markdown(inv: dict) -> str:
    L = ["# Schema inventory", "", f"_Generated {inv['generated_at']} by `python main.py inspect`. Do not edit._", ""]
    repo = inv["repo"]
    L += ["## Services", "", "| service | database | flyway default schema | migrations | JPA entities | tables |", "|---|---|---|---|---|---|"]
    by_db: dict[str, list[dict]] = {}
    for t in inv["tables"]:
        by_db.setdefault(t["db"], []).append(t)
    for svc, r in repo.items():
        L.append(f"| {svc} | {r['db']} | {r['default_schema'] or '-'} | {len(r['migrations'])} | {len(r['entities'])} | {len(by_db.get(r['db'], []))} |")
    L += ["", f"Tables: **{len(inv['tables'])}** across **{len(by_db)}** databases; "
          f"physical FKs: **{sum(len(t['fks']) for t in inv['tables'])}**; "
          f"logical (cross-service) references: **{len(inv['logical_relations'])}**.", ""]
    for db in sorted(by_db):
        L += [f"## {db}", ""]
        for t in sorted(by_db[db], key=lambda x: (x["schema"], x["name"])):
            L += [f"### {t['schema']}.{t['name']}", ""]
            if t["comment"]:
                L += [f"> {t['comment']}", ""]
            L += ["| column | type | null | default | notes |", "|---|---|---|---|---|"]
            for c in t["columns"]:
                notes = []
                if c["name"] in t["pk"]:
                    notes.append("PK")
                if c["generated"]:
                    notes.append("generated")
                for fk in t["fks"]:
                    if c["name"] in fk["cols"]:
                        notes.append(f"FK→{fk['ref_schema']}.{fk['ref_table']}({','.join(fk['ref_cols'])})")
                d = (c["default"] or "").replace("|", "\\|")
                L.append(f"| {c['name']} | {c['type']} | {'Y' if c['nullable'] else 'N'} | {d[:40]} | {' '.join(notes)} |")
            extra = []
            if t["uniques"]:
                extra.append("unique: " + "; ".join("(" + ",".join(u) + ")" for u in t["uniques"]))
            if t["checks"]:
                extra.append("checks: " + "; ".join(c[:110] for c in t["checks"]))
            if extra:
                L += ["", *["- " + e for e in extra]]
            L.append("")
    L += ["## Cross-service logical references (no DB-level FK)", "", "| child | column | → parent |", "|---|---|---|"]
    for r in inv["logical_relations"]:
        L.append(f"| {r['child_db']}.{r['child_table']} | {r['child_col']} | {r['parent_db']}.{r['parent_table']}.{r['parent_col']} |")
    return "\n".join(L) + "\n"


def run_inspect(settings: Settings, out_dir: Path | None = None) -> dict:
    repo = scan_repo(settings.repo_root)
    dbs = {r["db"]: svc for svc, r in repo.items()}
    inv = build_inventory(settings, dbs)
    inv["repo"] = repo
    out = out_dir or settings.inventory_dir
    out.mkdir(parents=True, exist_ok=True)
    (out / "schema_inventory.json").write_text(json.dumps(inv, indent=1), encoding="utf-8")
    (out / "SCHEMA_INVENTORY.md").write_text(render_markdown(inv), encoding="utf-8")
    return inv


# ------------------------------------------------------------------ scratch database builder
def build_scratch(settings: Settings, only: list[str] | None = None) -> None:
    """Create the service databases on a SCRATCH server and apply the repository's Flyway
    migrations (read-only use of the migration files) so `inspect`/tests have a real schema.
    Never run against a non-allow-listed host; creates databases only if they do not exist."""
    repo = scan_repo(settings.repo_root)
    for svc, r in repo.items():
        db = r["db"]
        if only and db not in only:
            continue
        assert_safe_target(settings.host, db, settings.allowed_hosts)
        with psycopg.connect(settings.dsn("postgres"), autocommit=True) as admin:
            if not admin.execute("select 1 from pg_database where datname=%s", (db,)).fetchone():
                admin.execute(f'CREATE DATABASE "{db}"')
        with psycopg.connect(settings.dsn(db), autocommit=True) as c:
            if r["default_schema"]:
                c.execute(f'CREATE SCHEMA IF NOT EXISTS "{r["default_schema"]}"')
                c.execute(f'SET search_path TO "{r["default_schema"]}", public')
            c.execute("SET default_transaction_read_only = off")
            if c.execute("select 1 from information_schema.tables where table_name in ('flyway_sdai_marker')").fetchone():
                continue
            app = next((settings.repo_root / "services" / svc).glob("*-app"))
            mdir = app / "src/main/resources/db/migration"
            for m in r["migrations"]:
                sql = (mdir / m["file"]).read_text(encoding="utf-8")
                c.execute(sql)
            c.execute("CREATE TABLE IF NOT EXISTS public.flyway_sdai_marker(done bool)")
