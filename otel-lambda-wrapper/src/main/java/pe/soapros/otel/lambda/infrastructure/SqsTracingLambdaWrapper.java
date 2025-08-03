package pe.soapros.otel.lambda.infrastructure;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.semconv.ServiceAttributes;
import pe.soapros.otel.metrics.infrastructure.MetricsFactory;
import pe.soapros.otel.metrics.infrastructure.LambdaMetricsCollector;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

public abstract class SqsTracingLambdaWrapper implements RequestHandler<SQSEvent, Void> {
    
    private final OpenTelemetry openTelemetry;
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

        // Inicializar tracer
        this.tracer = openTelemetry.getTracer(
                "pe.soapros.otel.lambda.sqs",
                Optional.ofNullable(serviceVersion).orElse("1.0.0")
        );
        
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

        try {
            event.getRecords().forEach(message -> processSqsMessage(message, lambdaContext));
            
            // Finalizar métricas de Lambda exitosamente
            lambdaMetricsCollector.endExecution(lambdaContext, true, null);
            
            return null;
            
        } catch (Exception ex) {
            // Finalizar métricas de Lambda con error
            lambdaMetricsCollector.endExecution(lambdaContext, false, ex);
            throw ex;
        }
    }

    private void processSqsMessage(SQSEvent.SQSMessage message, Context lambdaContext) {
        Map<String, String> headers = message.getMessageAttributes().entrySet().stream()
                .filter(entry -> entry.getValue().getStringValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getStringValue()));

        io.opentelemetry.context.Context otelContext = TraceContextExtractor.extractFromHeaders(headers);
        
        String queueName = extractQueueName(message.getEventSourceArn());
        Span span = tracer.spanBuilder(String.format("sqs %s process", queueName))
                .setParent(otelContext)
                .setSpanKind(SpanKind.CONSUMER)
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            enrichSpanWithSqsAttributes(span, message, lambdaContext);

            handle(message, lambdaContext);

            span.setStatus(StatusCode.OK);
            // Métricas manejadas por LambdaMetricsCollector

        } catch (Exception ex) {
            // Métricas de error manejadas por LambdaMetricsCollector
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

    private void enrichSpanWithSqsAttributes(Span span, SQSEvent.SQSMessage message, Context lambdaContext) {
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
        // Cold start se maneja automáticamente por LambdaMetricsCollector

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

    public abstract void handle(SQSEvent.SQSMessage message, Context context);
}