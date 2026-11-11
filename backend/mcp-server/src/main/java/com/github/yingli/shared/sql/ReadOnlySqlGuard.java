package com.github.yingli.shared.sql;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @Description 执行层只读闸门：SQL 进入 JDBC 前强制校验为单条 SELECT。
 * 编译器已经只生成 SELECT，这里是不依赖上游正确性的 fail-closed 最后防线：
 * 非 SELECT、多语句、注释、写操作/DDL、文件导出、系统库与锁/休眠函数一律拒绝。
 * 字符串与反引号内容先剥离再扫描，避免展示文案（如 'delete'）误伤。
 * @Author crm-agent
 */
final class ReadOnlySqlGuard {

    private static final Pattern LEADING_SELECT =
            Pattern.compile("^\\s*SELECT\\b", Pattern.CASE_INSENSITIVE);

    /** 编译器自带的优化器 hint 是唯一允许的块注释，先剥离再扫描其余文本 */
    private static final Pattern OPTIMIZER_HINT = Pattern.compile(
            "^\\s*SELECT\\s*/\\*\\+\\s*MAX_EXECUTION_TIME\\(\\s*\\d+\\s*\\)\\s*\\*/",
            Pattern.CASE_INSENSITIVE);

    /** 字符串之外出现即拒绝的语法 */
    private static final Pattern FORBIDDEN = Pattern.compile(
            "(?i)"
                    + "\\b(?:insert|update|delete|replace|alter|drop|truncate|create|rename|grant|revoke"
                    + "|call|exec|execute|handler|lock|unlock|set|use|do|kill|load|outfile|dumpfile"
                    + "|into|union|sleep|benchmark|procedure|load_file|get_lock|release_lock)\\b"
                    + "|\\bfor\\s+(?:update|share)\\b"
                    + "|@@|\\binformation_schema\\b|\\bmysql\\.|\\bperformance_schema\\b|\\bsys\\.");

    private ReadOnlySqlGuard() {
    }

    static void requireSelect(String sql) {
        if (sql == null || sql.isBlank()) {
            throw rejected("SQL 不能为空");
        }
        String normalized = stripAllowedHint(sql);
        if (!LEADING_SELECT.matcher(normalized).find()) {
            throw rejected("执行层只允许 SELECT 查询，实际：" + preview(normalized));
        }
        String bare = stripQuotedText(normalized);
        if (bare.indexOf(';') >= 0) {
            throw rejected("执行层禁止多语句（发现分号）");
        }
        if (bare.contains("--") || bare.contains("/*") || bare.indexOf('#') >= 0) {
            throw rejected("执行层禁止 SQL 注释");
        }
        Matcher forbidden = FORBIDDEN.matcher(bare);
        if (forbidden.find()) {
            throw rejected("执行层拒绝只读查询不允许的语法：" + forbidden.group());
        }
    }

    private static String stripAllowedHint(String sql) {
        Matcher hint = OPTIMIZER_HINT.matcher(sql);
        return hint.find() ? "SELECT " + sql.substring(hint.end()) : sql;
    }

    /**
     * 把单引号/双引号字符串与反引号标识符替换为空格，使关键字与分号扫描只作用于结构语法。
     * MySQL 允许反斜杠转义与双写引号转义，两种都要识别；引号未闭合直接拒绝（语法不完整）。
     */
    private static String stripQuotedText(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        char quote = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (quote != 0) {
                out.append(' ');
                if (c == '\\' && i + 1 < sql.length()) {
                    i++;
                    out.append(' ');
                } else if (c == quote) {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                        i++;
                        out.append(' ');
                    } else {
                        quote = 0;
                    }
                }
            } else if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                out.append(' ');
            } else {
                out.append(c);
            }
        }
        if (quote != 0) {
            throw rejected("执行层检测到未闭合的引号，SQL 不完整");
        }
        return out.toString();
    }

    private static String preview(String sql) {
        String trimmed = sql.trim().replaceAll("\\s+", " ");
        return trimmed.length() <= 40 ? trimmed : trimmed.substring(0, 40) + "...";
    }

    private static SqlGuardException rejected(String message) {
        return new SqlGuardException(message);
    }
}
