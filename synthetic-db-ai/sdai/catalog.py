"""Schema catalog model, loaded from the inventory produced by `inspect`."""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class Column:
    name: str
    type: str
    nullable: bool
    default: str | None
    generated: bool
    identity: bool
    max_len: int | None = None
    comment: str | None = None

    @property
    def insertable(self) -> bool:
        return not self.generated

    @property
    def required(self) -> bool:
        """NOT NULL without any default: the generator MUST supply it."""
        return (not self.nullable) and self.default is None and not self.generated and not self.identity


@dataclass
class Table:
    db: str
    service: str
    schema: str
    name: str
    columns: list[Column]
    pk: list[str]
    uniques: list[list[str]]
    fks: list[dict]
    checks: list[str]
    indexes: list[dict]
    comment: str | None = None
    live_rows: int = 0

    @property
    def fq(self) -> str:
        return f"{self.schema}.{self.name}"

    def col(self, name: str) -> Column:
        for c in self.columns:
            if c.name == name:
                return c
        raise KeyError(f"{self.fq} has no column {name}")

    def colnames(self) -> set[str]:
        return {c.name for c in self.columns}


@dataclass
class Catalog:
    tables: dict[tuple[str, str], Table] = field(default_factory=dict)  # (db, 'schema.table')
    repo: dict = field(default_factory=dict)
    logical: list[dict] = field(default_factory=list)

    @staticmethod
    def load(path: Path) -> "Catalog":
        d = json.loads(Path(path).read_text(encoding="utf-8"))
        cat = Catalog(repo=d.get("repo", {}), logical=d.get("logical_relations", []))
        for t in d["tables"]:
            cols = [Column(**c) for c in t.pop("columns")]
            tb = Table(columns=cols, **t)
            cat.tables[(tb.db, tb.fq)] = tb
        return cat

    def find(self, db: str, name: str) -> Table:
        """Resolve 'orders' / 'orders.orders' / 'public.x' inside a db."""
        if (db, name) in self.tables:
            return self.tables[(db, name)]
        hits = [t for (d, _), t in self.tables.items() if d == db and (t.name == name or t.fq.endswith("." + name))]
        if len(hits) != 1:
            raise KeyError(f"cannot resolve table {name!r} in {db} ({len(hits)} matches); run `inspect`")
        return hits[0]

    def dbs(self) -> list[str]:
        return sorted({d for d, _ in self.tables})

    def by_db(self, db: str) -> list[Table]:
        return [t for (d, _), t in self.tables.items() if d == db]
