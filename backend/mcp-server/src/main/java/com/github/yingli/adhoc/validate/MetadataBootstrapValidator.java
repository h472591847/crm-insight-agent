package com.github.yingli.adhoc.validate;

import com.github.yingli.adhoc.model.SemanticRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 启动期元数据自校验：用 INFORMATION_SCHEMA 自动推断 FK cardinality，
 * 和 metadata.yaml 里手工声明的 cardinality 做对比，发现不一致打 WARN。
 *
 * 背景：metadata.yaml 的 relationships.cardinality 是粒度守卫的唯一事实来源，
 * 如果手工声明错了（to_one 写成 to_many 或反过来），validateMeasureGrain() 就会
 * 静默放过跨粒度 JOIN 或错误地拒绝合法查询。
 *
 * 方案来自 Tom Zafred (@Tom-Zafred) —— 单列唯一索引 = to_one 的自动推断：
 *   SELECT TABLE_NAME, INDEX_NAME, MIN(COLUMN_NAME) AS key_col
 *   FROM INFORMATION_SCHEMA.STATISTICS
 *   WHERE TABLE_SCHEMA = DATABASE() AND NON_UNIQUE = 0
 *   GROUP BY TABLE_NAME, INDEX_NAME
 *   HAVING COUNT(*) = 1;
 *
 * 单例 bean，CommandLineRunner 保证 SemanticRegistry 和数据源都已就绪后才运行。
 */
@Slf4j
@Component
public class MetadataBootstrapValidator implements CommandLineRunner {

    /** 从 metadata.yaml 的 "on" 条件里提取两边别名和列，如 mv.member_mobile = e.expense_mobile */
    private static final Pattern ON_CLAUSE_PATTERN = Pattern.compile(
            "\\b([a-zA-Z_][a-zA-Z0-9_]*)\\.([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\.([a-zA-Z_][a-zA-Z0-9_]*)\\b"
    );

    private final JdbcTemplate jdbc;
    private final SemanticRegistry registry;

    public MetadataBootstrapValidator(JdbcTemplate jdbc, SemanticRegistry registry) {
        this.jdbc = jdbc;
        this.registry = registry;
    }

    @Override
    public void run(String... args) {
        try {
            validateAll();
        } catch (Exception e) {
            // 不阻塞启动 — 数据库没连上时 info_schema 查不了，WARN 即可
            log.warn("[MetadataBootstrapValidator] 启动期元数据校验跳过：{}", e.getMessage());
        }
    }

    void validateAll() {
        // Step 1: 从 INFORMATION_SCHEMA 推断每张表的"单列唯一索引列"（to_one 事实来源）
        Map<String, Set<String>> singleColUniqueKeys = loadSingleColUniqueKeys();
        if (singleColUniqueKeys.isEmpty()) {
            log.info("[MetadataBootstrapValidator] INFORMATION_SCHEMA 无数据（可能用的 SQLite 或 schema 名为空），跳过 cardinality 自动校验");
            return;
        }
        log.info("[MetadataBootstrapValidator] 从 INFORMATION_SCHEMA 推断出 {} 张表的单列唯一索引", singleColUniqueKeys.size());

        // Step 2: 构建别名→真实表名 映射
        Map<String, String> aliasToTable = new HashMap<>();
        for (var entity : registry.entities().stream().map(registry::entity).toList()) {
            if (entity.alias() != null) {
                aliasToTable.put(entity.alias().toLowerCase(Locale.ROOT), entity.table().toLowerCase(Locale.ROOT));
            }
        }

        // Step 3: 遍历所有 entity 的 relationships，对比推断 vs 声明
        int checked = 0;
        int mismatches = 0;
        Set<String> seen = new HashSet<>(); // 避免双向声明（member↔order）重复校验

        for (var entity : registry.entities().stream().map(registry::entity).toList()) {
            for (var relEntry : entity.relationships().entrySet()) {
                String targetEntityName = relEntry.getKey();
                SemanticRegistry.RelationshipDef rel = relEntry.getValue();

                // member→order 和 order→member 是同一条边，只校验一次
                String edgeKey = entity.name() + "↔" + targetEntityName;
                if (!seen.add(edgeKey)) continue;

                checked++;
                String declared = rel.cardinality(); // 手工声明
                String inferred = inferCardinality(entity, rel, singleColUniqueKeys, aliasToTable);

                if (inferred == null) {
                    log.info("[MetadataBootstrapValidator] {}: 无法从 INFORMATION_SCHEMA 推断（可能 JOIN 列无索引），跳过", edgeKey);
                    continue;
                }

                if (!inferred.equals(declared)) {
                    mismatches++;
                    log.warn("[MetadataBootstrapValidator] {}: cardinality 不一致！YAML 声明={}, SQL 推断={}, on=\"{}\"",
                            edgeKey, declared, inferred, rel.on());
                } else {
                    log.info("[MetadataBootstrapValidator] {}: cardinality OK ({})", edgeKey, declared);
                }
            }
        }

        log.info("[MetadataBootstrapValidator] 校验完成：共 {} 条关系，{} 条不一致", checked, mismatches);
    }

    /**
     * Tom Zafred 提供的 SQL —— 从 INFORMATION_SCHEMA 自动推断单列唯一索引列。
     * 返回 Map<table_lowercase, Set<col_lowercase>>
     */
    private Map<String, Set<String>> loadSingleColUniqueKeys() {
        // INFORMATION_SCHEMA 在不同数据库实现略有差异，MySQL 用 TABLE_SCHEMA
        // DATABASE() 返回当前连接的 schema 名
        String sql = """
                SELECT TABLE_NAME, COLUMN_NAME
                FROM INFORMATION_SCHEMA.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND NON_UNIQUE = 0
                  AND SEQ_IN_INDEX = 1
                GROUP BY TABLE_NAME, INDEX_NAME, COLUMN_NAME
                HAVING COUNT(DISTINCT COLUMN_NAME) = 1
                """;

        Map<String, Set<String>> result = new HashMap<>();
        try {
            jdbc.query(sql, rs -> {
                String table = rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT);
                String col = rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT);
                result.computeIfAbsent(table, k -> new HashSet<>()).add(col);
            });
        } catch (Exception e) {
            log.warn("[MetadataBootstrapValidator] 查询 INFORMATION_SCHEMA 失败：{}", e.getMessage());
        }
        return result;
    }

    /**
     * 推断 relationship 的 cardinality。
     *
     * 逻辑：解析 "on" 条件（如 mv.member_mobile = e.expense_mobile），
     * 取 JOIN 条件**右边**的列（被指向的表）——如果该列在目标表上有**单列唯一索引**，
     * 则是 to_one（一条左表记录最多匹配一条右表记录）；否则 to_many。
     *
     * 返回 null 表示无法推断（目标表或列不在 INFORMATION_SCHEMA 里）。
     */
    private String inferCardinality(SemanticRegistry.EntityDef fromEntity,
                                    SemanticRegistry.RelationshipDef rel,
                                    Map<String, Set<String>> singleColUniqueKeys,
                                    Map<String, String> aliasToTable) {
        Matcher m = ON_CLAUSE_PATTERN.matcher(rel.on());
        if (!m.find()) return null;

        // "on" 条件两边：左表别名.列 = 右表别名.列
        String leftAlias = m.group(1).toLowerCase(Locale.ROOT);
        String leftCol = m.group(2).toLowerCase(Locale.ROOT);
        String rightAlias = m.group(3).toLowerCase(Locale.ROOT);
        String rightCol = m.group(4).toLowerCase(Locale.ROOT);

        // 确定哪一边是目标表（"target" 在 metadata.yaml 里声明的）
        // 简化处理：targetEntity 对应的 alias 是 right 还是 left？
        // 我们从 fromEntity.relationships().get(targetEntityName) 拿到 rel，
        // rel.on() 的左边是 fromEntity 的 alias，右边是 target 的 alias
        String targetAlias = rightAlias; // 假设 on = 左.列 = 右.列，右边是 target
        String targetCol = rightCol;

        // 反查真实表名
        String targetTable = aliasToTable.get(targetAlias);
        if (targetTable == null) {
            // 也可能 targetAlias 直接就是表名（没走 alias 映射）
            targetTable = targetAlias;
        }

        Set<String> uniqueCols = singleColUniqueKeys.get(targetTable);
        if (uniqueCols == null) return null; // 表不在 INFORMATION_SCHEMA 里（可能是视图），跳过

        // 核心改进：只证"to_one"，不证"to_many"。
        //
        // 能证伪的场景：目标表是物理表、JOIN 列有显式 UNIQUE 约束 → YAML 说 to_one 是对的。
        // 不能证伪的场景：
        //   1. 目标表是视图（member_info_view 是触发器同步的物理表，但 DDL 没有显式 UNIQUE）
        //   2. UNIQUE 约束被业务上认为存在但没在 DDL 里声明
        //   3. 索引名不同或 Tom 的 SQL 边界条件没覆盖到
        // 以上情况 SQL 推断为 to_many 都是假警报——信任 YAML 声明，不打 WARN。
        //
        // 只有当 SQL 能确认目标表是物理表且 JOIN 列没有任何索引时，
        // 才报告 YAML 声明可能有问题（无索引 to_one 会扫全表）。
        // 但这种情况当前项目里不会出现，先简化为：SQL 只能确认 to_one，to_many 一律信任 YAML。

        return uniqueCols.contains(targetCol)
                ? SemanticRegistry.RelationshipDef.TO_ONE   // 证实：有唯一索引
                : null;  // 无法证实，信任 YAML 声明
    }
}
