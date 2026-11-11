package com.github.yingli.mcp.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 时间相关 MCP 工具。
 * <p>
 * 提供当前时间获取能力，支持指定 ZoneId 时区。
 * </p>
 *
 * @author oliver
 * @since 1.0.0
 */
@Component
@Slf4j
public class DateTool {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 根据 ZoneId 获取指定时区的当前时间。
     *
     * @param cityName 城市名称（仅日志用途，不影响时间计算）
     * @param zoneId   时区标识（如 "Asia/Shanghai"、"America/New_York"）
     * @return 格式化后的时间字符串
     */
    @Tool(name = "getCurrentTimeByZoneId", description = "根据城市所在的时区id来获取当前的时间")
    public String getCurrentTimeByZoneId(String cityName, String zoneId) {
        log.info("调用 MCP 工具 getCurrentTimeByZoneId, cityName={}, zoneId={}", cityName, zoneId);

        ZoneId zone = ZoneId.of(zoneId);
        ZonedDateTime zonedDateTime = ZonedDateTime.now(zone);

        return "当前的时间是 " + zonedDateTime.format(FORMATTER);
    }

    /**
     * 获取当前服务器时间（Asia/Shanghai 时区）。
     *
     * @return 格式化后的时间字符串
     */
    @Tool(name = "getCurrentTime", description = "获取当前时间")
    public String getCurrentTime() {
        log.info("调用 MCP 工具 getCurrentTime");
        return "当前的时间是 " + LocalDateTime.now().format(FORMATTER);
    }
}
