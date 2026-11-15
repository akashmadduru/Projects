from __future__ import annotations

from contextlib import contextmanager
from urllib.parse import urlparse

import psycopg

from .config import Settings
from .safety import UnsafeTarget, assert_safe_target, check_sql


class Target:
    """A guarded connection to ONE service database."""

    def __init__(self, settings: Settings, db: str, autocommit: bool = True):
        dsn = settings.dsn(db)
        u = urlparse(dsn)
        assert_safe_target(u.hostname or "", u.path.lstrip("/"), settings.allowed_hosts)
        self.db = db
        self.conn = psycopg.connect(dsn, autocommit=autocommit, connect_timeout=10, keepalives=1, keepalives_idle=15, keepalives_interval=5, keepalives_count=3)

    # read-only / allow-listed statements
    def q(self, sql: str, params=None) -> list[tuple]:
        check_sql(sql)
        with self.conn.cursor() as cur:
            cur.execute(sql, params)
            return cur.fetchall() if cur.description else []

    def exec_safe(self, sql: str) -> None:
        check_sql(sql)
        with self.conn.cursor() as cur:
            cur.execute(sql)

    def copy_in(self, table: str, cols: list[str], data: bytes) -> None:
        sql = f'COPY {table} ({", ".join(chr(34) + c + chr(34) for c in cols)}) FROM STDIN'
        with self.conn.cursor() as cur:
            with cur.copy(sql) as cp:
                step = 8 << 20
                for i in range(0, len(data), step):
                    cp.write(data[i:i + step])

    def update_own_rows(self, table: str, key_col: str, set_cols: list[str], rows: list[tuple]) -> None:
        """The ONLY mutation besides COPY: set denormalised aggregates on rows this tool inserted.
        rows = [(key, v1, v2...)]. Always key-restricted; never a table-wide UPDATE."""
        allowed = {"sellers": {"rating_avg", "rating_count"}, "deals": {"claimed"}}.get(table.split(".")[-1])
        if allowed is None or not set(set_cols) <= allowed or key_col != "id":
            raise UnsafeTarget(f"UPDATE of {table}({set_cols}) is not permitted")
        sql = f"UPDATE {table} SET " + ", ".join(f'"{c}" = %s' for c in set_cols) + f' WHERE "{key_col}" = %s'
        with self.conn.cursor() as cur:
            cur.executemany(sql, [(*r[1:], r[0]) for r in rows])

    def commit(self):
        self.conn.commit()

    def rollback(self):
        self.conn.rollback()

    def close(self):
        try:
            self.conn.close()
        except Exception:
            pass


@contextmanager
def target(settings: Settings, db: str, autocommit: bool = True):
    t = Target(settings, db, autocommit)
    try:
        yield t
    finally:
        t.close()
