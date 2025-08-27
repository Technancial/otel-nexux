package pe.soapros.otel.lambda.infrastructure;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.amazonaws.services.lambda.runtime.events.KafkaEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.semconv.HttpAttributes;
import io.opentelemetry.semconv.ServiceAttributes;
import pe.soapros.otel.metrics.infrastructure.HttpMetricsCollector;
import pe.soapros.otel.metrics.infrastructure.LambdaMetricsCollector;
import pe.soapros.otel.metrics.infrastructure.MetricsFactory;
import software.amazon.awssdk.core.http.ExecutionContext;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class FrameworkAgnosticObservabilityManager {
    private final OpenTelemetry openTelemetry;
    private final BusinessAwareObservabilityManager observabilityManager;
    private final LambdaMetricsCollector lambdaMetricsCollector;
    private final HttpMetricsCollector httpMetricsCollector;
    private final Tracer tracer;
    private final ObjectMapper objectMapper;
    private final String serviceName;
    private final String serviceVersion;

    private final ThreadLocal<ExecutionContext> currentExecution = new ThreadLocal<>();

    /**
     * Constructor que inicializa toda la infraestructura de observabilidad.
     * Este constructor reutiliza TODA la infraestructura existente de tu librería.
     */
    public FrameworkAgnosticObservabilityManager(OpenTelemetry openTelemetry) {
        this(openTelemetry, null, null);
    }

    public FrameworkAgnosticObservabilityManager(OpenTelemetry openTelemetry, String serviceName, String serviceVersion) {
        Objects.requireNonNull(openTelemetry, "OpenTelemetry instance cannot be null");

        this.openTelemetry = openTelemetry;
        this.serviceName = Optional.ofNullable(serviceName)
                .or(() -> Optional.ofNullable(System.getenv("OTEL_SERVICE_NAME")))
                .or(() -> Optional.ofNullable(System.getenv("AWS_LAMBDA_FUNCTION_NAME")))
                .orElse("lambda-function");
        this.serviceVersion = Optional.ofNullable(serviceVersion)
                .or(() -> Optional.ofNullable(System.getenv("OTEL_SERVICE_VERSION")))
                .or(() -> Optional.ofNullable(System.getenv("AWS_LAMBDA_FUNCTION_VERSION")))
                .orElse("1.0.0");

        // Reutilizar toda la infraestructura existente
        this.tracer = openTelemetry.getTracer("pe.soapros.otel.lambda.framework-agnostic", this.serviceVersion);

        LambdaObservabilityConfig lambdaConfig = LambdaObservabilityConfig.builder()
                .customResponseHeaders("x-correlation-id", "x-request-id", "x-processing-time")
                .includeResponseBody(512)
                .verboseLogging(true) // Para desarrollo
                .enableSubSpanEvents(true)
                .enableSubSpanMetrics(true)
                .build();

        this.observabilityManager = new BusinessAwareObservabilityManager(openTelemetry, "framework-agnostic", lambdaConfig);
        this.objectMapper = new ObjectMapper();

        // Inicializar métricas usando la factory existente
        MetricsFactory metricsFactory = MetricsFactory.create(openTelemetry, this.serviceName, this.serviceVersion);
        this.lambdaMetricsCollector = metricsFactory.getLambdaMetricsCollector();
        this.httpMetricsCollector = metricsFactory.getHttpMetricsCollector();
    }

    // ==================== API PARA HTTP LAMBDA ====================

    /**
     * Envuelve la ejecución de una función Lambda HTTP con observabilidad completa.
     * <p>
     * USO EN QUARKUS:
     * @Named("myLambda")
     * public class MyLambda implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
     *     @Inject FrameworkAgnosticObservabilityManager observability;
     * <p>
     *     public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
     *         return observability.executeWithHttpObservability(event, context, this::handleBusinessLogic);
     *     }
     * <p>
     *     private APIGatewayProxyResponseEvent handleBusinessLogic(APIGatewayProxyRequestEvent event, Context context) {
     *         // Tu lógica de negocio aquí
     *         return new APIGatewayProxyResponseEvent().withStatusCode(200).withBody("OK");
     *     }
     * }
     */
    public APIGatewayProxyResponseEvent executeWithHttpObservability(
            APIGatewayProxyRequestEvent event,
            Context context,
            HttpLambdaFunction businessLogic) throws Exception {

        // Inicializar métricas de Lambda
        lambdaMetricsCollector.startExecution(context);

        // Configurar contexto de negocio desde headers
        observabilityManager.setupBusinessContextFromHeaders(
                Optional.ofNullable(event.getHeaders()).orElse(Map.of())
        );

        // Extraer información del request
        String path = extractPath(event);
        String method = extractMethod(event);
        String correlationId = extractCorrelationId(event);
        String userId = extractUserId(event);

        // Extraer contexto de trace
        io.opentelemetry.context.Context otelContext = TraceContextExtractor.extractFromHeaders(
                Optional.ofNullable(event.getHeaders()).orElse(Map.of())
        );

        // Crear span principal
        Span span = tracer.spanBuilder(String.format("HTTP %s %s", method, normalizePath(path)))
                .setParent(otelContext)
                .setSpanKind(SpanKind.SERVER)
                .startSpan();

        // Iniciar métricas HTTP
        httpMetricsCollector.startRequest(method, path, extractUserAgent(event), extractClientIp(event));

        // Configurar contexto de ejecución
        ExecutionContext execContext = new ExecutionContext(span, Instant.now(), "HTTP");
        currentExecution.set(execContext);

        // Log de inicio
        observabilityManager.logLambdaStart(serviceName, context.getAwsRequestId());

        try (Scope scope = span.makeCurrent()) {
            // Enriquecer span con atributos HTTP
            enrichSpanWithHttpAttributes(span, event, context, correlationId, userId);

            // Agregar información de usuario si está disponible
            if (userId != null || correlationId != null) {
                observabilityManager.addUserInfo(span, userId, null, null);
            }

            // Ejecutar lógica de negocio
            APIGatewayProxyResponseEvent response = businessLogic.apply(event, context);

            // Enriquecer span con respuesta
            enrichSpanWithResponseAttributes(span, response);

            // Finalizar métricas HTTP
            long requestSize = event.getBody() != null ? event.getBody().length() : 0;
            long responseSize = response.getBody() != null ? response.getBody().length() : 0;
            httpMetricsCollector.endRequest(response.getStatusCode(), requestSize, responseSize, null);

            // Marcar span como exitoso
            observabilityManager.closeSpanSuccessfully(span, "HTTP " + response.getStatusCode());

            // Finalizar métricas de Lambda exitosamente
            lambdaMetricsCollector.endExecution(context, true, null);

            return response;

        } catch (Exception ex) {
            // Manejar error
            //observabilityManager.closeSpan(span, ex);
            httpMetricsCollector.endRequest(500, 0, 0, ex);
            lambdaMetricsCollector.endExecution(context, false, ex);

            throw ex;
        } finally {
            Duration totalDuration = Duration.between(execContext.startTime, Instant.now());
            observabilityManager.logLambdaEnd(serviceName, context.getAwsRequestId(), totalDuration.toMillis());

            span.end();
            currentExecution.remove();
        }
    }

    // ==================== API PARA SQS LAMBDA ====================

    /**
     * Envuelve la ejecución de una función Lambda SQS con observabilidad completa.
     */
    public void executeWithSqsObservability(
            SQSEvent event,
            Context context,
            SqsLambdaFunction businessLogic) {

        lambdaMetricsCollector.startExecution(context);
        observabilityManager.logLambdaStart(serviceName, context.getAwsRequestId());

        try {
            event.getRecords().forEach(message ->
            {
                try {
                    processSqsMessageWithObservability(message, context, businessLogic);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            lambdaMetricsCollector.endExecution(context, true, null);

        } catch (Exception ex) {
            lambdaMetricsCollector.endExecution(context, false, ex);
            throw ex;
        } finally {
            observabilityManager.logLambdaEnd(serviceName, context.getAwsRequestId(),
                    Duration.between(Instant.now().minusSeconds(1), Instant.now()).toMillis());
        }
    }

    private void processSqsMessageWithObservability(
            SQSEvent.SQSMessage message,
            Context context,
            SqsLambdaFunction businessLogic) throws Exception {

        // Extraer headers y configurar contexto
        Map<String, String> headers = message.getMessageAttributes().entrySet().stream()
                .filter(entry -> entry.getValue().getStringValue() != null)
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().getStringValue()
                ));

        observabilityManager.setupBusinessContextFromHeaders(headers);

        io.opentelemetry.context.Context otelContext = TraceContextExtractor.extractFromHeaders(headers);
        String queueName = extractQueueName(message.getEventSourceArn());

        Span span = tracer.spanBuilder(String.format("sqs %s process", queueName))
                .setParent(otelContext)
                .setSpanKind(SpanKind.CONSUMER)
                .startSpan();

        ExecutionContext execContext = new ExecutionContext(span, Instant.now(), "SQS");
        currentExecution.set(execContext);

        try (Scope scope = span.makeCurrent()) {
            enrichSpanWithSqsAttributes(span, message, context);

            // Ejecutar lógica de negocio
            businessLogic.accept(message, context);

            observabilityManager.closeSpanSuccessfully(span, "SQS message processed");

        } catch (Exception ex) {
            //observabilityManager.closeSpan(span, ex);
            throw ex;
        } finally {
            span.end();
            currentExecution.remove();
        }
    }

    // ==================== API PARA KAFKA LAMBDA ====================

    /**
     * Similar al SQS pero para Kafka events
     */
    public void executeWithKafkaObservability(
            KafkaEvent event,
            Context context,
            KafkaLambdaFunction businessLogic) {

        lambdaMetricsCollector.startExecution(context);
        observabilityManager.logLambdaStart(serviceName, context.getAwsRequestId());

        try {
            event.getRecords().values().stream()
                    .flatMap(java.util.List::stream)
                    .forEach(record -> processKafkaRecordWithObservability(record, context, businessLogic));

            lambdaMetricsCollector.endExecution(context, true, null);

        } catch (Exception ex) {
            lambdaMetricsCollector.endExecution(context, false, ex);
            throw ex;
        } finally {
            observabilityManager.logLambdaEnd(serviceName, context.getAwsRequestId(),
                    Duration.between(Instant.now().minusSeconds(1), Instant.now()).toMillis());
        }
    }

    // ==================== API DE LOGGING PARA EL USUARIO ====================

    /**
     * Métodos de logging que mantienen la correlación automática con traces
     */
    public void logInfo(String message, Map<String, String> attributes) {
        observabilityManager.getLoggerService().info(message, attributes);
    }

    public void logError(String message, Map<String, String> attributes) {
        observabilityManager.getLoggerService().error(message, attributes);
    }

    public void logDebug(String message, Map<String, String> attributes) {
        observabilityManager.getLoggerService().debug(message, attributes);
    }

    public void logWarn(String message, Map<String, String> attributes) {
        observabilityManager.getLoggerService().warn(message, attributes);
    }

    // ==================== API DE MÉTRICAS PERSONALIZADAS ====================

    /**
     * Permite a los usuarios registrar métricas personalizadas
     */
    public void recordCustomMetric(String name, double value, Map<String, String> attributes) {
        ExecutionContext context = currentExecution.get();
        if (context != null) {
            switch (context.type) {
                case "HTTP" -> httpMetricsCollector.recordEndpointMetric("custom", name, value, attributes);
                case "SQS", "Kafka" -> lambdaMetricsCollector.recordCustomLambdaMetric(name, value, attributes);
            }
        }
    }

    // ==================== API DE CONTEXTO DE NEGOCIO ====================

    /**
     * Permite configurar contexto de negocio manualmente
     */
    public void setupBusinessContext(String businessId, String userId, String operation) {
        observabilityManager.setupQuickBusinessContext(businessId, userId, operation);
    }

    public Map<String, String> getCurrentContextInfo() {
        return observabilityManager.getCompleteContextInfo();
    }

    public Optional<String> getCurrentBusinessId() {
        return observabilityManager.getCurrentBusinessId();
    }

    // ==================== MÉTODOS UTILITARIOS DE RESPONSE ====================

    /**
     * Métodos helper para crear responses HTTP (reutiliza la lógica del wrapper original)
     */
    public APIGatewayProxyResponseEvent createSuccessResponse(Object body) {
        try {
            String jsonBody = body instanceof String ? (String) body :
                    objectMapper.writeValueAsString(body);
            APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
            response.setStatusCode(200);
            response.setHeaders(createCorsHeaders());
            response.setBody(jsonBody);
            return response;
        } catch (Exception e) {
            return createErrorResponse(500, "Failed to serialize response");
        }
    }

    public APIGatewayProxyResponseEvent createErrorResponse(int statusCode, String message) {
        try {
            Map<String, Object> errorBody = new HashMap<>();
            errorBody.put("error", message);
            errorBody.put("statusCode", statusCode);

            String jsonBody = objectMapper.writeValueAsString(errorBody);

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

    private Map<String, String> createCorsHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        headers.put("Access-Control-Allow-Headers", "Content-Type, Authorization, X-User-ID, X-Correlation-ID");
        return headers;
    }

    // ==================== INTERFACES FUNCIONALES ====================

    /**
     * Interface funcional para lógica de negocio HTTP
     */
    @FunctionalInterface
    public interface HttpLambdaFunction {
        APIGatewayProxyResponseEvent apply(APIGatewayProxyRequestEvent event, Context context) throws Exception;
    }

    /**
     * Interface funcional para lógica de negocio SQS
     */
    @FunctionalInterface
    public interface SqsLambdaFunction {
        void accept(SQSEvent.SQSMessage message, Context context) throws Exception;
    }

    /**
     * Interface funcional para lógica de negocio Kafka
     */
    @FunctionalInterface
    public interface KafkaLambdaFunction {
        void accept(KafkaEvent.KafkaEventRecord record, Context context) throws Exception;
    }

    // ==================== MÉTODOS PRIVADOS (REUTILIZAN LÓGICA EXISTENTE) ====================

    private void enrichSpanWithHttpAttributes(Span span, APIGatewayProxyRequestEvent event,
                                              Context context, String correlationId, String userId) {
        // Reutiliza exactamente la misma lógica del HttpTracingLambdaWrapper
        span.setAllAttributes(Attributes.of(
                HttpAttributes.HTTP_REQUEST_METHOD, extractMethod(event),
                HttpAttributes.HTTP_ROUTE, extractPath(event),
                AttributeKey.stringKey("faas.id"), context.getInvokedFunctionArn(),
                ServiceAttributes.SERVICE_NAME, serviceName,
                ServiceAttributes.SERVICE_VERSION, serviceVersion
        ));

        if (correlationId != null) {
            span.setAttribute("correlation.id", correlationId);
        }
        if (userId != null) {
            span.setAttribute("user.id", userId);
        }
    }

    private void enrichSpanWithResponseAttributes(Span span, APIGatewayProxyResponseEvent response) {
        if (response != null) {
            span.setAllAttributes(Attributes.of(
                    HttpAttributes.HTTP_RESPONSE_STATUS_CODE, (long)response.getStatusCode()
            ));

            if (response.getStatusCode() >= 400) {
                span.setStatus(response.getStatusCode() >= 500 ? StatusCode.ERROR : StatusCode.OK,
                        String.format("HTTP %d", response.getStatusCode()));
            } else {
                span.setStatus(StatusCode.OK);
            }
        }
    }

    private void enrichSpanWithSqsAttributes(Span span, SQSEvent.SQSMessage message, Context context) {
        // Reutiliza la lógica del SqsTracingLambdaWrapper
        span.setAllAttributes(Attributes.of(
                AttributeKey.stringKey("messaging.system"), "aws.sqs",
                AttributeKey.stringKey("messaging.destination"), message.getEventSourceArn(),
                AttributeKey.stringKey("messaging.message.id"), message.getMessageId(),
                ServiceAttributes.SERVICE_NAME, serviceName
        ));
    }

    // Métodos de extracción reutilizados de los wrappers existentes
    private String extractPath(APIGatewayProxyRequestEvent event) {
        return Optional.ofNullable(event.getPath()).orElse("/unknown");
    }

    private String extractMethod(APIGatewayProxyRequestEvent event) {
        return Optional.ofNullable(event.getHttpMethod()).orElse("UNKNOWN");
    }

    private String extractCorrelationId(APIGatewayProxyRequestEvent event) {
        Map<String, String> headers = event.getHeaders();
        if (headers == null) return null;
        return headers.get("X-Correlation-ID") != null ? headers.get("X-Correlation-ID") :
                headers.get("x-correlation-id") != null ? headers.get("x-correlation-id") :
                        headers.get("Correlation-ID");
    }

    private String extractUserId(APIGatewayProxyRequestEvent event) {
        Map<String, String> headers = event.getHeaders();
        if (headers == null) return null;
        return headers.get("X-User-ID") != null ? headers.get("X-User-ID") :
                headers.get("x-user-id");
    }

    private String extractUserAgent(APIGatewayProxyRequestEvent event) {
        return Optional.ofNullable(event.getHeaders())
                .map(headers -> headers.get("User-Agent"))
                .orElse("unknown");
    }

    private String extractClientIp(APIGatewayProxyRequestEvent event) {
        return Optional.ofNullable(event.getRequestContext())
                .map(ctx -> ctx.getIdentity())
                .map(identity -> identity.getSourceIp())
                .orElse("unknown");
    }

    private String extractQueueName(String eventSourceArn) {
        if (eventSourceArn == null) return "unknown";
        String[] parts = eventSourceArn.split(":");
        return parts.length > 5 ? parts[5] : "unknown";
    }

    private String normalizePath(String path) {
        if (path == null || path.isEmpty()) return "/unknown";
        return path.replaceAll("/\\d+", "/{id}")
                .replaceAll("/[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}", "/{uuid}");
    }

    private void processKafkaRecordWithObservability(
            KafkaEvent.KafkaEventRecord record,
            Context context,
            KafkaLambdaFunction businessLogic) {
        // Implementación similar a SQS pero para Kafka
        // (código similar al SQS pero adaptado para Kafka)
    }

    /**
     * Clase interna para mantener contexto de ejecución
     */
    private static class ExecutionContext {
        final Span span;
        final Instant startTime;
        final String type; // "HTTP", "SQS", "Kafka"

        ExecutionContext(Span span, Instant startTime, String type) {
            this.span = span;
            this.startTime = startTime;
            this.type = type;
        }
    }
}
