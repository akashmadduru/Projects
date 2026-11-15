"""End-to-end against a SCRATCH Postgres (skipped unless SDAI_PG_PASSWORD is set and the DBs are reachable).
Loads a small dataset with the real engine, resumes it, and runs the full `validate` suite."""
import os

import pytest

from sdai import config as cfg

pytestmark = pytest.mark.skipif(not os.environ.get("SDAI_PG_PASSWORD"), reason="no scratch Postgres configured")


def test_generate_resume_and_validate(tmp_path, settings, catalog):
    from sdai.engine import run_generate
    from sdai.validate import run_validate
    from sdai.db import Target
    try:
        Target(settings, "auth_db").close()
    except Exception as e:
        pytest.skip(f"scratch DB not reachable: {e}")
    s = cfg.load()
    s.state_dir, s.seed, s.workers = tmp_path, 4242, 2
    assert run_generate(s, 0, users=700, fresh=True, assume_yes=True) == 0
    assert run_generate(s, 0, users=700, assume_yes=True) == 0            # resume: nothing left to do, nothing duplicated
    assert run_validate(s, sample=2000) == 0
