package com.github.yingli.adhoc.response;

import java.util.List;
import java.util.Map;

/**
 * @Description Ad-hoc 查询结果：错误不抛异常而是随结果返回（对齐指标框架），模型必须如实转述 errorMessage。
 * @param sqlHash 编译指纹，任何数字可反查到具体 SQL（复现与审计凭据）
 * @param caliber 口径描述：实体/维度/基线过滤，转述结果时一并输出
 * @Author crm-agent
 */
public record AdhocResult(String errorCode,
                          String errorMessage,
                          List<String> columns,
                          List<Map<String, Object>> rows,
                          String sqlHash,
                          String caliber,
                          Integer rowCount) {

    public static AdhocResult error(String code, String message) {
        return new AdhocResult(code, message, List.of(), List.of(), null, null, null);
    }
}
