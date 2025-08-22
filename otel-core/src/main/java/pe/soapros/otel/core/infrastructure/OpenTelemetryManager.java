package pe.soapros.otel.core.infrastructure;

import io.opentelemetry.api.OpenTelemetry;
import pe.soapros.otel.core.domain.LoggerService;
import pe.soapros.otel.core.domain.MetricsService;
import pe.soapros.otel.core.domain.TracerService;

import java.util.concurrent.atomic.AtomicBoolean;

public class OpenTelemetryManager {
    
    private static volatile OpenTelemetryManager instance;
    private static final Object lock = new Object();
    
    private final OpenTelemetry openTelemetry;
    private final ObservabilityConfig config;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    
    private volatile TracerService tracerService;
    private volatile MetricsService metricsService;
    private volatile LoggerService loggerService;
    
    private OpenTelemetryManager(ObservabilityConfig config) {
        this.config = config;
        this.openTelemetry = config.createOpenTelemetry();
        this.initialized.set(true);
    }
    
    public static OpenTelemetryManager initialize(ObservabilityConfig config) {
        if (instance != null) {
            throw new IllegalStateException("OpenTelemetryManager already initialized. Use getInstance() instead.");
        }
        
        synchronized (lock) {
            if (instance == null) {
                instance = new OpenTelemetryManager(config);
            }
        }
        return instance;
    }
    
    public static OpenTelemetryManager initialize(String serviceName) {
        ObservabilityConfig config = ObservabilityConfig.builder(serviceName)
                .environment(detectEnvironment())
                .build();
        return initialize(config);
    }
    
    public static OpenTelemetryManager getInstance() {
        if (instance == null) {
            throw new IllegalStateException("OpenTelemetryManager not initialized. Call initialize() first.");
        }
        return instance;
    }
    
    public static boolean isInitialized() {
        return instance != null && instance.initialized.get();
    }
    
    public OpenTelemetry getOpenTelemetry() {
        return openTelemetry;
    }
    
    public ObservabilityConfig getConfig() {
        return config;
    }
    
    public TracerService getTracerService() {
        if (tracerService == null) {
            synchronized (this) {
                if (tracerService == null) {
                    tracerService = createTracerService();
                }
            }
        }
        return tracerService;
    }
    
    public MetricsService getMetricsService() {
        if (metricsService == null) {
            synchronized (this) {
                if (metricsService == null) {
                    metricsService = createMetricsService();
                }
            }
        }
        return metricsService;
    }
    
    public LoggerService getLoggerService() {
        if (loggerService == null) {
            synchronized (this) {
                if (loggerService == null) {
                    loggerService = createLoggerService();
                }
            }
        }
        return loggerService;
    }
    
    public TracerService createTracerService(String instrumentationName) {
        return new OpenTelemetryTracerService(openTelemetry.getTracer(instrumentationName));
    }
    
    public MetricsService createMetricsService(String instrumentationName) {
        return new OpenTelemetryMetricsService(openTelemetry.getMeter(instrumentationName));
    }
    
    public LoggerService createLoggerService(String instrumentationName) {
        return new OpenTelemetryLoggerService(
            openTelemetry.getLogsBridge().get(instrumentationName), 
            instrumentationName, 
            true // Enable trace correlation by default
        );
    }
    
    private TracerService createTracerService() {
        return createTracerService(config.getServiceName());
    }
    
    private MetricsService createMetricsService() {
        return createMetricsService(config.getServiceName());
    }
    
    private LoggerService createLoggerService() {
        return createLoggerService(config.getServiceName());
    }
    
    private static ObservabilityConfig.Environment detectEnvironment() {
        String env = System.getenv("ENVIRONMENT");
        if (env == null) {
            env = System.getProperty("environment", "development");
        }
        
        try {
            return ObservabilityConfig.Environment.valueOf(env.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ObservabilityConfig.Environment.DEVELOPMENT;
        }
    }
    
    public static void reset() {
        synchronized (lock) {
            if (instance != null) {
                instance.initialized.set(false);
                instance = null;
            }
        }
    }
    
    public boolean isHealthy() {
        return initialized.get() && openTelemetry != null;
    }
    
    public String getHealthStatus() {
        if (!isHealthy()) {
            return "UNHEALTHY: Not properly initialized";
        }
        
        return String.format("HEALTHY: Service=%s, Version=%s, Environment=%s, Endpoint=%s",
                config.getServiceName(),
                config.getServiceVersion(),
                config.getEnvironment(),
                config.getOtlpEndpoint());
    }
}