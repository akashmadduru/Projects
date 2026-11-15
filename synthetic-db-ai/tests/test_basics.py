import numpy as np
import pytest

from sdai.rand import base36, e_money, e_ts, group_expand, uid, uid_str, first_of_key
from sdai.safety import UnsafeTarget, assert_safe_target, check_sql
from sdai.sizes import fmt_size, parse_size


def test_parse_size():
    assert parse_size("100MB") == 100 << 20
    assert parse_size("1GB") == 1 << 30
    assert parse_size("10gb") == 10 << 30
    assert parse_size("2.5GB") == int(2.5 * (1 << 30))
    with pytest.raises(ValueError):
        parse_size("ten")
    assert fmt_size(10 << 30).startswith("10.0 GB")


def test_uuid_format_and_uniqueness():
    ids = uid("order", np.arange(200_000), 7).s()
    assert len(set(ids.tolist())) == 200_000
    s = ids[0].decode()
    assert len(s) == 36 and s[8] == s[13] == s[18] == s[23] == "-" and s[14] == "4"
    assert uid_str("order", 5, 7) == uid("order", np.array([5]), 7).s()[0].decode()
    assert uid_str("order", 5, 7) != uid_str("order", 5, 8)        # seed changes ids
    assert uid_str("order", 5, 7) != uid_str("user", 5, 7)         # kind changes ids


def test_money_and_time_encoding():
    assert e_money(np.array([0, 5, 1234, -250]), 2).tolist() == [b"0.00", b"0.05", b"12.34", b"-2.50"]
    assert e_money(np.array([1999]), 4).tolist() == [b"0.1999"]
    assert e_ts(np.array([0])).tolist() == [b"1970-01-01T00:00:00+00"]


def test_helpers():
    owner, ordn = group_expand(np.array([2, 0, 3]))
    assert owner.tolist() == [0, 0, 2, 2, 2] and ordn.tolist() == [0, 1, 0, 1, 2]
    assert first_of_key(np.array([5, 7, 5, 9, 7])).tolist() == [True, True, False, True, False]
    assert base36(np.array([0, 35, 36]), 3).tolist() == [b"000", b"00z", b"010"]


def test_production_guard():
    ok = ["localhost", "127.0.0.1"]
    assert_safe_target("localhost", "order_db", ok)
    for host in ("db.prod.internal", "mydb.abc.us-east-1.rds.amazonaws.com", "10.0.0.5"):
        with pytest.raises(UnsafeTarget):
            assert_safe_target(host, "order_db", ok)
    with pytest.raises(UnsafeTarget):
        assert_safe_target("localhost", "order_db_prod", ok)


def test_sql_guard_is_append_only():
    for good in ("select 1", "SELECT count(*) FROM orders.orders", "SET synchronous_commit = off", "ANALYZE orders.orders", "with x as (select 1) select * from x"):
        check_sql(good)
    for bad in ("DELETE FROM orders.orders", "drop table orders.orders", "TRUNCATE orders.orders", "alter table x add column y int",
                "INSERT INTO x VALUES (1)", "UPDATE x SET a=1", "create table x(a int)", "grant all on x to y", "vacuum full"):
        with pytest.raises(UnsafeTarget):
            check_sql(bad)


def test_sql_guard_blocks_statement_smuggling():
    for bad in ("select 1; drop table orders.orders", "select * from x where a = 1; delete from x",
                "with d as (delete from orders.orders returning 1) select * from d", "select 1 for update", "COPY orders.orders FROM '/etc/passwd'"):
        with pytest.raises(UnsafeTarget):
            check_sql(bad)
    check_sql("select count(*) from orders.orders where status = 'DROP; DELETE'")      # keywords inside literals are data
    check_sql("select updated_at, created_at from orders.orders")                    # identifiers are not keywords


def test_update_allow_list():
    from sdai.db import Target
    t = object.__new__(Target)
    for table, cols in (("orders.orders", ["status"]), ("seller.sellers", ["commission_percent"]), ("promotion.deals", ["quantity_limit"])):
        with pytest.raises(UnsafeTarget):
            Target.update_own_rows(t, table, "id", cols, [("x", 1)])
