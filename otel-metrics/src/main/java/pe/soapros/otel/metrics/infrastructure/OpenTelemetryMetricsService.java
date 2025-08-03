package pe.soapros.otel.metrics.infrastructure;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.*;
import pe.soapros.otel.metrics.domain.MetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementación de OpenTelemetry para el servicio de métricas.
 * Proporciona funcionalidades completas para counters, gauges, histograms
 * y métricas especializadas para microservicios y Lambda.
 */
public class OpenTelemetryMetricsService implements MetricsService {
    
    private static final Logger logger = LoggerFactory.getLogger(OpenTelemetryMetricsService.class);
    
    private final Meter meter;
    private final String serviceName;
    
    // Cache para reutilizar instrumentos métricos
    private final ConcurrentHashMap<String, LongCounter> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DoubleGauge> gauges = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DoubleHistogram> histograms = new ConcurrentHashMap<>();
    
    public OpenTelemetryMetricsService(Meter meter, String serviceName) {
        this.meter = meter;
        this.serviceName = serviceName;
    }

    // ==================== COUNTERS ====================
    
    @Override
    public void incrementCounter(String name, String description, Map<String, String> attributes) {
        incrementCounter(name, description, 1L, attributes);
    }
    
    @Override
    public void incrementCounter(String name, String description, long value, Map<String, String> attributes) {
        Attributes otelAttributes = mapToAttributes(attributes);
        incrementCounter(name, description, value, otelAttributes);
    }
    
    @Override
    public void incrementCounter(String name, String description, long value, Attributes attributes) {
        try {
            LongCounter counter = counters.computeIfAbsent(name, k -> 
                meter.counterBuilder(name)
                    .setDescription(description)
                    .setUnit("1")
                    .build()
            );
            
            counter.add(value, attributes);
            
        } catch (Exception e) {
            logger.error("Error incrementing counter {}: {}", name, e.getMessage(), e);
        }
    }

    // ==================== GAUGES ====================
    
    @Override
    public void recordGauge(String name, String description, double value, Map<String, String> attributes) {
        Attributes otelAttributes = mapToAttributes(attributes);
        recordGauge(name, description, value, otelAttributes);
    }
    
    @Override
    public void recordGauge(String name, String description, double value, Attributes attributes) {
        try {
            DoubleGauge gauge = gauges.computeIfAbsent(name, k ->
                meter.gaugeBuilder(name)
                    .setDescription(description)
                    .build()
            );
            
            // Para gauges, necesitamos usar un callback o establecer el valor
            // En este caso, usaremos un histograma como alternativa para valores puntuales
            DoubleHistogram histogram = histograms.computeIfAbsent(name + "_gauge", k ->
                meter.histogramBuilder(name + "_gauge")
                    .setDescription(description + " (as gauge)")
                    .build()
            );
            
            histogram.record(value, attributes);
            
        } catch (Exception e) {
            logger.error("Error recording gauge {}: {}", name, e.getMessage(), e);
        }
    }

    // ==================== HISTOGRAMS ====================
    
    @Override
    public void recordDuration(String name, String description, Duration duration, Map<String, String> attributes) {
        double durationMs = duration.toNanos() / 1_000_000.0; // Convert to milliseconds
        recordHistogram(name, description, durationMs, attributes);
    }
    
    @Override
    public void recordHistogram(String name, String description, double value, Map<String, String> attributes) {
        Attributes otelAttributes = mapToAttributes(attributes);
        recordHistogram(name, description, value, otelAttributes);
    }
    
    @Override
    public void recordHistogram(String name, String description, double value, Attributes attributes) {
        try {
            DoubleHistogram histogram = histograms.computeIfAbsent(name, k ->
                meter.histogramBuilder(name)
                    .setDescription(description)
                    .setUnit("ms")
                    .build()
            );
            
            histogram.record(value, attributes);
            
        } catch (Exception e) {
            logger.error("Error recording histogram {}: {}", name, e.getMessage(), e);
        }
    }

    // ==================== MÉTRICAS DE CONVENIENCIA ====================
    
    @Override
    public void recordHttpRequest(String method, String route, int statusCode, Duration duration, 
                                 long requestSize, long responseSize) {
        
        Attributes httpAttributes = Attributes.builder()
            .put(AttributeKey.stringKey("http.method"), method)
            .put(AttributeKey.stringKey("http.route"), route)
            .put(AttributeKey.longKey("http.status_code"), statusCode)
            .put(AttributeKey.stringKey("http.status_class"), getStatusClass(statusCode))
            .put(AttributeKey.stringKey("service.name"), serviceName)
            .build();
        
        // Counter de requests totales
        incrementCounter("http.requests.total", "Total HTTP requests", 1L, httpAttributes);
        
        // Histogram de duración
        recordHistogram("http.request.duration", "HTTP request duration", 
            duration.toNanos() / 1_000_000.0, httpAttributes);
        
        // Histogram de tamaños
        if (requestSize > 0) {
            recordHistogram("http.request.size", "HTTP request size in bytes", 
                requestSize, httpAttributes);
        }
        
        if (responseSize > 0) {
            recordHistogram("http.response.size", "HTTP response size in bytes", 
                responseSize, httpAttributes);
        }
        
        // Counter de errores si aplica
        if (statusCode >= 400) {
            incrementCounter("http.requests.errors.total", "Total HTTP errors", 1L, httpAttributes);
        }
    }
    
    @Override
    public void recordDatabaseOperation(String operation, String table, Duration duration, boolean success) {
        
        Attributes dbAttributes = Attributes.builder()
            .put(AttributeKey.stringKey("db.operation"), operation)
            .put(AttributeKey.stringKey("db.table"), table)
            .put(AttributeKey.booleanKey("db.success"), success)
            .put(AttributeKey.stringKey("service.name"), serviceName)
            .build();
        
        // Counter de operaciones totales
        incrementCounter("db.operations.total", "Total database operations", 1L, dbAttributes);
        
        // Histogram de duración
        recordHistogram("db.operation.duration", "Database operation duration", 
            duration.toNanos() / 1_000_000.0, dbAttributes);
        
        // Counter de errores si aplica
        if (!success) {
            incrementCounter("db.operations.errors.total", "Total database errors", 1L, dbAttributes);
        }
    }
    
    @Override
    public void recordLambdaExecution(String functionName, Duration duration, boolean coldStart, 
                                     long memoryUsed, boolean success) {
        
        Attributes lambdaAttributes = Attributes.builder()
            .put(AttributeKey.stringKey("lambda.function_name"), functionName)
            .put(AttributeKey.booleanKey("lambda.cold_start"), coldStart)
            .put(AttributeKey.booleanKey("lambda.success"), success)
            .put(AttributeKey.stringKey("service.name"), serviceName)
            .build();
        
        // Counter de invocaciones totales
        incrementCounter("lambda.invocations.total", "Total Lambda invocations", 1L, lambdaAttributes);
        
        // Histogram de duración
        recordHistogram("lambda.execution.duration", "Lambda execution duration", 
            duration.toNanos() / 1_000_000.0, lambdaAttributes);
        
        // Gauge de memoria utilizada
        recordGauge("lambda.memory.used", "Lambda memory used in MB", memoryUsed, 
            Map.of("lambda.function_name", functionName));
        
        // Counter de cold starts
        if (coldStart) {
            incrementCounter("lambda.cold_starts.total", "Total Lambda cold starts", 1L, lambdaAttributes);
        }
        
        // Counter de errores si aplica
        if (!success) {
            incrementCounter("lambda.invocations.errors.total", "Total Lambda errors", 1L, lambdaAttributes);
        }
    }
    
    @Override
    public void recordError(String errorType, String source, Map<String, String> attributes) {
        Map<String, String> errorAttributes = new java.util.HashMap<>(attributes);
        errorAttributes.put("error.type", errorType);
        errorAttributes.put("error.source", source);
        errorAttributes.put("service.name", serviceName);
        
        incrementCounter("errors.total", "Total errors", 1L, errorAttributes);
    }
    
    @Override
    public void recordResourceUsage(String resourceType, double usage, String unit, Map<String, String> attributes) {
        Map<String, String> resourceAttributes = new java.util.HashMap<>(attributes);
        resourceAttributes.put("resource.type", resourceType);
        resourceAttributes.put("resource.unit", unit);
        resourceAttributes.put("service.name", serviceName);
        
        recordGauge("resource.usage", "Resource usage", usage, resourceAttributes);
    }

    // ==================== MÉTRICAS DE NEGOCIO ====================
    
    @Override
    public void recordBusinessMetric(String metricName, String description, double value, String unit, 
                                   Map<String, String> attributes) {
        Map<String, String> businessAttributes = new java.util.HashMap<>(attributes);
        businessAttributes.put("metric.unit", unit);
        businessAttributes.put("service.name", serviceName);
        
        recordHistogram("business." + metricName, description, value, businessAttributes);
    }
    
    @Override
    public void recordBusinessTransaction(String transactionType, String status, Duration duration, 
                                        double amount, String currency) {
        
        Attributes transactionAttributes = Attributes.builder()
            .put(AttributeKey.stringKey("transaction.type"), transactionType)
            .put(AttributeKey.stringKey("transaction.status"), status)
            .put(AttributeKey.stringKey("transaction.currency"), currency)
            .put(AttributeKey.stringKey("service.name"), serviceName)
            .build();
        
        // Counter de transacciones
        incrementCounter("business.transactions.total", "Total business transactions", 1L, transactionAttributes);
        
        // Histogram de duración
        recordHistogram("business.transaction.duration", "Business transaction duration", 
            duration.toNanos() / 1_000_000.0, transactionAttributes);
        
        // Histogram de monto
        recordHistogram("business.transaction.amount", "Business transaction amount", 
            amount, transactionAttributes);
        
        // Counter de transacciones exitosas/fallidas
        if ("success".equals(status)) {
            incrementCounter("business.transactions.success.total", "Successful business transactions", 1L, transactionAttributes);
        } else {
            incrementCounter("business.transactions.failed.total", "Failed business transactions", 1L, transactionAttributes);
        }
    }

    // ==================== MÉTODOS UTILITARIOS ====================
    
    private Attributes mapToAttributes(Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return Attributes.empty();
        }
        
        var builder = Attributes.builder();
        attributes.forEach((key, value) -> builder.put(AttributeKey.stringKey(key), value));
        
        // Agregar service name automáticamente
        builder.put(AttributeKey.stringKey("service.name"), serviceName);
        
        return builder.build();
    }
    
    private String getStatusClass(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) return "2xx";
        if (statusCode >= 300 && statusCode < 400) return "3xx";
        if (statusCode >= 400 && statusCode < 500) return "4xx";
        if (statusCode >= 500) return "5xx";
        return "1xx";
    }
    
    /**
     * Limpiar los caches de instrumentos (útil para testing)
     */
    public void clearInstrumentCache() {
        counters.clear();
        gauges.clear();
        histograms.clear();
    }
    
    /**
     * Obtener estadísticas del cache de instrumentos
     */
    public Map<String, Integer> getInstrumentCacheStats() {
        return Map.of(
            "counters", counters.size(),
            "gauges", gauges.size(),
            "histograms", histograms.size()
        );
    }
}