"""Production / destructive-operation guards.

Two independent layers:
  1. assert_safe_target(): refuses any host that is not an explicitly allow-listed local host,
     and any host/db name that looks like production.
  2. check_sql(): the only SQL the generator may execute.  No DELETE/DROP/TRUNCATE/ALTER/CREATE/
     GRANT.  UPDATE is allowed only through db.Target.update_own_rows(), which is key-restricted.
"""
from __future__ import annotations

import re

_PROD = re.compile(r"(^|[-_.])(prod|production|prd|live)([-_.]|$)", re.I)
_CLOUD = re.compile(r"(rds\.amazonaws\.com|\.azure\.com|cloud\.google\.com|supabase\.|neon\.tech|aivencloud)", re.I)


class UnsafeTarget(RuntimeError):
    pass


def assert_safe_target(host: str, dbname: str, allowed_hosts: list[str]) -> None:
    if _PROD.search(host) or _PROD.search(dbname) or _CLOUD.search(host):
        raise UnsafeTarget(f"refusing to touch {host}/{dbname}: looks like a production/managed database")
    if host not in allowed_hosts:
        raise UnsafeTarget(
            f"host '{host}' is not in the allowed list {allowed_hosts}. "
            "Add it to target.allowed_hosts (or SDAI_ALLOWED_HOSTS) only if it is a non-production database.")


_READ = re.compile(r"^\s*(select|with|show|explain)\b", re.I)
_ALLOWED_MISC = re.compile(r"^\s*(set\s+(session|local)?\s*[a-z_.]+|analyze\s+[\w.\"]+|reset\s+\w+)\s*(=|to|;|$)", re.I)
_MUTATORS = re.compile(r"\b(insert|update|delete|drop|truncate|alter|create|grant|revoke|vacuum|reindex|cluster|copy|merge|call|do|lock)\b", re.I)


def check_sql(sql: str) -> None:
    s = sql.strip().rstrip(";")
    bare = re.sub(r"'(?:[^']|'')*'", "''", s)                      # ignore string literals
    if ";" in bare:
        raise UnsafeTarget("blocked SQL: multiple statements")
    if _READ.match(s):
        if _MUTATORS.search(bare):                              # a SELECT may call setval(); nothing else may mutate
            raise UnsafeTarget(f"blocked SQL: {s[:80]}")
        return
    if _ALLOWED_MISC.match(s):
        return
    raise UnsafeTarget(f"blocked SQL (generator is append-only): {s[:80]}")
