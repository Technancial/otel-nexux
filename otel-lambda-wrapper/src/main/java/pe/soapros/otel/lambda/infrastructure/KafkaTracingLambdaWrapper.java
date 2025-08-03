package pe.soapros.otel.lambda.infrastructure;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.KafkaEvent;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.semconv.ServiceAttributes;
import pe.soapros.otel.metrics.infrastructure.MetricsFactory;
import pe.soapros.otel.metrics.infrastructure.LambdaMetricsCollector;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public abstract class KafkaTracingLambdaWrapper implements RequestHandler<KafkaEvent, Void> {
    
    private final OpenTelemetry openTelemetry;
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

        // Inicializar tracer
        this.tracer = openTelemetry.getTracer(
                "pe.soapros.otel.lambda.kafka",
                Optional.ofNullable(serviceVersion).orElse("1.0.0")
        );
        
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
        }
    }

    private void processKafkaRecord(KafkaEvent.KafkaEventRecord record, Context lambdaContext) {
        io.opentelemetry.context.Context context = TraceContextExtractor.extractFromKafkaRecord(record);

        try (Scope ignored = context.makeCurrent()) {
            String topic = record.getTopic();
            Span span = tracer.spanBuilder(String.format("kafka %s process", topic))
                    .setParent(context)
                    .setSpanKind(SpanKind.CONSUMER)
                    .startSpan();

            try (Scope spanScope = span.makeCurrent()) {
                enrichSpanWithKafkaAttributes(span, record, lambdaContext);

                handleRecord(record, lambdaContext);

                span.setStatus(StatusCode.OK);
                // Métricas manejadas por LambdaMetricsCollector

            } catch (Exception e) {
                // Métricas de error manejadas por LambdaMetricsCollector
                SpanManager.closeSpan(span, e);
                throw e;
            } finally {
                span.end();
            }
        }
    }

    private void enrichSpanWithKafkaAttributes(Span span, KafkaEvent.KafkaEventRecord record, Context lambdaContext) {
        // Atributos estándar de messaging
        span.setAttribute("messaging.system", "kafka");
        span.setAttribute("messaging.destination", record.getTopic());
        span.setAttribute("messaging.operation", "process");
        span.setAttribute("messaging.kafka.consumer.group", 
                Optional.ofNullable(System.getenv("KAFKA_CONSUMER_GROUP")).orElse("unknown"));

        // Atributos específicos de Kafka
        span.setAttribute("messaging.kafka.message.offset", record.getOffset());
        span.setAttribute("messaging.kafka.partition", record.getPartition());
        span.setAttribute("messaging.kafka.topic", record.getTopic());

        // Atributos de FaaS y Lambda
        span.setAttribute("faas.trigger", "pubsub");
        span.setAttribute("faas.execution", lambdaContext.getAwsRequestId());
        // Cold start se maneja automáticamente por LambdaMetricsCollector

        // Atributos de servicio
        if (serviceName != null) {
            span.setAttribute(ServiceAttributes.SERVICE_NAME, serviceName);
        }
        if (serviceVersion != null) {
            span.setAttribute(ServiceAttributes.SERVICE_VERSION, serviceVersion);
        }

        // Información adicional del mensaje
        if (record.getValue() != null) {
            span.setAttribute("messaging.message.payload.size_bytes", record.getValue().length());
        }

        // Headers del mensaje Kafka si están disponibles
        if (record.getHeaders() != null && !record.getHeaders().isEmpty()) {
            span.setAttribute("messaging.kafka.message.headers.count", record.getHeaders().size());
        }
    }

    protected abstract void handleRecord(KafkaEvent.KafkaEventRecord record, Context lambdaContext);

}