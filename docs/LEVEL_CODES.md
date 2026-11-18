# Member Level Codes — Reference

> **Tom Zafred DEV.to suggestion**: Moved from inlined `@Tool` descriptions to MCP Resource.
> Model fetches on-demand instead of loading 50KB of enum data into tool-definitions.json.

Seven parallel member-level dimensions exist in the CRM. Three carry `set_level` codes verified against production data:

## 1. COMPANY_LEVEL（公司级别 / 毛利级别，18 months）

21 codes: AA, AB, AC, BA, BB, BC, CA, CB, CC, DA, DB, DC, EA, EB, EC, FA, FB, FC, GA, GB, GC

| Pattern | Count |
|---|---|
| A* | 3 |
| B* | 3 |
| C* | 3 |
| D* | 3 |
| E* | 3 |
| F* | 3 |
| G* | 3 |

## 2. HALF_YEAR_SALES_LEVEL（一年半销售级别，18 months）

17 codes: AA, AB, AC, BA, BB, BC, CA, CB, CC, DA, DB, DC, EA, EB, EC, FA, FB

| Pattern | Count |
|---|---|
| A* | 3 |
| B* | 3 |
| C* | 3 |
| D* | 3 |
| E* | 3 |
| F* | 2 |

Note: Missing `FC` — production data confirmed only 17 codes for this dimension.

## 3. TOTAL_YEAR_SALES_LEVEL（总销售级别，all-time）

24 codes: AA, AB, AC, BA, BB, BC, CA, CB, CC, DA, DB, DC, EA, EB, EC, FA, FB, FC, GA, GB, GC, HA, HB, HC

| Pattern | Count |
|---|---|
| A* | 3 |
| B* | 3 |
| C* | 3 |
| D* | 3 |
| E* | 3 |
| F* | 3 |
| G* | 3 |
| H* | 3 |

## 4. Other Level Dimensions (no set_level codes)

| Dimension | Description | Codes |
|---|---|---|
| MEMBER_LEVEL | 新会员等级 | 初享 / 鼎享（Chinese labels, not set_level） |
| HAIDING_LEVEL | CRM 内部级别 | 未知（CRM 原生字段） |
| CITY_LEVEL | 城市级别 | set_level variant（same 21-code pattern as COMPANY_LEVEL） |
| STORE_LEVEL | 门店级别 | set_level variant |
| PERSONAL_LEVEL | 个人级别 | set_level variant |

## How to use from MCP

Model can fetch this doc via MCP Resource:

```
Resource URI: crm://levels/set_level-codes
```

Server registers this via Spring AI `SyncResourceSpecification`:

```java
@Bean
public ResourceSpecification levelCodesResource() {
    return new SyncResourceSpecification(
        "crm://levels/set_level-codes",
        () -> List.of(new Resource("crm://levels/set_level-codes", loadLevelCodesContent()))
    );
}
```

## Rationale

- **Why not inline in @Tool descriptions?** Bloats `tool-definitions.json` to ~50KB, wastes MCP SSE bandwidth and model context tokens
- **Why MCP Resource over @Tool?** Resources are fetched on-demand, not eagerly loaded into every tool description
- **Why three parallel dimensions?** Different business units use different level definitions — sales uses HALF_YEAR_SALES_LEVEL, finance uses COMPANY_LEVEL

## Source

- Enum data verified against production CRM database (2026-11-07 snapshot)
- Original inlined text: `ToolPromptSnippets.AMBIGUOUS_MEMBER_LEVEL`
- Column definitions: `MemberInfo.java` (entity comments)
