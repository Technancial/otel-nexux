package pe.soapros.otel.metrics.infrastructure;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.Meter;
import pe.soapros.otel.core.infrastructure.OpenTelemetryManager;
import pe.soapros.otel.metrics.domain.MetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory para crear instancias de servicios y colectores de métricas
 * configurados con OpenTelemetry. Proporciona un punto central de
 * configuración para todas las métricas del sistema.
 */
public class MetricsFactory {
    
    private static final Logger logger = LoggerFactory.getLogger(MetricsFactory.class);
    
    private final OpenTelemetry openTelemetry;
    private final String serviceName;
    private final String serviceVersion;
    
    // Instancias singleton de los servicios
    private volatile MetricsService metricsService;
    private volatile LambdaMetricsCollector lambdaMetricsCollector;
    private volatile HttpMetricsCollector httpMetricsCollector;
    private volatile DatabaseMetricsCollector databaseMetricsCollector;
    private volatile BusinessMetricsCollector businessMetricsCollector;
    
    public MetricsFactory(OpenTelemetry openTelemetry, String serviceName, String serviceVersion) {
        this.openTelemetry = openTelemetry;
        this.serviceName = serviceName;
        this.serviceVersion = serviceVersion;
        
        logger.info("Initialized MetricsFactory for service: {} version: {}", serviceName, serviceVersion);
    }
    
    /**
     * Crea o devuelve la instancia singleton del MetricsService
     */
    public MetricsService getMetricsService() {
        if (metricsService == null) {
            synchronized (this) {
                if (metricsService == null) {
                    Meter meter = openTelemetry.getMeter(serviceName);
                    
                    metricsService = new OpenTelemetryMetricsService(meter, serviceName);
                    logger.debug("Created MetricsService instance for service: {}", serviceName);
                }
            }
        }
        return metricsService;
    }
    
    /**
     * Crea o devuelve la instancia singleton del LambdaMetricsCollector
     */
    public LambdaMetricsCollector getLambdaMetricsCollector() {
        if (lambdaMetricsCollector == null) {
            synchronized (this) {
                if (lambdaMetricsCollector == null) {
                    lambdaMetricsCollector = new LambdaMetricsCollector(
                        getMetricsService(), 
                        serviceName, 
                        serviceVersion
                    );
                    logger.debug("Created LambdaMetricsCollector instance for service: {}", serviceName);
                }
            }
        }
        return lambdaMetricsCollector;
    }
    
    /**
     * Crea o devuelve la instancia singleton del HttpMetricsCollector
     */
    public HttpMetricsCollector getHttpMetricsCollector() {
        if (httpMetricsCollector == null) {
            synchronized (this) {
                if (httpMetricsCollector == null) {
                    httpMetricsCollector = new HttpMetricsCollector(
                        getMetricsService(), 
                        serviceName
                    );
                    logger.debug("Created HttpMetricsCollector instance for service: {}", serviceName);
                }
            }
        }
        return httpMetricsCollector;
    }
    
    /**
     * Crea o devuelve la instancia singleton del DatabaseMetricsCollector
     */
    public DatabaseMetricsCollector getDatabaseMetricsCollector() {
        if (databaseMetricsCollector == null) {
            synchronized (this) {
                if (databaseMetricsCollector == null) {
                    databaseMetricsCollector = new DatabaseMetricsCollector(
                        getMetricsService(), 
                        serviceName
                    );
                    logger.debug("Created DatabaseMetricsCollector instance for service: {}", serviceName);
                }
            }
        }
        return databaseMetricsCollector;
    }
    
    /**
     * Crea o devuelve la instancia singleton del BusinessMetricsCollector
     */
    public BusinessMetricsCollector getBusinessMetricsCollector() {
        if (businessMetricsCollector == null) {
            synchronized (this) {
                if (businessMetricsCollector == null) {
                    businessMetricsCollector = new BusinessMetricsCollector(
                        getMetricsService(), 
                        serviceName
                    );
                    logger.debug("Created BusinessMetricsCollector instance for service: {}", serviceName);
                }
            }
        }
        return businessMetricsCollector;
    }
    
    /**
     * Crea una nueva instancia de LambdaMetricsCollector (para casos especiales)
     */
    public LambdaMetricsCollector createLambdaMetricsCollector(String functionName, String functionVersion) {
        LambdaMetricsCollector collector = new LambdaMetricsCollector(
            getMetricsService(), 
            functionName != null ? functionName : serviceName, 
            functionVersion != null ? functionVersion : serviceVersion
        );
        logger.debug("Created custom LambdaMetricsCollector for function: {} version: {}", functionName, functionVersion);
        return collector;
    }
    
    /**
     * Crea una nueva instancia de HttpMetricsCollector (para casos especiales)
     */
    public HttpMetricsCollector createHttpMetricsCollector(String customServiceName) {
        HttpMetricsCollector collector = new HttpMetricsCollector(
            getMetricsService(), 
            customServiceName != null ? customServiceName : serviceName
        );
        logger.debug("Created custom HttpMetricsCollector for service: {}", customServiceName);
        return collector;
    }
    
    /**
     * Crea una nueva instancia de DatabaseMetricsCollector (para casos especiales)
     */
    public DatabaseMetricsCollector createDatabaseMetricsCollector(String customServiceName) {
        DatabaseMetricsCollector collector = new DatabaseMetricsCollector(
            getMetricsService(), 
            customServiceName != null ? customServiceName : serviceName
        );
        logger.debug("Created custom DatabaseMetricsCollector for service: {}", customServiceName);
        return collector;
    }
    
    /**
     * Crea una nueva instancia de BusinessMetricsCollector (para casos especiales)
     */
    public BusinessMetricsCollector createBusinessMetricsCollector(String customServiceName) {
        BusinessMetricsCollector collector = new BusinessMetricsCollector(
            getMetricsService(), 
            customServiceName != null ? customServiceName : serviceName
        );
        logger.debug("Created custom BusinessMetricsCollector for service: {}", customServiceName);
        return collector;
    }
    
    /**
     * Obtiene información de configuración de la factory
     */
    public FactoryInfo getFactoryInfo() {
        return new FactoryInfo(
            serviceName,
            serviceVersion,
            metricsService != null,
            lambdaMetricsCollector != null,
            httpMetricsCollector != null,
            databaseMetricsCollector != null,
            businessMetricsCollector != null
        );
    }
    
    /**
     * Limpia todas las instancias singleton (útil para testing)
     */
    public void clearInstances() {
        synchronized (this) {
            if (metricsService instanceof OpenTelemetryMetricsService) {
                ((OpenTelemetryMetricsService) metricsService).clearInstrumentCache();
            }
            
            if (lambdaMetricsCollector != null) {
                LambdaMetricsCollector.clearColdStartCache();
            }
            
            if (httpMetricsCollector != null) {
                httpMetricsCollector.clearRateCounters();
            }
            
            if (databaseMetricsCollector != null) {
                databaseMetricsCollector.clearCounters();
            }
            
            if (businessMetricsCollector != null) {
                businessMetricsCollector.clearAccumulators();
            }
            
            metricsService = null;
            lambdaMetricsCollector = null;
            httpMetricsCollector = null;
            databaseMetricsCollector = null;
            businessMetricsCollector = null;
            
            logger.debug("Cleared all MetricsFactory instances for service: {}", serviceName);
        }
    }
    
    /**
     * Obtiene estadísticas de todas las instancias activas
     */
    public java.util.Map<String, Object> getAllStats() {
        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        
        stats.put("factory_info", getFactoryInfo());
        
        if (metricsService instanceof OpenTelemetryMetricsService) {
            stats.put("metrics_service", ((OpenTelemetryMetricsService) metricsService).getInstrumentCacheStats());
        }
        
        if (lambdaMetricsCollector != null) {
            stats.put("lambda_metrics", LambdaMetricsCollector.getColdStartCacheStats());
        }
        
        if (httpMetricsCollector != null) {
            stats.put("http_metrics", httpMetricsCollector.getRateCounterStats());
        }
        
        if (databaseMetricsCollector != null) {
            stats.put("database_metrics", databaseMetricsCollector.getCounterStats());
        }
        
        if (businessMetricsCollector != null) {
            stats.put("business_metrics", businessMetricsCollector.getAccumulatorStats());
        }
        
        return stats;
    }
    
    /**
     * Factory method estático para crear instancias con configuración por defecto
     */
    public static MetricsFactory create(OpenTelemetry openTelemetry, String serviceName) {
        return create(openTelemetry, serviceName, "1.0.0");
    }
    
    /**
     * Factory method estático para crear instancias con configuración personalizada
     */
    public static MetricsFactory create(OpenTelemetry openTelemetry, String serviceName, String serviceVersion) {
        if (openTelemetry == null) {
            throw new IllegalArgumentException("OpenTelemetry instance cannot be null");
        }
        if (serviceName == null || serviceName.trim().isEmpty()) {
            throw new IllegalArgumentException("Service name cannot be null or empty");
        }
        if (serviceVersion == null || serviceVersion.trim().isEmpty()) {
            serviceVersion = "1.0.0";
        }
        
        return new MetricsFactory(openTelemetry, serviceName.trim(), serviceVersion.trim());
    }
    
    /**
     * Crear instancia usando OpenTelemetryManager centralizado
     */
    public static MetricsFactory fromManager() {
        if (!OpenTelemetryManager.isInitialized()) {
            throw new IllegalStateException("OpenTelemetryManager not initialized. Call OpenTelemetryManager.initialize() first.");
        }
        
        var manager = OpenTelemetryManager.getInstance();
        var config = manager.getConfig();
        
        return new MetricsFactory(
            manager.getOpenTelemetry(), 
            config.getServiceName(), 
            config.getServiceVersion()
        );
    }
    
    /**
     * Crear instancia usando OpenTelemetryManager con nombres personalizados
     */
    public static MetricsFactory fromManager(String customServiceName, String customServiceVersion) {
        if (!OpenTelemetryManager.isInitialized()) {
            throw new IllegalStateException("OpenTelemetryManager not initialized. Call OpenTelemetryManager.initialize() first.");
        }
        
        var manager = OpenTelemetryManager.getInstance();
        
        return new MetricsFactory(
            manager.getOpenTelemetry(), 
            customServiceName != null ? customServiceName : manager.getConfig().getServiceName(), 
            customServiceVersion != null ? customServiceVersion : manager.getConfig().getServiceVersion()
        );
    }
    
    /**
     * Clase interna para información de la factory
     */
    public static class FactoryInfo {
        public final String serviceName;
        public final String serviceVersion;
        public final boolean metricsServiceInitialized;
        public final boolean lambdaCollectorInitialized;
        public final boolean httpCollectorInitialized;
        public final boolean databaseCollectorInitialized;
        public final boolean businessCollectorInitialized;
        
        FactoryInfo(String serviceName, String serviceVersion, boolean metricsServiceInitialized,
                   boolean lambdaCollectorInitialized, boolean httpCollectorInitialized,
                   boolean databaseCollectorInitialized, boolean businessCollectorInitialized) {
            this.serviceName = serviceName;
            this.serviceVersion = serviceVersion;
            this.metricsServiceInitialized = metricsServiceInitialized;
            this.lambdaCollectorInitialized = lambdaCollectorInitialized;
            this.httpCollectorInitialized = httpCollectorInitialized;
            this.databaseCollectorInitialized = databaseCollectorInitialized;
            this.businessCollectorInitialized = businessCollectorInitialized;
        }
        
        @Override
        public String toString() {
            return String.format("FactoryInfo{serviceName='%s', serviceVersion='%s', " +
                                "initialized: metrics=%s, lambda=%s, http=%s, database=%s, business=%s}",
                                serviceName, serviceVersion, metricsServiceInitialized,
                                lambdaCollectorInitialized, httpCollectorInitialized,
                                databaseCollectorInitialized, businessCollectorInitialized);
        }
    }
}