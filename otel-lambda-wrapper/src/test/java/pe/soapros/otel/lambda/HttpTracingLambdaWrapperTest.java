package pe.soapros.otel.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pe.soapros.otel.lambda.infrastructure.HttpTracingLambdaWrapper;


import static org.junit.jupiter.api.Assertions.*;

public class HttpTracingLambdaWrapperTest {

    private OpenTelemetry openTelemetry;
    private InMemorySpanExporter spanExporter;

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
    void testHttpWrapperCreatesSpan() {
        HttpTracingLambdaWrapper wrapper = new HttpTracingLambdaWrapper(openTelemetry) {
            @Override
            public APIGatewayProxyResponseEvent handle(APIGatewayProxyRequestEvent input, Context context) {
                return new APIGatewayProxyResponseEvent().withStatusCode(200);
            }
        };

        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent()
                .withHttpMethod("GET")
                .withPath("/test");

        Context context = new MockLambdaContext();

        APIGatewayProxyResponseEvent response = wrapper.handleRequest(event, context);

        assertEquals(200, response.getStatusCode());

        var spans = spanExporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        assertEquals("HTTP GET /test", spans.getFirst().getName());
    }
}