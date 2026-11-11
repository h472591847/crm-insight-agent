# -*- coding: utf-8 -*-
"""Step 0 探测（第二轮）：固定历史区间选择 + 上线 SQL 的真实耗时与执行计划。

目的：
1. 上一轮发现三表时间覆盖不一致（info 只到 2026-06-27，details 到 2026-10-22），需据此选定新的固定对账区间；
2. 真实库 details 有 1529 万行，需实测将要上线的聚合 SQL 是否会撞上 MetricsLimits 的 10s 超时 / 8s hint 上限；
3. 复核上一轮因 MySQL 保留字(lines)失败的探测项，以及租户列填充率、异常 SKU 是否会挤进商品排行 Top10。

只读：连接后立即 START TRANSACTION READ ONLY，结束 rollback；不打印凭证与个人级数据。
用法：python scripts/probe/step0_probe_window_perf.py
"""
import os
import time

import pymysql

ENV_PATH = os.path.join("mcp-server", "src", "main", "resources", ".env")


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
try:
    # 本脚本要测"真实耗时"，所以放开超时；应用侧仍是 10s，不可混淆
    cur.execute("SET SESSION MAX_EXECUTION_TIME=600000")
except Exception:
    pass
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


STAMP = "'2026-06-01'", "'2026-06-28'"  # 左闭右开候选窗口（6/1 ~ 6/27 全天）

print("=" * 90)
print("--- A. 时间覆盖复核：info 与 details 的数据新鲜度是否一致 ---")
one("SELECT MAX(expense_buyDate) FROM expense_calendar_info WHERE del_flag='0'", "info 最大消费时间")
one("SELECT MAX(expense_d_buyDate) FROM expense_calendar_details WHERE del_flag='0'", "details 最大消费时间")
one("SELECT MAX(member_created) FROM member_info WHERE del_flag='0'", "member 最大创建时间")
one("SELECT COUNT(*) FROM expense_calendar_details "
    "WHERE expense_d_buyDate >= '2026-07-01' AND del_flag='0'", "details 2026-07 之后行数")
one("SELECT COUNT(*) FROM expense_calendar_info "
    "WHERE expense_buyDate >= '2026-07-01' AND del_flag='0'", "info 2026-07 之后行数")

print("\n--- B. 候选窗口 2026-06-01~27 的数据体量 ---")
one("SELECT COUNT(*) FROM expense_calendar_info WHERE expense_state='10' AND del_flag='0' "
    "AND expense_buyDate >= %s AND expense_buyDate < %s" % STAMP, "info 窗口订单数")
one("SELECT COUNT(*) FROM expense_calendar_details WHERE expense_d_state='10' AND del_flag='0' "
    "AND expense_d_buyDate >= %s AND expense_d_buyDate < %s" % STAMP, "details 窗口明细数")

print("\n--- C. 上一轮保留字失败的项（改为 cnt） ---")
many("SELECT DATE_FORMAT(expense_d_buyDate,'%Y-%m') ym, COUNT(*) cnt "
     "FROM expense_calendar_details WHERE expense_d_state='10' AND del_flag='0' "
     "AND expense_d_buyDate >= DATE_SUB(CURDATE(), INTERVAL 10 MONTH) GROUP BY ym ORDER BY ym",
     "details 近10个月按月分布")

print("\n--- D. 租户列填充率（决定 tenant 过滤是否可用） ---")
one("SELECT COUNT(*), SUM(CASE WHEN expense_tenant IS NULL OR TRIM(expense_tenant)='' THEN 1 ELSE 0 END) "
    "FROM expense_calendar_info WHERE del_flag='0'", "info 总行/租户为空")
one("SELECT COUNT(*), SUM(CASE WHEN member_tenant IS NULL OR TRIM(member_tenant)='' THEN 1 ELSE 0 END) "
    "FROM member_info WHERE del_flag='0'", "member 总行/租户为空")
one("SELECT COUNT(*), SUM(CASE WHEN expense_d_stationCode IS NULL OR TRIM(expense_d_stationCode)='' "
    "THEN 1 ELSE 0 END) FROM expense_calendar_details WHERE del_flag='0'", "details 总行/空门店编码")

print("\n--- E. 将要上线的 SQL 真实耗时（应用侧上限 10s / hint 8s） ---")
many("SELECT expense_stationCode code, MAX(expense_stationName) nm, "
     "COALESCE(SUM(CAST(NULLIF(TRIM(expense_realamt),'') AS DECIMAL(20,2))),0) sales, COUNT(*) cnt "
     "FROM expense_calendar_info "
     "WHERE expense_state='10' AND del_flag='0' "
     "AND expense_buyDate >= '2025-05-01' AND expense_buyDate < '2026-10-31' "
     "GROUP BY expense_stationCode ORDER BY sales DESC LIMIT 5",
     "门店业绩·18个月窗口 Top5")
many("SELECT expense_stationCode code, MAX(expense_stationName) nm, "
     "COALESCE(SUM(CAST(NULLIF(TRIM(expense_realamt),'') AS DECIMAL(20,2))),0) sales, COUNT(*) cnt "
     "FROM expense_calendar_info "
     "WHERE expense_state='10' AND del_flag='0' "
     "AND expense_buyDate >= %s AND expense_buyDate < %s "
     "GROUP BY expense_stationCode ORDER BY sales DESC LIMIT 5" % STAMP,
     "门店业绩·单月窗口 Top5")
many("SELECT expense_d_gdcode sku, MAX(expense_d_commName) nm, "
     "COALESCE(SUM(CAST(NULLIF(TRIM(expense_d_realamt),'') AS DECIMAL(20,2))),0) sales, "
     "COALESCE(SUM(CAST(NULLIF(TRIM(expense_d_qty),'') AS DECIMAL(20,4))),0) qty "
     "FROM expense_calendar_details "
     "WHERE expense_d_state='10' AND del_flag='0' "
     "AND expense_d_buyDate >= %s AND expense_d_buyDate < %s "
     "GROUP BY expense_d_gdcode ORDER BY sales DESC LIMIT 10" % STAMP,
     "商品排行·单月窗口 Top10")
many("SELECT expense_d_gdcode sku, MAX(expense_d_commName) nm, "
     "COALESCE(SUM(CAST(NULLIF(TRIM(expense_d_realamt),'') AS DECIMAL(20,2))),0) sales "
     "FROM expense_calendar_details "
     "WHERE expense_d_state='10' AND del_flag='0' "
     "AND expense_d_buyDate >= '2025-05-01' AND expense_d_buyDate < '2026-10-31' "
     "GROUP BY expense_d_gdcode ORDER BY sales DESC LIMIT 5",
     "商品排行·18个月窗口 Top5")

print("\n--- F. 执行计划：窗口过滤是否走到索引 ---")
many("EXPLAIN SELECT expense_d_gdcode, SUM(CAST(NULLIF(TRIM(expense_d_realamt),'') AS DECIMAL(20,2))) "
     "FROM expense_calendar_details "
     "WHERE expense_d_state='10' AND del_flag='0' "
     "AND expense_d_buyDate >= %s AND expense_d_buyDate < %s "
     "GROUP BY expense_d_gdcode" % STAMP, "EXPLAIN details 单月聚合")
many("EXPLAIN SELECT expense_stationCode, SUM(CAST(NULLIF(TRIM(expense_realamt),'') AS DECIMAL(20,2))) "
     "FROM expense_calendar_info "
     "WHERE expense_state='10' AND del_flag='0' "
     "AND expense_buyDate >= %s AND expense_buyDate < %s "
     "GROUP BY expense_stationCode" % STAMP, "EXPLAIN info 单月聚合")

print("\n--- G. 品类取值：93 个去重值里是否有可用的品类/香型 ---")
many("SELECT NULLIF(TRIM(expense_d_commCodeName),'') cat, COUNT(*) cnt "
     "FROM expense_calendar_details WHERE del_flag='0' "
     "GROUP BY cat ORDER BY cnt DESC LIMIT 20", "品类取值 Top20")

print("\n--- H. 门店毛利字段（Phase 2 前置） ---")
one("SELECT COUNT(*), SUM(CASE WHEN gross_margin IS NULL OR TRIM(gross_margin)='' THEN 1 ELSE 0 END), "
    "SUM(CASE WHEN gross_margin REGEXP '^-?[0-9]+(\\\\.[0-9]+)?$' THEN 1 ELSE 0 END) "
    "FROM expense_calendar_info WHERE del_flag='0'", "gross_margin 总数/空/合法数字")

print("\n" + "=" * 90)
conn.rollback()
cur.close()
conn.close()
print("[DONE] 第二轮只读探测结束，事务已 rollback。")
