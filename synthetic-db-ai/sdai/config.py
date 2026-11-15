from __future__ import annotations

import os
import re
from dataclasses import dataclass, field
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
REPO_ROOT = ROOT.parent  # the microservices repository that contains this project


def _expand(v):
    if isinstance(v, str):
        def rep(m):
            return os.environ.get(m.group(1), m.group(2) or "")
        return re.sub(r"\$\{([A-Z0-9_]+)(?::-([^}]*))?\}", rep, v)
    if isinstance(v, dict):
        return {k: _expand(x) for k, x in v.items()}
    if isinstance(v, list):
        return [_expand(x) for x in v]
    return v


@dataclass
class Settings:
    host: str = "127.0.0.1"
    port: int = 5432
    user: str = "postgres"
    password: str = ""
    allowed_hosts: list[str] = field(default_factory=lambda: ["localhost", "127.0.0.1", "::1"])
    seed: int = 20260101
    workers: int = 0
    as_of: str | None = None
    orders_per_user: float = 5.5
    index_overhead: float = 1.0
    llm_provider: str = "none"
    llm_model_anthropic: str = "claude-haiku-5-5"
    llm_model_openai: str = "gpt-4o-mini"
    llm_pool_size: int = 400
    llm_max_calls: int = 60
    repo_root: Path = REPO_ROOT
    state_dir: Path = ROOT / "state"
    cache_dir: Path = ROOT / "cache"
    inventory_dir: Path = ROOT / "inventory"
    db_overrides: dict[str, str] = field(default_factory=dict)  # db name -> full DSN

    def dsn(self, db: str) -> str:
        if db in self.db_overrides:
            return self.db_overrides[db]
        pw = f":{self.password}" if self.password else ""
        return f"postgresql://{self.user}{pw}@{self.host}:{self.port}/{db}"

    def n_workers(self, mem_per_worker: int = 0) -> int:
        """CPU count - 1, but never more workers than fit in ~60 % of the memory that is free right now."""
        if self.workers > 0:
            return self.workers
        n = max(1, (os.cpu_count() or 2) - 1)
        avail = available_memory()
        if avail and mem_per_worker:
            n = max(1, min(n, int(avail * 0.6 // mem_per_worker)))
        return n


def load(path: str | Path | None = None) -> Settings:
    p = Path(path) if path else ROOT / "config" / "synthetic.yaml"
    raw = _expand(yaml.safe_load(p.read_text(encoding="utf-8"))) if p.exists() else {}
    t, g, l = raw.get("target", {}), raw.get("generation", {}), raw.get("llm", {})
    s = Settings()
    s.host = str(os.environ.get("SDAI_PG_HOST") or t.get("host") or s.host)
    s.port = int(os.environ.get("SDAI_PG_PORT") or t.get("port") or s.port)
    s.user = str(os.environ.get("SDAI_PG_USER") or t.get("user") or s.user)
    s.password = str(os.environ.get("SDAI_PG_PASSWORD") or t.get("password") or "")
    s.allowed_hosts = list(t.get("allowed_hosts") or s.allowed_hosts)
    extra = os.environ.get("SDAI_ALLOWED_HOSTS")
    if extra:
        s.allowed_hosts += [x.strip() for x in extra.split(",") if x.strip()]
    s.seed = int(g.get("seed", s.seed))
    s.workers = int(os.environ.get("SDAI_WORKERS") or g.get("workers") or 0)
    s.as_of = g.get("as_of") or None
    s.orders_per_user = float(g.get("orders_per_user", s.orders_per_user))
    s.index_overhead = float(g.get("index_overhead", 1.0))
    s.llm_provider = str(os.environ.get("SDAI_LLM") or l.get("provider") or "none").lower()
    s.llm_model_anthropic = l.get("model_anthropic", s.llm_model_anthropic)
    s.llm_model_openai = l.get("model_openai", s.llm_model_openai)
    s.llm_pool_size = int(l.get("pool_size", s.llm_pool_size))
    s.llm_max_calls = int(l.get("max_calls", s.llm_max_calls))
    if os.environ.get("SDAI_REPO_ROOT"):
        s.repo_root = Path(os.environ["SDAI_REPO_ROOT"])
    if os.environ.get("SDAI_STATE_DIR"):
        s.state_dir = Path(os.environ["SDAI_STATE_DIR"])
    return s


def available_memory() -> int | None:
    """Bytes of RAM currently available (psutil if present, else /proc/meminfo, else Windows API)."""
    try:
        import psutil
        return int(psutil.virtual_memory().available)
    except Exception:
        pass
    try:
        for line in open("/proc/meminfo"):
            if line.startswith("MemAvailable:"):
                return int(line.split()[1]) * 1024
    except Exception:
        pass
    try:
        import ctypes

        class MEM(ctypes.Structure):
            _fields_ = [("l", ctypes.c_ulong), ("load", ctypes.c_ulong), ("total", ctypes.c_ulonglong), ("avail", ctypes.c_ulonglong),
                        ("a", ctypes.c_ulonglong), ("b", ctypes.c_ulonglong), ("c", ctypes.c_ulonglong), ("d", ctypes.c_ulonglong), ("e", ctypes.c_ulonglong)]
        m = MEM(); m.l = ctypes.sizeof(MEM)
        ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(m))
        return int(m.avail)
    except Exception:
        return None
