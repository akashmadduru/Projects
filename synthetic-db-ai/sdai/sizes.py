import re

_U = {"B": 1, "KB": 1 << 10, "MB": 1 << 20, "GB": 1 << 30, "TB": 1 << 40,
      "K": 1 << 10, "M": 1 << 20, "G": 1 << 30, "T": 1 << 40}


def parse_size(s: str) -> int:
    m = re.fullmatch(r"\s*([0-9]*\.?[0-9]+)\s*([a-zA-Z]*)\s*", str(s))
    if not m:
        raise ValueError(f"bad size {s!r}; use e.g. 100MB, 1GB, 2.5GB")
    unit = (m.group(2) or "B").upper()
    if unit not in _U:
        raise ValueError(f"unknown size unit {m.group(2)!r}")
    return int(float(m.group(1)) * _U[unit])


def fmt_size(n: float) -> str:
    for u in ("B", "KB", "MB", "GB", "TB"):
        if abs(n) < 1024 or u == "TB":
            return f"{n:.1f} {u}" if u != "B" else f"{int(n)} B"
        n /= 1024
