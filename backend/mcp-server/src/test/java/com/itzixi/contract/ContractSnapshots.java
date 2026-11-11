package com.itzixi.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @ClassName ContractSnapshots
 * @Description 契约快照的规范化/读取/比对工具（测试专用，放在中立包）。
 * <p>
 * 指标域与 adhoc 域的契约测试都要用这组工具，若放在 {@code com.itzixi.metrics} 会导致
 * adhoc 测试反向 import metrics，违反分层方案第三章规则一；故独立到中立包，两域均可依赖。
 *
 * @Author crm-agent
 */
public final class ContractSnapshots {

    /** 契约快照专用 ObjectMapper：缩进输出，便于人工审阅 diff */
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** 契约基线目录（测试资源） */
    private static final String CONTRACT_DIR = "contract/";

    private ContractSnapshots() {
    }

    /**
     * 把任意对象（Map/List/JsonNode）转为「键排序后的规范 JSON」。
     * 比较规则：键顺序不敏感、数组顺序敏感、标量保持原值。
     */
    public static String canonicalJson(Object value) {
        try {
            JsonNode tree = value instanceof JsonNode node ? node : JSON.valueToTree(value);
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(sortKeys(tree));
        } catch (IOException e) {
            throw new UncheckedIOException("契约快照序列化失败", e);
        }
    }

    /** 解析 JSON 文本为树，供契约测试与生成器共用。 */
    public static JsonNode parseJson(String json) {
        try {
            return JSON.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("契约基线不是合法 JSON", e);
        }
    }

    /** 读取测试资源中的契约基线，返回规范形式文本。 */
    public static String readBaseline(String baselineName) {
        String path = CONTRACT_DIR + baselineName;
        try (InputStream in = ContractSnapshots.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("契约基线缺失：" + path + "（请先运行 ContractBaselineGenerator 生成）");
            }
            return canonicalJson(parseJson(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException("读取契约基线失败：" + path, e);
        }
    }

    /**
     * 契约断言：实际输出必须与基线零 diff。
     * 失败信息直接给出规范化后的实际值，便于定位被改动的字段。
     */
    public static void assertContractBaseline(String baselineName, Object actual) {
        assertThat(canonicalJson(actual))
                .as("契约基线 %s 必须零 diff（键顺序不敏感）", baselineName)
                .isEqualTo(readBaseline(baselineName));
    }

    /** 生成器专用：把规范化 JSON 写入 src/test/resources/contract/。 */
    public static void writeBaseline(String baselineName, Object value) {
        java.nio.file.Path target = java.nio.file.Path.of("src", "test", "resources", CONTRACT_DIR + baselineName);
        try {
            java.nio.file.Files.createDirectories(target.getParent());
            java.nio.file.Files.writeString(target, canonicalJson(value) + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("写入契约基线失败：" + target, e);
        }
    }

    /** 递归把 JSON 对象键按字典序重排，数组保持原顺序。 */
    private static Object sortKeys(JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> sorted = new TreeMap<>();
            node.fields().forEachRemaining(e -> sorted.put(e.getKey(), sortKeys(e.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            List<Object> out = new ArrayList<>(node.size());
            node.forEach(item -> out.add(sortKeys(item)));
            return out;
        }
        return JSON.convertValue(node, Object.class);
    }

    /** 契约基线文件结构辅助：有序 Map 构造，便于阅读与 diff。 */
    public static Map<String, Object> orderedMap(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return out;
    }
}
