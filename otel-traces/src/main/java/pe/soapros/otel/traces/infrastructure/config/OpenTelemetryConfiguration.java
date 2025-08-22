package pe.soapros.otel.traces.infrastructure.config;

import io.opentelemetry.api.OpenTelemetry;
import pe.soapros.otel.core.infrastructure.ObservabilityConfig;
import pe.soapros.otel.core.infrastructure.OpenTelemetryManager;

@Deprecated
public class OpenTelemetryConfiguration {
    
    private OpenTelemetryConfiguration() {
        // private constructor to prevent instantiation
    }

    @Deprecated
    public static synchronized OpenTelemetry init(String serviceName) {
        if (!OpenTelemetryManager.isInitialized()) {
            OpenTelemetryManager.initialize(serviceName);
        }
        return OpenTelemetryManager.getInstance().getOpenTelemetry();
    }

    @Deprecated
    public static OpenTelemetry getInstance() {
        if (!OpenTelemetryManager.isInitialized()) {
            throw new IllegalStateException("OpenTelemetry has not been initialized. Use OpenTelemetryManager.initialize() first.");
        }
        return OpenTelemetryManager.getInstance().getOpenTelemetry();
    }

}
