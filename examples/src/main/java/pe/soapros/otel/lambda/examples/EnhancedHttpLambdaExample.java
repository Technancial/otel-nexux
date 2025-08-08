package pe.soapros.otel.lambda.examples;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import io.opentelemetry.api.OpenTelemetry;
import pe.soapros.otel.lambda.infrastructure.HttpTracingLambdaWrapper;
import pe.soapros.otel.traces.infrastructure.config.OpenTelemetryConfiguration;

import java.util.HashMap;
import java.util.Map;

public class EnhancedHttpLambdaExample extends HttpTracingLambdaWrapper {

    public EnhancedHttpLambdaExample() {
        super(OpenTelemetryConfiguration.init("EnhancedHttpLambda"));
    }

    public EnhancedHttpLambdaExample(OpenTelemetry openTelemetry) {
        super(openTelemetry);
    }

    @Override
    public APIGatewayProxyResponseEvent handle(APIGatewayProxyRequestEvent event, Context context) {
        
        // Log the start of business logic with structured logging
        Map<String, String> logAttributes = new HashMap<>();
        logAttributes.put("http.method", event.getHttpMethod());
        logAttributes.put("http.path", event.getPath());
        logAttributes.put("business.operation", "process_request");
        
        logInfo("Starting business logic processing", logAttributes);
        
        try {
            String path = event.getPath();
            String method = event.getHttpMethod();
            
            // Add business context to current span
            getObservabilityManager().addBusinessContext(
                getCurrentSpan(), 
                "user_management", 
                extractEntityId(event), 
                "user"
            );
            
            // Simulate different operations based on path
            if (path.startsWith("/users")) {
                return handleUsersRequest(event, context);
            } else if (path.startsWith("/health")) {
                return handleHealthCheck(event, context);
            } else if (path.startsWith("/orders")) {
                return handleOrdersRequest(event, context);
            } else {
                logWarn("Unknown endpoint accessed", Map.of(
                    "http.path", path,
                    "http.method", method,
                    "business.operation", "unknown_endpoint"
                ));
                return createErrorResponse(404, "Endpoint not found");
            }
            
        } catch (Exception e) {
            // Log error with context
            Map<String, String> errorAttributes = new HashMap<>();
            errorAttributes.put("error.type", e.getClass().getSimpleName());
            errorAttributes.put("error.message", e.getMessage());
            errorAttributes.put("business.operation", "process_request");
            
            logError("Error processing request: " + e.getMessage(), errorAttributes);
            
            return createErrorResponse(500, "Internal server error");
        }
    }
    
    private APIGatewayProxyResponseEvent handleUsersRequest(APIGatewayProxyRequestEvent event, Context context) {
        String method = event.getHttpMethod();
        
        Map<String, String> logAttributes = new HashMap<>();
        logAttributes.put("business.operation", "handle_users");
        logAttributes.put("http.method", method);
        
        logInfo("Handling users request", logAttributes);
        
        if ("GET".equals(method)) {
            return handleGetUsers(event);
        } else if ("POST".equals(method)) {
            return handleCreateUser(event);
        } else if ("PUT".equals(method)) {
            return handleUpdateUser(event);
        } else if ("DELETE".equals(method)) {
            return handleDeleteUser(event);
        }
        
        return createErrorResponse(405, "Method not allowed");
    }
    
    private APIGatewayProxyResponseEvent handleGetUsers(APIGatewayProxyRequestEvent event) {
        logDebug("Retrieving users list", Map.of(
            "business.operation", "get_users",
            "query.parameters", String.valueOf(event.getQueryStringParameters())
        ));
        
        // Simulate database operation
        simulateDataAccess("SELECT * FROM users", 150);
        
        Map<String, Object> response = new HashMap<>();
        response.put("users", new String[]{"user1", "user2", "user3"});
        response.put("total", 3);
        
        logInfo("Users retrieved successfully", Map.of(
            "business.operation", "get_users", 
            "result.count", "3"
        ));
        
        return createSuccessResponse(response);
    }
    
    private APIGatewayProxyResponseEvent handleCreateUser(APIGatewayProxyRequestEvent event) {
        logInfo("Creating new user", Map.of(
            "business.operation", "create_user",
            "request.body.length", String.valueOf(event.getBody() != null ? event.getBody().length() : 0)
        ));
        
        // Simulate validation
        if (event.getBody() == null || event.getBody().isEmpty()) {
            logWarn("User creation attempted with empty body", Map.of(
                "business.operation", "create_user",
                "validation.error", "empty_body"
            ));
            return createErrorResponse(400, "Request body is required");
        }
        
        // Simulate database insert
        simulateDataAccess("INSERT INTO users", 200);
        
        Map<String, Object> response = new HashMap<>();
        response.put("id", "12345");
        response.put("message", "User created successfully");
        
        logInfo("User created successfully", Map.of(
            "business.operation", "create_user",
            "user.id", "12345"
        ));
        
        return createSuccessResponse(response);
    }
    
    private APIGatewayProxyResponseEvent handleUpdateUser(APIGatewayProxyRequestEvent event) {
        String userId = extractUserIdFromPath(event.getPath());
        
        Map<String, String> logAttributes = new HashMap<>();
        logAttributes.put("business.operation", "update_user");
        logAttributes.put("user.id", userId);
        
        logInfo("Updating user", logAttributes);
        
        if (userId == null) {
            logWarn("User update attempted without user ID", Map.of(
                "business.operation", "update_user",
                "validation.error", "missing_user_id"
            ));
            return createErrorResponse(400, "User ID is required");
        }
        
        // Simulate database update
        simulateDataAccess("UPDATE users SET ... WHERE id = " + userId, 180);
        
        logInfo("User updated successfully", Map.of(
            "business.operation", "update_user",
            "user.id", userId
        ));
        
        return createSuccessResponse(Map.of("message", "User updated successfully"));
    }
    
    private APIGatewayProxyResponseEvent handleDeleteUser(APIGatewayProxyRequestEvent event) {
        String userId = extractUserIdFromPath(event.getPath());
        
        Map<String, String> logAttributes = new HashMap<>();
        logAttributes.put("business.operation", "delete_user");
        logAttributes.put("user.id", userId);
        
        logInfo("Deleting user", logAttributes);
        
        if (userId == null) {
            logWarn("User deletion attempted without user ID", Map.of(
                "business.operation", "delete_user",
                "validation.error", "missing_user_id"
            ));
            return createErrorResponse(400, "User ID is required");
        }
        
        // Simulate database delete
        simulateDataAccess("DELETE FROM users WHERE id = " + userId, 120);
        
        logInfo("User deleted successfully", Map.of(
            "business.operation", "delete_user", 
            "user.id", userId
        ));
        
        return createSuccessResponse(Map.of("message", "User deleted successfully"));
    }
    
    private APIGatewayProxyResponseEvent handleOrdersRequest(APIGatewayProxyRequestEvent event, Context context) {
        logInfo("Handling orders request", Map.of(
            "business.operation", "handle_orders",
            "http.method", event.getHttpMethod()
        ));
        
        // Simulate slow operation to demonstrate performance logging
        simulateSlowOperation(3000);
        
        Map<String, Object> response = new HashMap<>();
        response.put("orders", new String[]{"order1", "order2"});
        response.put("total", 2);
        
        return createSuccessResponse(response);
    }
    
    private APIGatewayProxyResponseEvent handleHealthCheck(APIGatewayProxyRequestEvent event, Context context) {
        logDebug("Health check requested", Map.of(
            "business.operation", "health_check"
        ));
        
        Map<String, Object> response = new HashMap<>();
        response.put("status", "healthy");
        response.put("timestamp", System.currentTimeMillis());
        response.put("service", "enhanced-http-lambda-example");
        
        return createSuccessResponse(response);
    }
    
    // Helper methods
    private String extractUserIdFromPath(String path) {
        if (path == null) return null;
        String[] parts = path.split("/");
        return parts.length > 2 ? parts[2] : null;
    }
    
    private String extractEntityId(APIGatewayProxyRequestEvent event) {
        String path = event.getPath();
        if (path == null) return null;
        
        String[] parts = path.split("/");
        return parts.length > 2 ? parts[2] : null;
    }
    
    private void simulateDataAccess(String operation, long durationMs) {
        logDebug("Database operation started", Map.of(
            "db.operation", operation,
            "db.expected_duration_ms", String.valueOf(durationMs)
        ));
        
        try {
            Thread.sleep(durationMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logWarn("Database operation interrupted", Map.of(
                "db.operation", operation,
                "error.type", "InterruptedException"
            ));
        }
        
        logDebug("Database operation completed", Map.of(
            "db.operation", operation,
            "db.duration_ms", String.valueOf(durationMs)
        ));
    }
    
    private void simulateSlowOperation(long durationMs) {
        logWarn("Starting slow operation", Map.of(
            "operation.type", "slow_operation",
            "expected_duration_ms", String.valueOf(durationMs)
        ));
        
        try {
            Thread.sleep(durationMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    // Helper method to get current span (would need to be implemented in base class)
    private io.opentelemetry.api.trace.Span getCurrentSpan() {
        return io.opentelemetry.api.trace.Span.current();
    }
}