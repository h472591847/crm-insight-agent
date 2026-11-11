# -*- coding: utf-8 -*-
"""要客（VIP）指标数据探测：检查 member_info_view 可访问性与 track 字段数据质量。

- 凭证从 mcp-server/src/main/resources/.env 读取
- 只读事务，不写入
- 输出仅为计数与分布，不输出个人信息

用法：python scripts/probe/step0_probe_vip_metrics.py
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
    read_timeout=180,
)
cur = conn.cursor()

try:
    cur.execute("SET SESSION MAX_EXECUTION_TIME=120000")
except Exception as e:
    print("[WARN] MAX_EXECUTION_TIME 不支持，忽略：" + str(e))
cur.execute("START TRANSACTION READ ONLY")


def one(sql, label):
    t0 = time.time()
    try:
        cur.execute(sql)
        print("[ONE ] %-50s => %s  (%.0fms)" % (label, cur.fetchone(), (time.time() - t0) * 1000))
    except Exception as e:
        print("[FAIL] %-50s => %s" % (label, e))


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
print("要客（VIP）指标数据探测")
print("=" * 90)

one("SELECT DATABASE(), VERSION()", "库/版本")

print("\n--- 1. member_info_view 可访问性 ---")
one("SELECT COUNT(*) FROM `member_info_view`", "member_info_view 总行数")
one("SELECT COUNT(*) FROM `member_info_view` WHERE del_flag = '0'", "del_flag='0' 行数")
one("SELECT COUNT(*) FROM `member_info`", "member_info 总行数（对比）")

print("\n--- 2. track 字段分布 ---")
many("""
    SELECT track, COUNT(*) AS cnt
    FROM member_info_view
    WHERE del_flag = '0'
    GROUP BY track
    ORDER BY cnt DESC
""", "track 分布（del_flag='0'）")

many("""
    SELECT track, COUNT(*) AS cnt
    FROM member_info
    WHERE del_flag = '0'
    GROUP BY track
    ORDER BY cnt DESC
""", "member_info track 分布（对比）")

print("\n--- 3. 要客（track=1）基础统计 ---")
one("""
    SELECT COUNT(*) AS vip_count,
           COALESCE(SUM(member_expense_totalsum), 0) AS total_expense,
           COALESCE(SUM(member_expense_totalsum_year), 0) AS total_expense_year
    FROM member_info_view
    WHERE del_flag = '0' AND track = 1
""", "要客总数/累计消费/近一年半消费")

one("""
    SELECT COALESCE(AVG(member_expense_totalsum), 0) AS avg_expense,
           COALESCE(AVG(member_expense_totalsum_year), 0) AS avg_expense_year
    FROM member_info_view
    WHERE del_flag = '0' AND track = 1
""", "要客人均消费")

print("\n--- 4. track_time / track_cancel_time 数据质量 ---")
one("""
    SELECT COUNT(*) AS total,
           SUM(CASE WHEN track_time IS NOT NULL THEN 1 ELSE 0 END) AS has_track_time,
           SUM(CASE WHEN track_cancel_time IS NOT NULL THEN 1 ELSE 0 END) AS has_cancel_time,
           MIN(track_time) AS earliest_track_time,
           MAX(track_time) AS latest_track_time
    FROM member_info_view
    WHERE del_flag = '0' AND track = 1
""", "track_time 填充情况")

one("""
    SELECT COUNT(*) AS total,
           SUM(CASE WHEN track_cancel_time IS NOT NULL THEN 1 ELSE 0 END) AS has_cancel_time,
           MIN(track_cancel_time) AS earliest_cancel_time,
           MAX(track_cancel_time) AS latest_cancel_time
    FROM member_info_view
    WHERE del_flag = '0' AND track_cancel_time IS NOT NULL
""", "track_cancel_time 填充情况")

print("\n--- 5. track_user_id（导购）分布 ---")
one("""
    SELECT COUNT(DISTINCT track_user_id) AS distinct_staff_count
    FROM member_info_view
    WHERE del_flag = '0' AND track = 1 AND track_user_id IS NOT NULL
""", "有要客的导购数")

many("""
    SELECT track_user_id AS staff_id, COUNT(*) AS vip_count
    FROM member_info_view
    WHERE del_flag = '0' AND track = 1 AND track_user_id IS NOT NULL
    GROUP BY track_user_id
    ORDER BY vip_count DESC
    LIMIT 20
""", "Top 20 导购的要客数")

one("""
    SELECT COUNT(*) AS unassigned_vip_count
    FROM member_info_view
    WHERE del_flag = '0' AND track = 1 AND (track_user_id IS NULL OR track_user_id = '')
""", "未分配导购的要客数")

print("\n--- 6. 按门店统计要客 ---")
one("""
    SELECT COUNT(DISTINCT member_allot_store) AS distinct_store_count
    FROM member_info_view
    WHERE del_flag = '0' AND track = 1 AND member_allot_store IS NOT NULL
""", "有要客的门店数")

many("""
    SELECT member_allot_store AS store_code,
           MAX(member_allot_stationName) AS store_name,
           COUNT(*) AS vip_count,
           COALESCE(SUM(member_expense_totalsum), 0) AS total_expense
    FROM member_info_view
    WHERE del_flag = '0' AND track = 1
    GROUP BY member_allot_store
    ORDER BY vip_count DESC
    LIMIT 20
""", "Top 20 门店的要客数与消费")

print("\n--- 7. member_info vs member_info_view 一致性 ---")
one("""
    SELECT
        (SELECT COUNT(*) FROM member_info WHERE del_flag = '0') AS info_count,
        (SELECT COUNT(*) FROM member_info_view WHERE del_flag = '0') AS view_count
""", "两表有效行数对比")

one("""
    SELECT
        (SELECT COUNT(*) FROM member_info WHERE del_flag = '0' AND track = 1) AS info_vip,
        (SELECT COUNT(*) FROM member_info_view WHERE del_flag = '0' AND track = 1) AS view_vip
""", "两表要客数对比")

print("\n" + "=" * 90)
print("探测完成，执行 rollback 释放只读事务")
print("=" * 90)

conn.rollback()
cur.close()
conn.close()
