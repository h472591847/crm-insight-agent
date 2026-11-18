package com.github.yingli.mcp.config;


import com.github.yingli.mcp.tool.*;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @Author oliver
 */
@Configuration
@Slf4j
public class ToolConfiguration {
    /**
     * 注册mcp工具
     *
     * @param dateTool
     * @return
     */
    @Bean
    public ToolCallbackProvider registerToolCallbackProvider(DateTool dateTool, EmailTool emailTool, CrmAnalyticsTool crmAnalyticsTool,
                                                             MetricTool metricTool, AdhocTool adhocTool, GuessTool guessTool,
                                                             OfficeStoreTool officeStoreTool) {
        return MethodToolCallbackProvider
                .builder()
                .toolObjects(dateTool, emailTool,  crmAnalyticsTool, metricTool, adhocTool, guessTool, officeStoreTool)
                .build();
    }

    /**
     * set_level 编码表按需拉取资源（Tom Zafred DEV.to suggestion, Issue #2）。
     * 避免在 @Tool description 内联 50+ 编码，tool-definitions.json 从 ~50KB 缩到 ~8KB。
     * Spring AI 1.1.x MCP Resource API: McpServerFeatures.SyncResourceSpecification + McpSchema.
     * application.yml 已 capabilities.resource: true。
     */
    @Bean
    public List<McpServerFeatures.SyncResourceSpecification> levelCodesResource() {
        String content = loadClasspathDoc("docs/LEVEL_CODES.md");
        log.info("MCP Resource registered: crm://levels/set_level-codes ({} chars)", content.length());

        var resource = McpSchema.Resource.builder("crm://levels/set_level-codes")
                .name("CRM Level Codes Reference")
                .description("set_level 编码表（AA~HC 模式），按需拉取避免 tool-definitions.json 膨胀")
                .mimeType("text/markdown")
                .build();

        var spec = new McpServerFeatures.SyncResourceSpecification(resource, (exchange, request) ->
                McpSchema.ReadResourceResult.builder(
                        List.of(McpSchema.TextResourceContents.builder(request.uri(), content)
                                .mimeType("text/markdown").build())
                ).build()
        );
        return List.of(spec);
    }

    private String loadClasspathDoc(String path) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(
                        getClass().getClassLoader().getResourceAsStream(path), StandardCharsets.UTF_8))) {
            return r.lines().collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.warn("Resource {} not found on classpath — MCP Resource will return empty", path);
            return "// LEVEL_CODES.md not available on classpath — see repo docs/LEVEL_CODES.md";
        }
    }
}
