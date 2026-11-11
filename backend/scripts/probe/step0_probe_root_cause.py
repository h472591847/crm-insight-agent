# -*- coding: utf-8 -*-
"""Step 0 探测（第三轮）：定位 18 个月商品排行为何耗时 75s，以及脏值金额的量级影响。

只读：START TRANSACTION READ ONLY + 结束 rollback；不打印凭证与个人级数据。
用法：python scripts/probe/step0_probe_root_cause.py
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
    cur.execute("SET SESSION MAX_EXECUTION_TIME=600000")
except Exception:
    pass
cur.execute("START TRANSACTION READ ONLY")


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


RANK_18M = ("SELECT expense_d_gdcode sku, MAX(expense_d_commName) nm, "
            "COALESCE(SUM(CAST(NULLIF(TRIM(expense_d_realamt),'') AS DECIMAL(20,2))),0) sales "
            "FROM expense_calendar_details "
            "WHERE expense_d_state='10' AND del_flag='0' "
            "AND expense_d_buyDate >= '2025-05-01' AND expense_d_buyDate < '2026-10-31' "
            "GROUP BY expense_d_gdcode ORDER BY sales DESC LIMIT 5")

print("=" * 90)
print("--- 1. 18个月商品排行的执行计划（第二轮实测 75.8s） ---")
many("EXPLAIN " + RANK_18M, "EXPLAIN 18个月商品排行")

print("\n--- 2. 强制走 index_buyDate 后的耗时 ---")
many(RANK_18M.replace("FROM expense_calendar_details ",
                      "FROM expense_calendar_details FORCE INDEX (index_buyDate) "),
     "18个月商品排行 FORCE INDEX(index_buyDate)")

print("\n--- 3. 窗口内实际参与聚合的行数（判断是否真需要更大索引） ---")
many("SELECT COUNT(*) cnt FROM expense_calendar_details "
     "WHERE expense_d_state='10' AND del_flag='0' "
     "AND expense_d_buyDate >= '2025-05-01' AND expense_d_buyDate < '2026-10-31'",
     "18个月窗口明细行数")

print("\n--- 4. 只按 buyDate 过滤（去掉 state/del_flag）是否更快：判断优化器选型 ---")
many("EXPLAIN SELECT expense_d_gdcode, SUM(CAST(NULLIF(TRIM(expense_d_realamt),'') AS DECIMAL(20,2))) s "
     "FROM expense_calendar_details "
     "WHERE expense_d_buyDate >= '2025-05-01' AND expense_d_buyDate < '2026-10-31' "
     "GROUP BY expense_d_gdcode ORDER BY s DESC LIMIT 5",
     "EXPLAIN 仅按日期过滤")

print("\n--- 5. expense_d_realamt 非数字脏值的量级（是否影响排行） ---")
many("SELECT COUNT(*) bad_rows, COUNT(DISTINCT expense_d_gdcode) bad_sku, "
     "SUM(CHAR_LENGTH(expense_d_realamt)) chars "
     "FROM expense_calendar_details "
     "WHERE del_flag='0' AND expense_d_realamt IS NOT NULL AND TRIM(expense_d_realamt)<>'' "
     "AND expense_d_realamt NOT REGEXP '^-?[0-9]+([.][0-9]+)?$'",
     "脏值行数/涉及SKU数")
many("SELECT expense_d_realamt bad_val, COUNT(*) c "
     "FROM expense_calendar_details "
     "WHERE del_flag='0' AND expense_d_realamt IS NOT NULL AND TRIM(expense_d_realamt)<>'' "
     "AND expense_d_realamt NOT REGEXP '^-?[0-9]+([.][0-9]+)?$' "
     "GROUP BY expense_d_realamt ORDER BY c DESC LIMIT 10",
     "脏值取值 Top10（非个人数据）")
many("SELECT COUNT(*) in_window_bad FROM expense_calendar_details "
     "WHERE expense_d_state='10' AND del_flag='0' "
     "AND expense_d_buyDate >= '2026-06-01' AND expense_d_buyDate < '2026-06-28' "
     "AND expense_d_realamt NOT REGEXP '^-?[0-9]+([.][0-9]+)?$'",
     "候选窗口内的脏值行数")

print("\n--- 6. 异常 SKU 在候选窗口的排行位置（决定是否需要过滤口径） ---")
many("SELECT expense_d_gdcode sku, MAX(expense_d_commName) nm, "
     "COUNT(*) lines, "
     "COALESCE(SUM(CAST(NULLIF(TRIM(expense_d_realamt),'') AS DECIMAL(20,2))),0) sales "
     "FROM expense_calendar_details "
     "WHERE expense_d_state='10' AND del_flag='0' "
     "AND expense_d_buyDate >= '2026-06-01' AND expense_d_buyDate < '2026-06-28' "
     "AND expense_d_gdcode REGEXP '^[0-9]+[.][0-9]+$' "
     "GROUP BY expense_d_gdcode ORDER BY sales DESC",
     "候选窗口内异常SKU聚合")

print("\n--- 7. 订单头与明细同窗口金额是否自洽（跨表新鲜度差异验证） ---")
many("SELECT 'info' src, COALESCE(SUM(CAST(NULLIF(TRIM(expense_realamt),'') AS DECIMAL(20,2))),0) sales, "
     "COUNT(*) c FROM expense_calendar_info WHERE expense_state='10' AND del_flag='0' "
     "AND expense_buyDate >= '2026-06-01' AND expense_buyDate < '2026-06-28' "
     "UNION ALL "
     "SELECT 'details', COALESCE(SUM(CAST(NULLIF(TRIM(expense_d_realamt),'') AS DECIMAL(20,2))),0), COUNT(*) "
     "FROM expense_calendar_details WHERE expense_d_state='10' AND del_flag='0' "
     "AND expense_d_buyDate >= '2026-06-01' AND expense_d_buyDate < '2026-06-28'",
     "同窗口 info vs details 金额")

print("\n" + "=" * 90)
conn.rollback()
cur.close()
conn.close()
print("[DONE] 第三轮只读探测结束，事务已 rollback。")
