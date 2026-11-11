# -*- coding: utf-8 -*-
"""Step 0 只读探测：真实测试库 crmtest 重基线前的数据体检。

- 凭证从 mcp-server/src/main/resources/.env 读取，脚本内不含任何明文凭证，也不打印凭证。
- 连接建立后立即 START TRANSACTION READ ONLY，MySQL 层面保证不可能写入，结束时 rollback。
- 输出仅为计数、分布与时间覆盖，不输出手机号、姓名等个人级数据。

用法：python scripts/probe/step0_probe_crmtest.py
"""
import os
import time

import pymysql

ENV_PATH = os.path.join("mcp-server", "src", "main", "resources", ".env")
NUM_RE = r"'^-?[0-9]+(\.[0-9]+)?$'"


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
    read_timeout=180,
)
cur = conn.cursor()

try:
    cur.execute("SET SESSION MAX_EXECUTION_TIME=120000")  # MySQL 8 才支持，低版本忽略
except Exception as e:
    print("[WARN] MAX_EXECUTION_TIME 不支持，忽略：" + str(e))
cur.execute("START TRANSACTION READ ONLY")


def one(sql, label):
    t0 = time.time()
    try:
        cur.execute(sql)
        print("[ONE ] %-42s => %s  (%.0fms)" % (label, cur.fetchone(), (time.time() - t0) * 1000))
    except Exception as e:
        print("[FAIL] %-42s => %s" % (label, e))


def many(sql, label, limit_note=""):
    t0 = time.time()
    try:
        cur.execute(sql)
        res = cur.fetchall()
        print("\n[MANY] %s (%.0fms, %d rows)%s" % (label, (time.time() - t0) * 1000, len(res), limit_note))
        for r in res:
            print("        ", r)
        return res
    except Exception as e:
        print("[FAIL] %s => %s" % (label, e))
        return []


print("=" * 90)
one("SELECT DATABASE(), VERSION(), @@sql_mode", "库/版本/sql_mode")

print("\n--- 1. 行数 ---")
for t in ("expense_calendar_info", "expense_calendar_details", "member_info"):
    one("SELECT COUNT(*) FROM `%s`" % t, "行数 " + t)

print("\n--- 2. 状态与删除标记分布（验证是否真的不存在 state='20'） ---")
many("SELECT expense_state, del_flag, COUNT(*) c FROM expense_calendar_info "
     "GROUP BY expense_state, del_flag ORDER BY c DESC", "info state x del_flag")
many("SELECT expense_d_state, del_flag, COUNT(*) c FROM expense_calendar_details "
     "GROUP BY expense_d_state, del_flag ORDER BY c DESC", "details state x del_flag")
many("SELECT del_flag, COUNT(*) c FROM member_info GROUP BY del_flag ORDER BY c DESC",
     "member del_flag")

print("\n--- 3. 退单负数行（销售额抵消逻辑的前提） ---")
one("SELECT COUNT(*), COALESCE(SUM(CAST(NULLIF(TRIM(expense_realamt),'') AS DECIMAL(20,2))),0) "
    "FROM expense_calendar_info "
    "WHERE CAST(NULLIF(TRIM(expense_realamt),'') AS DECIMAL(20,2)) < 0", "info 实付<0 行数与金额")
one("SELECT COUNT(*) FROM expense_calendar_details "
    "WHERE CAST(NULLIF(TRIM(expense_d_realamt),'') AS DECIMAL(20,2)) < 0", "details 实付<0 行数")

print("\n--- 4. VARCHAR 脏值（(总数, NULL, 空串, 非数字)） ---")
for tbl, col in (("expense_calendar_info", "expense_realamt"),
                 ("expense_calendar_info", "expense_stdtotal"),
                 ("expense_calendar_info", "gross_margin"),
                 ("expense_calendar_details", "expense_d_realamt"),
                 ("expense_calendar_details", "expense_d_qty"),
                 ("expense_calendar_details", "expense_d_stdtotal")):
    one("SELECT COUNT(*), SUM(CASE WHEN {c} IS NULL THEN 1 ELSE 0 END), "
        "SUM(CASE WHEN TRIM({c})='' THEN 1 ELSE 0 END), "
        "SUM(CASE WHEN {c} IS NOT NULL AND TRIM({c})<>'' AND {c} NOT REGEXP {re} THEN 1 ELSE 0 END) "
        "FROM {t}".format(c=col, re=NUM_RE, t=tbl), "脏值 %s.%s" % (tbl, col))

print("\n--- 5. 时间覆盖与按月分布（用于选定新的固定历史区间） ---")
one("SELECT MIN(expense_buyDate), MAX(expense_buyDate) FROM expense_calendar_info", "info 消费时间范围")
one("SELECT MIN(expense_d_buyDate), MAX(expense_d_buyDate) FROM expense_calendar_details", "details 消费时间范围")
one("SELECT MIN(member_created), MAX(member_created) FROM member_info WHERE del_flag='0'", "member_created 范围")
many("SELECT DATE_FORMAT(expense_buyDate,'%Y-%m') ym, COUNT(*) orders, "
     "ROUND(SUM(CAST(NULLIF(TRIM(expense_realamt),'') AS DECIMAL(20,2))),2) sales "
     "FROM expense_calendar_info WHERE expense_state='10' AND del_flag='0' "
     "AND expense_buyDate >= DATE_SUB(CURDATE(), INTERVAL 26 MONTH) "
     "GROUP BY ym ORDER BY ym", "info 近26个月按月分布")
many("SELECT DATE_FORMAT(expense_d_buyDate,'%Y-%m') ym, COUNT(*) lines "
     "FROM expense_calendar_details WHERE expense_d_state='10' AND del_flag='0' "
     "AND expense_d_buyDate >= DATE_SUB(CURDATE(), INTERVAL 10 MONTH) "
     "GROUP BY ym ORDER BY ym", "details 近10个月按月分布")

print("\n--- 6. 租户与门店范围（决定“全局汇总”是否跨租户） ---")
one("SELECT COUNT(DISTINCT expense_tenant) FROM expense_calendar_info WHERE del_flag='0'", "info 租户去重数")
many("SELECT expense_tenant, COUNT(*) c FROM expense_calendar_info WHERE del_flag='0' "
     "GROUP BY expense_tenant ORDER BY c DESC LIMIT 10", "info 租户分布 Top10")
one("SELECT COUNT(DISTINCT member_tenant) FROM member_info WHERE del_flag='0'", "member 租户去重数")
one("SELECT COUNT(*) FROM expense_calendar_info WHERE expense_state='10' AND del_flag='0' "
    "AND (expense_stationCode IS NULL OR TRIM(expense_stationCode)='')", "info 空门店编码行数")
one("SELECT COUNT(*) FROM expense_calendar_info WHERE expense_state='10' AND del_flag='0' "
    "AND (expense_stationCode IS NULL OR TRIM(expense_stationCode)='') "
    "AND (expense_stationName IS NULL OR TRIM(expense_stationName)='')", "其中门店名也为空")

print("\n--- 7. 手机号完整性（影响会员/散客判定，仅输出计数） ---")
one("SELECT COUNT(*), SUM(CASE WHEN expense_mobile IS NULL OR TRIM(expense_mobile)='' "
    "THEN 1 ELSE 0 END) FROM expense_calendar_info "
    "WHERE expense_state='10' AND del_flag='0'", "info 订单总数/手机号缺失数")

print("\n--- 8. 商品维度 ---")
one("SELECT COUNT(*), SUM(CASE WHEN expense_d_gdcode IS NULL OR TRIM(expense_d_gdcode)='' "
    "THEN 1 ELSE 0 END), COUNT(DISTINCT NULLIF(TRIM(expense_d_gdcode),'')) "
    "FROM expense_calendar_details WHERE del_flag='0'", "details 总行/空SKU/去重SKU")
many("SELECT expense_d_gdcode, COUNT(*) c FROM expense_calendar_details "
     "WHERE del_flag='0' AND expense_d_gdcode REGEXP '^[0-9]+[.][0-9]+$' "
     "GROUP BY expense_d_gdcode ORDER BY c DESC LIMIT 10", "疑似价格误填SKU Top10")
one("SELECT COUNT(*), SUM(CASE WHEN expense_d_commCodeName IS NULL OR TRIM(expense_d_commCodeName)='' "
    "THEN 1 ELSE 0 END), COUNT(DISTINCT NULLIF(TRIM(expense_d_commCodeName),'')) "
    "FROM expense_calendar_details WHERE del_flag='0'", "details 总行/空品类/去重品类")

print("\n--- 9. 会员侧 ---")
one("SELECT COUNT(*), SUM(CASE WHEN member_sex=1 THEN 1 ELSE 0 END), "
    "SUM(CASE WHEN member_sex=2 THEN 1 ELSE 0 END), "
    "SUM(CASE WHEN member_sex IS NULL OR member_sex NOT IN (1,2) THEN 1 ELSE 0 END) "
    "FROM member_info WHERE del_flag='0'", "member 性别三分区(总/男/女/未知)")
one("SELECT COUNT(*), COUNT(member_yang_birthday), SUM(CASE WHEN age IS NOT NULL THEN 1 ELSE 0 END) "
    "FROM member_info WHERE del_flag='0'", "member 总数/生日填充/age填充")
one("SELECT COALESCE(SUM(member_expense_totalsum),0), "
    "COUNT(CASE WHEN member_expense_totalsum > 0 THEN 1 END) "
    "FROM member_info WHERE del_flag='0'", "member 累计消费总额/有消费会员数")
for col in ("customer_level", "new_member_level_key", "member_importance",
             "consume_hierarchy", "half_year_level", "total_year_level"):
    one("SELECT COUNT(NULLIF(TRIM({c}),'')), COUNT(DISTINCT NULLIF(TRIM({c}),'')) "
        "FROM member_info WHERE del_flag='0'".format(c=col), "层级字段 " + col + "(填充/去重)")
one("SELECT COUNT(NULLIF(TRIM(member_store),'')), COUNT(DISTINCT NULLIF(TRIM(member_store),'')), "
    "COUNT(NULLIF(TRIM(member_allot_store),'')), COUNT(DISTINCT NULLIF(TRIM(member_allot_store),'')) "
    "FROM member_info WHERE del_flag='0'", "member 注册店(填充/去重)/分配店(填充/去重)")
one("SELECT COUNT(*), COUNT(DISTINCT member_mobile) FROM member_info WHERE del_flag='0'",
    "member 总数/手机号去重")

print("\n--- 10. 索引清单（性能前提） ---")
many("SELECT TABLE_NAME, INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) cols "
     "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='crmtest' "
     "AND TABLE_NAME IN ('expense_calendar_info','expense_calendar_details','member_info') "
     "GROUP BY TABLE_NAME, INDEX_NAME ORDER BY TABLE_NAME, INDEX_NAME", "三表索引清单")

print("\n" + "=" * 90)
conn.rollback()
cur.close()
conn.close()
print("[DONE] 只读探测结束，事务已 rollback，未产生任何写入。")
