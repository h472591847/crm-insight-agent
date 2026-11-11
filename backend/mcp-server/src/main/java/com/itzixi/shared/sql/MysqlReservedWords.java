package com.itzixi.shared.sql;

import java.util.Set;

/**
 * @Description MySQL 8.0 保留字集合（取自 8.0.21 的 INFORMATION_SCHEMA.KEYWORDS 中 RESERVED=1）。
 * <p>
 * 度量 alias 裸用会拼成 {@code AS lines} 这类非法语句（1064），必须在编译前拒绝并让模型换名。
 * 两个域（metrics / adhoc）的校验器共用同一份集合，避免各自维护导致口径漂移。
 *
 * @Author crm-agent
 */
public final class MysqlReservedWords {

    public static final Set<String> WORDS = Set.of(
            "accessible", "add", "all", "alter", "analyze", "and", "as", "asc", "asensitive", "before",
            "between", "bigint", "binary", "blob", "both", "by", "call", "cascade", "case", "change",
            "char", "character", "check", "collate", "column", "condition", "constraint", "continue", "convert", "create",
            "cross", "cube", "cume_dist", "current_date", "current_time", "current_timestamp", "current_user", "cursor", "database", "databases",
            "day_hour", "day_microsecond", "day_minute", "day_second", "dec", "decimal", "declare", "default", "delayed", "delete",
            "dense_rank", "desc", "describe", "deterministic", "distinct", "distinctrow", "div", "double", "drop", "dual",
            "each", "else", "elseif", "empty", "enclosed", "escaped", "except", "exists", "exit", "explain",
            "false", "fetch", "first_value", "float", "float4", "float8", "for", "force", "foreign", "from",
            "fulltext", "function", "generated", "get", "grant", "group", "grouping", "groups", "having", "high_priority",
            "hour_microsecond", "hour_minute", "hour_second", "if", "ignore", "in", "index", "infile", "inner", "inout",
            "insensitive", "insert", "int", "int1", "int2", "int3", "int4", "int8", "integer", "interval",
            "into", "io_after_gtids", "io_before_gtids", "is", "iterate", "join", "json_table", "key", "keys", "kill",
            "lag", "last_value", "lateral", "lead", "leading", "leave", "left", "like", "limit", "linear",
            "lines", "load", "localtime", "localtimestamp", "lock", "long", "longblob", "longtext", "loop", "low_priority",
            "master_bind", "master_ssl_verify_server_cert", "match", "maxvalue", "mediumblob", "mediumint", "mediumtext", "middleint", "minute_microsecond", "minute_second",
            "mod", "modifies", "natural", "no_write_to_binlog", "not", "nth_value", "ntile", "null", "numeric", "of",
            "on", "optimize", "optimizer_costs", "option", "optionally", "or", "order", "out", "outer", "outfile",
            "over", "partition", "percent_rank", "precision", "primary", "procedure", "purge", "range", "rank", "read",
            "read_write", "reads", "real", "recursive", "references", "regexp", "release", "rename", "repeat", "replace",
            "require", "resignal", "restrict", "return", "revoke", "right", "rlike", "row", "row_number", "rows",
            "schema", "schemas", "second_microsecond", "select", "sensitive", "separator", "set", "show", "signal", "smallint",
            "spatial", "specific", "sql", "sql_big_result", "sql_calc_found_rows", "sql_small_result", "sqlexception", "sqlstate", "sqlwarning", "ssl",
            "starting", "stored", "straight_join", "system", "table", "terminated", "then", "tinyblob", "tinyint", "tinytext",
            "to", "trailing", "trigger", "true", "undo", "union", "unique", "unlock", "unsigned", "update",
            "usage", "use", "using", "utc_date", "utc_time", "utc_timestamp", "values", "varbinary", "varchar", "varcharacter",
            "varying", "virtual", "when", "where", "while", "window", "with", "write", "xor", "year_month",
            "zerofill");

    private MysqlReservedWords() {
    }

    /** 是否为 MySQL 8.0 保留字（用于拒绝裸用 alias）。 */
    public static boolean isReserved(String word) {
        return word != null && WORDS.contains(word);
    }
}
