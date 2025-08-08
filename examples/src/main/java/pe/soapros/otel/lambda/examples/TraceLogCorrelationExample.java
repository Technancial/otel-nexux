package pe.soapros.otel.lambda.examples;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import pe.soapros.otel.lambda.infrastructure.HttpTracingLambdaWrapper;
import pe.soapros.otel.logs.infrastructure.ContextAwareLoggerService;
import pe.soapros.otel.logs.infrastructure.TraceContextLogEnricher;
import pe.soapros.otel.traces.infrastructure.config.OpenTelemetryConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Ejemplo que demuestra cómo los logs automáticamente incluyen
 * traceId y spanId para correlación completa entre logs y traces.
 */
public class TraceLogCorrelationExample extends HttpTracingLambdaWrapper {
    
    // Logger tradicional de SLF4J (para demostrar correlación con MDC)
    private static final Logger traditionalLogger = LoggerFactory.getLogger(TraceLogCorrelationExample.class);
    
    public TraceLogCorrelationExample() {
        super(OpenTelemetryConfiguration.init("TraceLogCorrelation"));
    }

    public TraceLogCorrelationExample(OpenTelemetry openTelemetry) {
        super(openTelemetry);
    }

    @Override
    public APIGatewayProxyResponseEvent handle(APIGatewayProxyRequestEvent event, Context context) {
        
        // ===== 1. DEMOSTRACIÓN CON LOGGER CONTEXT-AWARE =====
        
        // El ContextAwareLoggerService automáticamente incluye traceId y spanId
        logInfo("🚀 STARTING REQUEST PROCESSING", Map.of(
            "http.method", event.getHttpMethod(),
            "http.path", event.getPath(),
            "demo.type", "context_aware_logging"
        ));
        
        // ===== 2. DEMOSTRACIÓN CON LOGGER TRADICIONAL + MDC =====
        
        // Enriquecer MDC con información del trace actual
        TraceContextLogEnricher.enrichMDC();
        
        try {
            // Ahora el logger tradicional tendrá traceId y spanId en el MDC
            traditionalLogger.info("📝 TRADITIONAL LOGGER - Request received: {} {}", 
                event.getHttpMethod(), event.getPath());
            
            // Simular operaciones de negocio con logging correlacionado
            return processRequestWithCorrelatedLogging(event, context);
            
        } finally {
            // Limpiar MDC al final
            TraceContextLogEnricher.clearMDC();
        }
    }
    
    private APIGatewayProxyResponseEvent processRequestWithCorrelatedLogging(
            APIGatewayProxyRequestEvent event, Context context) {
        
        String operation = determineOperation(event);
        
        // Log con información del span actual automáticamente incluida
        logInfo("🔄 PROCESSING BUSINESS LOGIC", Map.of(
            "business.operation", operation,
            "lambda.request_id", context.getAwsRequestId(),
            "lambda.function_name", context.getFunctionName()
        ));
        
        try {
            switch (operation) {
                case "get_user_profile":
                    return handleGetUserProfile(event);
                case "update_user_profile":
                    return handleUpdateUserProfile(event);
                case "delete_user_profile":
                    return handleDeleteUserProfile(event);
                case "health_check":
                    return handleHealthCheck();
                default:
                    return handleUnknownOperation(operation);
            }
            
        } catch (Exception e) {
            // Error logging con correlación automática
            logError("❌ BUSINESS LOGIC FAILED", Map.of(
                "business.operation", operation,
                "error.type", e.getClass().getSimpleName(),
                "error.message", e.getMessage()
            ));
            
            // También usar logger tradicional para mostrar correlación
            traditionalLogger.error("💥 TRADITIONAL LOGGER - Operation failed: {}", operation, e);
            
            return createErrorResponse(500, "Internal server error");
        }
    }
    
    private APIGatewayProxyResponseEvent handleGetUserProfile(APIGatewayProxyRequestEvent event) {
        String userId = extractUserIdFromPath(event.getPath());
        
        // Log de inicio de operación
        logInfo("👤 RETRIEVING USER PROFILE", Map.of(
            "user.id", userId != null ? userId : "unknown",
            "operation.step", "start"
        ));
        
        // Simular acceso a base de datos
        simulateDatabaseAccess("SELECT * FROM users WHERE id = ?", userId, 200);
        
        // Crear span hijo para demostrar correlación en sub-operaciones
        Span currentSpan = Span.current();
        Span childSpan = getOpenTelemetry().getTracer("user-service")
            .spanBuilder("validate_user_permissions")
            .setParent(io.opentelemetry.context.Context.current())
            .startSpan();
        
        try (var scope = childSpan.makeCurrent()) {
            // Los logs dentro de este span tendrán el nuevo spanId pero el mismo traceId
            logInfo("🔐 VALIDATING USER PERMISSIONS", Map.of(
                "user.id", userId != null ? userId : "unknown",
                "operation.step", "permission_check"
            ));
            
            // Logger tradicional también tendrá la nueva correlación
            TraceContextLogEnricher.enrichMDC();
            traditionalLogger.info("🔍 TRADITIONAL LOGGER - Validating permissions for user: {}", userId);
            TraceContextLogEnricher.clearMDC();
            
            simulatePermissionCheck(userId, 100);
            
        } finally {
            childSpan.end();
        }
        
        // Log de finalización exitosa
        logInfo("✅ USER PROFILE RETRIEVED SUCCESSFULLY", Map.of(
            "user.id", userId != null ? userId : "unknown",
            "operation.step", "complete",
            "result.status", "success"
        ));
        
        Map<String, Object> response = new HashMap<>();
        response.put("userId", userId);
        response.put("name", "John Doe");
        response.put("email", "john.doe@example.com");
        response.put("traceInfo", getCurrentTraceInfo());
        
        return createSuccessResponse(response);
    }
    
    private APIGatewayProxyResponseEvent handleUpdateUserProfile(APIGatewayProxyRequestEvent event) {
        String userId = extractUserIdFromPath(event.getPath());
        
        logInfo("✏️ UPDATING USER PROFILE", Map.of(
            "user.id", userId != null ? userId : "unknown",
            "operation.step", "start",
            "payload.size", String.valueOf(event.getBody() != null ? event.getBody().length() : 0)
        ));
        
        // Validación
        if (event.getBody() == null || event.getBody().isEmpty()) {
            logWarn("⚠️ UPDATE ATTEMPTED WITH EMPTY PAYLOAD", Map.of(
                "user.id", userId != null ? userId : "unknown",
                "validation.error", "empty_payload"
            ));
            return createErrorResponse(400, "Request body is required");
        }
        
        // Simular actualización
        simulateDatabaseAccess("UPDATE users SET ... WHERE id = ?", userId, 250);
        
        logInfo("✅ USER PROFILE UPDATED SUCCESSFULLY", Map.of(
            "user.id", userId != null ? userId : "unknown",
            "operation.step", "complete"
        ));
        
        return createSuccessResponse(Map.of(
            "message", "User profile updated successfully",
            "userId", userId,
            "traceInfo", getCurrentTraceInfo()
        ));
    }
    
    private APIGatewayProxyResponseEvent handleDeleteUserProfile(APIGatewayProxyRequestEvent event) {
        String userId = extractUserIdFromPath(event.getPath());
        
        logInfo("🗑️ DELETING USER PROFILE", Map.of(
            "user.id", userId != null ? userId : "unknown",
            "operation.step", "start"
        ));
        
        // Operación crítica - log de warning
        logWarn("⚠️ CRITICAL OPERATION - USER DELETION", Map.of(
            "user.id", userId != null ? userId : "unknown",
            "operation.type", "destructive"
        ));
        
        simulateDatabaseAccess("DELETE FROM users WHERE id = ?", userId, 180);
        
        logInfo("✅ USER PROFILE DELETED SUCCESSFULLY", Map.of(
            "user.id", userId != null ? userId : "unknown",
            "operation.step", "complete"
        ));
        
        return createSuccessResponse(Map.of(
            "message", "User profile deleted successfully",
            "traceInfo", getCurrentTraceInfo()
        ));
    }
    
    private APIGatewayProxyResponseEvent handleHealthCheck() {
        logDebug("🏥 HEALTH CHECK REQUESTED", Map.of(
            "operation.type", "health_check"
        ));
        
        return createSuccessResponse(Map.of(
            "status", "healthy",
            "timestamp", System.currentTimeMillis(),
            "service", "trace-log-correlation-example",
            "traceInfo", getCurrentTraceInfo()
        ));
    }
    
    private APIGatewayProxyResponseEvent handleUnknownOperation(String operation) {
        logWarn("❓ UNKNOWN OPERATION REQUESTED", Map.of(
            "operation", operation,
            "error.type", "unknown_operation"
        ));
        
        return createErrorResponse(404, "Operation not found: " + operation);
    }
    
    // ===== MÉTODOS UTILITARIOS =====
    
    private String determineOperation(APIGatewayProxyRequestEvent event) {
        String path = event.getPath();
        String method = event.getHttpMethod();
        
        if ("/health".equals(path)) {
            return "health_check";
        } else if (path.startsWith("/users/") && "GET".equals(method)) {
            return "get_user_profile";
        } else if (path.startsWith("/users/") && "PUT".equals(method)) {
            return "update_user_profile";
        } else if (path.startsWith("/users/") && "DELETE".equals(method)) {
            return "delete_user_profile";
        }
        
        return "unknown_operation";
    }
    
    private String extractUserIdFromPath(String path) {
        if (path == null) return null;
        String[] parts = path.split("/");
        return parts.length > 2 ? parts[2] : null;
    }
    
    private void simulateDatabaseAccess(String operation, String param, long durationMs) {
        logDebug("🗄️ DATABASE OPERATION STARTED", Map.of(
            "db.operation", operation,
            "db.parameter", param != null ? param : "null",
            "db.expected_duration_ms", String.valueOf(durationMs)
        ));
        
        try {
            Thread.sleep(durationMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logWarn("⚠️ DATABASE OPERATION INTERRUPTED", Map.of(
                "db.operation", operation,
                "error.type", "InterruptedException"
            ));
        }
        
        logDebug("✅ DATABASE OPERATION COMPLETED", Map.of(
            "db.operation", operation,
            "db.actual_duration_ms", String.valueOf(durationMs)
        ));
    }
    
    private void simulatePermissionCheck(String userId, long durationMs) {
        logDebug("🔐 PERMISSION CHECK STARTED", Map.of(
            "user.id", userId != null ? userId : "unknown",
            "expected_duration_ms", String.valueOf(durationMs)
        ));
        
        try {
            Thread.sleep(durationMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        logDebug("✅ PERMISSION CHECK COMPLETED", Map.of(
            "user.id", userId != null ? userId : "unknown",
            "permission.granted", "true"
        ));
    }
    
    /**
     * Obtiene información del trace actual para incluir en la respuesta
     */
    private Map<String, String> getCurrentTraceInfo() {
        return TraceContextLogEnricher.getCurrentTraceInfo();
    }
    
    /**
     * Obtiene acceso al OpenTelemetry instance para crear spans hijos
     */
    private OpenTelemetry getOpenTelemetry() {
        return getObservabilityManager().getLoggerService() instanceof ContextAwareLoggerService ? 
            super.openTelemetry : super.openTelemetry;
    }
}