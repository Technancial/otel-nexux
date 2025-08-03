package pe.soapros.otel.metrics.domain;

import io.opentelemetry.api.common.Attributes;

import java.time.Duration;
import java.util.Map;

/**
 * Interfaz principal para el servicio de métricas que soporta los tipos básicos
 * de métricas más comunes en microservicios y funciones Lambda.
 */
public interface MetricsService {

    // ==================== COUNTERS ====================
    
    /**
     * Incrementa un contador por 1
     */
    void incrementCounter(String name, String description, Map<String, String> attributes);
    
    /**
     * Incrementa un contador por un valor específico
     */
    void incrementCounter(String name, String description, long value, Map<String, String> attributes);
    
    /**
     * Incrementa un contador con atributos OpenTelemetry
     */
    void incrementCounter(String name, String description, long value, Attributes attributes);

    // ==================== GAUGES ====================
    
    /**
     * Registra un valor de gauge (métrica que puede subir y bajar)
     */
    void recordGauge(String name, String description, double value, Map<String, String> attributes);
    
    /**
     * Registra un valor de gauge con atributos OpenTelemetry
     */
    void recordGauge(String name, String description, double value, Attributes attributes);

    // ==================== HISTOGRAMS ====================
    
    /**
     * Registra una duración en un histograma
     */
    void recordDuration(String name, String description, Duration duration, Map<String, String> attributes);
    
    /**
     * Registra un valor en un histograma
     */
    void recordHistogram(String name, String description, double value, Map<String, String> attributes);
    
    /**
     * Registra un valor en un histograma con atributos OpenTelemetry
     */
    void recordHistogram(String name, String description, double value, Attributes attributes);

    // ==================== MÉTRICAS DE CONVENIENCIA ====================
    
    /**
     * Registra métricas de una operación HTTP
     */
    void recordHttpRequest(String method, String route, int statusCode, Duration duration, long requestSize, long responseSize);
    
    /**
     * Registra métricas de una operación de base de datos
     */
    void recordDatabaseOperation(String operation, String table, Duration duration, boolean success);
    
    /**
     * Registra métricas de una función Lambda
     */
    void recordLambdaExecution(String functionName, Duration duration, boolean coldStart, long memoryUsed, boolean success);
    
    /**
     * Registra métricas de errores
     */
    void recordError(String errorType, String source, Map<String, String> attributes);
    
    /**
     * Registra métricas de uso de recursos
     */
    void recordResourceUsage(String resourceType, double usage, String unit, Map<String, String> attributes);

    // ==================== MÉTRICAS DE NEGOCIO ====================
    
    /**
     * Registra métricas de negocio personalizadas
     */
    void recordBusinessMetric(String metricName, String description, double value, String unit, Map<String, String> attributes);
    
    /**
     * Registra transacciones de negocio
     */
    void recordBusinessTransaction(String transactionType, String status, Duration duration, double amount, String currency);
}