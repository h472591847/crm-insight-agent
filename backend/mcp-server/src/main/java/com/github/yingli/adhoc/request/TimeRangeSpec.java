package com.github.yingli.adhoc.request;

import lombok.Data;
import lombok.ToString;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * @Description 时间窗口：列引用只能来自实体声明的 timeColumns 名字。
 * @Author crm-agent
 */
@Data
@ToString
public class TimeRangeSpec {

    @ToolParam(description = "时间列所属实体，缺省为 entities[0]", required = false)
    private String entity;

    @ToolParam(description = "时间维度名（元数据 timeColumns 的 key），如 TRACK_TIME")
    private String timeDimension;

    @ToolParam(description = "开始日期 yyyy-MM-dd（含）")
    private String startDate;

    @ToolParam(description = "结束日期 yyyy-MM-dd（含当日，即右开 +1 天）")
    private String endDate;
}
