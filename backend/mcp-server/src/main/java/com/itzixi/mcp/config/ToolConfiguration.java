package com.itzixi.mcp.config;


import com.itzixi.mcp.tool.*;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @Author oliver
 */
@Configuration
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
}
