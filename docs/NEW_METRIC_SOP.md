# 新增指标 SOP

> 目标：10 分钟（+ 对账）完成一个新指标上线，Java 代码零改动。

---

## 第 1 步：产品给口径（唯一无法自动化的环节）

口径必须写入 `backend/docs/ai-agent/BUSINESS_METRICS.md`，**状态必须为 ✅ 已确认**。

口径至少回答以下问题（详见 [方法论 §01](METHODOLOGY.md#01-指标口径怎么确认最容易被忽略的硬阻塞)）：

| # | 问题 |
|---|---|
| 1 | 用哪个金额字段？实付还是标价？ |
| 2 | 时间边界？左闭右开还是两头含？ |
| 3 | 退单/退款怎么处理？负行抵消还是过滤？ |
| 4 | 有效订单状态集合？ |
| 5 | 统计粒度？订单头还是订单明细？ |
| 6 | VARCHAR 脏值怎么处理？ |
| 7 | 分母选什么？ |

**原则**：口径未确认 → 配置先用 `status: UNCONFIRMED`，工具会明确拒绝而不是给可疑数字。

## 第 2 步：draftMetric 起草 YAML（MCP Tool 或手动）

**方式 A：让 Agent 调用 MCP Tool**（自动包 NUM、自动推断 grain、干跑 SQL）

```
角色：你是 CRM 指标建模助手。依据下方口径正文 + 表结构片段 + 配置字段规范，
只输出符合 metrics/*.yaml schema 的一条指标配置。

口径正文：<产品确认文本>
表结构：DATABASE_SCHEMA.md 相关表段落
```

模型组装参数 → `MetricDrafter.draft()` → 返回：
- `yaml`（可直接粘贴的配置片段）
- `compiledSqlSample`（干跑出来的 SQL，不连库）
- `autoWrappedColumns`（哪些 VARCHAR 被自动包了 NUM）
- `validationErrors`（有就重试，没有就 OK）

**方式 B：手动写 YAML**（参考同域已有指标）

```yaml
- metric: STATION_AVG_ORDER_VALUE
  title: 门店客单价
  domain: order
  status: UNCONFIRMED    # 第 1 步口径确认后改成 CONFIRMED
  caliber: "<产品确认的口径正文>"
  table: expense_calendar_info
  grain: ORDER_HEAD
  timeColumn: expense_buyDate
  baselineFilters: ["expense_state = '10'", "del_flag = '0'"]
  dimensions: [{ code: STATION, expr: expense_stationCode, alias: station_code }]
  attributes: [{ expr: expense_stationName, alias: station_name, agg: MAX }]
  measures:
    - { code: SALES,  expr: "NUM(expense_realamt)", agg: SUM,   alias: sales_amount, unit: CNY, zeroFill: true }
    - { code: ORDERS, expr: "*",                    agg: COUNT, alias: order_count }
  derived:
    - { code: AVG_ORDER_VALUE, formula: "SALES / ORDERS", scale: 2, unit: CNY }
  sortable: [SALES, ORDERS, AVG_ORDER_VALUE]
  limitDefault: 100
  limitMax: 500
  withTotalRow: true
  nullDimensionLabel: 未知门店
```

## 第 3 步：干跑自检

调用 `explainMetric(metric=STATION_AVG_ORDER_VALUE, dimensions=[STATION], startDate=2026-06-01, endDate=2026-06-27)`

返回：
- 生成的 SQL + `sqlHash`
- EXPLAIN 执行计划（确认命中 `index_buyDate` / `index_stationCode`）
- 无执行，纯编译验证

**自检清单**：
- [ ] `NUM()` 都包了吗？VARCHAR 金额列遗漏 → 启动失败
- [ ] grain 对吗？ORDER_HEAD 指标不能用明细表维度
- [ ] baselineFilters 对吗？`del_flag='0'` + 有效状态
- [ ] 时间边界左闭右开吗？编译器自动生成，配置不用写
- [ ] PII 列没在 dimensions/attributes 里吗？

## 第 4 步：对账

写一个集成测试，与产品认可的基准数字逐值比对。

```java
@SpringBootTest
class StationAvgOrderValueReconciliationTest {

    static final LocalDate START = LocalDate.of(2026, 6, 1);
    static final LocalDate END   = LocalDate.of(2026, 6, 27);

    @Test
    void 配置化结果与手写SQL完全一致() {
        MetricResult r = metricQueryService.query(
            "order",
            new MetricQueryRequest("STATION_AVG_ORDER_VALUE",
                List.of("STATION"), null, START, END, null, null, null));

        // 逐值比对（基线来自 CRM 报表或手写 SQL）
        assertThat(r.totalRow().salesAmount()).isEqualByComparingTo("2445299.92");
        assertThat(r.totalRow().orderCount()).isEqualTo(228);
        assertThat(r.totalRow().avgOrderValue()).isEqualByComparingTo("10725.00");
    }
}
```

**对账来源**（三者之一）：
1. CRM 系统已有报表导出数字
2. 产品给的基线数字（必须写进测试注释）
3. 独立基准 SQL 跑出的数字（与手写 Mapper 双跑比对）

## 第 5 步：提交 + 重启

1. 把 YAML 粘进 `mcp-server/src/main/resources/metrics/<domain>.yaml`
2. **口径确认后把 `status: UNCONFIRMED` 改成 `CONFIRMED`**
3. 重启 mcp-server（或未来热加载生效时免重启）
4. Playwright 跑一遍 DEMO（确保没搞坏其他指标）

```powershell
$env:PLAYWRIGHT_BROWSERS_PATH = "E:\agent_project\crm_agent\demo-e2e\.playwright-browsers"
cd E:\agent_project\crm_agent\demo-e2e
npx.cmd playwright test
```

## 常见错误与排查

| 现象 | 原因 | 处置 |
|---|---|---|
| 启动失败：`VARCHAR 列 expense_xxx 未包 NUM()` | §3.3 规则 1 | measure.expr 改成 `NUM(expense_xxx)` |
| 返回 `METRICS_NOT_CONFIRMED` | `status: UNCONFIRMED` | 找产品定口径，不要硬改成 CONFIRMED 绕过 |
| 返回 `INVALID_PARAM` + 非法维度/过滤 | dimension/filter/code 不在指标声明白名单 | 查 `listMetrics(metric=...)` 的可用枚举 |
| 返回 `METRIC_NOT_FOUND` | metric code 写错（UPPER_SNAKE） | 查 `listMetrics(domain=...)` 所有可用指标 |
| SQL_REJECTED + 时间越界 | 全局 550 天或指标级 maxRangeDays | 缩小区间或改默认值 |
| 返回数字比 CRM 报表大 | ORDER_HEAD 指标用了明细维度 → grain 冲突被启动期挡住了？那可能 baselineFilters 漏了状态过滤 | 校对 `expense_state = '10' AND del_flag = '0'` |
| 返回数字比 CRM 报表小 | baselineFilters 多加了过滤条件，或误过滤了退单负行 | 退单靠 SUM 自然抵消，不要手动过滤退单状态 |
| 查询慢/超时 | 未走索引列做时间/门店过滤 | 用 `explainMetric` 确认命中 `index_buyDate` |
| 空结果 | 区间无数据（测试库默认"本月"可能空） | 显式传历史区间 |

## 禁止事项

| ❌ 禁止 | 原因 |
|---|---|
| 模型直接写 SQL 粘进配置 | 绕过编译器，丢失确定性 + 口径闸门 |
| `baselineFilters` 里写非字面量（如 `NOW()`、子查询） | 编译器信任配置，不加额外校验 |
| dimension 引用 PII 列 | `member_mobile` / `member_name` 禁止输出（除非数据安全评估通过） |
| 跳过对账直接改 `status: CONFIRMED` | 口径未确认的数字比无数字更危险 |
| `sortBy` 用未在 `sortable` 里声明的 code | 编译期会拦截 |
