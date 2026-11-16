import json

import pytest

from sdai import config as cfg
from sdai.llm import build_pools, parse_passages


def test_plan_scales_linearly_with_size(settings, catalog):
    from sdai.planner import make_plan
    a = make_plan(settings, 100 << 20, catalog=catalog)
    b = make_plan(settings, 200 << 20, catalog=catalog)
    assert 1.8 < b["world"]["U"] / a["world"]["U"] < 2.2
    assert abs(a["est_disk_bytes_total"] - (100 << 20)) / (100 << 20) < 0.02
    assert a["stage_dbs"]["all"] and all(t["rows"] >= 0 for t in a["per_table"].values())


def test_parse_passages_filters_junk():
    txt = 'Sure! ["' + "A" * 40 + '", "short", "' + "B" * 30 + '\\n tab"]'
    out = parse_passages(txt)
    assert len(out) == 2 and "short" not in out


def test_llm_pools_with_fake_provider(tmp_path):
    s = cfg.load()
    s.llm_provider, s.cache_dir, s.llm_pool_size, s.llm_max_calls = "anthropic", tmp_path, 20, 200
    calls = []

    def fake(prompt):
        calls.append(prompt)
        return json.dumps([f"{prompt[:20]} passage number {i} with enough length to be kept" for i in range(30)])
    p = build_pools(s, call=fake)
    pools = json.loads(p.read_text())
    assert {"descriptions", "rev_pos", "rev_neg", "agent_msgs", "bio"} <= set(pools)
    assert len(calls) <= 200
    assert not any("@" in c for c in calls)            # prompts never carry data from the databases


def test_llm_is_optional(tmp_path, monkeypatch):
    s = cfg.load()
    s.llm_provider, s.cache_dir = "anthropic", tmp_path
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    assert build_pools(s) is None and s.llm_provider == "none"
