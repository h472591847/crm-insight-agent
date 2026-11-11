# -*- coding: utf-8 -*-
"""Step 0 探测（第三轮）：MEMBER_OVERVIEW 全量聚合在真实库上的耗时与执行计划。

背景：应用侧 SqlExecutor 注入 /*+ MAX_EXECUTION_TIME(8000) */ 后，
不带过滤条件的会员总览在 crmtest 真实库触发 error 3024（服务端到点终止）。
本脚本放开 session 超时，测"真实耗时"，用于决策：
放宽超时上限 / 应用层缓存 / 申请覆盖索引。

只读：连接后立即 START TRANSACTION READ ONLY，结束 rollback；不打印凭证与个人级数据。
用法：python scripts/probe/step0_probe_member_overview_perf.py
"""
import os
import time

import pymysql

ENV_PATH = os.path.join("mcp-server", "src", "main", "resources", ".env")

# 与 SqlCompiler 编译产物等价的总览 SQL（LIMIT 100 保留，单行聚合不影响耗时）
OVERVIEW_SQL_HEAD = (
    "SELECT COUNT(*) AS total_members, "
    "COALESCE(SUM(CASE WHEN member_sex = 1 THEN 1 ELSE 0 END), 0) AS male_count, "
    "COALESCE(SUM(CASE WHEN member_sex = 2 THEN 1 ELSE 0 END), 0) AS female_count, "
    "COALESCE(SUM(CASE WHEN member_sex IS NULL OR member_sex NOT IN (1, 2) THEN 1 ELSE 0 END), 0) AS unknown_count, "
    "COALESCE(SUM(CASE WHEN member_expense_totalsum > 0 THEN 1 ELSE 0 END), 0) AS expense_member_count, "
    "COALESCE(SUM(COALESCE(member_expense_totalsum, 0)), 0) AS total_expense_amount "
    "FROM member_info WHERE (del_flag = '0')"
)
OVERVIEW_SQL = OVERVIEW_SQL_HEAD + " LIMIT 100"


def read_env(path):
    vals = {}
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            vals[k.strip()] = v.strip().strip('"').strip("'")
    return vals


env = read_env(ENV_PATH)
conn = pymysql.connect(
    host=env.get("MYSQL_HOST", "localhost"),
    port=int(env.get("MYSQL_PORT", "3306")),
    user=env["MYSQL_USER"],
    password=env["MYSQL_PASSWORD"],
    database="crmtest",
    charset="utf8mb4",
    autocommit=False,
    connect_timeout=10,
    read_timeout=600,
)
cur = conn.cursor()
# 本脚本要测"真实耗时"，所以放开超时；应用侧仍是 10s / hint 8s，不可混淆
cur.execute("SET SESSION MAX_EXECUTION_TIME=600000")
cur.execute("START TRANSACTION READ ONLY")


def one(sql, label):
    t0 = time.time()
    try:
        cur.execute(sql)
        print("[ONE ] %-40s => %s  (%.0fms)" % (label, cur.fetchone(), (time.time() - t0) * 1000))
    except Exception as e:
        print("[FAIL] %-40s => %s" % (label, e))


def many(sql, label):
    t0 = time.time()
    try:
        cur.execute(sql)
        res = cur.fetchall()
        print("\n[MANY] %s (%.0fms, %d rows)" % (label, (time.time() - t0) * 1000, len(res)))
        for r in res:
            print("        ", r)
        return res
    except Exception as e:
        print("[FAIL] %s => %s" % (label, e))
        return []


print("=" * 90)
print("--- A. member_info 现有索引（决定是否存在可用的覆盖索引） ---")
many("SHOW INDEX FROM member_info", "SHOW INDEX member_info")

print("\n--- B. 总览 SQL 真实耗时：热跑两次（第二次看缓冲池命中后的差距） ---")
one(OVERVIEW_SQL, "总览全量·第1次")
one(OVERVIEW_SQL, "总览全量·第2次")

print("\n--- C. 执行计划：是否全表扫描 ---")
many("EXPLAIN " + OVERVIEW_SQL, "EXPLAIN 总览全量")

print("\n--- D. 对比项：放宽 hint 到 15s/20s 时是否够用（真实耗时已在 B 测出，这里仅验证服务端视角） ---")
t0 = time.time()
try:
    cur.execute("SELECT /*+ MAX_EXECUTION_TIME(1) */ COUNT(*) FROM member_info WHERE del_flag='0'")
    print("[ONE ] hint=1ms 应被终止 => 未被终止?! %s" % (cur.fetchone(),))
except Exception as e:
    print("[OK  ] hint 生效验证（预期 3024）=> %s  (%.0fms)" % (type(e).__name__, (time.time() - t0) * 1000))

print("\n--- E. 带门店过滤时的耗时（STATION 可选过滤能否作为兜底手段） ---")
station = None
res = many("SELECT member_allot_store, COUNT(*) cnt FROM member_info WHERE del_flag='0' "
           "GROUP BY member_allot_store ORDER BY cnt DESC LIMIT 3", "门店分布 Top3（决定过滤样本）")
if res:
    station = res[0][0]
    filtered = OVERVIEW_SQL_HEAD + " AND member_allot_store = '%s' LIMIT 100" % station
    one(filtered, "总览·单店过滤")
    many("EXPLAIN " + filtered, "EXPLAIN 总览·单店过滤")

print("\n" + "=" * 90)
conn.rollback()
cur.close()
conn.close()
print("[DONE] 第三轮只读探测结束，事务已 rollback。")
