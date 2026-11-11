# 后端模块（backend）

本目录是 CRM Insight Agent 的后端实现，完整文档请查看仓库根目录的 [README.md](../README.md)。

## 模块说明

| 模块 | 职责 |
|---|---|
| `mcp-common` | 公共资源（统一 logback 配置） |
| `mcp-client` | Agent 编排层（Spring AI + MCP Client + Redis ChatMemory） |
| `mcp-server` | 工具层（MCP Server + 固化指标引擎 + Ad-hoc 语义层 + 只读 SQL 安全闸门） |

## 快速命令

```bash
# 生成 Mock 数据
python mock-data/generate_mock_data.py

# 单测（默认排除依赖真实 DB 的集成测试）
./mvnw -pl mcp-server -am test
```
