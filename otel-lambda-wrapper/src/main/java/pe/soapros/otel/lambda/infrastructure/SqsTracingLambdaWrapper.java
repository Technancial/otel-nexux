package pe.soapros.otel.lambda.infrastructure;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.semconv.ServiceAttributes;
import pe.soapros.otel.metrics.infrastructure.MetricsFactory;
import pe.soapros.otel.metrics.infrastructure.LambdaMetricsCollector;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

public abstract class SqsTracingLambdaWrapper implements RequestHandler<SQSEvent, Void> {
    
    protected final OpenTelemetry openTelemetry;
    private final BusinessAwareObservabilityManager observabilityManager;
    private final LambdaMetricsCollector lambdaMetricsCollector;

    private final Tracer tracer;
    private final String serviceName;
    private final String serviceVersion;

    public SqsTracingLambdaWrapper(OpenTelemetry openTelemetry) {
        Objects.requireNonNull(openTelemetry, "OpenTelemetry instance cannot be null");
        this.openTelemetry = openTelemetry;

        // Configuración desde variables de entorno
        this.serviceName = Optional.ofNullable(System.getenv("OTEL_SERVICE_NAME"))
                .orElse(System.getenv("AWS_LAMBDA_FUNCTION_NAME"));
        this.serviceVersion = Optional.ofNullable(System.getenv("OTEL_SERVICE_VERSION"))
                .orElse(System.getenv("AWS_LAMBDA_FUNCTION_VERSION"));

        // Inicializar tracer y observability manager
        this.tracer = openTelemetry.getTracer(
                "pe.soapros.otel.lambda.sqs",
                Optional.ofNullable(serviceVersion).orElse("1.0.0")
        );

        LambdaObservabilityConfig lambdaObservabilityConfig = LambdaObservabilityConfig.builder()
                .customResponseHeaders("x-correlation-id", "x-request-id", "x-processing-time")
                .includeResponseBody(512)
                .verboseLogging(true) // Para desarrollo
                .enableSubSpanEvents(true)
                .enableSubSpanMetrics(true)
                .build();

        this.observabilityManager = new BusinessAwareObservabilityManager(openTelemetry, "sqs-lambda-wrapper", lambdaObservabilityConfig);
        
        // Inicializar metrics factory y lambda metrics collector
        MetricsFactory metricsFactory = MetricsFactory.create(
                openTelemetry, 
                serviceName != null ? serviceName : "sqs-lambda-wrapper", 
                serviceVersion != null ? serviceVersion : "1.0.0"
        );
        this.lambdaMetricsCollector = metricsFactory.getLambdaMetricsCollector();
    }

    @Override
    public Void handleRequest(SQSEvent event, Context lambdaContext) {
        // Iniciar métricas de Lambda con el colector especializado
        lambdaMetricsCollector.startExecution(lambdaContext);

        // Log lambda start
        observabilityManager.logLambdaStart(serviceName, lambdaContext.getAwsRequestId());
        
        try {
            event.getRecords().forEach(message -> processSqsMessage(message, lambdaContext));
            
            // Finalizar métricas de Lambda exitosamente
            lambdaMetricsCollector.endExecution(lambdaContext, true, null);
            
            return null;
            
        } catch (Exception ex) {
            // Finalizar métricas de Lambda con error
            lambdaMetricsCollector.endExecution(lambdaContext, false, ex);
            throw ex;
        } finally {
            Duration totalDuration = Duration.between(Instant.now().minusMillis(System.currentTimeMillis() - lambdaContext.getRemainingTimeInMillis()), Instant.now());
            observabilityManager.logLambdaEnd(serviceName, lambdaContext.getAwsRequestId(), totalDuration.toMillis());
            
            if (lambdaContext.getRemainingTimeInMillis() < 5000) {
                forceFlushTelemetry();
            }
        }
    }

    private void processSqsMessage(SQSEvent.SQSMessage message, Context lambdaContext) {
        Map<String, String> headers = message.getMessageAttributes().entrySet().stream()
                .filter(entry -> entry.getValue().getStringValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getStringValue()));

        // Setup business context from message attributes
        observabilityManager.setupBusinessContextFromHeaders(headers);

        io.opentelemetry.context.Context otelContext = TraceContextExtractor.extractFromHeaders(headers);
        
        String queueName = extractQueueName(message.getEventSourceArn());
        final String correlationId = extractCorrelationId(headers);
        final String userId = extractUserId(headers);
        
        Span span = tracer.spanBuilder(String.format("sqs %s process", queueName))
                .setParent(otelContext)
                .setSpanKind(SpanKind.CONSUMER)
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            enrichSpanWithSqsAttributes(span, message, lambdaContext, correlationId, userId);
            
            // Add user info to observability manager
            if (userId != null || correlationId != null) {
                observabilityManager.addUserInfo(span, userId, null, null);
            }

            handle(message, lambdaContext);

            // Log performance info
            Duration duration = Duration.between(Instant.now().minusMillis(System.currentTimeMillis() - lambdaContext.getRemainingTimeInMillis()), Instant.now());
            observabilityManager.addPerformanceInfo(span, duration.toMillis(), "SQS " + queueName + " process");
            
            // Close span successfully
            observabilityManager.closeSpanSuccessfully(span, "SQS message processed");

        } catch (Exception ex) {
            // Use observability manager for error handling
            //observabilityManager.closeSpan(span, ex);
            throw ex;
        } finally {
            span.end();
        }
    }

    private String extractQueueName(String eventSourceArn) {
        if (eventSourceArn == null) {
            return "unknown";
        }
        String[] parts = eventSourceArn.split(":");
        return parts.length > 5 ? parts[5] : "unknown";
    }

    private void enrichSpanWithSqsAttributes(Span span, SQSEvent.SQSMessage message, Context lambdaContext, String correlationId, String userId) {
        // Atributos estándar de messaging
        span.setAllAttributes(Attributes.of(
                AttributeKey.stringKey("messaging.system"), "aws.sqs",
                AttributeKey.stringKey("messaging.destination"), message.getEventSourceArn(),
                AttributeKey.stringKey("messaging.operation"), "process",
                AttributeKey.stringKey("messaging.message.id"), message.getMessageId()
        ));

        // Atributos específicos de SQS
        span.setAllAttributes(Attributes.of(
                AttributeKey.stringKey("messaging.aws.sqs.source_arn"), message.getEventSourceArn(),
                AttributeKey.stringKey("messaging.aws.sqs.receipt_handle"), message.getReceiptHandle(),
                AttributeKey.stringKey("messaging.aws.sqs.md5_of_body"), message.getMd5OfBody()
        ));
        
        if (message.getAttributes() != null) {
            String approximateReceiveCount = message.getAttributes().get("ApproximateReceiveCount");
            if (approximateReceiveCount != null) {
                try {
                    span.setAttribute("messaging.aws.sqs.approximate_receive_count", Long.parseLong(approximateReceiveCount));
                } catch (NumberFormatException ignored) {
                    // Ignore if not a valid number
                }
            }

            String approximateFirstReceiveTimestamp = message.getAttributes().get("ApproximateFirstReceiveTimestamp");
            if (approximateFirstReceiveTimestamp != null) {
                span.setAttribute("messaging.aws.sqs.approximate_first_receive_timestamp", approximateFirstReceiveTimestamp);
            }

            String sentTimestamp = message.getAttributes().get("SentTimestamp");
            if (sentTimestamp != null) {
                span.setAttribute("messaging.aws.sqs.sent_timestamp", sentTimestamp);
            }
        }

        // Atributos de FaaS y Lambda
        span.setAllAttributes(Attributes.of(
                AttributeKey.stringKey("faas.trigger"), "pubsub",
                AttributeKey.stringKey("faas.id"), lambdaContext.getInvokedFunctionArn(),
                AttributeKey.stringKey("faas.execution"), lambdaContext.getAwsRequestId(),
                AttributeKey.doubleKey("faas.timeout"), (double) lambdaContext.getRemainingTimeInMillis(),
                AttributeKey.doubleKey("faas.memory_limit"), (double) lambdaContext.getMemoryLimitInMB()
        ));

        // Atributos de AWS
        span.setAllAttributes(Attributes.of(
                AttributeKey.stringKey("aws.lambda.log_group"), lambdaContext.getLogGroupName(),
                AttributeKey.stringKey("aws.lambda.log_stream"), lambdaContext.getLogStreamName()
        ));

        // Atributos de negocio
        if (correlationId != null) {
            span.setAttribute("correlation.id", correlationId);
        }
        if (userId != null) {
            span.setAttribute("user.id", userId);
        }

        // Atributos de servicio
        span.setAllAttributes(Attributes.of(
                ServiceAttributes.SERVICE_NAME, serviceName,
                ServiceAttributes.SERVICE_VERSION, serviceVersion,
                AttributeKey.stringKey("deployment.environment"),
                System.getenv().getOrDefault("DEPLOYMENT_ENVIRONMENT", "development")
        ));

        // Información adicional del mensaje
        if (message.getBody() != null) {
            span.setAttribute("messaging.message.payload.size_bytes", message.getBody().length());
        }

        // Atributos de mensaje
        if (message.getMessageAttributes() != null && !message.getMessageAttributes().isEmpty()) {
            span.setAttribute("messaging.aws.sqs.message_attributes.count", message.getMessageAttributes().size());
        }
    }

    private String extractCorrelationId(Map<String, String> headers) {
        if (headers == null) return null;

        // Buscar diferentes nombres de headers de correlación
        return headers.get("X-Correlation-ID") != null ? headers.get("X-Correlation-ID") :
                headers.get("x-correlation-id") != null ? headers.get("x-correlation-id") :
                        headers.get("Correlation-ID") != null ? headers.get("Correlation-ID") :
                                headers.get("X-Request-ID") != null ? headers.get("X-Request-ID") : null;
    }

    private String extractUserId(Map<String, String> headers) {
        if (headers == null) return null;

        // Buscar diferentes nombres de headers de usuario
        return headers.get("X-User-ID") != null ? headers.get("X-User-ID") :
                headers.get("x-user-id") != null ? headers.get("x-user-id") :
                        headers.get("User-ID") != null ? headers.get("User-ID") : null;
    }

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

    protected Map<String, String> getCurrentContextInfo() {
        return observabilityManager.getCompleteContextInfo();
    }

    protected Optional<String> getCurrentBusinessId() {
        return observabilityManager.getCurrentBusinessId();
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

    public abstract void handle(SQSEvent.SQSMessage message, Context context);
}