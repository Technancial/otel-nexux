package pe.soapros.otel.core.infrastructure;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class BusinessContextEnricher {
    
    // MDC keys for trace context
    private static final String TRACE_ID_KEY = "traceId";
    private static final String SPAN_ID_KEY = "spanId";
    private static final String TRACE_SAMPLED_KEY = "traceSampled";

    // MDC keys for business context
    private static final String BUSINESS_ID_KEY = "businessId";
    private static final String USER_ID_KEY = "userId";
    private static final String TENANT_ID_KEY = "tenantId";
    private static final String CORRELATION_ID_KEY = "correlationId";
    private static final String TRANSACTION_ID_KEY = "transactionId";
    private static final String EXECUTION_ID_KEY = "executionId";
    private static final String OPERATION_KEY = "operation";
    private static final String COMPONENT_KEY = "component";
    private static final String SESSION_ID_KEY = "sessionId";

    private static final ThreadLocal<BusinessContext> currentBusinessContext = new ThreadLocal<>();

    public static void enrichMDCWithTrace() {
        try {
            Span currentSpan = Span.current();
            SpanContext spanContext = currentSpan.getSpanContext();

            if (spanContext.isValid()) {
                MDC.put(TRACE_ID_KEY, spanContext.getTraceId());
                MDC.put(SPAN_ID_KEY, spanContext.getSpanId());
                MDC.put(TRACE_SAMPLED_KEY, String.valueOf(spanContext.isSampled()));
            }
        } catch (Exception e) {
            // Silently ignore - don't break logging
        }
    }

    public static void enrichMDCWithBusiness(BusinessContext context) {
        if (context == null) return;

        // Add trace context first
        enrichMDCWithTrace();

        // Add business context
        try {
            currentBusinessContext.set(context);

            putIfNotNull(BUSINESS_ID_KEY, context.businessId());
            putIfNotNull(USER_ID_KEY, context.userId());
            putIfNotNull(TENANT_ID_KEY, context.tenantId());
            putIfNotNull(CORRELATION_ID_KEY, context.correlationId());
            putIfNotNull(TRANSACTION_ID_KEY, context.transactionId());
            putIfNotNull(EXECUTION_ID_KEY, context.executionId());
            putIfNotNull(OPERATION_KEY, context.operation());
            putIfNotNull(COMPONENT_KEY, context.component());
            putIfNotNull(SESSION_ID_KEY, context.sessionId());

            // Add custom attributes
            if (context.customAttributes() != null) {
                context.customAttributes().forEach((key, value) -> {
                    if (value != null) {
                        MDC.put("custom." + key, value);
                    }
                });
            }
        } catch (Exception e) {
            // Silently ignore
        }
    }

    public static void enrichMDCFromHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return;

        enrichMDCWithTrace();

        String businessId = getHeaderValue(headers, "X-Business-ID", "x-business-id", "Business-ID");
        String userId = getHeaderValue(headers, "X-User-ID", "x-user-id", "User-ID");
        String tenantId = getHeaderValue(headers, "X-Tenant-ID", "x-tenant-id", "Tenant-ID");
        String correlationId = getHeaderValue(headers, "X-Correlation-ID", "x-correlation-id",
                "Correlation-ID", "X-Request-ID", "x-request-id");
        String operation = getHeaderValue(headers, "X-Operation", "x-operation", "Operation");

        if (businessId != null || userId != null || correlationId != null) {
            BusinessContext context = BusinessContext.builder()
                    .businessId(businessId)
                    .userId(userId)
                    .tenantId(tenantId)
                    .correlationId(correlationId)
                    .operation(operation)
                    .build();

            enrichMDCWithBusiness(context);
        }
    }

    public static void updateBusinessId(String businessId) {
        BusinessContext current = currentBusinessContext.get();
        if (current != null) {
            BusinessContext updated = current.toBuilder()
                    .businessId(businessId)
                    .build();
            enrichMDCWithBusiness(updated);
        } else {
            putIfNotNull(BUSINESS_ID_KEY, businessId);
            currentBusinessContext.set(BusinessContext.builder()
                    .businessId(businessId)
                    .build());
        }
    }

    public static void updateOperation(String operation) {
        BusinessContext current = currentBusinessContext.get();
        if (current != null) {
            BusinessContext updated = current.toBuilder()
                    .operation(operation)
                    .build();
            enrichMDCWithBusiness(updated);
        } else {
            putIfNotNull(OPERATION_KEY, operation);
            currentBusinessContext.set(BusinessContext.builder()
                    .operation(operation)
                    .build());
        }
    }

    public static Optional<String> getCurrentBusinessId() {
        BusinessContext current = currentBusinessContext.get();
        if (current != null && current.businessId() != null) {
            return Optional.of(current.businessId());
        }

        String businessId = MDC.get(BUSINESS_ID_KEY);
        return businessId != null ? Optional.of(businessId) : Optional.empty();
    }

    public static BusinessContext getCurrentBusinessContext() {
        return currentBusinessContext.get();
    }

    public static Map<String, String> getCompleteContextInfo() {
        Map<String, String> contextInfo = new HashMap<>();

        // Trace information
        contextInfo.put("trace_id", MDC.get(TRACE_ID_KEY));
        contextInfo.put("span_id", MDC.get(SPAN_ID_KEY));
        contextInfo.put("trace_sampled", MDC.get(TRACE_SAMPLED_KEY));

        // Business information
        contextInfo.put("business_id", MDC.get(BUSINESS_ID_KEY));
        contextInfo.put("user_id", MDC.get(USER_ID_KEY));
        contextInfo.put("tenant_id", MDC.get(TENANT_ID_KEY));
        contextInfo.put("correlation_id", MDC.get(CORRELATION_ID_KEY));
        contextInfo.put("transaction_id", MDC.get(TRANSACTION_ID_KEY));
        contextInfo.put("execution_id", MDC.get(EXECUTION_ID_KEY));
        contextInfo.put("operation", MDC.get(OPERATION_KEY));
        contextInfo.put("component", MDC.get(COMPONENT_KEY));
        contextInfo.put("session_id", MDC.get(SESSION_ID_KEY));

        // Remove null entries
        contextInfo.entrySet().removeIf(entry -> entry.getValue() == null);

        return contextInfo;
    }

    public static Map<String, String> getCurrentTraceInfo() {
        Map<String, String> traceInfo = new HashMap<>();

        String traceId = MDC.get(TRACE_ID_KEY);
        String spanId = MDC.get(SPAN_ID_KEY);
        String traceSampled = MDC.get(TRACE_SAMPLED_KEY);

        if (traceId != null) traceInfo.put("trace_id", traceId);
        if (spanId != null) traceInfo.put("span_id", spanId);
        if (traceSampled != null) traceInfo.put("trace_sampled", traceSampled);

        return traceInfo;
    }

    public static void clearMDC() {
        try {
            MDC.clear();
            currentBusinessContext.remove();
        } catch (Exception e) {
            // Silently ignore
        }
    }

    public static void clearBusinessContext() {
        try {
            currentBusinessContext.remove();

            // Remove only business keys from MDC
            MDC.remove(BUSINESS_ID_KEY);
            MDC.remove(USER_ID_KEY);
            MDC.remove(TENANT_ID_KEY);
            MDC.remove(CORRELATION_ID_KEY);
            MDC.remove(TRANSACTION_ID_KEY);
            MDC.remove(EXECUTION_ID_KEY);
            MDC.remove(OPERATION_KEY);
            MDC.remove(COMPONENT_KEY);
            MDC.remove(SESSION_ID_KEY);

            // Remove custom attributes
            if (MDC.getCopyOfContextMap() != null) {
                MDC.getCopyOfContextMap().keySet().stream()
                        .filter(key -> key.startsWith("custom."))
                        .forEach(MDC::remove);
            }

        } catch (Exception e) {
            // Silently ignore
        }
    }

    // Utility methods
    private static void putIfNotNull(String key, String value) {
        if (value != null && !value.trim().isEmpty()) {
            MDC.put(key, value.trim());
        }
    }

    private static String getHeaderValue(Map<String, String> headers, String... possibleKeys) {
        if (headers == null) return null;

        for (String key : possibleKeys) {
            String value = headers.get(key);
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }
}