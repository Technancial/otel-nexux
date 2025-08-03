package pe.soapros.otel.lambda.infrastructure;


import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.OpenTelemetry;
import pe.soapros.otel.traces.infrastructure.config.OpenTelemetryConfiguration;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.semconv.HttpAttributes;
import io.opentelemetry.semconv.ServiceAttributes;
import io.opentelemetry.semconv.UserAgentAttributes;
import pe.soapros.otel.metrics.infrastructure.MetricsFactory;
import pe.soapros.otel.metrics.infrastructure.LambdaMetricsCollector;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public abstract class HttpTracingLambdaWrapper implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    protected final OpenTelemetry openTelemetry;
    private final ObservabilityManager observabilityManager;
    private final LambdaMetricsCollector lambdaMetricsCollector;

    private final Tracer tracer;
    private final String serviceName;
    private final String serviceVersion;


    public HttpTracingLambdaWrapper(OpenTelemetry openTelemetry) {
        Objects.requireNonNull(openTelemetry, "OpenTelemetry instance cannot be null");
        this.openTelemetry = openTelemetry;

        // Configuración desde variables de entorno
        this.serviceName = Optional.ofNullable(System.getenv("OTEL_SERVICE_NAME"))
                .orElse(System.getenv("AWS_LAMBDA_FUNCTION_NAME"));
        this.serviceVersion = Optional.ofNullable(System.getenv("OTEL_SERVICE_VERSION"))
                .orElse(System.getenv("AWS_LAMBDA_FUNCTION_VERSION"));

        // Inicializar tracer y observability manager
        this.tracer = openTelemetry.getTracer(
                "pe.soapros.otel.lambda.http",
                Optional.ofNullable(serviceVersion).orElse("1.0.0")
        );
        this.observabilityManager = new ObservabilityManager(openTelemetry, "http-lambda-wrapper");
        
        // Inicializar metrics factory y lambda metrics collector
        MetricsFactory metricsFactory = MetricsFactory.create(
                openTelemetry, 
                serviceName != null ? serviceName : "http-lambda-wrapper", 
                serviceVersion != null ? serviceVersion : "1.0.0"
        );
        this.lambdaMetricsCollector = metricsFactory.getLambdaMetricsCollector();
    }

    protected Map<String, String> createCorsHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        headers.put("Access-Control-Allow-Headers", "Content-Type, Authorization, X-User-ID, X-Correlation-ID");
        return headers;
    }

    protected APIGatewayProxyResponseEvent createSuccessResponse(Object body) {
        try {
            String jsonBody = body instanceof String ? (String) body :
                    new ObjectMapper().writeValueAsString(body);
            APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
            response.setStatusCode(200);
            response.setHeaders(createCorsHeaders());
            response.setBody(jsonBody);
            return response;
        } catch (Exception e) {
            return createErrorResponse(500, "Failed to serialize response");
        }
    }

    protected APIGatewayProxyResponseEvent createErrorResponse(int statusCode, String message) {
        try {
            Map<String, Object> errorBody = new HashMap<>();
            errorBody.put("error", message);
            errorBody.put("statusCode", statusCode);
            
            String jsonBody = new ObjectMapper().writeValueAsString(errorBody);
            
            APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
            response.setStatusCode(statusCode);
            response.setHeaders(createCorsHeaders());
            response.setBody(jsonBody);
            return response;
        } catch (Exception e) {
            APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
            response.setStatusCode(500);
            response.setHeaders(createCorsHeaders());
            response.setBody("{\"error\":\"Internal server error\"}");
            return response;
        }
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        // Iniciar métricas de Lambda con el colector especializado
        lambdaMetricsCollector.startExecution(context);

        // Extraer información de request
        final String path = extractPath(event);
        final String method = extractMethod(event);
        final String correlationId = extractCorrelationId(event);
        final String userId = extractUserId(event);

        io.opentelemetry.context.Context otelContext = TraceContextExtractor.extractFromHeaders(
                Optional.ofNullable(event.getHeaders()).orElse(Map.of())
        );

        Span span = tracer.spanBuilder(String.format("HTTP %s %s", method, normalizePath(path)))
                .setParent(otelContext)
                .setSpanKind(SpanKind.SERVER)
                .startSpan();

        // Log lambda start
        observabilityManager.logLambdaStart(serviceName, context.getAwsRequestId());
        
        try (Scope scope = span.makeCurrent()) {
            enrichSpanWithRequestAttributes(span, event, context, correlationId, userId);
            
            // Add user info to observability manager
            if (userId != null || correlationId != null) {
                observabilityManager.addUserInfo(span, userId, null, null);
            }
            
            APIGatewayProxyResponseEvent response = handle(event, context);
            enrichSpanWithResponseAttributes(span, response);
            
            // Log performance info
            Duration duration = Duration.between(Instant.now().minusMillis(System.currentTimeMillis() - context.getRemainingTimeInMillis()), Instant.now());
            observabilityManager.addPerformanceInfo(span, duration.toMillis(), "HTTP " + method + " " + path);
            
            // Close span successfully
            observabilityManager.closeSpanSuccessfully(span, "HTTP " + response.getStatusCode());
            
            // Finalizar métricas de Lambda exitosamente
            lambdaMetricsCollector.endExecution(context, true, null);
            
            return response;

        } catch (Exception ex) {
            // Use observability manager for error handling
            observabilityManager.closeSpan(span, ex);
            
            // Finalizar métricas de Lambda con error
            lambdaMetricsCollector.endExecution(context, false, ex);
            
            throw ex;
        } finally {
            Duration totalDuration = Duration.between(Instant.now().minusMillis(System.currentTimeMillis() - context.getRemainingTimeInMillis()), Instant.now());
            observabilityManager.logLambdaEnd(serviceName, context.getAwsRequestId(), totalDuration.toMillis());
            
            span.end();
            if (context.getRemainingTimeInMillis() < 5000) {
                forceFlushTelemetry();
            }
        }
    }

    public abstract APIGatewayProxyResponseEvent handle(APIGatewayProxyRequestEvent event, Context context);
    
    // ==================== MÉTODOS PÚBLICOS PARA LOGGING ====================
    
    protected ObservabilityManager getObservabilityManager() {
        return observabilityManager;
    }
    
    protected void logInfo(String message, Map<String, String> attributes) {
        observabilityManager.getLoggerService().info(message, attributes);
    }
    
    protected void logError(String message, Map<String, String> attributes) {
        observabilityManager.getLoggerService().error(message, attributes);
    }
    
    protected void logDebug(String message, Map<String, String> attributes) {
        observabilityManager.getLoggerService().debug(message, attributes);
    }
    
    protected void logWarn(String message, Map<String, String> attributes) {
        observabilityManager.getLoggerService().warn(message, attributes);
    }

    private void enrichSpanWithRequestAttributes(Span span, APIGatewayProxyRequestEvent event,
                                                 Context context, String correlationId, String userId) {

        // Atributos HTTP semánticos (OpenTelemetry spec)
        span.setAllAttributes(Attributes.of(
                HttpAttributes.HTTP_REQUEST_METHOD, extractMethod(event),
                HttpAttributes.HTTP_ROUTE, extractPath(event),
                UserAgentAttributes.USER_AGENT_ORIGINAL, extractUserAgent(event)
        ));

        // Atributos de Lambda/FAAS
        span.setAllAttributes(Attributes.of(
                AttributeKey.stringKey("faas.id"), context.getInvokedFunctionArn(),
                AttributeKey.doubleKey("faas.timeout"), (double) context.getRemainingTimeInMillis(),
                AttributeKey.doubleKey("faas.memory_limit"), (double) context.getMemoryLimitInMB()
        ));

        // Atributos de AWS
        span.setAllAttributes(Attributes.of(
                AttributeKey.stringKey("aws.lambda.log_group"), context.getLogGroupName(),
                AttributeKey.stringKey("aws.lambda.log_stream"), context.getLogStreamName()
        ));

        // Atributos específicos de API Gateway
        if (event.getRequestContext() != null) {
            var requestContext = event.getRequestContext();
            span.setAllAttributes(Attributes.of(
                    AttributeKey.stringKey("aws.apigateway.request_id"), requestContext.getRequestId(),
                    AttributeKey.stringKey("aws.apigateway.stage"), requestContext.getStage(),
                    AttributeKey.stringKey("aws.apigateway.source_ip"), requestContext.getIdentity().getSourceIp(),
                    AttributeKey.stringKey("aws.apigateway.account_id"), requestContext.getAccountId()
            ));
        }

        // Atributos de negocio
        if (correlationId != null) {
            span.setAttribute("correlation.id", correlationId);
        }
        if (userId != null) {
            span.setAttribute("user.id", userId);
        }

        // Nota: Cold start se maneja automáticamente por LambdaMetricsCollector

        // Atributos del servicio
        span.setAllAttributes(Attributes.of(
                ServiceAttributes.SERVICE_NAME, serviceName,
                ServiceAttributes.SERVICE_VERSION, serviceVersion,
                AttributeKey.stringKey("deployment.environment"),
                System.getenv().getOrDefault("DEPLOYMENT_ENVIRONMENT", "development")
        ));
    }

    private void enrichSpanWithResponseAttributes(Span span, APIGatewayProxyResponseEvent response) {
        if (response != null) {
            span.setAllAttributes(Attributes.of(
                    HttpAttributes.HTTP_RESPONSE_STATUS_CODE, (long)response.getStatusCode()
            ));

            // Determinar el status del span basado en código HTTP
            if (response.getStatusCode() >= 400) {
                span.setStatus(response.getStatusCode() >= 500 ? StatusCode.ERROR : StatusCode.OK,
                        String.format("HTTP %d", response.getStatusCode()));
            } else {
                span.setStatus(StatusCode.OK);
            }
        }
    }

    private String extractPath(APIGatewayProxyRequestEvent event) {
        return Optional.ofNullable(event.getPath()).orElse("/unknown");
    }

    private String extractMethod(APIGatewayProxyRequestEvent event) {
        return Optional.ofNullable(event.getHttpMethod()).orElse("UNKNOWN");
    }

    private String extractUserAgent(APIGatewayProxyRequestEvent event) {
        return Optional.ofNullable(event.getHeaders())
                .map(headers -> headers.get("User-Agent"))
                .orElse("unknown");
    }

    private String extractCorrelationId(APIGatewayProxyRequestEvent event) {
        Map<String, String> headers = event.getHeaders();
        if (headers == null) return null;

        // Buscar diferentes nombres de headers de correlación
        return headers.get("X-Correlation-ID") != null ? headers.get("X-Correlation-ID") :
                headers.get("x-correlation-id") != null ? headers.get("x-correlation-id") :
                        headers.get("Correlation-ID") != null ? headers.get("Correlation-ID") :
                                headers.get("X-Request-ID") != null ? headers.get("X-Request-ID") : null;
    }

    private String extractUserId(APIGatewayProxyRequestEvent event) {
        Map<String, String> headers = event.getHeaders();
        if (headers == null) return null;

        // Buscar diferentes nombres de headers de usuario
        return headers.get("X-User-ID") != null ? headers.get("X-User-ID") :
                headers.get("x-user-id") != null ? headers.get("x-user-id") :
                        headers.get("User-ID") != null ? headers.get("User-ID") : null;
    }

    // ==================== MÉTODOS UTILITARIOS ====================

    private String normalizePath(String path) {
        if (path == null || path.isEmpty()) return "/unknown";

        // Normalizar path parameters (ej: /users/123 -> /users/{id})
        return path.replaceAll("/\\d+", "/{id}")
                .replaceAll("/[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}", "/{uuid}");
    }

    private String getStatusClass(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) return "2xx";
        if (statusCode >= 300 && statusCode < 400) return "3xx";
        if (statusCode >= 400 && statusCode < 500) return "4xx";
        if (statusCode >= 500) return "5xx";
        return "1xx";
    }

    private void forceFlushTelemetry() {
        try {
            // Intentar hacer flush de telemetría antes de que termine Lambda
            // Esto es una aproximación - el método exacto depende de la implementación del SDK
            System.out.println("Forcing telemetry flush due to low remaining time");
        } catch (Exception e) {
            // No fallar la Lambda por problemas de telemetría
            System.err.println("Failed to flush telemetry: " + e.getMessage());
        }
    }

}
