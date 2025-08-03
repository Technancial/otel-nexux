package pe.soapros.otel.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pe.soapros.otel.lambda.infrastructure.SqsTracingLambdaWrapper;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SqsTracingLambdaWrapperTest {

    private InMemorySpanExporter spanExporter;
    private OpenTelemetry openTelemetry;

    @BeforeEach
    void setUp() {
        spanExporter = InMemorySpanExporter.create();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build();
        openTelemetry = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
    }

    @Test
    void testSqsWrapperCreatesSpan() {
        SQSEvent event = new SQSEvent();
        SQSEvent.SQSMessage message = new SQSEvent.SQSMessage();
        message.setMessageId(UUID.randomUUID().toString());
        message.setEventSourceArn("arn:aws:sqs:us-east-1:123456789012:my-queue");

        Map<String, SQSEvent.MessageAttribute> attributes = new HashMap<>();
        SQSEvent.MessageAttribute attr = new SQSEvent.MessageAttribute();
        attr.setStringValue("dummy");
        attributes.put("x-otel-trace-id", attr);
        message.setMessageAttributes(attributes);

        event.setRecords(Collections.singletonList(message));

        SqsTracingLambdaWrapper wrapper = new SqsTracingLambdaWrapper(openTelemetry) {
            @Override
            public void handle(SQSEvent.SQSMessage message, Context context) {
                // No-op
            }
        };

        wrapper.handleRequest(event, new MockLambdaContext());

        var spans = spanExporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        var span = spans.getFirst();
        assertEquals("sqs my-queue process", span.getName());
        assertEquals(SpanKind.CONSUMER, span.getKind());
        assertEquals(message.getMessageId(), span.getAttributes().get(AttributeKey.stringKey("messaging.message.id")));
    }
}



