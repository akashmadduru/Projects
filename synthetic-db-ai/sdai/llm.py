"""Optional LLM text enrichment (Anthropic or OpenAI).

Why pools instead of per-row calls: a 10 GB dataset has millions of text fields; calling a model per row would cost a
fortune and take days.  Instead the model is asked ONCE for a few hundred varied, realistic passages per pool
(product descriptions per category, reviews per sentiment, support replies, marketing copy, customer personas);
generators then sample and recombine them with row-specific facts, so text stays varied and grounded.

Privacy: prompts contain only generic category / sentiment words - never data from your databases.
Cost control: `llm.max_calls` caps the number of API calls; results are cached in cache/llm_pools.json and reused.
Offline: with provider `none` (default) or on any API error the built-in Faker/template pools are used.
"""
from __future__ import annotations

import json
import os
import re
import time
from pathlib import Path

from .config import Settings
from .vocab import ROOTS

SYSTEM = ("You write realistic e-commerce content for a synthetic test dataset. Output ONLY a JSON array of strings, "
          "no markdown, no commentary. Each string is one standalone passage. Avoid tabs, newlines, backslashes and any real brand names or personal data.")

# pool name -> (prompt template, passages per call)
POOLS = {
    "descriptions": ("Write {n} distinct product descriptions (60-110 words each) for products in the category '{cat}'. Use varied tone and structure; mention concrete features.", 12),
    "rev_pos": ("Write {n} distinct positive customer reviews (1-3 sentences) for a general consumer product. Vary voice and detail.", 25),
    "rev_neu": ("Write {n} distinct mixed/neutral customer reviews (1-3 sentences) for a general consumer product.", 25),
    "rev_neg": ("Write {n} distinct negative customer reviews (1-3 sentences) for a general consumer product; realistic complaints (delivery, quality, support).", 25),
    "agent_msgs": ("Write {n} distinct customer-support agent replies (1-3 sentences) for e-commerce tickets about orders, refunds, returns and accounts.", 25),
    "bio": ("Write {n} distinct short customer personas (2-3 sentences) describing shopping habits and preferences of fictional shoppers.", 25),
}


class LLMError(RuntimeError):
    pass


def _call_anthropic(s: Settings, prompt: str) -> str:
    import anthropic
    client = anthropic.Anthropic()                      # reads ANTHROPIC_API_KEY
    r = client.messages.create(model=s.llm_model_anthropic, max_tokens=4096, system=SYSTEM, messages=[{"role": "user", "content": prompt}])
    return "".join(b.text for b in r.content if getattr(b, "type", "") == "text")


def _call_openai(s: Settings, prompt: str) -> str:
    from openai import OpenAI
    client = OpenAI()                                   # reads OPENAI_API_KEY
    r = client.chat.completions.create(model=s.llm_model_openai, messages=[{"role": "system", "content": SYSTEM}, {"role": "user", "content": prompt}])
    return r.choices[0].message.content or ""


def parse_passages(text: str) -> list[str]:
    m = re.search(r"\[.*\]", text, re.S)
    if not m:
        return []
    try:
        arr = json.loads(m.group(0))
    except json.JSONDecodeError:
        return []
    return [re.sub(r"[\t\r\n\\]+", " ", x).strip() for x in arr if isinstance(x, str) and 20 <= len(x) <= 900]


def build_pools(s: Settings, call=None) -> Path | None:
    """Create cache/llm_pools.json.  `call(prompt)->str` is injectable for tests."""
    out = s.cache_dir / "llm_pools.json"
    if s.llm_provider == "none":
        return None
    if out.exists():
        print(f"LLM pools: reusing {out}")
        return out
    fn = call
    if fn is None:
        key = "ANTHROPIC_API_KEY" if s.llm_provider == "anthropic" else "OPENAI_API_KEY"
        if not os.environ.get(key):
            print(f"LLM: {key} is not set - continuing with the built-in Faker/template text pools.")
            s.llm_provider = "none"
            return None
        fn = (lambda p: _call_anthropic(s, p)) if s.llm_provider == "anthropic" else (lambda p: _call_openai(s, p))
    pools: dict[str, list[str]] = {}
    calls = 0
    jobs = [("descriptions", c) for c in ROOTS] + [(k, None) for k in POOLS if k != "descriptions"]
    per_pool_target = s.llm_pool_size
    for name, cat in jobs:
        tmpl, n = POOLS[name]
        want = per_pool_target // len(ROOTS) if name == "descriptions" else per_pool_target
        while len(pools.setdefault(name, [])) < want and calls < s.llm_max_calls:
            calls += 1
            try:
                got = parse_passages(fn(tmpl.format(n=n, cat=cat)))
            except Exception as e:                      # network/quota/etc: degrade gracefully
                print(f"LLM call failed ({type(e).__name__}: {str(e)[:80]}); using built-in text for the rest.")
                calls = s.llm_max_calls
                break
            pools[name] += got
            if not got:
                break
            time.sleep(0.2)
    pools = {k: v for k, v in pools.items() if v}
    if not pools:
        s.llm_provider = "none"
        return None
    s.cache_dir.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(pools), encoding="utf-8")
    print(f"LLM pools written: {', '.join(f'{k}={len(v)}' for k, v in pools.items())} ({calls} calls)")
    return out
