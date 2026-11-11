"""Step 0 基线采集（只读）：为真实库 crmtest 生成 Phase 1 + Phase 2 全部指标的对账基线 YAML。

输出文件：mcp-server/src/test/resources/baseline/crmtest-baseline.yaml
被 src/test/java/com/itzixi/metrics/BaselineFixtures.java 读取。

安全约束：
- 全程 START TRANSACTION READ ONLY，结束 rollback，只执行 SELECT；
- 只输出聚合数字，不输出任何个人级字段与生日原值；
- 凭证运行时从 mcp-server/src/main/resources/.env 读取，不打印。

业务口径依据（2026-10-30 产品确认，已写入 DEVELOPMENT_LOG）：
- 取消 tenant 维度/过滤，统计聚焦门店；
- 异常 SKU 按真实商品统计，不过滤不合并且允许进榜；
- 年龄由阴历生日 member_yin_birthday 计算，接受与阳历直比的周岁偏差。
"""
import re
import time
import pymysql

ENV_PATH = r"D:\java_project\spring-ai-mcp-rag-dev\mcp-server\src\main\resources\.env"
OUT_PATH = r"D:\java_project\spring-ai-mcp-rag-dev\mcp-server\src\test\resources\baseline\crmtest-baseline.yaml"

START = "2026-06-01"
END = "2026-06-27"
END_EXCLUSIVE = "2026-06-28"


def read_env(path):
    env = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            m = re.match(r'^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$', line)
            if m:
                env[m.group(1)] = m.group(2).strip().strip('"').strip("'")
    return env


env = read_env(ENV_PATH)
conn = pymysql.connect(
    host=env.get("MYSQL_HOST", "127.0.0.1"),
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

OUT = {}
MS = {}


def one(sql, args=None, key=None):
    """执行单行聚合查询，返回 dict。"""
    t0 = time.perf_counter()
    cur.execute(sql, args if args else None)
    cols = [d[0] for d in cur.description]
    row = dict(zip(cols, cur.fetchone()))
    MS[key or 'misc'] = round((time.perf_counter() - t0) * 1000)
    return row


def rows(sql, args=None, key=None):
    t0 = time.perf_counter()
    cur.execute(sql, args if args else None)
    cols = [d[0] for d in cur.description]
    out = [dict(zip(cols, r)) for r in cur.fetchall()]
    MS[key or 'misc'] = round((time.perf_counter() - t0) * 1000)
    return out


print("采集开始：窗口 %s ~ %s（左闭右开 %s）" % (START, END, END_EXCLUSIVE))

# ---------------------------------------------------------------- 元信息
meta = {
    "database": "crmtest",
    "capturedAt": time.strftime("%Y-%m-%d"),
    "start": START,
    "endExclusive": END_EXCLUSIVE,
}
meta["tableRows"] = {
    "expenseCalendarInfo": one("SELECT COUNT(*) AS c FROM expense_calendar_info", key="rows_info")["c"],
    "expenseCalendarDetails": one("SELECT COUNT(*) AS c FROM expense_calendar_details", key="rows_detail")["c"],
    "memberInfo": one("SELECT COUNT(*) AS c FROM member_info_view", key="rows_member")["c"],
}

# ---------------------------------------------------------------- 会员总览
mo = one("""
    SELECT COUNT(*)                                                              AS total_members,
           SUM(member_sex = 1)                                                   AS male_count,
           SUM(member_sex = 2)                                                   AS female_count,
           SUM(member_sex IS NULL OR member_sex NOT IN (1, 2))                    AS unknown_count,
           SUM(COALESCE(member_expense_totalsum, 0) > 0)                          AS expense_member_count,
           COALESCE(SUM(COALESCE(member_expense_totalsum, 0)), 0)                 AS total_expense_amount
    FROM member_info_view WHERE del_flag = '0'
""", key="memberOverview")
member_overview = {k: str(v) for k, v in mo.items()}

# ---------------------------------------------------------------- 门店业绩
# 分组表达式必须与指标配置（order.yaml STATION 维度 = expense_stationCode）及 Phase 1 手写 XML
# 完全一致：不加 TRIM。MySQL 的 utf8 比较规则本就忽略尾部空格，TRIM 反而会让基线与指标口径偏离。
sp_rows = rows("""
    SELECT expense_stationCode AS code, MAX(expense_stationName) AS name,
           COALESCE(SUM(CAST(NULLIF(TRIM(expense_realamt), '') AS DECIMAL(20,2))), 0) AS sales,
           COUNT(*) AS orders
    FROM expense_calendar_info
    WHERE expense_state = '10' AND del_flag = '0'
      AND expense_buyDate >= %s AND expense_buyDate < %s
    GROUP BY expense_stationCode
    ORDER BY sales DESC
""", (START, END_EXCLUSIVE), key="stationPerformance")
blank_groups = [r for r in sp_rows if not r["code"]]
top1 = sp_rows[0]
station_performance = {
    "groupCount": len(sp_rows),
    "totalSalesAmount": str(sum(r["sales"] for r in sp_rows)),
    "totalOrderCount": sum(r["orders"] for r in sp_rows),
    "blankStationCodeGroups": len(blank_groups),
    "top1": {
        "stationCode": top1["code"], "stationName": top1["name"],
        "salesAmount": str(top1["sales"]), "orderCount": top1["orders"],
    },
}

# ---------------------------------------------------------------- 商品排行 Top10（异常 SKU 按真实商品统计，不过滤）
pr_rows = rows("""
    SELECT expense_d_gdcode AS sku, MAX(expense_d_commName) AS name,
           COALESCE(SUM(CAST(NULLIF(TRIM(expense_d_realamt), '') AS DECIMAL(20,2))), 0) AS sales,
           COALESCE(SUM(CAST(NULLIF(TRIM(expense_d_qty), '') AS DECIMAL(20,4))), 0)     AS qty
    FROM expense_calendar_details
    WHERE expense_d_state = '10' AND del_flag = '0'
      AND expense_d_buyDate >= %s AND expense_d_buyDate < %s
    GROUP BY expense_d_gdcode
    ORDER BY sales DESC
    LIMIT 10
""", (START, END_EXCLUSIVE), key="productRanking")
pr_window = one("""
    SELECT COUNT(*) AS c FROM expense_calendar_details
    WHERE expense_d_state = '10' AND del_flag = '0'
      AND expense_d_buyDate >= %s AND expense_d_buyDate < %s
""", (START, END_EXCLUSIVE), key="productRankingRows")
product_ranking = {
    "windowDetailRowCount": pr_window["c"],
    "top10": [{"rank": i + 1, "sku": r["sku"], "productName": r["name"],
               "salesAmount": str(r["sales"]), "quantity": str(r["qty"])}
              for i, r in enumerate(pr_rows)],
}

# ---------------------------------------------------------------- Phase 2 指标
active_rows = rows("""
    SELECT expense_stationCode AS code,
           COUNT(DISTINCT expense_mobile) AS active_members, COUNT(*) AS orders
    FROM expense_calendar_info
    WHERE expense_state = '10' AND del_flag = '0'
      AND expense_buyDate >= %s AND expense_buyDate < %s
    GROUP BY expense_stationCode
""", (START, END_EXCLUSIVE), key="activeMember")
active_totals = one("""
    SELECT COUNT(DISTINCT expense_mobile) AS active_members, COUNT(*) AS orders
    FROM expense_calendar_info
    WHERE expense_state = '10' AND del_flag = '0'
      AND expense_buyDate >= %s AND expense_buyDate < %s
""", (START, END_EXCLUSIVE), key="activeMemberTotal")

# storeGroupCount 必须是 GROUP BY member_store 的“结果行数”，与指标/测试 oracle 同语义：
# COUNT(DISTINCT member_store) 会忽略 NULL，比实际分组少 1 组（存在 member_store 为 NULL 的会员），
# 直接拿它当分组数会让基线与真实行为差一个组（2026-10-30 实跑发现 261 vs 260）。
new_members = one("""
    SELECT (SELECT COUNT(*) FROM member_info_view
             WHERE del_flag = '0' AND member_created >= %s AND member_created < %s)         AS total,
           (SELECT COUNT(*) FROM (SELECT 1 FROM member_info_view
                                    WHERE del_flag = '0'
                                      AND member_created >= %s AND member_created < %s
                                    GROUP BY member_store) g)                               AS store_groups,
           (SELECT COUNT(DISTINCT member_store) FROM member_info_view
             WHERE del_flag = '0' AND member_created >= %s AND member_created < %s)          AS distinct_stores
""", (START, END_EXCLUSIVE, START, END_EXCLUSIVE, START, END_EXCLUSIVE), key="memberNew")

level_dist = one("""
    SELECT COUNT(*) AS total,
           COUNT(NULLIF(TRIM(new_member_level_key), '')) AS filled,
           COUNT(DISTINCT NULLIF(TRIM(new_member_level_key), '')) AS distinct_values
    FROM member_info_view WHERE del_flag = '0'
""", key="levelDist")

# 年龄：阴历生日（varchar），先 STR_TO_DATE 再算周岁；只输出分桶计数
age_rows = rows("""
    SELECT CASE
             WHEN member_yin_birthday IS NULL OR TRIM(member_yin_birthday) = ''
                  OR STR_TO_DATE(member_yin_birthday, '%Y-%m-%d') IS NULL
                  OR YEAR(STR_TO_DATE(member_yin_birthday, '%Y-%m-%d')) < 1900 THEN '未知'
             WHEN TIMESTAMPDIFF(YEAR, STR_TO_DATE(member_yin_birthday, '%Y-%m-%d'), CURDATE()) <= 19 THEN '19岁及以下'
             WHEN TIMESTAMPDIFF(YEAR, STR_TO_DATE(member_yin_birthday, '%Y-%m-%d'), CURDATE()) <= 29 THEN '20-29岁'
             WHEN TIMESTAMPDIFF(YEAR, STR_TO_DATE(member_yin_birthday, '%Y-%m-%d'), CURDATE()) <= 39 THEN '30-39岁'
             WHEN TIMESTAMPDIFF(YEAR, STR_TO_DATE(member_yin_birthday, '%Y-%m-%d'), CURDATE()) <= 49 THEN '40-49岁'
             WHEN TIMESTAMPDIFF(YEAR, STR_TO_DATE(member_yin_birthday, '%Y-%m-%d'), CURDATE()) <= 59 THEN '50-59岁'
             ELSE '60岁及以上' END AS band,
           COUNT(*) AS members
    FROM member_info_view WHERE del_flag = '0'
    GROUP BY 1 ORDER BY members DESC
""", key="ageDist")
yin_filled = one("""
    SELECT COUNT(NULLIF(TRIM(member_yin_birthday), ''))  AS yin_filled,
           COUNT(NULLIF(TRIM(member_yang_birthday), '')) AS yang_filled,
           COUNT(NULLIF(TRIM(age), ''))                  AS age_col_filled
    FROM member_info_view WHERE del_flag = '0'
""", key="birthdayFill")

annual = one("""
    SELECT COALESCE(SUM(CAST(NULLIF(TRIM(expense_realamt), '') AS DECIMAL(20,2))), 0) AS total_consumption,
           COUNT(DISTINCT expense_mobile) AS expense_members
    FROM expense_calendar_info
    WHERE expense_state = '10' AND del_flag = '0'
      AND expense_buyDate >= %s AND expense_buyDate < %s
""", (START, END_EXCLUSIVE), key="annualAvg")

# ---------------------------------------------------------------- 数据质量（tenant 已取消，仅留证据）
dq = one("""
    SELECT SUM(expense_mobile IS NULL OR TRIM(expense_mobile) = '') AS info_mobile_blank,
           COUNT(*)                                                 AS info_effective_orders,
           SUM(expense_tenant IS NULL OR TRIM(expense_tenant) = '') AS info_tenant_blank,
           COUNT(DISTINCT NULLIF(TRIM(expense_tenant), ''))         AS info_tenant_distinct
    FROM expense_calendar_info WHERE expense_state = '10' AND del_flag = '0'
""", key="dqInfo")
dq_member = one("""
    SELECT COUNT(NULLIF(TRIM(member_tenant), '')) AS member_tenant_filled,
           COUNT(DISTINCT member_mobile)          AS distinct_mobiles
    FROM member_info_view WHERE del_flag = '0'
""", key="dqMember")
dq_detail = one("""
    SELECT SUM(CAST(NULLIF(TRIM(expense_d_realamt), '') AS DECIMAL(20,2)) IS NULL
               AND NULLIF(TRIM(expense_d_realamt), '') IS NOT NULL) AS amt_cast_null,
           SUM(del_flag = '1')                                      AS detail_deleted_rows
    FROM expense_calendar_details WHERE expense_d_state = '10'
""", key="dqDetail")

conn.rollback()

# ---------------------------------------------------------------- 写 YAML
L = []
w = L.append
w("# 真实测试库 crmtest 的对账基线（Phase 1 + Phase 2 全部指标）")
w("# 生成: python scripts/probe/step0_baseline_full.py   只读探测，无写入")
w("# 读取: src/test/java/com/itzixi/metrics/BaselineFixtures.java")
w("# 本文件随仓库提交（2026-10-30 用户确认）：真库对账测试需要它，不入库会导致新人克隆后测试报错。")
w("# 因此只允许写聚合数值与门店/商品名，禁止写入个人级字段（生日/手机号/地址）、凭证与明细结果。")
w("# 数值来源：2026-10-30 只读探测。主对账依赖测试内同刻执行的独立 oracle SQL 逐组比对；")
w("# 本文件的数值是二次校验（防止口径漂移），业务复核后如有出入以业务为准并重新生成本文件。")
w("meta:")
w("  database: %s" % meta["database"])
w("  capturedAt: '%s'" % meta["capturedAt"])
w("  window:")
w("    start: '%s'" % meta["start"])
w("    endExclusive: '%s'" % meta["endExclusive"])
w("  tableRows:")
for k, v in meta["tableRows"].items():
    w("    %s: %d" % (k, v))
w("  probeLatencyMs:")
for k, v in MS.items():
    w("    %s: %d" % (k, v))
w("")
w("memberOverview:            # 全局，del_flag='0'，无时间条件")
w("  totalMembers: %s" % member_overview["total_members"])
w("  maleCount: %s" % member_overview["male_count"])
w("  femaleCount: %s" % member_overview["female_count"])
w("  unknownCount: %s" % member_overview["unknown_count"])
w("  expenseMemberCount: %s" % member_overview["expense_member_count"])
w("  totalExpenseAmount: %s" % member_overview["total_expense_amount"])
w("  selfCheck: maleCount + femaleCount + unknownCount == totalMembers")
w("")
w("stationPerformance:        # 窗口同上，按 expense_stationCode 分组")
w("  groupCount: %d" % station_performance["groupCount"])
w("  totalSalesAmount: %s" % station_performance["totalSalesAmount"])
w("  totalOrderCount: %d" % station_performance["totalOrderCount"])
w("  blankStationCodeGroups: %d   # 归并为“未知门店”" % station_performance["blankStationCodeGroups"])
w("  top1:")
for k in ["stationCode", "stationName", "salesAmount", "orderCount"]:
    v = station_performance["top1"][k]
    w("    %s: %s" % (k, ("'%s'" % v) if isinstance(v, str) else v))
w("")
w("productRanking:            # 窗口同上，按销售额降序 Top10；异常 SKU 按真实商品统计不过滤（2026-10-30 确认）")
w("  windowDetailRowCount: %d" % product_ranking["windowDetailRowCount"])
w("  top10:")
for r in product_ranking["top10"]:
    w("    - { rank: %d, sku: '%s', productName: '%s', salesAmount: %s, quantity: %s }"
      % (r["rank"], r["sku"], r["productName"].replace("'", "''"), r["salesAmount"], r["quantity"]))
w("")
w("activeMember:              # ACTIVE_MEMBER，窗口同上")
w("  stationGroupCount: %d" % len(active_rows))
w("  distinctMembersTotal: %d" % active_totals["active_members"])
w("  orderCountTotal: %d" % active_totals["orders"])
w("  sumOfGroupsDistinctMembers: %d   # 跨店重复消费，故 > 全局去重" % sum(r["active_members"] for r in active_rows))
w("")
w("memberNew:                 # MEMBER_NEW，按 member_created 窗口 + 注册门店 member_store")
w("  totalNewMembers: %d" % new_members["total"])
w("  storeGroupCount: %d       # GROUP BY member_store 的行数，含 member_store 为 NULL 的一组" % new_members["store_groups"])
w("  distinctStoreCodes: %d    # COUNT(DISTINCT member_store)，忽略 NULL，比分组数少 1" % new_members["distinct_stores"])
w("")
w("memberLevelDist:           # MEMBER_LEVEL_DIST 的 NEW_LEVEL 维度，全局无时间条件")
w("  totalMembers: %d" % level_dist["total"])
w("  filledCount: %d" % level_dist["filled"])
w("  distinctValues: %d" % level_dist["distinct_values"])
w("  unfilledCount: %d" % (level_dist["total"] - level_dist["filled"]))
w("")
w("ageDist:                   # AGE_DIST，2026-10-30 起按阴历生日 member_yin_birthday 分桶")
w("  yinBirthdayFilled: %d    # 阳历 member_yang_birthday 填充数=%d（100%% 为空），age 列填充数=%d"
  % (yin_filled["yin_filled"], yin_filled["yang_filled"], yin_filled["age_col_filled"]))
for r in age_rows:
    w("  '%s': %d" % (r["band"], r["members"]))
w("  selfCheck: 各分桶求和 == memberOverview.totalMembers")
w("")
w("annualAvgConsumption:      # ANNUAL_AVG_CONSUMPTION，窗口同上")
w("  totalConsumption: %s" % annual["total_consumption"])
w("  expenseMemberCount: %d" % annual["expense_members"])
w("  avgPerMember: %.2f   # totalConsumption / expenseMemberCount，HALF_UP 2 位"
  % (annual["total_consumption"] / annual["expense_members"]))
w("")
w("dataQuality:               # 口径前置事实（变更这些值说明数据变了，需重新体检）")
w("  infoMobileBlankCount: %d          # 活跃/年均消费按 mobile 去重的前提" % dq["info_mobile_blank"])
w("  infoEffectiveOrderRows: %d" % dq["info_effective_orders"])
w("  infoTenantBlankCount: %d          # tenant 已于 2026-10-30 取消，仅留证据" % dq["info_tenant_blank"])
w("  infoTenantDistinctNonBlank: %d" % dq["info_tenant_distinct"])
w("  memberTenantFilled: %d            # 应为 0（member_tenant 全空）" % dq_member["member_tenant_filled"])
w("  memberDistinctMobiles: %d         # 与 totalMembers 相等 → 手机号可作会员唯一关联键" % dq_member["distinct_mobiles"])
w("  detailAmtCastNullCount: %d        # 应为 0：无静默丢失金额" % dq_detail["amt_cast_null"])
w("  detailDeletedRows: %d             # state='10' 且 del_flag='1'，已被基线过滤排除" % dq_detail["detail_deleted_rows"])

with open(OUT_PATH, "w", encoding="utf-8") as f:
    f.write("\n".join(L) + "\n")

print("已写出：%s（%d 行）" % (OUT_PATH, len(L)))
print("会员总数=%s  门店组=%d  销售额=%s  商品Top1=%s/%s  活跃去重=%d  新增=%d  阴历填充=%s" % (
    member_overview["total_members"], station_performance["groupCount"],
    station_performance["totalSalesAmount"], product_ranking["top10"][0]["productName"],
    product_ranking["top10"][0]["salesAmount"], active_totals["active_members"],
    new_members["total"], yin_filled["yin_filled"]))
print("结束：rollback，无写入。")
conn.close()
