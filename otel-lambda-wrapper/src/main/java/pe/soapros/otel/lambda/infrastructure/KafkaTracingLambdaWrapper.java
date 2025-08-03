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

import java.util.List;

public abstract class KafkaTracingLambdaWrapper implements RequestHandler<KafkaEvent, Void> {
    private final OpenTelemetry openTelemetry;

    protected KafkaTracingLambdaWrapper(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @Override
    public Void handleRequest(KafkaEvent event, Context lambdaContext) {
        Tracer tracer = openTelemetry.getTracer("pe.soapros.otel.kafka");

        event.getRecords().values().stream()
                .flatMap(List::stream)
                .forEach(record -> {
                    io.opentelemetry.context.Context context = TraceContextExtractor.extractFromKafkaRecord(record);

                    try (Scope ignored = context.makeCurrent()) {
                        String topic = record.getTopic();
                        Span span = tracer.spanBuilder("kafka:" + topic)
                                .setSpanKind(SpanKind.CONSUMER)
                                .startSpan();


                        try (Scope spanScope = span.makeCurrent()) {
                            span.setAttribute("messaging.system", "kafka");
                            span.setAttribute("messaging.destination", topic);
                            span.setAttribute("messaging.operation", "process");
                            span.setAttribute("faas.trigger", "pubsub");
                            span.setAttribute("kafka.offset", record.getOffset());
                            span.setAttribute("kafka.partition", record.getPartition());
                            span.setAttribute("kafka.topic", record.getTopic());

                            handleRecord(record, lambdaContext);
                            span.setStatus(StatusCode.OK);
                        } catch (Exception e) {
                            SpanManager.closeSpan(span, e);
                            throw e;
                        } finally {
                            span.end();
                        }
                    }
                });

        return null;
    }

    protected abstract void handleRecord(KafkaEvent.KafkaEventRecord record, Context lambdaContext);


}
