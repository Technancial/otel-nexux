package pe.soapros.otel.lambda.infrastructure;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import pe.soapros.otel.core.domain.LoggerService;
import pe.soapros.otel.logs.domain.LogsManager;
import pe.soapros.otel.logs.infrastructure.ContextAwareLoggerService;

import java.util.HashMap;
import java.util.Map;

public class ObservabilityManager {
    
    private final LoggerService loggerService;
    private final OpenTelemetry openTelemetry;
    
    public static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");
    public static final AttributeKey<String> ERROR_MESSAGE = AttributeKey.stringKey("error.message");
    public static final AttributeKey<String> EXCEPTION_STACKTRACE = AttributeKey.stringKey("exception.stacktrace");
    public static final AttributeKey<Boolean> EXCEPTION_ESCAPED = AttributeKey.booleanKey("exception.escaped");
    
    public ObservabilityManager(OpenTelemetry openTelemetry, String instrumentationName) {
        this.openTelemetry = openTelemetry;
        LogsManager logsManager = new LogsManager(openTelemetry);
        // Usar el logger context-aware que automáticamente incluye traceId y spanId
        this.loggerService = new ContextAwareLoggerService(
            logsManager.getLoggerProvider(), 
            instrumentationName
        );
    }
    
    public Span startSpanWithContext(String spanName, Context parentContext, Tracer tracer) {
        Map<String, String> logAttributes = new HashMap<>();
        logAttributes.put("span.name", spanName);
        logAttributes.put("operation", "span.start");
        
        loggerService.debug("Starting span: " + spanName, logAttributes);
        
        return tracer.spanBuilder(spanName)
                .setParent(parentContext)
                .startSpan();
    }
    
    public void closeSpan(Span span, Exception exception) {
        if (span == null) return;
        
        Map<String, String> logAttributes = new HashMap<>();
        logAttributes.put("operation", "span.close");
        logAttributes.put("status", "error");
        logAttributes.put("exception.type", exception.getClass().getSimpleName());
        logAttributes.put("exception.message", exception.getMessage() != null ? exception.getMessage() : "Unknown error");
        
        try {
            // Log the error
            loggerService.error("Lambda execution failed: " + exception.getMessage(), logAttributes);
            
            // Record exception in span
            span.recordException(exception);
            span.setStatus(StatusCode.ERROR, exception.getMessage());
            
            // Add attributes to span
            span.setAllAttributes(Attributes.of(
                    ERROR_TYPE, exception.getClass().getSimpleName(),
                    ERROR_MESSAGE, exception.getMessage() != null ? exception.getMessage() : "Unknown error",
                    EXCEPTION_ESCAPED, true
            ));
            
            // Add event to span
            span.addEvent("exception", Attributes.of(
                    AttributeKey.stringKey("exception.type"), exception.getClass().getName(),
                    AttributeKey.stringKey("exception.message"), exception.getMessage() != null ? exception.getMessage() : ""
            ));
            
        } catch (Exception e) {
            loggerService.error("Error while closing span: " + e.getMessage(), 
                Map.of("operation", "span.close.error", "error.type", e.getClass().getSimpleName()));
        }
    }
    
    public void closeSpanSuccessfully(Span span) {
        closeSpanSuccessfully(span, null);
    }
    
    public void closeSpanSuccessfully(Span span, String message) {
        if (span == null) return;
        
        Map<String, String> logAttributes = new HashMap<>();
        logAttributes.put("operation", "span.close");
        logAttributes.put("status", "success");
        
        try {
            span.setStatus(StatusCode.OK, message);
            
            if (message != null) {
                logAttributes.put("result", message);
                span.addEvent("success", Attributes.of(
                        AttributeKey.stringKey("result"), message
                ));
            }
            
            loggerService.info("Lambda execution completed successfully" + 
                (message != null ? ": " + message : ""), logAttributes);
                
        } catch (Exception e) {
            loggerService.error("Error while closing span successfully: " + e.getMessage(),
                Map.of("operation", "span.close.success.error", "error.type", e.getClass().getSimpleName()));
        }
    }
    
    public void addPerformanceInfo(Span span, long durationMs, String operation) {
        if (span == null) return;
        
        Map<String, String> logAttributes = new HashMap<>();
        logAttributes.put("operation", "performance.info");
        logAttributes.put("operation.name", operation);
        logAttributes.put("duration_ms", String.valueOf(durationMs));
        
        try {
            span.setAllAttributes(Attributes.of(
                    AttributeKey.longKey("operation.duration_ms"), durationMs,
                    AttributeKey.stringKey("operation.name"), operation
            ));
            
            span.addEvent("operation.completed", Attributes.of(
                    AttributeKey.longKey("duration_ms"), durationMs
            ));
            
            // Log performance info based on duration
            if (durationMs > 5000) { // More than 5 seconds
                loggerService.warn("Slow operation detected: " + operation + " took " + durationMs + "ms", logAttributes);
            } else {
                loggerService.debug("Operation completed: " + operation + " took " + durationMs + "ms", logAttributes);
            }
            
        } catch (Exception e) {
            loggerService.error("Error while adding performance info: " + e.getMessage(),
                Map.of("operation", "performance.info.error", "error.type", e.getClass().getSimpleName()));
        }
    }
    
    public void addSizeInfo(Span span, long requestSize, long responseSize) {
        if (span == null) return;
        
        Map<String, String> logAttributes = new HashMap<>();
        logAttributes.put("operation", "size.info");
        logAttributes.put("request.size_bytes", String.valueOf(requestSize));
        logAttributes.put("response.size_bytes", String.valueOf(responseSize));
        
        try {
            span.setAllAttributes(Attributes.of(
                    AttributeKey.longKey("request.size_bytes"), requestSize,
                    AttributeKey.longKey("response.size_bytes"), responseSize
            ));
            
            // Log size warnings for large payloads
            if (requestSize > 1024 * 1024) { // More than 1MB
                loggerService.warn("Large request payload: " + requestSize + " bytes", logAttributes);
            }
            if (responseSize > 1024 * 1024) { // More than 1MB
                loggerService.warn("Large response payload: " + responseSize + " bytes", logAttributes);
            }
            
            loggerService.debug("Payload sizes recorded", logAttributes);
            
        } catch (Exception e) {
            loggerService.error("Error while adding size info: " + e.getMessage(),
                Map.of("operation", "size.info.error", "error.type", e.getClass().getSimpleName()));
        }
    }
    
    public void addUserInfo(Span span, String userId, String userRole, String tenantId) {
        if (span == null) return;
        
        Map<String, String> logAttributes = new HashMap<>();
        logAttributes.put("operation", "user.info");
        
        try {
            if (userId != null) {
                span.setAttribute("user.id", userId);
                logAttributes.put("user.id", userId);
            }
            if (userRole != null) {
                span.setAttribute("user.role", userRole);
                logAttributes.put("user.role", userRole);
            }
            if (tenantId != null) {
                span.setAttribute("tenant.id", tenantId);
                logAttributes.put("tenant.id", tenantId);
            }
            
            loggerService.info("User context added to span", logAttributes);
            
        } catch (Exception e) {
            loggerService.error("Error while adding user info: " + e.getMessage(),
                Map.of("operation", "user.info.error", "error.type", e.getClass().getSimpleName()));
        }
    }
    
    public void addBusinessContext(Span span, String businessOperation, String entityId, String entityType) {
        if (span == null) return;
        
        Map<String, String> logAttributes = new HashMap<>();
        logAttributes.put("operation", "business.context");
        
        try {
            if (businessOperation != null) {
                span.setAttribute("business.operation", businessOperation);
                logAttributes.put("business.operation", businessOperation);
            }
            if (entityId != null) {
                span.setAttribute("business.entity.id", entityId);
                logAttributes.put("business.entity.id", entityId);
            }
            if (entityType != null) {
                span.setAttribute("business.entity.type", entityType);
                logAttributes.put("business.entity.type", entityType);
            }
            
            loggerService.info("Business context added to span", logAttributes);
            
        } catch (Exception e) {
            loggerService.error("Error while adding business context: " + e.getMessage(),
                Map.of("operation", "business.context.error", "error.type", e.getClass().getSimpleName()));
        }
    }
    
    public void logLambdaStart(String functionName, String requestId) {
        Map<String, String> logAttributes = new HashMap<>();
        logAttributes.put("lambda.function_name", functionName);
        logAttributes.put("lambda.request_id", requestId);
        logAttributes.put("operation", "lambda.start");
        
        loggerService.info("Lambda function started", logAttributes);
    }
    
    public void logLambdaEnd(String functionName, String requestId, long durationMs) {
        Map<String, String> logAttributes = new HashMap<>();
        logAttributes.put("lambda.function_name", functionName);
        logAttributes.put("lambda.request_id", requestId);
        logAttributes.put("lambda.duration_ms", String.valueOf(durationMs));
        logAttributes.put("operation", "lambda.end");
        
        loggerService.info("Lambda function completed", logAttributes);
    }
    
    public void logColdStart(String functionName) {
        Map<String, String> logAttributes = new HashMap<>();
        logAttributes.put("lambda.function_name", functionName);
        logAttributes.put("lambda.cold_start", "true");
        logAttributes.put("operation", "lambda.cold_start");
        
        loggerService.info("Cold start detected", logAttributes);
    }
    
    public LoggerService getLoggerService() {
        return loggerService;
    }
}