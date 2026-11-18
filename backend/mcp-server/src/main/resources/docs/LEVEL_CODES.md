# Member Level Codes — Reference

> **Tom Zafred DEV.to suggestion**: Moved from inlined `@Tool` descriptions to MCP Resource.
> Model fetches on-demand instead of loading 50KB of enum data into tool-definitions.json.

Seven parallel member-level dimensions exist in the CRM. Three carry `set_level` codes verified against production data:

## 1. COMPANY_LEVEL（公司级别 / 毛利级别，18 months）

21 codes: AA, AB, AC, BA, BB, BC, CA, CB, CC, DA, DB, DC, EA, EB, EC, FA, FB, FC, GA, GB, GC

## 2. HALF_YEAR_SALES_LEVEL（一年半销售级别，18 months）

17 codes: AA, AB, AC, BA, BB, BC, CA, CB, CC, DA, DB, DC, EA, EB, EC, FA, FB

Note: Missing `FC` — production data confirmed only 17 codes for this dimension.

## 3. TOTAL_YEAR_SALES_LEVEL（总销售级别，all-time）

24 codes: AA, AB, AC, BA, BB, BC, CA, CB, CC, DA, DB, DC, EA, EB, EC, FA, FB, FC, GA, GB, GC, HA, HB, HC

## 4. Other Level Dimensions (no set_level codes)

| Dimension | Description | Codes |
|---|---|---|
| MEMBER_LEVEL | New member tier | 初享 / 鼎享 (Chinese labels) |
| HAIDING_LEVEL | CRM internal level | Unknown (CRM native field) |
| CITY_LEVEL | City level | set_level variant (same 21-code pattern as COMPANY_LEVEL) |
| STORE_LEVEL | Store level | set_level variant |
| PERSONAL_LEVEL | Personal level | set_level variant |

## MCP Resource URI

```
crm://levels/set_level-codes
```

## Rationale

- **Why not inline in @Tool descriptions?** Bloats `tool-definitions.json` from ~8KB to ~50KB, wastes MCP SSE bandwidth and model context tokens
- **Why MCP Resource over @Tool?** Resources are fetched on-demand, not eagerly loaded into every tool description
- **Why three parallel dimensions?** Different business units use different level definitions — sales uses HALF_YEAR_SALES_LEVEL, finance uses COMPANY_LEVEL

## Source

- Enum data verified against production CRM database (2026-11-07 snapshot)
- Original inlined text: `ToolPromptSnippets.AMBIGUOUS_MEMBER_LEVEL`
- Column definitions: `MemberInfo.java` (entity comments)
