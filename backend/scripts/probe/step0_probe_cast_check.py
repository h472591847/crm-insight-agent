# -*- coding: utf-8 -*-
"""Step 0 探测（第四轮）：精确判定"非数字"是否真的会造成金额静默丢失，并补齐异常 SKU 定位。

背景：第三轮用正则 ^-?[0-9]+(\\.[0-9]+)?$ 判定出 7871 条"非数字"，但取值实为 '.4'、'.25'
一类合法 MySQL 数字字面量，CAST 可能正常转换。因此必须以 CAST 结果是否为 NULL 为准，
而不是以正则为准——否则会向业务谎报数据风险。

只读：START TRANSACTION READ ONLY + 结束 rollback。
用法：python scripts/probe/step0_probe_cast_check.py
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
    read_timeout=900,
)
cur = conn.cursor()
try:
    cur.execute("SET SESSION MAX_EXECUTION_TIME=900000")
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


CAST = "CAST(NULLIF(TRIM({c}),'') AS DECIMAL(20,2))"

print("=" * 90)
print("--- 1. CAST 是否真的失败（以转换结果为 NULL 为准，而非正则） ---")
# 仅测两个真正影响指标的字段：第一轮已证实 info.expense_realamt 无空无非数字，无需重扫 1529万行
for tbl, col in (("expense_calendar_details", "expense_d_realamt"),
                 ("expense_calendar_info", "gross_margin")):
    many("SELECT COUNT(*) total_rows, "
         "SUM(CASE WHEN TRIM({c}) IS NULL OR TRIM({c})='' THEN 1 ELSE 0 END) blank, "
         "SUM(CASE WHEN {c} NOT REGEXP '^-?[0-9]+([.][0-9]+)?$' THEN 1 ELSE 0 END) regex_bad, "
         "SUM(CASE WHEN {cast} IS NULL AND {c} IS NOT NULL AND TRIM({c})<>'' THEN 1 ELSE 0 END) cast_null "
         "FROM {t} WHERE del_flag='0'".format(c=col, cast=CAST.format(c=col), t=tbl),
         "CAST 判定 %s.%s" % (tbl, col))

print("\n--- 2. 明细数量字段同样判定 ---")
many("SELECT COUNT(*) total_rows, "
     "SUM(CASE WHEN {c} NOT REGEXP '^-?[0-9]+([.][0-9]+)?$' THEN 1 ELSE 0 END) regex_bad, "
     "SUM(CASE WHEN CAST(NULLIF(TRIM({c}),'') AS DECIMAL(20,4)) IS NULL AND {c} IS NOT NULL "
     "AND TRIM({c})<>'' THEN 1 ELSE 0 END) cast_null "
     "FROM expense_calendar_details WHERE del_flag='0'".format(c="expense_d_qty"),
     "CAST 判定 details.expense_d_qty")

print("\n--- 3. 候选窗口 2026-06-01~27 内异常 SKU 的排行位置 ---")
many("SELECT expense_d_gdcode sku, MAX(expense_d_commName) nm, COUNT(*) line_cnt, "
     "COALESCE(SUM(CAST(NULLIF(TRIM(expense_d_realamt),'') AS DECIMAL(20,2))),0) sales "
     "FROM expense_calendar_details "
     "WHERE expense_d_state='10' AND del_flag='0' "
     "AND expense_d_buyDate >= '2026-06-01' AND expense_d_buyDate < '2026-06-28' "
     "AND expense_d_gdcode REGEXP '^[0-9]+[.][0-9]+$' "
     "GROUP BY expense_d_gdcode ORDER BY sales DESC",
     "窗口内异常SKU聚合")

print("\n--- 4. 跳表新鲜度差异（只读安全形式，不做 1529 万行关联子查询） ---")
many("SELECT MIN(expense_d_buyDate) d_min, MAX(expense_d_buyDate) d_max "
     "FROM expense_calendar_details "
     "WHERE expense_d_buyDate >= '2026-06-28' AND del_flag='0'",
     "details 在 info 上界之后的数据范围")

print("\n" + "=" * 90)
conn.rollback()
cur.close()
conn.close()
print("[DONE] 第四轮只读探测结束，事务已 rollback。")
