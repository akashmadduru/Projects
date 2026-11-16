import os
import sys
from pathlib import Path

import numpy as np
import pytest

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from sdai import config as cfg                      # noqa: E402
from sdai import world as W                         # noqa: E402
from sdai.catalog import Catalog                    # noqa: E402
from sdai.emitter import Emitter                    # noqa: E402
from sdai.gen_util import Ctx                       # noqa: E402
from sdai.vocab import build_vocab                  # noqa: E402

INV = ROOT / "inventory" / "schema_inventory.json"


@pytest.fixture(scope="session")
def settings():
    return cfg.load()


@pytest.fixture(scope="session")
def catalog():
    if not INV.exists():
        pytest.skip("run `python main.py inspect` first")
    return Catalog.load(INV)


@pytest.fixture(scope="session")
def vocab():
    return build_vocab(1234)


class Capture(Emitter):
    """Dry emitter that keeps every emitted column array (after the full schema-contract checks)."""

    def __init__(self, *a, **k):
        super().__init__(*a, **k)
        self.cap: dict = {}

    def __call__(self, db, table, cols):
        self.cap.setdefault((db, table), []).append({k: np.asarray(v) for k, v in cols.items()})
        super().__call__(db, table, cols)

    def col(self, db, table, name):
        return np.concatenate([c[name] for c in self.cap[(db, table)]])


@pytest.fixture(scope="session")
def small_world(settings, catalog, vocab):
    """One full dry generation of 400 customers + static + catalog; shared by the rule tests."""
    from sdai.gen_catalog import catalog_chunk
    from sdai.gen_customer import customer_chunk
    from sdai.gen_static import emit_static
    w = W.World(seed=1234, as_of=1_800_000_000, U=2000)
    em = Capture(settings, catalog, dry=True)
    rng = np.random.default_rng(5)
    ctx = Ctx(w=w, voc=vocab, rng=rng, chunk=0, serial_base={"product_views": 1, "chat_messages": 1})
    emit_static(ctx, em)
    customer_chunk(ctx, 100, 500, em)
    cctx = Ctx(w=w, voc=vocab, rng=np.random.default_rng(6), chunk=0, agg=ctx.tally)
    catalog_chunk(cctx, 0, 300, em)
    return w, em
