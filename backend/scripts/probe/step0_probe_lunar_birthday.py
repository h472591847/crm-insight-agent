"""Step 0 追加探测（只读）：阴历生日字段可用性 + 性别取值分布。

背景：产品确认年龄应基于阴历生日计算，member_yang_birthday（阳历）在真实库全空。
本脚本只输出聚合统计（计数/长度分布/格式匹配数），不输出任何生日原值，避免 PII 落日志。
凭证从 mcp-server/src/main/resources/.env 读取，不打印。
"""
import re
import time
import pymysql

ENV_PATH = r"D:\java_project\spring-ai-mcp-rag-dev\mcp-server\src\main\resources\.env"


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
    read_timeout=200,
)
cur = conn.cursor()
cur.execute("START TRANSACTION READ ONLY")
FAILS = []


def q(title, sql, limit=40):
    t0 = time.perf_counter()
    try:
        cur.execute(sql)
        rows = cur.fetchall()
        cols = [d[0] for d in cur.description]
    except Exception as e:
        ms = (time.perf_counter() - t0) * 1000
        FAILS.append(title)
        print(f"\n### {title}  [ERROR {ms:.0f}ms] {type(e).__name__}: {e}")
        return None
    ms = (time.perf_counter() - t0) * 1000
    print(f"\n### {title}  [{ms:.0f}ms]  rows={len(rows)}")
    print("\t".join(cols))
    for r in rows[:limit]:
        print("\t".join("NULL" if v is None else str(v) for v in r))
    return rows


MEMBER = "FROM member_info WHERE del_flag = '0'"

print("=" * 78)
print("Step0 追加探测：member_info 生日/性别相关列（真实库 crmtest）")
print("=" * 78)

# ---- 1. 相关列的准确名称与类型 ----
q("1-member_info 中含 birthday/sex/age 的列",
  """
  SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='crmtest' AND TABLE_NAME='member_info'
    AND (COLUMN_NAME LIKE '%birthday%' OR COLUMN_NAME LIKE '%sex%' OR COLUMN_NAME LIKE '%age%')
  ORDER BY ORDINAL_POSITION
  """)

# ---- 2. 生日字段填充率（不取原值） ----
q("2-生日字段填充情况（del_flag=0）",
  f"""
  SELECT COUNT(*) AS total,
         SUM(member_yin_birthday  IS NULL OR TRIM(member_yin_birthday ) = '') AS yin_blank,
         SUM(member_yang_birthday IS NULL OR TRIM(member_yang_birthday) = '') AS yang_blank,
         SUM(age IS NULL OR TRIM(age) = '')                                    AS age_blank
  {MEMBER}
  """)

# ---- 3. 阴历生日的存储形态：长度分布 + 格式匹配（不输出原值） ----
q("3-member_yin_birthday 长度分布",
  f"""
  SELECT CHAR_LENGTH(member_yin_birthday) AS len, COUNT(*) AS cnt
  {MEMBER} AND member_yin_birthday IS NOT NULL AND TRIM(member_yin_birthday) <> ''
  GROUP BY CHAR_LENGTH(member_yin_birthday) ORDER BY cnt DESC LIMIT 15
  """)

q("4-member_yin_birthday 格式判定（只输出计数）",
  f"""
  SELECT
    SUM(member_yin_birthday REGEXP '^[0-9]{{4}}-[0-9]{{1,2}}-[0-9]{{1,2}}$') AS ymd_like,
    SUM(member_yin_birthday REGEXP '^[0-9]{{4}}\\.[0-9]{{1,2}}\\.[0-9]{{1,2}}$') AS dot_ymd,
    SUM(member_yin_birthday REGEXP '^[0-9]{{4}}/[0-9]{{1,2}}/[0-9]{{1,2}}$') AS slash_ymd,
    SUM(member_yin_birthday REGEXP '^[0-9]+$') AS pure_digits,
    SUM(STR_TO_DATE(member_yin_birthday, '%Y-%m-%d') IS NULL) AS not_parseable_as_ymd,
    COUNT(YEAR(member_yin_birthday)) AS year_func_ok
  {MEMBER} AND member_yin_birthday IS NOT NULL AND TRIM(member_yin_birthday) <> ''
  """)

# ---- 5. 阴历生日能否被 MySQL 当日期解析（决定 SQL 怎么写） ----
q("5-阴历生日可否直接参与日期函数（只输出聚合，不输出原值）",
  f"""
  SELECT
    COUNT(*) AS non_blank,
    MIN(STR_TO_DATE(member_yin_birthday, '%Y-%m-%d')) AS min_parsed,
    MAX(STR_TO_DATE(member_yin_birthday, '%Y-%m-%d')) AS max_parsed,
    COUNT(DISTINCT YEAR(STR_TO_DATE(member_yin_birthday, '%Y-%m-%d'))) AS distinct_years
  {MEMBER} AND member_yin_birthday IS NOT NULL AND TRIM(member_yin_birthday) <> ''
  """)

q("6-阴历生日 CAST 成日期后 TIMESTAMPDIFF 是否可用（负值/异常计数）",
  f"""
  SELECT
    SUM(TIMESTAMPDIFF(YEAR, STR_TO_DATE(member_yin_birthday, '%Y-%m-%d'), CURDATE()) < 0)  AS negative_age,
    SUM(TIMESTAMPDIFF(YEAR, STR_TO_DATE(member_yin_birthday, '%Y-%m-%d'), CURDATE()) > 120) AS over_120,
    SUM(TIMESTAMPDIFF(YEAR, STR_TO_DATE(member_yin_birthday, '%Y-%m-%d'), CURDATE())
        BETWEEN 0 AND 120) AS age_0_120
  {MEMBER} AND member_yin_birthday IS NOT NULL AND TRIM(member_yin_birthday) <> ''
  """)

# ---- 7. 年龄分桶预演：以阴历生日计算的分布 ----
q("7-按阴历生日的年龄分桶预演",
  f"""
  SELECT CASE
           WHEN TIMESTAMPDIFF(YEAR, STR_TO_DATE(member_yin_birthday, '%Y-%m-%d'), CURDATE()) <= 19 THEN '19岁及以下'
           WHEN TIMESTAMPDIFF(YEAR, STR_TO_DATE(member_yin_birthday, '%Y-%m-%d'), CURDATE()) <= 29 THEN '20-29岁'
           WHEN TIMESTAMPDIFF(YEAR, STR_TO_DATE(member_yin_birthday, '%Y-%m-%d'), CURDATE()) <= 39 THEN '30-39岁'
           WHEN TIMESTAMPDIFF(YEAR, STR_TO_DATE(member_yin_birthday, '%Y-%m-%d'), CURDATE()) <= 49 THEN '40-49岁'
           WHEN TIMESTAMPDIFF(YEAR, STR_TO_DATE(member_yin_birthday, '%Y-%m-%d'), CURDATE()) <= 59 THEN '50-59岁'
           WHEN TIMESTAMPDIFF(YEAR, STR_TO_DATE(member_yin_birthday, '%Y-%m-%d'), CURDATE()) >= 60 THEN '60岁及以上'
           ELSE '未知' END AS age_band,
         COUNT(*) AS member_count
  {MEMBER} AND member_yin_birthday IS NOT NULL AND TRIM(member_yin_birthday) <> ''
  GROUP BY 1 ORDER BY member_count DESC
  """)

# ---- 8. 性别取值全枚举（决定能否做性别分布指标） ----
q("8-member_sex 取值全枚举",
  f"""
  SELECT member_sex, COUNT(*) AS cnt
  {MEMBER}
  GROUP BY member_sex ORDER BY cnt DESC LIMIT 20
  """)

q("9-性别三分区自洽校验",
  f"""
  SELECT SUM(member_sex = 1) AS male, SUM(member_sex = 2) AS female,
         SUM(member_sex IS NULL OR member_sex NOT IN (1,2)) AS unknown_, COUNT(*) AS total
  {MEMBER}
  """)

conn.rollback()
print("\n" + "=" * 78)
print(f"结束：rollback（只读，无写入）。失败查询数={len(FAILS)} {FAILS}")
print("=" * 78)
conn.close()
