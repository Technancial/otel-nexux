package pe.soapros.otel.traces.infrastructure;

import pe.soapros.otel.core.domain.TracerService;
import pe.soapros.otel.core.infrastructure.OpenTelemetryManager;
import pe.soapros.otel.traces.infrastructure.config.OpenTelemetryConfiguration;

public class TracingFactory {
    
    public static TracerService createTracerService(String instrumentationName) {
        if (!OpenTelemetryManager.isInitialized()) {
            throw new IllegalStateException("OpenTelemetryManager not initialized. Call OpenTelemetryManager.initialize() first.");
        }
        
        return OpenTelemetryManager.getInstance().createTracerService(instrumentationName);
    }
    
    public static TracerService getDefaultTracerService() {
        if (!OpenTelemetryManager.isInitialized()) {
            throw new IllegalStateException("OpenTelemetryManager not initialized. Call OpenTelemetryManager.initialize() first.");
        }
        
        return OpenTelemetryManager.getInstance().getTracerService();
    }
    
    @Deprecated
    public static TracerService createTracerServiceLegacy(String serviceName, String instrumentationName) {
        OpenTelemetryConfiguration.init(serviceName);
        return createTracerService(instrumentationName);
    }
}