package pe.soapros.otel.traces.infrastructure.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;

import java.time.Duration;

public class OpenTelemetryConfiguration {
    private static final String DEFAULT_SERVICE_NAME = "otel-service";
    private static final String DEFAULT_OTLP_ENDPOINT = "http://localhost:4317"; // fallback
    private static volatile OpenTelemetry openTelemetry;

    private OpenTelemetryConfiguration() {
        // private constructor to prevent instantiation
    }

    public static synchronized OpenTelemetry init(String serviceName) {
        if (openTelemetry != null) {
            return openTelemetry;
        }

        String endpointCollector = System.getenv().getOrDefault("OTEL_EXPORTER_OTLP_ENDPOINT", DEFAULT_OTLP_ENDPOINT);

        OtlpGrpcSpanExporter spanExporter = OtlpGrpcSpanExporter.builder()
                .setEndpoint(endpointCollector)
                .setTimeout(Duration.ofSeconds(10))
                .build();

        Resource resource = Resource.create(
                Attributes.of(AttributeKey.stringKey("service.name"),
                        serviceName != null ? serviceName : DEFAULT_SERVICE_NAME)
        );

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
                .build();

        openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .buildAndRegisterGlobal();

        return openTelemetry;
    }

    public static OpenTelemetry getInstance() {
        if (openTelemetry == null) {
            throw new IllegalStateException("OpenTelemetry has not been initialized. Call init() first.");
        }
        return openTelemetry;
    }

}
