package pe.soapros.otel.lambda.infrastructure;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

public abstract class SqsTracingLambdaWrapper implements RequestHandler<SQSEvent, Void> {
    
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

    public SqsTracingLambdaWrapper(OpenTelemetry openTelemetry) {
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
                "pe.soapros.otel.lambda.sqs",
                Optional.ofNullable(serviceVersion).orElse("1.0.0")
        );
        this.meter = openTelemetry.getMeter("pe.soapros.otel.lambda.sqs.metrics");
    }

    private void initializeMetricsIfNeeded() {
        if (messageCounter == null && enableDetailedMetrics) {
            synchronized (this) {
                if (messageCounter == null) {
                    messageCounter = meter.counterBuilder("sqs_messages_processed_total")
                            .setDescription("Total number of SQS messages processed")
                            .build();

                    errorCounter = meter.counterBuilder("sqs_messages_errors_total")
                            .setDescription("Total number of SQS message processing errors")
                            .build();

                    processingDurationHistogram = meter.histogramBuilder("sqs_message_processing_duration_seconds")
                            .setDescription("Time spent processing SQS messages")
                            .setUnit("s")
                            .build();

                    coldStartCounter = meter.counterBuilder("sqs_lambda_cold_starts_total")
                            .setDescription("Total number of Lambda cold starts for SQS processing")
                            .build();
                }
            }
        }
    }

    @Override
    public Void handleRequest(SQSEvent event, Context lambdaContext) {
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

        event.getRecords().forEach(message -> processSqsMessage(message, lambdaContext, coldStart));

        return null;
    }

    private void processSqsMessage(SQSEvent.SQSMessage message, Context lambdaContext, boolean coldStart) {
        Map<String, String> headers = message.getMessageAttributes().entrySet().stream()
                .filter(entry -> entry.getValue().getStringValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getStringValue()));

        io.opentelemetry.context.Context otelContext = TraceContextExtractor.extractFromHeaders(headers);
        
        String queueName = extractQueueName(message.getEventSourceArn());
        Span span = tracer.spanBuilder(String.format("sqs %s process", queueName))
                .setParent(otelContext)
                .setSpanKind(SpanKind.CONSUMER)
                .startSpan();

        Instant startTime = Instant.now();

        try (Scope scope = span.makeCurrent()) {
            enrichSpanWithSqsAttributes(span, message, lambdaContext, coldStart);

            handle(message, lambdaContext);

            span.setStatus(StatusCode.OK);
            recordSuccessMetrics(queueName, startTime);

        } catch (Exception ex) {
            recordErrorMetrics(queueName, startTime);
            SpanManager.closeSpan(span, ex);
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

    private void enrichSpanWithSqsAttributes(Span span, SQSEvent.SQSMessage message, Context lambdaContext, boolean coldStart) {
        // Atributos estándar de messaging
        span.setAttribute("messaging.system", "aws.sqs");
        span.setAttribute("messaging.destination", message.getEventSourceArn());
        span.setAttribute("messaging.operation", "process");
        span.setAttribute("messaging.message.id", message.getMessageId());

        // Atributos específicos de SQS
        span.setAttribute("messaging.aws.sqs.source_arn", message.getEventSourceArn());
        span.setAttribute("messaging.aws.sqs.receipt_handle", message.getReceiptHandle());
        span.setAttribute("messaging.aws.sqs.md5_of_body", message.getMd5OfBody());
        
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
        if (message.getBody() != null) {
            span.setAttribute("messaging.message.payload.size_bytes", message.getBody().length());
        }

        // Atributos de mensaje
        if (message.getMessageAttributes() != null && !message.getMessageAttributes().isEmpty()) {
            span.setAttribute("messaging.aws.sqs.message_attributes.count", message.getMessageAttributes().size());
        }
    }

    private void recordSuccessMetrics(String queueName, Instant startTime) {
        if (enableDetailedMetrics && messageCounter != null) {
            messageCounter.add(1, Attributes.of(
                    AttributeKey.stringKey("queue"), queueName,
                    AttributeKey.stringKey("status"), "success"
            ));

            Duration duration = Duration.between(startTime, Instant.now());
            processingDurationHistogram.record(duration.toNanos() / 1_000_000_000.0,
                    Attributes.of(
                            AttributeKey.stringKey("queue"), queueName,
                            AttributeKey.stringKey("status"), "success"
                    ));
        }
    }

    private void recordErrorMetrics(String queueName, Instant startTime) {
        if (enableDetailedMetrics && errorCounter != null) {
            errorCounter.add(1, Attributes.of(
                    AttributeKey.stringKey("queue"), queueName,
                    AttributeKey.stringKey("status"), "error"
            ));

            Duration duration = Duration.between(startTime, Instant.now());
            processingDurationHistogram.record(duration.toNanos() / 1_000_000_000.0,
                    Attributes.of(
                            AttributeKey.stringKey("queue"), queueName,
                            AttributeKey.stringKey("status"), "error"
                    ));
        }
    }

    public abstract void handle(SQSEvent.SQSMessage message, Context context);
}
