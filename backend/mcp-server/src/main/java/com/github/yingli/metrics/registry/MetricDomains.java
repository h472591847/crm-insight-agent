package com.github.yingli.metrics.registry;


import java.util.Locale;
import java.util.Set;

/**
 * @Description 指标域的口径与白名单。目录服务与查询服务共用，避免域归一化逻辑散落在配置类里。
 * <p>
 * B3 前置：本类落地后，{@code MetricQueryService} 才能移入 {@code metrics.service} 包
 * （原 {@code MetricRegistryConfiguration.normalizeDomain()} 为包内可见，跨包不可调用）。
 *
 * @Author crm-agent
 */
public final class MetricDomains {

    /** 允许的域白名单（原 shared.limits.MetricsLimits.ALLOWED_DOMAINS 迁移至此，语义归属指标域） */
    public static final Set<String> ALLOWED = Set.of("member", "order", "delivery", "finance");

    private MetricDomains() {
    }

    /** 域编码归一化：null 保持 null；其余去空格转小写。 */
    public static String normalize(String domain) {
        return domain == null ? null : domain.trim().toLowerCase(Locale.ROOT);
    }
}
