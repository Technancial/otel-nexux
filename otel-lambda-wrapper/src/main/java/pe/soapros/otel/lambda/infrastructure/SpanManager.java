package pe.soapros.otel.lambda.infrastructure;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;

public class SpanManager {
    public static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");
    public static final AttributeKey<String> ERROR_MESSAGE = AttributeKey.stringKey("error.message");
    public static final AttributeKey<String> EXCEPTION_STACKTRACE = AttributeKey.stringKey("exception.stacktrace");
    public static final AttributeKey<Boolean> EXCEPTION_ESCAPED = AttributeKey.booleanKey("exception.escaped");

    public static Span startSpanWithContext(String spanName, Context parentContext, Tracer tracer) {
        return tracer.spanBuilder(spanName)
                .setParent(parentContext)
                .startSpan();
    }

    public static void closeSpan(Span span, Exception exception) {
        if (span == null) return;

        try {
            // Registrar la excepción
            span.recordException(exception);

            // Configurar status de error
            span.setStatus(StatusCode.ERROR, exception.getMessage());

            // Agregar atributos adicionales de error
            span.setAllAttributes(Attributes.of(
                    ERROR_TYPE, exception.getClass().getSimpleName(),
                    ERROR_MESSAGE, exception.getMessage() != null ? exception.getMessage() : "Unknown error",
                    EXCEPTION_ESCAPED, true
            ));

            // Agregar evento de error
            span.addEvent("exception", Attributes.of(
                    AttributeKey.stringKey("exception.type"), exception.getClass().getName(),
                    AttributeKey.stringKey("exception.message"), exception.getMessage() != null ? exception.getMessage() : ""
            ));

        } catch (Exception e) {
            System.err.println("Error while closing span: " + e.getMessage());
        }
    }

    public static void closeSpanSuccessfully(Span span) {
        closeSpanSuccessfully(span, null);
    }

    public static void closeSpanSuccessfully(Span span, String message) {
        if (span == null) return;

        try {
            span.setStatus(StatusCode.OK, message);

            if (message != null) {
                span.addEvent("success", Attributes.of(
                        AttributeKey.stringKey("result"), message
                ));
            }
        } catch (Exception e) {
            System.err.println("Error while closing span successfully: " + e.getMessage());
        }
    }

    public static void addPerformanceInfo(Span span, long durationMs, String operation) {
        if (span == null) return;

        try {
            span.setAllAttributes(Attributes.of(
                    AttributeKey.longKey("operation.duration_ms"), durationMs,
                    AttributeKey.stringKey("operation.name"), operation
            ));

            // Agregar evento de timing
            span.addEvent("operation.completed", Attributes.of(
                    AttributeKey.longKey("duration_ms"), durationMs
            ));

        } catch (Exception e) {
            System.err.println("Error while adding performance info: " + e.getMessage());
        }
    }
    public static void addSizeInfo(Span span, long requestSize, long responseSize) {
        if (span == null) return;

        try {
            span.setAllAttributes(Attributes.of(
                    AttributeKey.longKey("request.size_bytes"), requestSize,
                    AttributeKey.longKey("response.size_bytes"), responseSize
            ));
        } catch (Exception e) {
            System.err.println("Error while adding size info: " + e.getMessage());
        }
    }

    public static void addUserInfo(Span span, String userId, String userRole, String tenantId) {
        if (span == null) return;

        try {
            // Usar setAttribute individual en lugar de builder
            if (userId != null) {
                span.setAttribute("user.id", userId);
            }
            if (userRole != null) {
                span.setAttribute("user.role", userRole);
            }
            if (tenantId != null) {
                span.setAttribute("tenant.id", tenantId);
            }

        } catch (Exception e) {
            System.err.println("Error while adding user info: " + e.getMessage());
        }
    }

    public static void addBusinessContext(Span span, String businessOperation, String entityId, String entityType) {
        if (span == null) return;

        try {
            // Usar setAttribute individual en lugar de builder
            if (businessOperation != null) {
                span.setAttribute("business.operation", businessOperation);
            }
            if (entityId != null) {
                span.setAttribute("business.entity.id", entityId);
            }
            if (entityType != null) {
                span.setAttribute("business.entity.type", entityType);
            }

        } catch (Exception e) {
            System.err.println("Error while adding business context: " + e.getMessage());
        }
    }
}
