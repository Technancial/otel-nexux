package pe.soapros.otel.core.infrastructure;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.semconv.ServiceAttributes;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

public class ObservabilityConfig {
    
    public enum Environment {
        DEVELOPMENT("http://localhost:4317", Duration.ofSeconds(5), 100),
        STAGING("", Duration.ofSeconds(10), 512),
        PRODUCTION("", Duration.ofSeconds(30), 2048);
        
        private final String defaultEndpoint;
        private final Duration exportTimeout;
        private final int maxBatchSize;
        
        Environment(String defaultEndpoint, Duration exportTimeout, int maxBatchSize) {
            this.defaultEndpoint = defaultEndpoint;
            this.exportTimeout = exportTimeout;
            this.maxBatchSize = maxBatchSize;
        }
        
        public String getDefaultEndpoint() { return defaultEndpoint; }
        public Duration getExportTimeout() { return exportTimeout; }
        public int getMaxBatchSize() { return maxBatchSize; }
    }
    
    private final String serviceName;
    private final String serviceVersion;
    private final Environment environment;
    private final String otlpEndpoint;
    private final Map<String, String> customAttributes;
    
    private ObservabilityConfig(Builder builder) {
        this.serviceName = builder.serviceName;
        this.serviceVersion = builder.serviceVersion;
        this.environment = builder.environment;
        this.otlpEndpoint = resolveEndpoint(builder.otlpEndpoint);
        this.customAttributes = Map.copyOf(builder.customAttributes);
    }
    
    public OpenTelemetry createOpenTelemetry() {
        Resource resource = createResource();
        
        return OpenTelemetrySdk.builder()
                .setTracerProvider(createTracerProvider(resource))
                .setMeterProvider(createMeterProvider(resource))
                .setLoggerProvider(createLoggerProvider(resource))
                .build();
    }
    
    private Resource createResource() {
        var resourceBuilder = Resource.getDefault()
                .toBuilder()
                .put(ServiceAttributes.SERVICE_NAME, serviceName)
                .put(ServiceAttributes.SERVICE_VERSION, serviceVersion)
                .put(AttributeKey.stringKey("deployment.environment"), environment.name().toLowerCase());
        
        customAttributes.forEach((key, value) -> 
            resourceBuilder.put(AttributeKey.stringKey(key), value));
        
        return resourceBuilder.build();
    }
    
    private SdkTracerProvider createTracerProvider(Resource resource) {
        OtlpGrpcSpanExporter spanExporter = OtlpGrpcSpanExporter.builder()
                .setEndpoint(otlpEndpoint)
                .setTimeout(environment.getExportTimeout())
                .build();
        
        return SdkTracerProvider.builder()
                .setResource(resource)
                .addSpanProcessor(BatchSpanProcessor.builder(spanExporter)
                        .setMaxExportBatchSize(environment.getMaxBatchSize())
                        .setExporterTimeout(environment.getExportTimeout())
                        .build())
                .build();
    }
    
    private SdkMeterProvider createMeterProvider(Resource resource) {
        OtlpGrpcMetricExporter metricExporter = OtlpGrpcMetricExporter.builder()
                .setEndpoint(otlpEndpoint)
                .setTimeout(environment.getExportTimeout())
                .build();
        
        return SdkMeterProvider.builder()
                .setResource(resource)
                .registerMetricReader(PeriodicMetricReader.builder(metricExporter)
                        .setInterval(Duration.ofSeconds(30))
                        .build())
                .build();
    }
    
    private SdkLoggerProvider createLoggerProvider(Resource resource) {
        OtlpGrpcLogRecordExporter logExporter = OtlpGrpcLogRecordExporter.builder()
                .setEndpoint(otlpEndpoint)
                .setTimeout(environment.getExportTimeout())
                .build();
        
        return SdkLoggerProvider.builder()
                .setResource(resource)
                .addLogRecordProcessor(BatchLogRecordProcessor.builder(logExporter)
                        .setMaxExportBatchSize(environment.getMaxBatchSize())
                        .setExporterTimeout(environment.getExportTimeout())
                        .setScheduleDelay(Duration.ofMillis(500))
                        .build())
                .build();
    }
    
    private String resolveEndpoint(String customEndpoint) {
        if (customEndpoint != null && !customEndpoint.isEmpty()) {
            return customEndpoint;
        }
        
        String envEndpoint = System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT");
        if (envEndpoint != null && !envEndpoint.isEmpty()) {
            return envEndpoint;
        }
        
        return environment.getDefaultEndpoint();
    }
    
    public String getServiceName() { return serviceName; }
    public String getServiceVersion() { return serviceVersion; }
    public Environment getEnvironment() { return environment; }
    public String getOtlpEndpoint() { return otlpEndpoint; }
    public Map<String, String> getCustomAttributes() { return customAttributes; }
    
    public static Builder builder(String serviceName) {
        return new Builder(serviceName);
    }
    
    public static class Builder {
        private final String serviceName;
        private String serviceVersion = "1.0.0";
        private Environment environment = Environment.DEVELOPMENT;
        private String otlpEndpoint;
        private Map<String, String> customAttributes = Map.of();
        
        private Builder(String serviceName) {
            this.serviceName = serviceName;
        }
        
        public Builder serviceVersion(String serviceVersion) {
            this.serviceVersion = serviceVersion;
            return this;
        }
        
        public Builder environment(Environment environment) {
            this.environment = environment;
            return this;
        }
        
        public Builder environment(String environmentName) {
            this.environment = Environment.valueOf(environmentName.toUpperCase());
            return this;
        }
        
        public Builder otlpEndpoint(String otlpEndpoint) {
            this.otlpEndpoint = otlpEndpoint;
            return this;
        }
        
        public Builder customAttributes(Map<String, String> customAttributes) {
            this.customAttributes = customAttributes;
            return this;
        }
        
        public ObservabilityConfig build() {
            return new ObservabilityConfig(this);
        }
    }
}