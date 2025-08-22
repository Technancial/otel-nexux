package pe.soapros.otel.lambda.infrastructure;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import lombok.Getter;
import pe.soapros.otel.core.domain.LoggerService;
import pe.soapros.otel.core.infrastructure.OpenTelemetryManager;
import pe.soapros.otel.core.infrastructure.BusinessContext;
import pe.soapros.otel.core.infrastructure.BusinessContextEnricher;
import pe.soapros.otel.logs.infrastructure.BusinessContextLogEnricher;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static pe.soapros.otel.lambda.infrastructure.ObservabilityConstants.*;

@Getter
public class ObservabilityManager {
    
    private final LoggerService loggerService;
    private final OpenTelemetry openTelemetry;
    
    public ObservabilityManager(OpenTelemetry openTelemetry, String instrumentationName) {
        this.openTelemetry = openTelemetry;
        // Usar el logger service centralizado que ya incluye trace correlation
        this.loggerService = new pe.soapros.otel.core.infrastructure.OpenTelemetryLoggerService(
            openTelemetry.getLogsBridge().get(instrumentationName),
            instrumentationName,
            true // Enable trace correlation
        );
    }
    
    public static ObservabilityManager fromManager(String instrumentationName) {
        if (!OpenTelemetryManager.isInitialized()) {
            throw new IllegalStateException("OpenTelemetryManager not initialized. Call OpenTelemetryManager.initialize() first.");
        }
        
        return new ObservabilityManager(
            OpenTelemetryManager.getInstance().getOpenTelemetry(), 
            instrumentationName
        );
    }
    
    public static ObservabilityManager fromManagerWithDefaults() {
        if (!OpenTelemetryManager.isInitialized()) {
            throw new IllegalStateException("OpenTelemetryManager not initialized. Call OpenTelemetryManager.initialize() first.");
        }
        
        var manager = OpenTelemetryManager.getInstance();
        return new ObservabilityManager(
            manager.getOpenTelemetry(), 
            manager.getConfig().getServiceName()
        );
    }

    public void setupQuickBusinessContext(String businessId, String userId, String operation) {
        // Enriquecer MDC con contexto básico
        BusinessContext context = BusinessContext.builder()
                .businessId(businessId)
                .userId(userId)
                .operation(operation)
                .build();

        BusinessContextEnricher.enrichMDCWithBusiness(context);

        // También enriquecer el span actual si existe
        Span currentSpan = Span.current();
        if (currentSpan.getSpanContext().isValid()) {
            enrichSpanWithQuickContext(currentSpan, businessId, userId, operation);
        }

        logInfo("Business context configured", Map.of(
                "business.id", businessId != null ? businessId : "null",
                "user.id", userId != null ? userId : "null",
                "operation", operation != null ? operation : "null"
        ));
    }

    public void setupBusinessContextFromHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return;
        }

        // Extraer información de negocio de headers comunes
        String businessId = extractBusinessId(headers);
        String userId = extractUserId(headers);
        String correlationId = extractCorrelationId(headers);
        String tenantId = extractTenantId(headers);
        String operation = extractOperation(headers);

        BusinessContext context = BusinessContext.builder()
                .businessId(businessId)
                .userId(userId)
                .correlationId(correlationId)
                .tenantId(tenantId)
                .operation(operation)
                .build();

        BusinessContextEnricher.enrichMDCWithBusiness(context);

        // También enriquecer el span actual si existe
        Span currentSpan = Span.current();
        if (currentSpan.getSpanContext().isValid()) {
            enrichSpanWithBusinessContext(currentSpan, context);
        }

        logDebug("Business context configured from headers", Map.of(
                "headers.count", String.valueOf(headers.size()),
                "business.id", businessId != null ? businessId : "null",
                "user.id", userId != null ? userId : "null",
                "correlation.id", correlationId != null ? correlationId : "null"
        ));
    }

    public void updateBusinessId(String businessId) {
        BusinessContextEnricher.updateBusinessId(businessId);

        // Actualizar span actual
        Span currentSpan = Span.current();
        if (currentSpan.getSpanContext().isValid()) {
            currentSpan.setAttribute(BUSINESS_ID, businessId);
        }

        logDebug("Business ID updated", Map.of("business.id", businessId));
    }

    public void updateOperation(String operation) {
        BusinessContextEnricher.updateOperation(operation);

        // Actualizar span actual
        Span currentSpan = Span.current();
        if (currentSpan.getSpanContext().isValid()) {
            currentSpan.setAttribute(OPERATION, operation);
        }

        logDebug("Operation updated", Map.of("operation", operation));
    }

    public Optional<String> getCurrentBusinessId() {
        return BusinessContextLogEnricher.getCurrentBusinessId();
    }

    public Map<String, String> getCompleteContextInfo() {
        return BusinessContextLogEnricher.getCompleteContextInfo();
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
                    OPERATION_DURATION_MS, durationMs,
                    OPERATION_NAME, operation
            ));
            
            span.addEvent("operation.completed", Attributes.of(
                    OPERATION_DURATION_MS, durationMs
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
                    REQUEST_SIZE_BYTES, requestSize,
                    RESPONSE_SIZE_BYTES, responseSize
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

        BusinessContextEnricher.clearMDC();
    }
    
    public void logColdStart(String functionName) {
        Map<String, String> logAttributes = new HashMap<>();
        logAttributes.put("lambda.function_name", functionName);
        logAttributes.put("lambda.cold_start", "true");
        logAttributes.put("operation", "lambda.cold_start");
        
        loggerService.info("Cold start detected", logAttributes);
    }

    // ==================== MÉTODOS AUXILIARES PARA LOGGING ====================

    /**
     * Log de info con contexto automático
     */
    protected void logInfo(String message, Map<String, String> attributes) {
        loggerService.info(message, attributes);
    }

    /**
     * Log de debug con contexto automático
     */
    protected void logDebug(String message, Map<String, String> attributes) {
        loggerService.debug(message, attributes);
    }

    /**
     * Log de warning con contexto automático
     */
    protected void logWarn(String message, Map<String, String> attributes) {
        loggerService.warn(message, attributes);
    }

    /**
     * Log de error con contexto automático
     */
    protected void logError(String message, Map<String, String> attributes) {
        loggerService.error(message, attributes);
    }

    // ==================== MÉTODOS PRIVADOS PARA EXTRACCIÓN DE CONTEXTO ====================

    private void enrichSpanWithQuickContext(Span span, String businessId, String userId, String operation) {
        try {
            if (businessId != null) {
                span.setAttribute(BUSINESS_ID, businessId);
            }
            if (userId != null) {
                span.setAttribute(USER_ID, userId);
            }
            if (operation != null) {
                span.setAttribute(OPERATION, operation);
            }
        } catch (Exception e) {
            logError("Error enriching span with quick context: " + e.getMessage(), Map.of());
        }
    }

    private void enrichSpanWithBusinessContext(Span span, BusinessContext context) {
        if (span == null || context == null) return;

        try {
            if (context.businessId() != null) {
                span.setAttribute(BUSINESS_ID, context.businessId());
            }
            if (context.userId() != null) {
                span.setAttribute(USER_ID, context.userId());
            }
            if (context.operation() != null) {
                span.setAttribute(OPERATION, context.operation());
            }
            if (context.tenantId() != null) {
                span.setAttribute(TENANT_ID, context.tenantId());
            }
            if (context.correlationId() != null) {
                span.setAttribute(CORRELATION_ID, context.correlationId());
            }
        } catch (Exception e) {
            logError("Error enriching span with business context: " + e.getMessage(), Map.of());
        }
    }

    private String extractBusinessId(Map<String, String> headers) {
        return getHeaderValue(headers, "X-Business-ID", "x-business-id", "Business-ID", "business-id");
    }

    private String extractUserId(Map<String, String> headers) {
        return getHeaderValue(headers, "X-User-ID", "x-user-id", "User-ID", "user-id");
    }

    private String extractCorrelationId(Map<String, String> headers) {
        return getHeaderValue(headers, "X-Correlation-ID", "x-correlation-id", "Correlation-ID",
                "correlation-id", "X-Request-ID", "x-request-id");
    }

    private String extractTenantId(Map<String, String> headers) {
        return getHeaderValue(headers, "X-Tenant-ID", "x-tenant-id", "Tenant-ID", "tenant-id");
    }

    private String extractOperation(Map<String, String> headers) {
        return getHeaderValue(headers, "X-Operation", "x-operation", "Operation", "operation");
    }

    private String getHeaderValue(Map<String, String> headers, String... possibleKeys) {
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