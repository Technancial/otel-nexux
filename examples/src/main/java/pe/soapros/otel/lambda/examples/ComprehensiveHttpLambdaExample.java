package pe.soapros.otel.lambda.examples;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.GlobalOpenTelemetry;
import pe.soapros.otel.lambda.infrastructure.HttpTracingLambdaWrapper;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Ejemplo completo de función Lambda HTTP que demuestra todas las capacidades
 * del HttpTracingLambdaWrapper incluyendo:
 * - Manejo de diferentes métodos HTTP
 * - Configuración automática de contexto de negocio desde headers
 * - Logging estructurado con correlación de trazas
 * - Manejo de errores con observabilidad
 * - Métricas automáticas de rendimiento
 * - Respuestas JSON bien estructuradas
 */
public class ComprehensiveHttpLambdaExample extends HttpTracingLambdaWrapper {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ComprehensiveHttpLambdaExample() {
        super(GlobalOpenTelemetry.get());
    }

    @Override
    public APIGatewayProxyResponseEvent handle(APIGatewayProxyRequestEvent event, Context context) {
        
        // Log información básica del request usando el wrapper
        logInfo("Processing HTTP request", Map.of(
                "method", event.getHttpMethod(),
                "path", event.getPath(),
                "requestId", context.getAwsRequestId()
        ));

        try {
            // Obtener contexto de negocio configurado automáticamente por el wrapper
            Map<String, String> businessContext = getCurrentContextInfo();
            
            // Log del contexto de negocio si está disponible
            if (!businessContext.isEmpty()) {
                logInfo("Business context detected", businessContext);
            }

            // Enrutar según el método HTTP y path
            String method = event.getHttpMethod();
            String path = event.getPath();

            return switch (method.toUpperCase()) {
                case "GET" -> handleGetRequest(event, context);
                case "POST" -> handlePostRequest(event, context);
                case "PUT" -> handlePutRequest(event, context);
                case "DELETE" -> handleDeleteRequest(event, context);
                case "OPTIONS" -> handleOptionsRequest(event, context);
                default -> createErrorResponse(405, "Method not allowed: " + method);
            };

        } catch (Exception e) {
            // El wrapper maneja automáticamente el logging de errores y métricas
            logError("Unexpected error processing request", Map.of(
                    "error.type", e.getClass().getSimpleName(),
                    "error.message", e.getMessage() != null ? e.getMessage() : "Unknown error"
            ));
            return createErrorResponse(500, "Internal server error");
        }
    }

    private APIGatewayProxyResponseEvent handleGetRequest(APIGatewayProxyRequestEvent event, Context context) {
        logDebug("Handling GET request", Map.of("path", event.getPath()));
        
        // Simular obtención de datos con diferentes escenarios
        String path = event.getPath();
        
        if (path.contains("/users/")) {
            return handleGetUser(event);
        } else if (path.equals("/health")) {
            return handleHealthCheck(event);
        } else if (path.equals("/metrics")) {
            return handleGetMetrics(event);
        } else {
            return handleGetDefault(event);
        }
    }

    private APIGatewayProxyResponseEvent handleGetUser(APIGatewayProxyRequestEvent event) {
        // Extraer ID de usuario del path
        String path = event.getPath();
        String userId = extractUserIdFromPath(path);
        
        if (userId == null) {
            return createErrorResponse(400, "Invalid user ID in path");
        }

        // Configurar contexto de negocio específico para esta operación
        getObservabilityManager().updateOperation("get-user");
        
        // Simular obtención de usuario
        Map<String, Object> userData = Map.of(
                "id", userId,
                "name", "John Doe",
                "email", "john.doe@example.com",
                "lastLogin", LocalDateTime.now().toString(),
                "status", "active"
        );

        logInfo("User data retrieved successfully", Map.of(
                "user.id", userId,
                "operation", "get-user"
        ));

        return createSuccessResponse(Map.of(
                "user", userData,
                "timestamp", LocalDateTime.now().toString(),
                "requestId", getCurrentBusinessId().orElse("unknown")
        ));
    }

    private APIGatewayProxyResponseEvent handleHealthCheck(APIGatewayProxyRequestEvent event) {
        logDebug("Health check requested", Map.of());
        
        Map<String, Object> healthData = Map.of(
                "status", "healthy",
                "timestamp", LocalDateTime.now().toString(),
                "version", "1.0.0",
                "uptime", "24h 15m",
                "dependencies", Map.of(
                        "database", "healthy",
                        "cache", "healthy",
                        "external-api", "healthy"
                )
        );

        return createSuccessResponse(healthData);
    }

    private APIGatewayProxyResponseEvent handleGetMetrics(APIGatewayProxyRequestEvent event) {
        logDebug("Metrics requested", Map.of());
        
        // Simular métricas de negocio
        Map<String, Object> metricsData = Map.of(
                "requests_total", 1250,
                "requests_success", 1180,
                "requests_error", 70,
                "avg_response_time_ms", 145,
                "active_users", 32,
                "timestamp", LocalDateTime.now().toString()
        );

        return createSuccessResponse(Map.of(
                "metrics", metricsData,
                "collection_time", LocalDateTime.now().toString()
        ));
    }

    private APIGatewayProxyResponseEvent handleGetDefault(APIGatewayProxyRequestEvent event) {
        logDebug("Default GET handler", Map.of("path", event.getPath()));
        
        Map<String, Object> response = Map.of(
                "message", "Welcome to OTEL Observability Lambda API",
                "path", event.getPath(),
                "method", "GET",
                "timestamp", LocalDateTime.now().toString(),
                "features", Map.of(
                        "tracing", "enabled",
                        "metrics", "enabled",
                        "logging", "enabled",
                        "business_context", "enabled"
                )
        );

        return createSuccessResponse(response);
    }

    private APIGatewayProxyResponseEvent handlePostRequest(APIGatewayProxyRequestEvent event, Context context) {
        logDebug("Handling POST request", Map.of("path", event.getPath()));
        
        try {
            // Parsear el body del request
            String body = event.getBody();
            if (body == null || body.trim().isEmpty()) {
                return createErrorResponse(400, "Request body is required for POST requests");
            }

            // Configurar contexto de negocio
            getObservabilityManager().updateOperation("create-resource");

            @SuppressWarnings("unchecked")
            Map<String, Object> requestData = objectMapper.readValue(body, Map.class);

            // Simular creación de recurso
            String resourceId = UUID.randomUUID().toString();
            
            Map<String, Object> createdResource = new HashMap<>(requestData);
            createdResource.put("id", resourceId);
            createdResource.put("createdAt", LocalDateTime.now().toString());
            createdResource.put("status", "created");

            logInfo("Resource created successfully", Map.of(
                    "resource.id", resourceId,
                    "operation", "create-resource"
            ));

            APIGatewayProxyResponseEvent response = createSuccessResponse(Map.of(
                    "message", "Resource created successfully",
                    "resource", createdResource
            ));
            response.setStatusCode(201); // Created
            return response;

        } catch (Exception e) {
            logError("Error processing POST request", Map.of(
                    "error.type", e.getClass().getSimpleName(),
                    "error.message", e.getMessage() != null ? e.getMessage() : "Unknown error"
            ));
            return createErrorResponse(400, "Invalid request body: " + e.getMessage());
        }
    }

    private APIGatewayProxyResponseEvent handlePutRequest(APIGatewayProxyRequestEvent event, Context context) {
        logDebug("Handling PUT request", Map.of("path", event.getPath()));
        
        String userId = extractUserIdFromPath(event.getPath());
        if (userId == null) {
            return createErrorResponse(400, "Invalid user ID in path");
        }

        try {
            String body = event.getBody();
            if (body == null || body.trim().isEmpty()) {
                return createErrorResponse(400, "Request body is required for PUT requests");
            }

            getObservabilityManager().updateOperation("update-user");

            @SuppressWarnings("unchecked")
            Map<String, Object> updateData = objectMapper.readValue(body, Map.class);

            // Simular actualización
            Map<String, Object> updatedUser = new HashMap<>(updateData);
            updatedUser.put("id", userId);
            updatedUser.put("updatedAt", LocalDateTime.now().toString());

            logInfo("User updated successfully", Map.of(
                    "user.id", userId,
                    "operation", "update-user"
            ));

            return createSuccessResponse(Map.of(
                    "message", "User updated successfully",
                    "user", updatedUser
            ));

        } catch (Exception e) {
            logError("Error processing PUT request", Map.of(
                    "error.type", e.getClass().getSimpleName(),
                    "user.id", userId
            ));
            return createErrorResponse(400, "Invalid request body: " + e.getMessage());
        }
    }

    private APIGatewayProxyResponseEvent handleDeleteRequest(APIGatewayProxyRequestEvent event, Context context) {
        logDebug("Handling DELETE request", Map.of("path", event.getPath()));
        
        String userId = extractUserIdFromPath(event.getPath());
        if (userId == null) {
            return createErrorResponse(400, "Invalid user ID in path");
        }

        getObservabilityManager().updateOperation("delete-user");

        // Simular eliminación (aquí iría la lógica real)
        logInfo("User deleted successfully", Map.of(
                "user.id", userId,
                "operation", "delete-user"
        ));

        return createSuccessResponse(Map.of(
                "message", "User deleted successfully",
                "userId", userId,
                "deletedAt", LocalDateTime.now().toString()
        ));
    }

    private APIGatewayProxyResponseEvent handleOptionsRequest(APIGatewayProxyRequestEvent event, Context context) {
        logDebug("Handling OPTIONS request (CORS preflight)", Map.of("path", event.getPath()));
        
        // El wrapper ya maneja los headers CORS automáticamente
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setStatusCode(200);
        response.setHeaders(createCorsHeaders());
        response.setBody("");
        
        return response;
    }

    /**
     * Extrae el ID de usuario del path /users/{id}
     */
    private String extractUserIdFromPath(String path) {
        if (path == null || !path.contains("/users/")) {
            return null;
        }
        
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("users".equals(parts[i]) && i + 1 < parts.length) {
                return parts[i + 1];
            }
        }
        return null;
    }

    /**
     * Simulación de error para testing de observabilidad
     */
    @SuppressWarnings("unused")
    private APIGatewayProxyResponseEvent simulateError() throws Exception {
        // Esta función es útil para testing de manejo de errores
        throw new RuntimeException("Simulated error for observability testing");
    }

    /**
     * Ejemplo de operación con contexto de negocio complejo
     */
    @SuppressWarnings("unused")
    private void demonstrateBusinessContextUsage(String businessId, String operationType) {
        // Configurar contexto de negocio complejo
        getObservabilityManager().setupQuickBusinessContext(
                businessId, 
                getCurrentBusinessId().orElse("system"), 
                operationType
        );

        // El contexto estará disponible en todos los logs y trazas posteriores
        logInfo("Complex business operation started", Map.of(
                "business.operation", operationType,
                "business.id", businessId
        ));
    }
}