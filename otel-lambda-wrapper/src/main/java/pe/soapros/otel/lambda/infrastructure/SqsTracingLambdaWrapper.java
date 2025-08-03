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

import java.util.Map;
import java.util.stream.Collectors;

public abstract class SqsTracingLambdaWrapper implements RequestHandler<SQSEvent, Void> {

    private final Tracer tracer;

    public SqsTracingLambdaWrapper(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer("pe.soapros.otel.lambda.sqs");
    }

    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        event.getRecords().forEach(message -> {
            Map<String, String> headers = message.getMessageAttributes().entrySet().stream()
                    .filter(entry -> entry.getValue().getStringValue() != null)
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getStringValue()));

            io.opentelemetry.context.Context otelContext = TraceContextExtractor.extractFromHeaders(headers);
            String spanName = message.getEventSourceArn() != null
                    ? "sqs:" + message.getEventSourceArn()
                    : "lambda-sqs-handler";

            Span span = tracer.spanBuilder(spanName)
                    .setParent(otelContext)
                    .setSpanKind(SpanKind.CONSUMER)
                    .startSpan();

            try (Scope scope = span.makeCurrent()) {
                span.setAttribute("messaging.system", "aws.sqs");
                span.setAttribute("messaging.destination", message.getEventSourceArn());
                span.setAttribute("messaging.message_id", message.getMessageId());
                handle(message, context);
            } catch (Exception ex) {
                SpanManager.closeSpan(span, ex);
                throw ex;
            } finally {
                span.end();
            }
        });

        return null;
    }

    public abstract void handle(SQSEvent.SQSMessage message, Context context);
}
