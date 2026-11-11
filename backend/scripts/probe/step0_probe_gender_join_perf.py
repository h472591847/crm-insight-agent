# -*- coding: utf-8 -*-
"""Step 0 探测（路线 C）：GENDER_CONSUMPTION_COMPARE 受控 LEFT JOIN 的性能与基数正确性。

背景：框架新增受控 LEFT JOIN 后，订单头表按会员性别分组。join 类指标最大的
风险不是慢，而是"看起来正确的错数字"——若关联键 member_mobile 在会员表不唯一，
LEFT JOIN 会把同一订单复制多行，SUM(实付) 直接虚高（AGENTS 第六节：一对多联查
必须先明确关系、避免订单级金额重复累计）。本脚本一次性回答两件事：
  1) 基数：member_mobile 是否 1:1（决定 SUM/COUNT 是否可信）；
  2) 性能：join 在固定窗口的真实耗时与执行计划（决定超时/索引策略）。

与 SqlCompiler 编译产物逐字等价的 SQL 见 JOIN_SQL_HEAD / TOTALS_SQL_HEAD。
只读：连接后立即 START TRANSACTION READ ONLY，结束 rollback；不打印凭证与个人级明细。
用法：python scripts/probe/step0_probe_gender_join_perf.py
"""
import os
import time

import pymysql

ENV_PATH = os.path.join("mcp-server", "src", "main", "resources", ".env")

# 固定探测窗口（与后续基线对账口径保持一致；窗口内有数据才有意义，先看 E 项分布）
WIN_START = "2026-06-01"
WIN_END = "2026-06-27"

# 与 SqlCompiler.genderComparison golden SQL 等价：主表别名 e + LEFT JOIN member_info m
JOIN_SQL_HEAD = (
    "SELECT CASE WHEN m.member_sex = 1 THEN '男' WHEN m.member_sex = 2 THEN '女' "
    "ELSE '未知性别' END AS gender, "
    "COALESCE(SUM(CAST(NULLIF(TRIM(e.expense_realamt), '') AS DECIMAL(20, 2))), 0) AS sales_amount, "
    "COUNT(*) AS order_count, "
    "COUNT(DISTINCT e.expense_mobile) AS buyer_count "
    "FROM expense_calendar_info e "
    "LEFT JOIN member_info m ON e.expense_mobile = m.member_mobile "
    "WHERE (e.expense_state = '10') AND (e.del_flag = '0') "
    "AND ((m.del_flag = '0' OR m.member_mobile IS NULL)) "
    "AND e.expense_buyDate >= '%(start)s' "
    "AND e.expense_buyDate < DATE_ADD('%(end)s', INTERVAL 1 DAY) "
    "GROUP BY CASE WHEN m.member_sex = 1 THEN '男' WHEN m.member_sex = 2 THEN '女' "
    "ELSE '未知性别' END ORDER BY sales_amount desc LIMIT 10"
)

# 合计查询：同 FROM 同 WHERE，仅去 GROUP BY / ORDER BY / LIMIT
TOTALS_SQL_HEAD = (
    "SELECT COALESCE(SUM(CAST(NULLIF(TRIM(e.expense_realamt), '') AS DECIMAL(20, 2))), 0) AS sales_amount, "
    "COUNT(*) AS order_count, "
    "COUNT(DISTINCT e.expense_mobile) AS buyer_count "
    "FROM expense_calendar_info e "
    "LEFT JOIN member_info m ON e.expense_mobile = m.member_mobile "
    "WHERE (e.expense_state = '10') AND (e.del_flag = '0') "
    "AND ((m.del_flag = '0' OR m.member_mobile IS NULL)) "
    "AND e.expense_buyDate >= '%(start)s' "
    "AND e.expense_buyDate < DATE_ADD('%(end)s', INTERVAL 1 DAY)"
)

# 不 join 的单表基线（用于对账：join 后的 order_count 必须等于此值，否则关联键非 1:1）
BASE_NO_JOIN_SQL = (
    "SELECT COUNT(*) AS order_count, "
    "COALESCE(SUM(CAST(NULLIF(TRIM(e.expense_realamt), '') AS DECIMAL(20, 2))), 0) AS sales_amount "
    "FROM expense_calendar_info e "
    "WHERE (e.expense_state = '10') AND (e.del_flag = '0') "
    "AND e.expense_buyDate >= '%(start)s' "
    "AND e.expense_buyDate < DATE_ADD('%(end)s', INTERVAL 1 DAY)"
)


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
# 本脚本测"真实耗时"，放开 session 超时；应用侧仍是 30s / hint 25s，不可混淆
cur.execute("SET SESSION MAX_EXECUTION_TIME=600000")
cur.execute("START TRANSACTION READ ONLY")

win = {"start": WIN_START, "end": WIN_END}


def one(sql, label):
    t0 = time.time()
    try:
        cur.execute(sql)
        print("[ONE ] %-42s => %s  (%.0fms)" % (label, cur.fetchone(), (time.time() - t0) * 1000))
    except Exception as e:
        print("[FAIL] %-42s => %s" % (label, e))


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


print("=" * 96)
print("--- A. 关联键索引：expense_calendar_info 是否有 expense_mobile / 主表可用索引 ---")
many("SHOW INDEX FROM expense_calendar_info", "SHOW INDEX expense_calendar_info")

print("\n--- B. 关联键索引：member_info 的 member_mobile 是否有唯一/普通索引（join 探测关键） ---")
many("SHOW INDEX FROM member_info", "SHOW INDEX member_info")

print("\n--- C. 基数正确性：member_mobile 是否 1:1（>1 说明 LEFT JOIN 会复制订单行 → SUM 虚高） ---")
dup = many(
    "SELECT member_mobile, COUNT(*) c FROM member_info WHERE del_flag='0' "
    "AND member_mobile IS NOT NULL AND member_mobile <> '' "
    "GROUP BY member_mobile HAVING COUNT(*) > 1 ORDER BY c DESC LIMIT 10",
    "member_info 内重复 member_mobile Top10")
print("    >>> 重复手机号分组数 =", len(dup), "（0 表示关联键唯一，join 不放大订单行数）")

print("\n--- D. 窗口内数据分布（确认固定窗口有数据，否则耗时/对账无意义） ---")
many("SELECT COUNT(*) AS orders FROM expense_calendar_info e "
     "WHERE e.expense_state='10' AND e.del_flag='0' "
     "AND e.expense_buyDate >= '%(start)s' AND e.expense_buyDate < DATE_ADD('%(end)s', INTERVAL 1 DAY)" % win,
     "窗口内订单头行数")

print("\n--- E. 不 join 单表基线 vs join 合计：order_count 必须相等（相等=无放大） ---")
# 列序：BASE_NO_JOIN=(order_count, sales_amount)；TOTALS=(sales_amount, order_count, buyer_count)
base = []
t0 = time.time()
try:
    cur.execute(BASE_NO_JOIN_SQL % win)
    base = cur.fetchone()
    print("[ONE ] 单表基线(不join)  (%.0fms) => orders=%s, sales=%s" % ((time.time() - t0) * 1000, base[0], base[1]))
except Exception as e:
    print("[FAIL] 单表基线 => %s" % e)
tot = []
t0 = time.time()
try:
    cur.execute(TOTALS_SQL_HEAD % win)
    tot = cur.fetchone()
    print("[ONE ] join 合计        (%.0fms) => sales=%s, orders=%s, buyers=%s" % ((time.time() - t0) * 1000, tot[0], tot[1], tot[2]))
except Exception as e:
    print("[FAIL] join 合计 => %s" % e)
if base and tot:
    join_orders = tot[1]
    base_orders = base[0]
    print("    >>> 放大判定：join orders(%s) - 基线 orders(%s) = %s ；差额=0 即关联键 1:1，SUM 可信"
          % (join_orders, base_orders, join_orders - base_orders))

print("\n--- F. join 分组查询真实耗时：热跑两次（第二次看缓冲池命中） ---")
one(JOIN_SQL_HEAD % win, "join 分组·第1次")
one(JOIN_SQL_HEAD % win, "join 分组·第2次")

print("\n--- G. 执行计划：是否走索引 / filesort / 全表扫描 ---")
many("EXPLAIN " + (JOIN_SQL_HEAD % win), "EXPLAIN join 分组")

print("\n--- H. 分组结果本身（人工核对男/女/未知三行是否合理） ---")
many(JOIN_SQL_HEAD % win, "join 分组结果")

print("\n" + "=" * 96)
conn.rollback()
cur.close()
conn.close()
print("[DONE] 路线 C join 只读探测结束，事务已 rollback。")
