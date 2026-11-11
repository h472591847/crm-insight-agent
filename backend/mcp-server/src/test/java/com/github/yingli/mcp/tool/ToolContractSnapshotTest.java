package com.github.yingli.mcp.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.yingli.adhoc.AdhocFixtures;
import com.github.yingli.mapper.MemberInfoMapper;
import com.github.yingli.mapper.OfficeMapper;
import com.github.yingli.metrics.MetricsFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;

/**
 * @ClassName ToolContractSnapshotTest
 * @Description B0 契约快照：用生产同一套 {@link MethodToolCallbackProvider} 组装全部工具，
 * 快照每个工具的 name / description / inputSchema。
 * <p>
 * 模型看到的就是这三项：任何重构只要让它们发生 diff，就说明模型契约被破坏。
 * 比较按「键排序后的规范 JSON」，键顺序不敏感、字段与描述逐字敏感。
 * @Author crm-agent
 */
public class ToolContractSnapshotTest {

    /** 与 ToolConfiguration#registerToolCallbackProvider 注册顺序保持一致 */
    public static Object snapshot() {
        ToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                .toolObjects(new DateTool(),
                        new EmailTool(mock(JavaMailSender.class), "noreply@contract.test"),
                        MetricsFixtures.crmAnalyticsTool(),
                        MetricsFixtures.metricTool(),
                        AdhocFixtures.adhocTool(),
                        new GuessTool(mock(MemberInfoMapper.class), mock(OfficeMapper.class), new ObjectMapper()),
                        new OfficeStoreTool(mock(OfficeMapper.class), new ObjectMapper()))
                .build();

        List<Map<String, Object>> tools = new ArrayList<>();
        for (ToolCallback callback : provider.getToolCallbacks()) {
            ToolDefinition def = callback.getToolDefinition();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", def.name());
            item.put("description", def.description());
            item.put("inputSchema", def.inputSchema());
            tools.add(item);
        }
        tools.sort(MetricsFixtures.byToolName());
        return MetricsFixtures.orderedMap("tools", tools);
    }

    @Test
    @DisplayName("全部 MCP 工具契约快照：name/description/inputSchema 零 diff")
    void toolDefinitionsUnchanged() {
        MetricsFixtures.assertContractBaseline("tool-definitions.json", snapshot());
    }
}
