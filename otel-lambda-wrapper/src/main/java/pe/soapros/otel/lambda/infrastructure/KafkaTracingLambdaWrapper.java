package pe.soapros.otel.lambda.infrastructure;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.KafkaEvent;
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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public abstract class KafkaTracingLambdaWrapper implements RequestHandler<KafkaEvent, Void> {
    
    protected final OpenTelemetry openTelemetry;
    private final BusinessAwareObservabilityManager observabilityManager;
    private final LambdaMetricsCollector lambdaMetricsCollector;

    private final Tracer tracer;
    private final String serviceName;
    private final String serviceVersion;

    protected KafkaTracingLambdaWrapper(OpenTelemetry openTelemetry) {
        Objects.requireNonNull(openTelemetry, "OpenTelemetry instance cannot be null");
        this.openTelemetry = openTelemetry;

        // Configuración desde variables de entorno
        this.serviceName = Optional.ofNullable(System.getenv("OTEL_SERVICE_NAME"))
                .orElse(System.getenv("AWS_LAMBDA_FUNCTION_NAME"));
        this.serviceVersion = Optional.ofNullable(System.getenv("OTEL_SERVICE_VERSION"))
                .orElse(System.getenv("AWS_LAMBDA_FUNCTION_VERSION"));

        // Inicializar tracer y observability manager
        this.tracer = openTelemetry.getTracer(
                "pe.soapros.otel.lambda.kafka",
                Optional.ofNullable(serviceVersion).orElse("1.0.0")
        );
        this.observabilityManager = new BusinessAwareObservabilityManager(openTelemetry, "kafka-lambda-wrapper");
        
        // Inicializar metrics factory y lambda metrics collector
        MetricsFactory metricsFactory = MetricsFactory.create(
                openTelemetry, 
                serviceName != null ? serviceName : "kafka-lambda-wrapper", 
                serviceVersion != null ? serviceVersion : "1.0.0"
        );
        this.lambdaMetricsCollector = metricsFactory.getLambdaMetricsCollector();
    }

    @Override
    public Void handleRequest(KafkaEvent event, Context lambdaContext) {
        // Iniciar métricas de Lambda con el colector especializado
        lambdaMetricsCollector.startExecution(lambdaContext);

        // Log lambda start
        observabilityManager.logLambdaStart(serviceName, lambdaContext.getAwsRequestId());
        
        try {
            event.getRecords().values().stream()
                    .flatMap(List::stream)
                    .forEach(record -> processKafkaRecord(record, lambdaContext));

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

    private void processKafkaRecord(KafkaEvent.KafkaEventRecord record, Context lambdaContext) {
        Map<String, String> headers = extractKafkaHeaders(record);
        
        // Setup business context from Kafka headers
        observabilityManager.setupBusinessContextFromHeaders(headers);

        io.opentelemetry.context.Context context = TraceContextExtractor.extractFromKafkaRecord(record);

        try (Scope ignored = context.makeCurrent()) {
            String topic = record.getTopic();
            final String correlationId = extractCorrelationId(headers);
            final String userId = extractUserId(headers);
            
            Span span = tracer.spanBuilder(String.format("kafka %s process", topic))
                    .setParent(context)
                    .setSpanKind(SpanKind.CONSUMER)
                    .startSpan();

            try (Scope spanScope = span.makeCurrent()) {
                enrichSpanWithKafkaAttributes(span, record, lambdaContext, correlationId, userId);
                
                // Add user info to observability manager
                if (userId != null || correlationId != null) {
                    observabilityManager.addUserInfo(span, userId, null, null);
                }

                handleRecord(record, lambdaContext);

                // Log performance info
                Duration duration = Duration.between(Instant.now().minusMillis(System.currentTimeMillis() - lambdaContext.getRemainingTimeInMillis()), Instant.now());
                observabilityManager.addPerformanceInfo(span, duration.toMillis(), "Kafka " + topic + " process");
                
                // Close span successfully
                observabilityManager.closeSpanSuccessfully(span, "Kafka record processed");

            } catch (Exception e) {
                // Use observability manager for error handling
                observabilityManager.closeSpan(span, e);
                throw e;
            } finally {
                span.end();
            }
        }
    }

    private void enrichSpanWithKafkaAttributes(Span span, KafkaEvent.KafkaEventRecord record, Context lambdaContext, String correlationId, String userId) {
        // Atributos estándar de messaging
        span.setAllAttributes(Attributes.of(
                AttributeKey.stringKey("messaging.system"), "kafka",
                AttributeKey.stringKey("messaging.destination"), record.getTopic(),
                AttributeKey.stringKey("messaging.operation"), "process",
                AttributeKey.stringKey("messaging.kafka.consumer.group"), 
                Optional.ofNullable(System.getenv("KAFKA_CONSUMER_GROUP")).orElse("unknown")
        ));

        // Atributos específicos de Kafka
        span.setAllAttributes(Attributes.of(
                AttributeKey.longKey("messaging.kafka.message.offset"), record.getOffset(),
                AttributeKey.longKey("messaging.kafka.partition"), (long) record.getPartition(),
                AttributeKey.stringKey("messaging.kafka.topic"), record.getTopic()
        ));

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
        if (record.getValue() != null) {
            span.setAttribute("messaging.message.payload.size_bytes", record.getValue().length());
        }

        // Headers del mensaje Kafka si están disponibles
        if (record.getHeaders() != null && !record.getHeaders().isEmpty()) {
            span.setAttribute("messaging.kafka.message.headers.count", record.getHeaders().size());
        }
    }

    private Map<String, String> extractKafkaHeaders(KafkaEvent.KafkaEventRecord record) {
        Map<String, String> headers = new HashMap<>();
        if (record.getHeaders() != null) {
            for (Map<String, byte[]> headerMap : record.getHeaders()) {
                headerMap.forEach((key, value) -> {
                    if (value != null) {
                        headers.put(key, new String(value, StandardCharsets.UTF_8));
                    }
                });
            }
        }
        return headers;
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

    protected abstract void handleRecord(KafkaEvent.KafkaEventRecord record, Context lambdaContext);

}