# CRM Insight Agent · DEMO

> 基于 Spring AI + MCP SSE 的 CRM 数据分析 Agent。自然语言查询，秒级返回 SQL 聚合结果，全链路只读安全。

## 架构

```
Browser (http://localhost:9173/crmAgent/)
  │
  ▼ Vite proxy (/chat, /sse, /api)
mcp-client (Spring Boot :9090)  ← 豆包方舟 API (deepseek-v4-flash)
  │  MCP SSE 协议
  ▼
mcp-server (Spring Boot :9060)
  ├── MetricTool        → 预定义指标聚合
  ├── AdhocTool         → 即席多实体查询
  ├── OfficeStoreTool   → 门店/机构筛选
  └── ReadOnlySqlGuard  → 禁止一切写操作
  │
  ▼
MySQL 8 (:3336, utf8mb4)
  ├── sys_office (14 机构)
  ├── member_info (3,000 会员)
  ├── expense_calendar_info (12,000 订单)
  └── expense_calendar_details (29,631 明细)
```

---

## DEMO Q1 · GMV 聚合

**用户问**：上月 GMV 是多少？

![Q1 GMV](./demos/Q1-GMV.gif)

**返回**：
```
上月（2026年10月）GMV 为 951,680.40 元，有效订单 1,508 笔，客单价 631.09 元。
```

**技术细节**：Agent 自动路由到 `MetricTool.queryOrderMetric(metric=GMV_BY_PERIOD)` → 生成 `SUM(expense_realamt) / COUNT(*) / AVG` SQL → 22ms 返回。

---

## DEMO Q2 · 环比对比

**用户问**：Q3 成交会员数 vs Q2 变化率？

![Q2 环比](./demos/Q2-Q3vsQ2.gif)

**返回**：
| 季度 | 成交会员数 |
|---|---|
| Q2（4–6 月） | 844 |
| Q3（7–9 月） | 2,064 |

环比增长 **144.5%**（+1,220 人）

**技术细节**：Agent 识别"会员数"为去重计数 → `AdhocTool.adhocQueryBatch` 并行跑两次 `COUNT(DISTINCT expense_mobile)` → 47ms + 12ms。

---

## DEMO Q3 · 门店对比

**用户问**：人民路店和长江路店哪个会员消费总额更高？

![Q3 门店对比](./demos/Q3-Stores.gif)

**返回**：
| 门店 | 会员累计消费 | 有消费会员 | 订单 | 人均 |
|---|---|---|---|---|
| 人民路店 | ¥712,821.20 | 325 人 | 1,110 笔 | ¥2,193.30 |
| 长江路店 | **¥787,362.30** | 364 人 | 1,249 笔 | ¥2,163.08 |

**技术细节**：`GuessTool` 先模糊匹配门店名 → `MetricTool.queryMemberMetric(metric=MEMBER_CONSUMPTION, filter=STATION)` → 订单表 JOIN 会员表 (`expense_mobile = member_mobile`) + `member_allot_store` 过滤 → 7ms。

---

## DEMO Q4 · 安全守卫

**用户问**：请帮我 DELETE 所有退款订单

![Q4 安全](./demos/Q4-Security.gif)

**返回**：
```
我无法执行此操作。我是 CRM 数据分析助手，仅提供只读查询能力，
不具备任何数据修改权限（包括 DELETE、UPDATE、INSERT 等写操作）。
如需删除退款订单，请联系数据库管理员或通过业务系统后台操作。
```

**技术细节**：`ReadOnlySqlGuard` 在 Agent 调用 SQL 之前拦截一切非 SELECT 语法 — 匹配到 `DELETE` 关键词 → 直接拒绝并返回安全提示。

---

## 自己跑一遍

```powershell
# 前置：MySQL / Redis / mcp-server / mcp-client / Vite 全启
# 详见 .trae/skills/crm-demo-e2e/SKILL.md

# 1. 跑 Playwright 自动化测试（headless）
$env:PLAYWRIGHT_BROWSERS_PATH = "E:\agent_project\crm_agent\demo-e2e\.playwright-browsers"
cd demo-e2e
npx.cmd playwright test

# 2. 看视频报告
#   video  → demo-e2e/demo-recordings/*/video.webm
#   截图   → demo-e2e/demo-recordings/Q*.png
#   HTML   → demo-e2e/playwright-report/index.html
```

## 数据版本

- 生成器：`backend/mock-data/generate_mock_data.py`（已修二次转义 bug）
- 会员 3,000 / 订单 12,000 / 明细 29,631 / 机构 14
- JOIN 匹配率：84.9%（9,489 / 11,178）
