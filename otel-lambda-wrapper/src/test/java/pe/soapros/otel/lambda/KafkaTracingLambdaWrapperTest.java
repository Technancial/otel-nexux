package pe.soapros.otel.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.KafkaEvent;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pe.soapros.otel.lambda.infrastructure.KafkaTracingLambdaWrapper;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class KafkaTracingLambdaWrapperTest {
    private InMemorySpanExporter spanExporter;
    private OpenTelemetry openTelemetry;

    @BeforeEach
    void setUp() {
        spanExporter = InMemorySpanExporter.create();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build();
        openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();
    }

    @Test
    void testKafkaWrapperCreatesSpan() {
        // Simulamos un evento con la estructura que usa Lambda para MSK
        KafkaEvent event = new KafkaEvent();

        KafkaEvent.KafkaEventRecord kafkaRecord = new KafkaEvent.KafkaEventRecord();
        kafkaRecord.setTopic("my-topic");
        kafkaRecord.setPartition(0);
        kafkaRecord.setOffset(42L);
        kafkaRecord.setTimestamp(System.currentTimeMillis());
        kafkaRecord.setValue(Base64.getEncoder().encodeToString("mensaje de prueba".getBytes()));

        // Simular headers: List<Map<String, byte[]>>
        Map<String, byte[]> headerMap = new HashMap<>();
        headerMap.put("x-otel-trace-id", "dummy-trace-id".getBytes());
        kafkaRecord.setHeaders(Collections.singletonList(headerMap));

        Map<String, List<KafkaEvent.KafkaEventRecord>> records = new HashMap<>();
        records.put("my-topic-0", List.of(kafkaRecord));
        event.setRecords(records);


        KafkaTracingLambdaWrapper wrapper = new KafkaTracingLambdaWrapper(openTelemetry) {
            @Override
            protected void handleRecord(KafkaEvent.KafkaEventRecord record, Context lambdaContext) {

            }
        };

        wrapper.handleRequest(event, new MockLambdaContext());

        var spans = spanExporter.getFinishedSpanItems();
        assertEquals(1, spans.size());

        var span = spans.getFirst();
        assertEquals("kafka my-topic process", span.getName());
        assertEquals(SpanKind.CONSUMER, span.getKind());
        assertEquals(42L, span.getAttributes().get(AttributeKey.longKey("messaging.kafka.message.offset")));
    }
}
