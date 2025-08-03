package pe.soapros.otel.lambda.infrastructure;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.KafkaEvent;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.semconv.ServiceAttributes;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public abstract class KafkaTracingLambdaWrapper implements RequestHandler<KafkaEvent, Void> {
    
    private final OpenTelemetry openTelemetry;

    private final Tracer tracer;
    private final Meter meter;

    private volatile LongCounter messageCounter;
    private volatile LongCounter errorCounter;
    private volatile DoubleHistogram processingDurationHistogram;
    private volatile LongCounter coldStartCounter;

    private final boolean enableDetailedMetrics;
    private final boolean enableColdStartDetection;
    private final String serviceName;
    private final String serviceVersion;

    private static volatile boolean isColdStart = true;

    protected KafkaTracingLambdaWrapper(OpenTelemetry openTelemetry) {
        Objects.requireNonNull(openTelemetry, "OpenTelemetry instance cannot be null");
        this.openTelemetry = openTelemetry;

        // Configuración desde variables de entorno
        this.serviceName = Optional.ofNullable(System.getenv("OTEL_SERVICE_NAME"))
                .orElse(System.getenv("AWS_LAMBDA_FUNCTION_NAME"));
        this.serviceVersion = Optional.ofNullable(System.getenv("OTEL_SERVICE_VERSION"))
                .orElse(System.getenv("AWS_LAMBDA_FUNCTION_VERSION"));

        this.enableDetailedMetrics = Boolean.parseBoolean(
                System.getenv().getOrDefault("OTEL_LAMBDA_ENABLE_DETAILED_METRICS", "true"));
        this.enableColdStartDetection = Boolean.parseBoolean(
                System.getenv().getOrDefault("OTEL_LAMBDA_ENABLE_COLD_START_DETECTION", "true"));

        // Inicializar tracer y meter
        this.tracer = openTelemetry.getTracer(
                "pe.soapros.otel.lambda.kafka",
                Optional.ofNullable(serviceVersion).orElse("1.0.0")
        );
        this.meter = openTelemetry.getMeter("pe.soapros.otel.lambda.kafka.metrics");
    }

    private void initializeMetricsIfNeeded() {
        if (messageCounter == null && enableDetailedMetrics) {
            synchronized (this) {
                if (messageCounter == null) {
                    messageCounter = meter.counterBuilder("kafka_messages_processed_total")
                            .setDescription("Total number of Kafka messages processed")
                            .build();

                    errorCounter = meter.counterBuilder("kafka_messages_errors_total")
                            .setDescription("Total number of Kafka message processing errors")
                            .build();

                    processingDurationHistogram = meter.histogramBuilder("kafka_message_processing_duration_seconds")
                            .setDescription("Time spent processing Kafka messages")
                            .setUnit("s")
                            .build();

                    coldStartCounter = meter.counterBuilder("kafka_lambda_cold_starts_total")
                            .setDescription("Total number of Lambda cold starts for Kafka processing")
                            .build();
                }
            }
        }
    }

    @Override
    public Void handleRequest(KafkaEvent event, Context lambdaContext) {
        initializeMetricsIfNeeded();

        // Detectar cold start
        boolean currentColdStart = false;
        if (enableColdStartDetection && isColdStart) {
            currentColdStart = true;
            isColdStart = false;
            if (coldStartCounter != null) {
                coldStartCounter.add(1);
            }
        }

        final boolean coldStart = currentColdStart;

        event.getRecords().values().stream()
                .flatMap(List::stream)
                .forEach(record -> processKafkaRecord(record, lambdaContext, coldStart));

        return null;
    }

    private void processKafkaRecord(KafkaEvent.KafkaEventRecord record, Context lambdaContext, boolean coldStart) {
        io.opentelemetry.context.Context context = TraceContextExtractor.extractFromKafkaRecord(record);

        try (Scope ignored = context.makeCurrent()) {
            String topic = record.getTopic();
            Span span = tracer.spanBuilder(String.format("kafka %s process", topic))
                    .setParent(context)
                    .setSpanKind(SpanKind.CONSUMER)
                    .startSpan();

            Instant startTime = Instant.now();

            try (Scope spanScope = span.makeCurrent()) {
                enrichSpanWithKafkaAttributes(span, record, lambdaContext, coldStart);

                handleRecord(record, lambdaContext);

                span.setStatus(StatusCode.OK);
                recordSuccessMetrics(topic, startTime);

            } catch (Exception e) {
                recordErrorMetrics(topic, startTime);
                SpanManager.closeSpan(span, e);
                throw e;
            } finally {
                span.end();
            }
        }
    }

    private void enrichSpanWithKafkaAttributes(Span span, KafkaEvent.KafkaEventRecord record, Context lambdaContext, boolean coldStart) {
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
        span.setAttribute("faas.coldstart", coldStart);

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

    private void recordSuccessMetrics(String topic, Instant startTime) {
        if (enableDetailedMetrics && messageCounter != null) {
            messageCounter.add(1, Attributes.of(
                    AttributeKey.stringKey("topic"), topic,
                    AttributeKey.stringKey("status"), "success"
            ));

            Duration duration = Duration.between(startTime, Instant.now());
            processingDurationHistogram.record(duration.toNanos() / 1_000_000_000.0,
                    Attributes.of(
                            AttributeKey.stringKey("topic"), topic,
                            AttributeKey.stringKey("status"), "success"
                    ));
        }
    }

    private void recordErrorMetrics(String topic, Instant startTime) {
        if (enableDetailedMetrics && errorCounter != null) {
            errorCounter.add(1, Attributes.of(
                    AttributeKey.stringKey("topic"), topic,
                    AttributeKey.stringKey("status"), "error"
            ));

            Duration duration = Duration.between(startTime, Instant.now());
            processingDurationHistogram.record(duration.toNanos() / 1_000_000_000.0,
                    Attributes.of(
                            AttributeKey.stringKey("topic"), topic,
                            AttributeKey.stringKey("status"), "error"
                    ));
        }
    }

    protected abstract void handleRecord(KafkaEvent.KafkaEventRecord record, Context lambdaContext);


}
