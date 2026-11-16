"""Turns generator output (column arrays) into validated COPY streams.

The emitter is the contract between the generators and the real schema: it refuses to write a column that does
not exist, omits nothing the schema requires, never puts NULL in a NOT NULL column and never overflows
varchar(n)/char(n) - so a schema change in a migration shows up as a clear error, not as silent bad data.
"""
from __future__ import annotations

import numpy as np

from .catalog import Catalog
from .db import Target
from .rand import NULL, rows_bytes

_ARRAY_TYPES = ("[]",)


class SchemaMismatch(RuntimeError):
    pass


class Emitter:
    def __init__(self, settings, catalog: Catalog, dry: bool = False, skip_dbs=(), collect_stats: bool = False):
        self.settings, self.cat, self.dry = settings, catalog, dry
        self.skip = set(skip_dbs)
        self.conns: dict[str, Target] = {}
        self.rows: dict[str, int] = {}
        self.bytes: dict[str, int] = {}
        self.colstats: dict[str, dict] = {}
        self.collect = collect_stats
        self.probed: set[str] = set()
        self.already_present: set[str] = set()   # dbs whose chunk was committed by an earlier (interrupted) run
        self._checked: set[tuple] = set()

    # ------------------------------------------------------------------ schema contract
    def _check_columns(self, tb, names):
        key = (tb.db, tb.fq, tuple(names))
        if key in self._checked:
            return
        have = {c.name: c for c in tb.columns}
        unknown = [n for n in names if n not in have]
        if unknown:
            raise SchemaMismatch(f"{tb.db}.{tb.fq}: generator emits columns that do not exist: {unknown}. Re-run `inspect` if a migration changed.")
        gen = [n for n in names if have[n].generated]
        if gen:
            raise SchemaMismatch(f"{tb.db}.{tb.fq}: cannot insert generated columns {gen}")
        missing = [c.name for c in tb.columns if c.required and c.name not in names]
        if missing:
            raise SchemaMismatch(f"{tb.db}.{tb.fq}: NOT NULL columns without default not supplied by the generator: {missing}")
        self._checked.add(key)

    def _check_values(self, tb, names, arrs):
        have = {c.name: c for c in tb.columns}
        for n, a in zip(names, arrs):
            c = have[n]
            if not c.nullable and a.dtype.kind == "S" and (a == NULL).any():
                raise SchemaMismatch(f"{tb.db}.{tb.fq}.{n}: NULL generated for a NOT NULL column")
            if c.max_len and a.dtype.kind == "S":
                mx = int(np.char.str_len(a).max()) if a.size else 0
                if mx > c.max_len:
                    raise SchemaMismatch(f"{tb.db}.{tb.fq}.{n}: value of length {mx} exceeds {c.type}")

    # ------------------------------------------------------------------ io
    def _conn(self, db):
        c = self.conns.get(db)
        if c is None:
            c = self.conns[db] = Target(self.settings, db, autocommit=False)
            c.exec_safe("SET synchronous_commit = off")
            # a killed generator must not leave a zombie backend holding row locks: make the SERVER notice a dead client quickly,
            # and never wait forever on a lock held by someone else (the chunk fails loudly and is retried on resume)
            c.exec_safe("SET tcp_keepalives_idle = 15")
            c.exec_safe("SET tcp_keepalives_interval = 5")
            c.exec_safe("SET tcp_keepalives_count = 3")
            c.exec_safe("SET lock_timeout = '300s'")
        return c

    def _probe(self, tb, names, arrs) -> bool:
        """Is the first row of this chunk already in the database? (chunk committed before a crash)"""
        if len(tb.pk) != 1 or tb.pk[0] not in names:
            return False
        first = arrs[names.index(tb.pk[0])][0].decode()
        rows = self._conn(tb.db).q(f'SELECT 1 FROM {tb.fq} WHERE "{tb.pk[0]}" = %s LIMIT 1', (first,))
        return bool(rows)

    def __call__(self, db: str, table: str, cols: dict):
        names = list(cols.keys())
        arrs = [np.asarray(v) for v in cols.values()]
        arrs = [a if a.dtype.kind == "S" else _to_bytes(a) for a in arrs]
        n = arrs[0].shape[0]
        if any(a.shape[0] != n for a in arrs):
            raise SchemaMismatch(f"{db}.{table}: column length mismatch {[(k, a.shape[0]) for k, a in zip(names, arrs)]}")
        tb = self.cat.find(db, table)
        self._check_columns(tb, names)
        if n == 0:
            return
        self._check_values(tb, names, arrs)
        if self.collect:
            st = self.colstats.setdefault(f"{db}:{tb.fq}", {"rows": 0, "cols": {k: [0, 0] for k in names}})
            st["rows"] += n
            for k, a in zip(names, arrs):
                nn = a != NULL
                st["cols"][k][0] += int(nn.sum())
                st["cols"][k][1] += int(np.char.str_len(a)[nn].sum())
        key = f"{db}:{tb.fq}"
        if db in self.skip or db in self.already_present:
            return
        data = rows_bytes(arrs)
        self.rows[key] = self.rows.get(key, 0) + n
        self.bytes[key] = self.bytes.get(key, 0) + len(data)
        if self.dry:
            return
        if db not in self.probed and len(tb.pk) == 1:
            self.probed.add(db)
            if self._probe(tb, names, arrs):
                self.already_present.add(db)
                self.conns[db].rollback()
                self.rows = {k: v for k, v in self.rows.items() if not k.startswith(db + ":")}
                self.bytes = {k: v for k, v in self.bytes.items() if not k.startswith(db + ":")}
                return
        self._conn(db).copy_in(tb.fq, names, data)

    def commit(self):
        for c in self.conns.values():
            c.commit()

    def rollback(self):
        for c in self.conns.values():
            try:
                c.rollback()
            except Exception:
                pass

    def close(self):
        for c in self.conns.values():
            c.close()
        self.conns.clear()

    def written_dbs(self) -> set[str]:
        return {k.split(":", 1)[0] for k in self.rows}


def _to_bytes(a):
    return a.astype("S")
