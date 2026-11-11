package com.itzixi.metrics;

import org.junit.jupiter.api.Assumptions;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @ClassName BaselineFixtures
 * @Description 真实测试库 crmtest 的对账基线读取器（classpath:baseline/crmtest-baseline.yaml）。
 * <p>
 * 为什么要把基线数值外置：这些数字属于<b>数据</b>而不是<b>口径</b>。库里补数据、清理数据或
 * 换一批样本后，它们必然变化；写死在断言里会让测试变成"提醒我该重新体检了"的信号灯，
 * 而不是口径正确性的证明。外置后，重新体检 = 重跑
 * {@code python scripts/probe/step0_baseline_full.py} 覆盖一个 YAML 文件，测试代码不动。
 * <p>
 * 分工（重要）：
 * <ul>
 *   <li>主对账 = 测试内同刻执行的独立 oracle SQL 与编译层结果<b>逐组逐值</b>比对，证明 SQL 生成正确；</li>
 *   <li>本基线 = 二次校验，证明口径没有整体漂移（组数、总量、Top1）。</li>
 * </ul>
 * 基线由只读探测生成，不含任何个人级数据；文件里的窗口必须与测试常量一致，
 * 否则数值断言会拿"别的区间"的结果来比对——{@link #assumeMatchesCurrentDb} 之外请自行保证窗口一致。
 * @Author crm-agent
 */
public final class BaselineFixtures {

    private static final String PATH = "baseline/crmtest-baseline.yaml";

    private static final Map<String, Object> ROOT = load();

    private BaselineFixtures() {
    }

    /** 基线采集时连接的库名（用于缺库/连错库时跳过而不是误报失败） */
    public static String database() {
        return text("meta", "database");
    }

    /** 基线窗口起始日（含） */
    public static String windowStart() {
        return text("meta", "window", "start");
    }

    /** 基线窗口结束日（含）：测试常量 END 取这天，SQL 侧左闭右开边界是 endExclusive */
    public static String windowEnd() {
        return java.time.LocalDate.parse(windowEndExclusive()).minusDays(1).toString();
    }

    /** 基线窗口的右开边界（编译层 DATE_ADD(?, INTERVAL 1 DAY) 应落在这天 00:00） */
    public static String windowEndExclusive() {
        return text("meta", "window", "endExclusive");
    }

    /**
     * 当前连接库与基线库不一致时跳过测试。
     * <p>
     * 只读对账测试断言的是特定一批样本数据的数值。若把 {@code spring.datasource} 指向别的库
     * （例如旧的 5000 条演示数据），失败信息会被误读成"口径算错了"。这里用 Assumption 明确表达
     * "环境不匹配，本测试不适用"，不污染口径结论。
     */
    public static void assumeMatchesCurrentDb(String currentDb) {
        String expected = database();
        Assumptions.assumeTrue(expected.equalsIgnoreCase(currentDb),
                "对账基线采集自 " + expected + " 库，当前连接 " + currentDb + "，跳过数值对账");
    }

    /** 取一个段落（section） */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> section(String name) {
        Object node = ROOT.get(name);
        if (!(node instanceof Map<?, ?> map)) {
            throw new IllegalStateException("基线文件缺少段落：" + name + "（" + PATH + "）");
        }
        return (Map<String, Object>) map;
    }

    /** 段落内的整数（计数类断言） */
    public static long count(String section, String key) {
        return number(section(section), key).longValue();
    }

    /** 段落内的金额/小数（BigDecimal 精确比较） */
    public static BigDecimal decimal(String section, String key) {
        return toBigDecimal(required(section(section), key, section + "." + key));
    }

    /** 段落内的文本 */
    public static String text(String section, String key) {
        Object v = required(section(section), key, section + "." + key);
        return String.valueOf(v);
    }

    /** 段落内的嵌套段落（如 stationPerformance.top1） */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> nested(String section, String key) {
        Object v = required(section(section), key, section + "." + key);
        if (!(v instanceof Map<?, ?> map)) {
            throw new IllegalStateException("基线 " + section + "." + key + " 应为映射，实际=" + v);
        }
        return (Map<String, Object>) map;
    }

    /** 段落内嵌套段落的文本（如 stationPerformance.top1.stationName） */
    public static String text(String section, String sub, String key) {
        return String.valueOf(required(nested(section, sub), key, section + "." + sub + "." + key));
    }

    /** 段落内嵌套段落的整数 */
    public static long count(String section, String sub, String key) {
        return number(nested(section, sub), key).longValue();
    }

    /** 段落内嵌套段落的金额 */
    public static BigDecimal decimal(String section, String sub, String key) {
        return toBigDecimal(required(nested(section, sub), key, section + "." + sub + "." + key));
    }

    /** 段落内所有数值型条目（跳过注释性/说明性字符串），用于"分桶求和=总数"这类自检 */
    public static Map<String, Long> numericEntries(String section) {
        Map<String, Long> out = new LinkedHashMap<>();
        section(section).forEach((k, v) -> {
            if (v instanceof Number n) {
                out.put(k, n.longValue());
            }
        });
        return out;
    }

    /** 商品排行 Top10（顺序即基线排名） */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> productTop10() {
        Object v = required(section("productRanking"), "top10", "productRanking.top10");
        if (!(v instanceof List<?> list)) {
            throw new IllegalStateException("基线 productRanking.top10 应为列表");
        }
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object item : list) {
            out.add((Map<String, Object>) item);
        }
        return out;
    }

    /** Top10 里某一名的字段（BigDecimal 用 salesAmount/quantity，文本用 sku/productName） */
    public static Object productTopField(int rank, String key) {
        for (Map<String, Object> row : productTop10()) {
            if (((Number) row.get("rank")).intValue() == rank) {
                return required(row, key, "productRanking.top10[" + rank + "]." + key);
            }
        }
        throw new IllegalStateException("基线商品排行缺少 rank=" + rank + " 的记录");
    }

    public static BigDecimal productTopDecimal(int rank, String key) {
        return toBigDecimal(productTopField(rank, key));
    }

    public static String productTopText(int rank, String key) {
        return String.valueOf(productTopField(rank, key));
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static Number number(Map<String, Object> node, String key) {
        Object v = required(node, key, key);
        if (!(v instanceof Number n)) {
            throw new IllegalStateException("基线字段应为数值，实际=" + v + "（" + key + "）");
        }
        return n;
    }

    private static Object required(Map<String, Object> node, String key, String label) {
        Object v = node.get(key);
        if (v == null) {
            throw new IllegalStateException("基线文件缺少字段：" + label + "（" + PATH + "）");
        }
        return v;
    }

    /**
     * YAML 里的金额会被解析成 Double（SnakeYAML 对无引号小数的默认行为）。
     * 走 {@link BigDecimal#valueOf(double)}（等价于 new BigDecimal(Double.toString(d))）拿到
     * 该 double 的最短往返十进制，再比较：与 SQL 返回的 DECIMAL 用 compareTo 判等不受标度影响。
     * 基线数值只用于"口径没漂移"的二次校验，精确逐值对账由测试内的 oracle SQL 负责。
     */
    private static BigDecimal toBigDecimal(Object v) {
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        return new BigDecimal(String.valueOf(v).trim());
    }

    private static Map<String, Object> load() {
        ClassPathResource resource = new ClassPathResource(PATH);
        if (!resource.exists()) {
            throw new IllegalStateException("缺少对账基线文件：" + PATH
                    + "，请执行 python scripts/probe/step0_baseline_full.py 生成");
        }
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(50);
        options.setNestingDepthLimit(20);
        options.setCodePointLimit(1_000_000);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        try (InputStream in = resource.getInputStream()) {
            Object root = yaml.load(in);
            if (root instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> out = (Map<String, Object>) map;
                return out;
            }
            throw new IllegalStateException("基线文件格式应为映射：" + PATH);
        } catch (IOException e) {
            throw new UncheckedIOException("读取基线文件失败：" + PATH, e);
        }
    }
}
