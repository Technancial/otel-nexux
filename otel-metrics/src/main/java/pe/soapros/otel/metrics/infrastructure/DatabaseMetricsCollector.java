package pe.soapros.otel.metrics.infrastructure;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import pe.soapros.otel.metrics.domain.MetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Colector especializado de métricas para operaciones de base de datos
 * y servicios externos. Captura métricas de latencia, conexiones, errores,
 * y rendimiento de queries.
 */
public class DatabaseMetricsCollector {
    
    private static final Logger logger = LoggerFactory.getLogger(DatabaseMetricsCollector.class);
    
    private final MetricsService metricsService;
    private final String serviceName;
    
    // Contadores para tracking de conexiones y queries  
    private final ConcurrentHashMap<String, AtomicLong> connectionCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> queryCounts = new ConcurrentHashMap<>();
    private final ThreadLocal<DatabaseOperationMetrics> currentOperation = new ThreadLocal<>();
    
    public DatabaseMetricsCollector(MetricsService metricsService, String serviceName) {
        this.metricsService = metricsService;
        this.serviceName = serviceName;
    }
    
    /**
     * Inicia la medición de una operación de base de datos
     */
    public void startDatabaseOperation(String operation, String table, String database, String connectionPool) {
        DatabaseOperationMetrics dbOperation = new DatabaseOperationMetrics(
            operation,
            table,
            database,
            connectionPool,
            Instant.now()
        );
        
        currentOperation.set(dbOperation);
        
        // Counter de operaciones iniciadas
        Attributes startAttributes = buildDatabaseAttributes(operation, table, database, connectionPool, true, null);
        
        metricsService.incrementCounter(
            "db.operations.started.total", 
            "Total database operations started", 
            1L, 
            startAttributes
        );
        
        // Actualizar contadores de queries
        String queryKey = database + ":" + table + ":" + operation;
        queryCounts.computeIfAbsent(queryKey, k -> new AtomicLong(0)).incrementAndGet();
        
        logger.debug("Started database operation metrics collection for {} on {}.{}", operation, database, table);
    }
    
    /**
     * Finaliza la medición de una operación de base de datos
     */
    public void endDatabaseOperation(boolean success, long recordsAffected, Throwable error) {
        DatabaseOperationMetrics operation = currentOperation.get();
        if (operation == null) {
            logger.warn("No database operation metrics found for current thread");
            return;
        }
        
        try {
            Duration duration = Duration.between(operation.startTime, Instant.now());
            
            Attributes endAttributes = buildDatabaseAttributes(
                operation.operation, 
                operation.table, 
                operation.database, 
                operation.connectionPool,
                success, 
                error
            );
            
            // Métricas básicas de database
            recordBasicDatabaseMetrics(operation, duration, success, recordsAffected, endAttributes);
            
            // Métricas de errores
            if (!success || error != null) {
                recordDatabaseErrorMetrics(operation, error, endAttributes);
            }
            
            // Métricas de performance
            recordDatabasePerformanceMetrics(operation, duration, recordsAffected, endAttributes);
            
            logger.debug("Completed database operation metrics for {} on {}.{} - {}ms - success: {}", 
                         operation.operation, operation.database, operation.table, duration.toMillis(), success);
            
        } finally {
            currentOperation.remove();
        }
    }
    
    /**
     * Registra métricas de conexión a base de datos
     */
    public void recordConnectionMetrics(String database, String connectionPool, int activeConnections, 
                                      int idleConnections, int maxConnections) {
        
        Map<String, String> connectionAttributes = Map.of(
            "db.name", database,
            "db.connection_pool", connectionPool,
            "service.name", serviceName
        );
        
        // Gauge de conexiones activas
        metricsService.recordGauge(
            "db.connections.active", 
            "Active database connections", 
            activeConnections, 
            connectionAttributes
        );
        
        // Gauge de conexiones idle
        metricsService.recordGauge(
            "db.connections.idle", 
            "Idle database connections", 
            idleConnections, 
            connectionAttributes
        );
        
        // Gauge de utilización del pool
        double poolUtilization = maxConnections > 0 ? (double) activeConnections / maxConnections : 0.0;
        metricsService.recordGauge(
            "db.connections.pool_utilization", 
            "Database connection pool utilization ratio", 
            poolUtilization, 
            connectionAttributes
        );
        
        // Warning si el pool está casi lleno
        if (poolUtilization > 0.8) {
            metricsService.incrementCounter(
                "db.connections.pool_warning.total", 
                "Database connection pool usage warnings", 
                1L, 
                connectionAttributes
            );
        }
        
        // Actualizar contadores de conexión
        String poolKey = database + ":" + connectionPool;
        connectionCounts.put(poolKey, new AtomicLong(activeConnections));
    }
    
    /**
     * Registra métricas de servicios externos (APIs REST, SOAP, etc.)
     */
    public void recordExternalServiceCall(String serviceName, String operation, String endpoint, 
                                        Duration duration, boolean success, int statusCode, Throwable error) {
        
        Attributes serviceAttributes = Attributes.builder()
            .put(AttributeKey.stringKey("external.service"), serviceName)
            .put(AttributeKey.stringKey("external.operation"), operation)
            .put(AttributeKey.stringKey("external.endpoint"), endpoint)
            .put(AttributeKey.booleanKey("external.success"), success)
            .put(AttributeKey.stringKey("service.name"), this.serviceName)
            .build();
        
        if (statusCode > 0) {
            serviceAttributes = serviceAttributes.toBuilder()
                .put(AttributeKey.longKey("external.status_code"), statusCode)
                .build();
        }
        
        // Counter de llamadas a servicios externos
        metricsService.incrementCounter(
            "external.service.calls.total", 
            "Total external service calls", 
            1L, 
            serviceAttributes
        );
        
        // Histogram de duración
        metricsService.recordHistogram(
            "external.service.duration", 
            "External service call duration in milliseconds", 
            duration.toNanos() / 1_000_000.0, 
            serviceAttributes
        );
        
        // Métricas de éxito/error
        if (success) {
            metricsService.incrementCounter(
                "external.service.calls.success.total", 
                "Successful external service calls", 
                1L, 
                serviceAttributes
            );
        } else {
            metricsService.incrementCounter(
                "external.service.calls.errors.total", 
                "Failed external service calls", 
                1L, 
                serviceAttributes
            );
            
            if (error != null) {
                recordExternalServiceErrorMetrics(serviceName, operation, error);
            }
        }
        
        // Métricas de disponibilidad por servicio
        recordServiceAvailabilityMetrics(serviceName, success);
    }
    
    /**
     * Registra métricas básicas de database
     */
    private void recordBasicDatabaseMetrics(DatabaseOperationMetrics operation, Duration duration, 
                                          boolean success, long recordsAffected, Attributes attributes) {
        
        // Counter de operaciones completadas
        metricsService.incrementCounter(
            "db.operations.total", 
            "Total database operations", 
            1L, 
            attributes
        );
        
        // Histogram de duración
        metricsService.recordHistogram(
            "db.operation.duration", 
            "Database operation duration in milliseconds", 
            duration.toNanos() / 1_000_000.0, 
            attributes
        );
        
        // Histogram de registros afectados
        if (recordsAffected >= 0) {
            metricsService.recordHistogram(
                "db.operation.records_affected", 
                "Records affected by database operation", 
                recordsAffected, 
                attributes
            );
        }
        
        // Counter de éxito/error
        if (success) {
            metricsService.incrementCounter(
                "db.operations.success.total", 
                "Successful database operations", 
                1L, 
                attributes
            );
        } else {
            metricsService.incrementCounter(
                "db.operations.errors.total", 
                "Failed database operations", 
                1L, 
                attributes
            );
        }
    }
    
    /**
     * Registra métricas de errores de database
     */
    private void recordDatabaseErrorMetrics(DatabaseOperationMetrics operation, Throwable error, Attributes attributes) {
        
        if (error != null) {
            Map<String, String> errorAttributes = Map.of(
                "db.operation", operation.operation,
                "db.table", operation.table,
                "db.name", operation.database,
                "error.type", error.getClass().getSimpleName(),
                "service.name", serviceName
            );
            
            // Counter por tipo de error
            metricsService.incrementCounter(
                "db.errors.by_type.total", 
                "Database errors by type", 
                1L, 
                errorAttributes
            );
            
            // Métricas específicas por tipo de error
            recordSpecificDatabaseErrorMetrics(error, errorAttributes);
        }
    }
    
    /**
     * Registra métricas de performance de database
     */
    private void recordDatabasePerformanceMetrics(DatabaseOperationMetrics operation, Duration duration, 
                                                long recordsAffected, Attributes attributes) {
        
        double durationMs = duration.toNanos() / 1_000_000.0;
        
        // Clasificar por rangos de latencia
        String latencyBucket = classifyDatabaseLatency(durationMs);
        Attributes latencyAttributes = attributes.toBuilder()
            .put(AttributeKey.stringKey("latency.bucket"), latencyBucket)
            .build();
            
        metricsService.incrementCounter(
            "db.operations.by_latency.total", 
            "Database operations by latency bucket", 
            1L, 
            latencyAttributes
        );
        
        // Métricas de throughput (registros por segundo)
        if (recordsAffected > 0 && durationMs > 0) {
            double recordsPerSecond = (recordsAffected * 1000.0) / durationMs;
            
            metricsService.recordHistogram(
                "db.operation.throughput_records_per_second", 
                "Database operation throughput in records per second", 
                recordsPerSecond, 
                attributes
            );
        }
        
        // Rate por tabla y operación
        recordTableOperationRateMetrics(operation);
        
        // Métricas de SLA de database
        recordDatabaseSlaMetrics(operation, durationMs);
    }
    
    /**
     * Registra métricas específicas por tipo de error de database
     */
    private void recordSpecificDatabaseErrorMetrics(Throwable error, Map<String, String> baseAttributes) {
        
        if (isConnectionError(error)) {
            metricsService.incrementCounter(
                "db.errors.connection.total", 
                "Database connection errors", 
                1L, 
                baseAttributes
            );
        } else if (isTimeoutError(error)) {
            metricsService.incrementCounter(
                "db.errors.timeout.total", 
                "Database timeout errors", 
                1L, 
                baseAttributes
            );
        } else if (isSqlSyntaxError(error)) {
            metricsService.incrementCounter(
                "db.errors.syntax.total", 
                "Database SQL syntax errors", 
                1L, 
                baseAttributes
            );
        } else if (isConstraintViolationError(error)) {
            metricsService.incrementCounter(
                "db.errors.constraint_violation.total", 
                "Database constraint violation errors", 
                1L, 
                baseAttributes
            );
        } else if (isDeadlockError(error)) {
            metricsService.incrementCounter(
                "db.errors.deadlock.total", 
                "Database deadlock errors", 
                1L, 
                baseAttributes
            );
        }
    }
    
    /**
     * Registra métricas de errores de servicios externos
     */
    private void recordExternalServiceErrorMetrics(String serviceName, String operation, Throwable error) {
        Map<String, String> errorAttributes = Map.of(
            "external.service", serviceName,
            "external.operation", operation,
            "error.type", error.getClass().getSimpleName(),
            "service.name", this.serviceName
        );
        
        metricsService.incrementCounter(
            "external.service.errors.by_type.total", 
            "External service errors by type", 
            1L, 
            errorAttributes
        );
        
        // Errores específicos de servicios externos
        if (isNetworkError(error)) {
            metricsService.incrementCounter(
                "external.service.errors.network.total", 
                "External service network errors", 
                1L, 
                errorAttributes
            );
        } else if (isTimeoutError(error)) {
            metricsService.incrementCounter(
                "external.service.errors.timeout.total", 
                "External service timeout errors", 
                1L, 
                errorAttributes
            );
        }
    }
    
    /**
     * Registra métricas de disponibilidad de servicios
     */
    private void recordServiceAvailabilityMetrics(String serviceName, boolean success) {
        Map<String, String> availabilityAttributes = Map.of(
            "external.service", serviceName,
            "service.name", this.serviceName
        );
        
        // Gauge de disponibilidad (simplificado: 1 = up, 0 = down)
        metricsService.recordGauge(
            "external.service.availability", 
            "External service availability", 
            success ? 1.0 : 0.0, 
            availabilityAttributes
        );
    }
    
    /**
     * Registra métricas de rate por tabla y operación
     */
    private void recordTableOperationRateMetrics(DatabaseOperationMetrics operation) {
        String queryKey = operation.database + ":" + operation.table + ":" + operation.operation;
        AtomicLong count = queryCounts.get(queryKey);
        
        if (count != null) {
            metricsService.recordGauge(
                "db.table.operation_count", 
                "Current operation count for database table", 
                count.get(), 
                Map.of(
                    "db.operation", operation.operation,
                    "db.table", operation.table,
                    "db.name", operation.database,
                    "service.name", serviceName
                )
            );
        }
    }
    
    /**
     * Registra métricas de SLA de database
     */
    private void recordDatabaseSlaMetrics(DatabaseOperationMetrics operation, double durationMs) {
        // SLA típicos: SELECT < 100ms, INSERT/UPDATE < 200ms, DELETE < 300ms
        boolean withinSla = false;
        
        switch (operation.operation.toUpperCase()) {
            case "SELECT":
            case "FIND":
                withinSla = durationMs < 100.0;
                break;
            case "INSERT":
            case "UPDATE":
            case "SAVE":
                withinSla = durationMs < 200.0;
                break;
            case "DELETE":
                withinSla = durationMs < 300.0;
                break;
            default:
                withinSla = durationMs < 500.0;
        }
        
        Map<String, String> slaAttributes = Map.of(
            "db.operation", operation.operation,
            "db.table", operation.table,
            "db.name", operation.database,
            "service.name", serviceName
        );
        
        if (withinSla) {
            metricsService.incrementCounter(
                "db.operations.within_sla.total", 
                "Database operations within SLA", 
                1L, 
                slaAttributes
            );
        } else {
            metricsService.incrementCounter(
                "db.operations.sla_violation.total", 
                "Database operations that violated SLA", 
                1L, 
                slaAttributes
            );
        }
    }
    
    // ===== MÉTODOS UTILITARIOS =====
    
    private Attributes buildDatabaseAttributes(String operation, String table, String database, 
                                             String connectionPool, boolean success, Throwable error) {
        var builder = Attributes.builder()
            .put(AttributeKey.stringKey("db.operation"), operation)
            .put(AttributeKey.stringKey("db.table"), table)
            .put(AttributeKey.stringKey("db.name"), database)
            .put(AttributeKey.stringKey("service.name"), serviceName);
            
        if (connectionPool != null) {
            builder.put(AttributeKey.stringKey("db.connection_pool"), connectionPool);
        }
        
        builder.put(AttributeKey.booleanKey("db.success"), success);
        
        if (error != null) {
            builder.put(AttributeKey.stringKey("error.type"), error.getClass().getSimpleName());
        }
        
        return builder.build();
    }
    
    private String classifyDatabaseLatency(double durationMs) {
        if (durationMs < 10) return "ultra_fast";
        if (durationMs < 50) return "fast";
        if (durationMs < 100) return "normal";
        if (durationMs < 500) return "slow";
        if (durationMs < 1000) return "very_slow";
        return "extremely_slow";
    }
    
    private boolean isConnectionError(Throwable error) {
        String message = error.getMessage();
        String className = error.getClass().getSimpleName().toLowerCase();
        return className.contains("connection") || 
               (message != null && message.toLowerCase().contains("connection"));
    }
    
    private boolean isTimeoutError(Throwable error) {
        return error instanceof InterruptedException ||
               (error.getMessage() != null && error.getMessage().toLowerCase().contains("timeout"));
    }
    
    private boolean isSqlSyntaxError(Throwable error) {
        String className = error.getClass().getSimpleName().toLowerCase();
        String message = error.getMessage();
        return className.contains("syntax") || 
               (message != null && (message.toLowerCase().contains("syntax") || 
                                  message.toLowerCase().contains("sql error")));
    }
    
    private boolean isConstraintViolationError(Throwable error) {
        String className = error.getClass().getSimpleName().toLowerCase();
        String message = error.getMessage();
        return className.contains("constraint") || 
               (message != null && (message.toLowerCase().contains("constraint") ||
                                  message.toLowerCase().contains("unique") ||
                                  message.toLowerCase().contains("foreign key")));
    }
    
    private boolean isDeadlockError(Throwable error) {
        String message = error.getMessage();
        return message != null && message.toLowerCase().contains("deadlock");
    }
    
    private boolean isNetworkError(Throwable error) {
        String className = error.getClass().getSimpleName().toLowerCase();
        return className.contains("network") || 
               className.contains("socket") ||
               className.contains("connection");
    }
    
    /**
     * Registra métricas personalizadas para operaciones específicas de database
     */
    public void recordCustomDatabaseMetric(String metricName, double value, Map<String, String> additionalAttributes) {
        Map<String, String> attributes = new java.util.HashMap<>(additionalAttributes);
        attributes.put("service.name", serviceName);
        
        DatabaseOperationMetrics operation = currentOperation.get();
        if (operation != null) {
            attributes.put("db.operation", operation.operation);
            attributes.put("db.table", operation.table);
            attributes.put("db.name", operation.database);
        }
        
        metricsService.recordHistogram(
            "db.custom." + metricName, 
            "Custom database metric: " + metricName, 
            value, 
            attributes
        );
    }
    
    /**
     * Limpia los contadores (útil para testing)
     */
    public void clearCounters() {
        connectionCounts.clear();
        queryCounts.clear();
    }
    
    /**
     * Obtiene estadísticas de los contadores
     */
    public Map<String, Object> getCounterStats() {
        Map<String, Object> stats = new java.util.HashMap<>();
        
        Map<String, Long> connections = new java.util.HashMap<>();
        connectionCounts.forEach((pool, count) -> connections.put(pool, count.get()));
        stats.put("connections", connections);
        
        Map<String, Long> queries = new java.util.HashMap<>();
        queryCounts.forEach((query, count) -> queries.put(query, count.get()));
        stats.put("queries", queries);
        
        return stats;
    }
    
    /**
     * Clase interna para mantener métricas de operación actual
     */
    private static class DatabaseOperationMetrics {
        final String operation;
        final String table;
        final String database;
        final String connectionPool;
        final Instant startTime;
        
        DatabaseOperationMetrics(String operation, String table, String database, 
                               String connectionPool, Instant startTime) {
            this.operation = operation;
            this.table = table;
            this.database = database;
            this.connectionPool = connectionPool;
            this.startTime = startTime;
        }
    }
}