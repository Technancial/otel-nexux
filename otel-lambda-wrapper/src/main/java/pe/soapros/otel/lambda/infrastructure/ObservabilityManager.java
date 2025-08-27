package pe.soapros.otel.lambda.infrastructure;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import lombok.Getter;
import pe.soapros.otel.core.domain.LoggerService;
import pe.soapros.otel.core.domain.TraceSpan;
import pe.soapros.otel.core.domain.TracerService;
import pe.soapros.otel.core.infrastructure.*;
import pe.soapros.otel.logs.infrastructure.BusinessContextLogEnricher;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static pe.soapros.otel.lambda.infrastructure.ObservabilityConstants.*;

/**
 * OBSERVABILITY MANAGER: Facade principal para la observabilidad en Lambda
 * <p>
 * Esta clase encapsula todas las funcionalidades de observabilidad específicas
 * para AWS Lambda con API Gateway
 */
@Getter
public class ObservabilityManager {

    private final OpenTelemetry openTelemetry;
    private final LoggerService loggerService;
    private final TracerService tracerService;
    private final ResponseHttpEnricher responseHttpEnricher;
    private final SubSpanExecutor subSpanExecutor;
    private final LambdaObservabilityConfig config;

    
    public ObservabilityManager(OpenTelemetry openTelemetry,
                                String instrumentationName,
                                LambdaObservabilityConfig config) {

        this.openTelemetry = openTelemetry;
        this.config = config != null ? config : LambdaObservabilityConfig.defaultForLambda();

        // Usar el logger service centralizado que ya incluye trace correlation
        /*this.loggerService = new pe.soapros.otel.core.infrastructure.OpenTelemetryLoggerService(
            openTelemetry.getLogsBridge().get(instrumentationName),
            instrumentationName,
            true // Enable trace correlation
        );*/
        this.loggerService = createLoggerService(openTelemetry, instrumentationName);
        this.tracerService = createTracerService();
        this.responseHttpEnricher = createResponseEnricher(this.config);
        this.subSpanExecutor = createSubSpanExecutor(this.config);
    }

    public static ObservabilityManager fromManagerWithDefaults() {
        if (!OpenTelemetryManager.isInitialized()) {
            throw new IllegalStateException("OpenTelemetryManager not initialized");
        }

        var manager = OpenTelemetryManager.getInstance();
        return new ObservabilityManager(
                manager.getOpenTelemetry(),
                manager.getConfig().getServiceName(),
                LambdaObservabilityConfig.defaultForLambda()
        );
    }

    public static ObservabilityManager fromManagerForDevelopment() {
        if (!OpenTelemetryManager.isInitialized()) {
            throw new IllegalStateException("OpenTelemetryManager not initialized");
        }

        var manager = OpenTelemetryManager.getInstance();
        return new ObservabilityManager(
                manager.getOpenTelemetry(),
                manager.getConfig().getServiceName(),
                LambdaObservabilityConfig.developmentConfig()
        );
    }

    public static ObservabilityManager fromManagerForProduction() {
        if (!OpenTelemetryManager.isInitialized()) {
            throw new IllegalStateException("OpenTelemetryManager not initialized");
        }

        var manager = OpenTelemetryManager.getInstance();
        return new ObservabilityManager(
                manager.getOpenTelemetry(),
                manager.getConfig().getServiceName(),
                LambdaObservabilityConfig.performanceOptimized()
        );
    }

    public static ObservabilityManager fromManager(LambdaObservabilityConfig config) {
        if (!OpenTelemetryManager.isInitialized()) {
            throw new IllegalStateException("OpenTelemetryManager not initialized");
        }

        var manager = OpenTelemetryManager.getInstance();
        return new ObservabilityManager(
                manager.getOpenTelemetry(),
                manager.getConfig().getServiceName(),
                config
        );
    }

    /**
     * 🎯 NUEVA: Enriquecimiento automático de span con respuesta HTTP
     */
    public void enrichSpanWithResponse(TraceSpan span, APIGatewayProxyResponseEvent response) {
        responseHttpEnricher.enrichWithApiGatewayResponse(span, response);

        if (config.isVerboseLogging()) {
            logInfo("Span enriched with HTTP response", Map.of(
                    "http.status_code", String.valueOf(response.getStatusCode()),
                    "span.id", span.getSpanId()
            ));
        }
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

    /**
     * @deprecated Use TracerService.startSpan() with SubSpanExecutor instead
     * Este método será removido en la próxima versión mayor.
     * <p>
     * RAZÓN: La responsabilidad de crear spans debe estar en TracerService.
     * Este método mezcla responsabilidades de logging y span creation.
     */
    @Deprecated
    public Span startSpanWithContext(String spanName, Context parentContext, Tracer tracer) {
        Map<String, String> logAttributes = new HashMap<>();
        logAttributes.put("span.name", spanName);
        logAttributes.put("operation", "span.start");
        
        loggerService.debug("Starting span: " + spanName, logAttributes);
        
        return tracer.spanBuilder(spanName)
                .setParent(parentContext)
                .startSpan();
    }

    /**
     * @deprecated Use TraceSpan.recordException() and TraceSpan.setStatus() directly
     * Este método será removido en la próxima versión mayor.
     * <p>
     * RAZÓN: Esta funcionalidad ya está disponible en la abstracción TraceSpan.
     * No necesitamos duplicar la lógica aquí.
     */
    @Deprecated(since = "2.0", forRemoval = true)
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

    @Deprecated
    public void closeSpanSuccessfully(Span span) {
        closeSpanSuccessfully(span, null);
    }

    @Deprecated
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

    @Deprecated
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

    @Deprecated
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

    @Deprecated
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

    @Deprecated
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
        logInfo("Lambda function started", Map.of(
                "lambda.function_name", functionName,
                "lambda.request_id", requestId
        ));
    }
    
    public void logLambdaEnd(String functionName, String requestId, long durationMs) {
        logInfo("Lambda function completed", Map.of(
                "lambda.function_name", functionName,
                "lambda.request_id", requestId,
                "lambda.duration_ms", String.valueOf(durationMs)
        ));

        BusinessContextEnricher.clearMDC();
    }
    
    public void logColdStart(String functionName) {
        Map<String, String> logAttributes = new HashMap<>();
        logAttributes.put("lambda.function_name", functionName);
        logAttributes.put("lambda.cold_start", "true");
        logAttributes.put("operation", "lambda.cold_start");
        
        loggerService.info("Cold start detected", logAttributes);
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

    // =============== MÉTODOS PRIVADOS DE SOPORTE ===============

    private LoggerService createLoggerService(OpenTelemetry openTelemetry, String instrumentationName) {
        return new pe.soapros.otel.core.infrastructure.OpenTelemetryLoggerService(
                openTelemetry.getLogsBridge().get(instrumentationName),
                instrumentationName,
                true // Enable trace correlation
        );
    }

    private TracerService createTracerService() {
        return OpenTelemetryManager.getInstance().getTracerService();
    }

    private ResponseHttpEnricher createResponseEnricher(LambdaObservabilityConfig config) {
        var builder = ResponseHttpEnricher.builder();

        if (config.getCustomResponseHeaders() != null) {
            builder.includeCustomHeaders(config.getCustomResponseHeaders().toArray(String[]::new));
        }

        if (config.isIncludeResponseBody()) {
            builder.includeResponseBody(config.getMaxResponseBodyLength());
        }

        return builder.build();
    }

    private SubSpanExecutor createSubSpanExecutor(LambdaObservabilityConfig config) {
        return new SubSpanExecutor(
                tracerService,
                config.isEnableSubSpanEvents(),
                config.isEnableSubSpanMetrics()
        );
    }

    public TraceSpan createLambdaSpanWithContext(String spanName,
                                                 Context parentContext,
                                                 Map<String, String> attributes) {
        Tracer tracer = openTelemetry.getTracer(
                OpenTelemetryManager.getInstance().getConfig().getServiceName()
        );

        SpanBuilder spanBuilder = tracer.spanBuilder(spanName);

        if (parentContext != null && parentContext != Context.root()) {

            spanBuilder.setParent(parentContext);

            if (config.isVerboseLogging()) {
                logDebug("Creating Lambda span with parent context", Map.of(
                        "span.name", spanName,
                        "has.parent", "true"
                ));
            }
        } else {
            if (config.isVerboseLogging()) {
                logDebug("Creating Lambda span without parent context", Map.of(
                        "span.name", spanName,
                        "has.parent", "false"
                ));
            }
        }

        Span span = spanBuilder.startSpan();

        if (attributes != null) {
            attributes.forEach(span::setAttribute);
        }

        return new OpenTelemetryTraceSpan(span);
    }

    private void enrichSpanWithQuickContext(Span span, String businessId, String userId, String operation) {
        if (businessId != null) span.setAttribute("business.id", businessId);
        if (userId != null) span.setAttribute("user.id", userId);
        if (operation != null) span.setAttribute("operation", operation);
    }

    private void enrichSpanWithBusinessContext(Span span, BusinessContext context) {
        if (context.businessId() != null) span.setAttribute("business.id", context.businessId());
        if (context.userId() != null) span.setAttribute("user.id", context.userId());
        if (context.operation() != null) span.setAttribute("operation", context.operation());
        if (context.tenantId() != null) span.setAttribute("tenant.id", context.tenantId());
        if (context.correlationId() != null) span.setAttribute("correlation.id", context.correlationId());
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