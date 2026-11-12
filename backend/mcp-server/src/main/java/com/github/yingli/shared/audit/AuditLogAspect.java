package com.github.yingli.shared.audit;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.lang.reflect.Array;
import java.util.Collection;

/**
 * 查询审计日志 AOP。切所有运行期查询入口，输出统一格式的审计行。
 * <p>
 * 记录内容（只记元信息，不记 PII/不记完整结果集）：
 * <pre>
 * [AUDIT] service=MetricQueryService metric=STATION_SALES dims=[STATION] filters=[] sqlHash=a1b2c3d4 rows=14 totalRows=1 costMs=23 success=true errorCode=null
 * </pre>
 * <p>
 * 开关：{@code crm.audit.enabled=false} 关闭（默认开启）。
 *
 * @author crm-agent
 */
@Slf4j
@Aspect
@Component
@ConditionalOnProperty(prefix = "crm.audit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AuditLogAspect {

    // === 切点：MetricQueryService.query + AdhocQueryService.query ===

    @Around("execution(* com.github.yingli.metrics.service.MetricQueryService.query(..))"
          + " || execution(* com.github.yingli.adhoc.service.AdhocQueryService.query(..))")
    public Object auditQuery(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.currentTimeMillis();
        Object result;
        Throwable error = null;
        try {
            result = pjp.proceed();
        } catch (Throwable t) {
            error = t;
            result = null;
        }
        long costMs = System.currentTimeMillis() - start;

        String point = pjp.getTarget().getClass().getSimpleName()
                     + "." + pjp.getSignature().getName();
        String summary = extractSummary(result, error, costMs);
        log.info("[AUDIT] service={} args={} {}", point, argsPreview(pjp.getArgs()), summary);

        if (error != null) {
            throw error;
        }
        return result;
    }

    // === 内部 ===

    /**
     * 从 MetricResult / AdhocResult 提取审计摘要。
     * 两个 record 都有 sqlHash / errorCode / 行数，但字段名不完全一致 —
     * 用反射做兜底，避免引入额外接口。
     */
    private String extractSummary(Object result, Throwable error, long costMs) {
        if (error != null) {
            String code = error.getClass().getSimpleName();
            return "success=false errorClass=" + code + " costMs=" + costMs;
        }
        if (result == null) {
            return "success=false errorClass=null costMs=" + costMs;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("success=true");
        sb.append(" costMs=").append(costMs);

        // sqlHash
        String hash = invoke(result, "getSqlHash");
        if (hash == null) hash = invoke(result, "sqlHash"); // record 原生访问
        if (hash != null) sb.append(" sqlHash=").append(truncate(hash, 12));

        // errorCode（AdhocResult 是 record 直接字段，MetricResult 有 getter）
        String err = invoke(result, "getErrorCode");
        if (err == null) err = invoke(result, "errorCode");
        if (err != null && !err.isBlank()) sb.append(" errorCode=").append(err);

        // 行数：MetricResult.rows / AdhocResult.rows
        Integer rows = invokeInt(result, "getRowCount");
        if (rows == null) rows = invokeInt(result, "rowCount");
        if (rows == null) rows = invokeInt(result, "size", "getRows", "rows");
        if (rows != null) sb.append(" rows=").append(rows);

        return sb.toString();
    }

    private String argsPreview(Object[] args) {
        if (args == null || args.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(safeToString(args[i]));
        }
        return truncate(sb.toString(), 200);
    }

    /** 入参摘要：只显式 String / Integer / enum；复杂对象只给类名避免 toString 泄漏 */
    private String safeToString(Object o) {
        if (o == null) return "null";
        if (o instanceof String s) return "\"" + truncate(s, 80) + "\"";
        if (o instanceof Number || o instanceof Boolean || o instanceof Enum) return String.valueOf(o);
        return o.getClass().getSimpleName();
    }

    private String invoke(Object target, String... methodNames) {
        for (String name : methodNames) {
            try {
                Object v = target.getClass().getMethod(name).invoke(target);
                if (v != null) return v.toString();
            } catch (NoSuchMethodException ignored) { /* 换下一个名字 */ }
            catch (ReflectiveOperationException ignored) { }
        }
        return null;
    }

    private Integer invokeInt(Object target, String... methodNames) {
        for (String name : methodNames) {
            try {
                Object v = target.getClass().getMethod(name).invoke(target);
                if (v instanceof Number n) return n.intValue();
            } catch (NoSuchMethodException ignored) { }
            catch (ReflectiveOperationException ignored) { }
        }
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
