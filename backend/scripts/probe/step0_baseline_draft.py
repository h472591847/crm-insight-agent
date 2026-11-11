# -*- coding: utf-8 -*-
"""Step 0 探测（第五轮）：采集真实库 crmtest 的新基线数字，直接输出 YAML 草稿。

对账窗口：2026-06-01 ~ 2026-06-27（左闭右开 06-28），选定依据见探测结论——
该窗口是三表数据量最大且连续的完整历史月份，info 与 details 均有 18万+/26万+ 行，
单月聚合实测耗时 1~1.2s，在应用侧 10s 超时内可稳定复现。

只读：START TRANSACTION READ ONLY + 结束 rollback；输出仅为聚合值。
用法：python scripts/probe/step0_baseline_draft.py
"""
import os
import time
from decimal import Decimal

import pymysql

ENV_PATH = os.path.join("mcp-server", "src", "main", "resources", ".env")
START, END_EXCL = "2026-06-01", "2026-06-28"


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
    read_timeout=300,
)
cur = conn.cursor()
cur.execute("START TRANSACTION READ ONLY")


def q1(sql):
    t0 = time.time()
    cur.execute(sql)
    r = cur.fetchone()
    q1.last_ms = (time.time() - t0) * 1000
    return r


def qall(sql):
    t0 = time.time()
    cur.execute(sql)
    r = cur.fetchall()
    qall.last_ms = (time.time() - t0) * 1000
    return r


def num(v):
    if isinstance(v, Decimal):
        s = format(v, "f")
        return s
    return str(v)


STAMP = "expense_buyDate >= '%s' AND expense_buyDate < '%s'" % (START, END_EXCL)
DSTAMP = "expense_d_buyDate >= '%s' AND expense_d_buyDate < '%s'" % (START, END_EXCL)
AMT = "CAST(NULLIF(TRIM(expense_realamt),'') AS DECIMAL(20,2))"
DAMT = "CAST(NULLIF(TRIM(expense_d_realamt),'') AS DECIMAL(20,2))"
DQTY = "CAST(NULLIF(TRIM(expense_d_qty),'') AS DECIMAL(20,4))"

print("=" * 70)
print("采集开始，窗口 %s ~ %s（左闭右开 %s）" % (START, END_EXCL[:8], END_EXCL))

m = q1("SELECT COUNT(*), SUM(CASE WHEN member_sex=1 THEN 1 ELSE 0 END), "
       "SUM(CASE WHEN member_sex=2 THEN 1 ELSE 0 END), "
       "SUM(CASE WHEN member_sex IS NULL OR member_sex NOT IN (1,2) THEN 1 ELSE 0 END), "
       "COUNT(CASE WHEN member_expense_totalsum>0 THEN 1 END), "
       "COALESCE(SUM(member_expense_totalsum),0) "
       "FROM member_info WHERE del_flag='0'")
member_ms = q1.last_ms

s = qall("SELECT expense_stationCode, MAX(expense_stationName), COALESCE(SUM(%s),0), COUNT(*) "
         "FROM expense_calendar_info WHERE expense_state='10' AND del_flag='0' AND %s "
         "GROUP BY expense_stationCode ORDER BY 3 DESC" % (AMT, STAMP))
station_ms = qall.last_ms
station_groups = len(s)
station_total_sales = sum(r[2] for r in s)
station_total_orders = sum(r[3] for r in s)
unknown_rows = [r for r in s if r[0] is None or str(r[0]).strip() == ""]
top1 = s[0]

p = qall("SELECT expense_d_gdcode, MAX(expense_d_commName), COALESCE(SUM(%s),0), COALESCE(SUM(%s),0) "
         "FROM expense_calendar_details WHERE expense_d_state='10' AND del_flag='0' AND %s "
         "GROUP BY expense_d_gdcode ORDER BY 3 DESC LIMIT 10" % (DAMT, DQTY, DSTAMP))
product_ms = qall.last_ms
ptop = q1("SELECT COUNT(*) FROM expense_calendar_details "
          "WHERE expense_d_state='10' AND del_flag='0' AND %s" % DSTAMP)
product_window_rows = ptop[0]

out = []
out.append("# crmtest 真实测试库 Phase 1 对账基线（草稿，由探测脚本实测生成，需业务复核后启用）")
out.append("# 生成方式: python scripts/probe/step0_baseline_draft.py")
out.append("# 环境: 库=crmtest  数据为只读聚合，不含个人级字段")
out.append("meta:")
out.append("  database: crmtest")
out.append("  capturedWindow:")
out.append("    start: '%s'" % START)
out.append("    endExclusive: '%s'" % END_EXCL)
out.append("  tableRows:")
out.append("    expenseCalendarInfo: %s" % num(q1("SELECT COUNT(*) FROM expense_calendar_info")[0]))
out.append("    expenseCalendarDetails: %s" % num(q1("SELECT COUNT(*) FROM expense_calendar_details")[0]))
out.append("    memberInfo: %s" % num(q1("SELECT COUNT(*) FROM member_info")[0]))
out.append("  probeLatencyMs:")
out.append("    memberOverview: %.0f" % member_ms)
out.append("    stationPerformanceAllGroups: %.0f" % station_ms)
out.append("    productRankingTop10: %.0f" % product_ms)
out.append("")
out.append("memberOverview:            # 全局，无 tenant/station 过滤（del_flag='0'）")
out.append("  totalMembers: %s" % num(m[0]))
out.append("  maleCount: %s" % num(m[1]))
out.append("  femaleCount: %s" % num(m[2]))
out.append("  unknownCount: %s" % num(m[3]))
out.append("  expenseMemberCount: %s" % num(m[4]))
out.append("  totalExpenseAmount: %s" % num(m[5]))
out.append("  selfCheck: %s" % ("male+female+unknown == total  # 请在启用前确认"))
out.append("")
out.append("stationPerformance:        # 窗口 %s ~ %s" % (START, END_EXCL[:8]))
out.append("  stationGroupCount: %d" % station_groups)
out.append("  totalSalesAmount: %s" % num(station_total_sales))
out.append("  totalOrderCount: %s" % num(station_total_orders))
out.append("  blankStationCodeGroups: %d   # 归并为\"未知门店\"的组数" % len(unknown_rows))
out.append("  top1:")
out.append("    stationCode: '%s'" % top1[0])
out.append("    stationName: '%s'" % str(top1[1]).replace("'", "''"))
out.append("    salesAmount: %s" % num(top1[2]))
out.append("    orderCount: %s" % num(top1[3]))
out.append("")
out.append("productRanking:            # 窗口同上，按销售额降序 Top10")
out.append("  windowDetailRowCount: %s" % num(product_window_rows))
out.append("  top10:")
for i, r in enumerate(p, 1):
    out.append("    - rank: %d" % i)
    out.append("      sku: '%s'" % r[0])
    out.append("      productName: '%s'" % str(r[1]).replace("'", "''"))
    out.append("      salesAmount: %s" % num(r[2]))
    out.append("      quantity: %s" % num(r[3]))

text = "\n".join(out)
print("\n" + text)

draft_dir = os.path.join("mcp-server", "src", "test", "resources", "baseline")
os.makedirs(draft_dir, exist_ok=True)
draft_path = os.path.join(draft_dir, "crmtest-baseline.DRAFT.yaml")
with open(draft_path, "w", encoding="utf-8") as fh:
    fh.write(text + "\n")
print("\n[saved draft] %s" % os.path.abspath(draft_path))
print("[NOTE] 文件名带 .DRAFT，未被任何测试读取；复核后再改名为 crmtest-baseline.yaml")

conn.rollback()
cur.close()
conn.close()
print("[DONE] 只读采集结束，事务已 rollback。")
