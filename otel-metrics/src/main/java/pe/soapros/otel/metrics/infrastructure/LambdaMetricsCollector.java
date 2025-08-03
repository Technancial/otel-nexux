package pe.soapros.otel.metrics.infrastructure;

import com.amazonaws.services.lambda.runtime.Context;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import pe.soapros.otel.metrics.domain.MetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Colector especializado de métricas para funciones AWS Lambda.
 * Captura métricas automáticas de cold starts, duración, memoria, y errores.
 */
public class LambdaMetricsCollector {
    
    private static final Logger logger = LoggerFactory.getLogger(LambdaMetricsCollector.class);
    
    private final MetricsService metricsService;
    private final String functionName;
    private final String functionVersion;
    
    // Cache para detectar cold starts
    private static final ConcurrentHashMap<String, Boolean> functionWarmupCache = new ConcurrentHashMap<>();
    
    // Registro de métricas de ejecución
    private final ThreadLocal<ExecutionMetrics> currentExecution = new ThreadLocal<>();
    
    public LambdaMetricsCollector(MetricsService metricsService, String functionName, String functionVersion) {
        this.metricsService = metricsService;
        this.functionName = functionName;
        this.functionVersion = functionVersion;
    }
    
    /**
     * Inicia la medición de una ejecución Lambda
     */
    public void startExecution(Context context) {
        String requestId = context.getAwsRequestId();
        boolean isColdStart = detectColdStart(context);
        
        ExecutionMetrics execution = new ExecutionMetrics(
            requestId,
            functionName,
            functionVersion,
            isColdStart,
            Instant.now(),
            context.getRemainingTimeInMillis()
        );
        
        currentExecution.set(execution);
        
        // Métricas de inicio
        Attributes startAttributes = Attributes.builder()
            .put(AttributeKey.stringKey("lambda.function_name"), functionName)
            .put(AttributeKey.stringKey("lambda.function_version"), functionVersion)
            .put(AttributeKey.stringKey("lambda.request_id"), requestId)
            .put(AttributeKey.booleanKey("lambda.cold_start"), isColdStart)
            .build();
        
        // Counter de invocaciones
        metricsService.incrementCounter(
            "lambda.invocations.total", 
            "Total Lambda function invocations", 
            1L, 
            startAttributes
        );
        
        // Counter de cold starts
        if (isColdStart) {
            metricsService.incrementCounter(
                "lambda.cold_starts.total", 
                "Total Lambda cold starts", 
                1L, 
                startAttributes
            );
            
            logger.debug("Cold start detected for function: {} requestId: {}", functionName, requestId);
        }
        
        // Gauge de tiempo restante al inicio
        metricsService.recordGauge(
            "lambda.remaining_time_ms", 
            "Lambda remaining execution time at start in milliseconds", 
            context.getRemainingTimeInMillis(), 
            Map.of(
                "lambda.function_name", functionName,
                "lambda.request_id", requestId,
                "measurement.point", "start"
            )
        );
        
        logger.debug("Started Lambda execution metrics collection for function: {} requestId: {}", 
                     functionName, requestId);
    }
    
    /**
     * Finaliza la medición de una ejecución Lambda
     */
    public void endExecution(Context context, boolean success, Throwable error) {
        ExecutionMetrics execution = currentExecution.get();
        if (execution == null) {
            logger.warn("No execution metrics found for request: {}", context.getAwsRequestId());
            return;
        }
        
        try {
            Duration executionDuration = Duration.between(execution.startTime, Instant.now());
            long memoryUsed = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            long memoryUsedMB = memoryUsed / (1024 * 1024);
            
            Attributes endAttributes = Attributes.builder()
                .put(AttributeKey.stringKey("lambda.function_name"), functionName)
                .put(AttributeKey.stringKey("lambda.function_version"), functionVersion)
                .put(AttributeKey.stringKey("lambda.request_id"), execution.requestId)
                .put(AttributeKey.booleanKey("lambda.cold_start"), execution.isColdStart)
                .put(AttributeKey.booleanKey("lambda.success"), success)
                .build();
            
            // Histogram de duración de ejecución
            metricsService.recordHistogram(
                "lambda.execution.duration", 
                "Lambda function execution duration in milliseconds", 
                executionDuration.toNanos() / 1_000_000.0, 
                endAttributes
            );
            
            // Gauge de memoria utilizada
            metricsService.recordGauge(
                "lambda.memory.used_mb", 
                "Lambda memory used in MB", 
                memoryUsedMB, 
                Map.of(
                    "lambda.function_name", functionName,
                    "lambda.request_id", execution.requestId
                )
            );
            
            // Gauge de tiempo restante al final
            metricsService.recordGauge(
                "lambda.remaining_time_ms", 
                "Lambda remaining execution time at end in milliseconds", 
                context.getRemainingTimeInMillis(), 
                Map.of(
                    "lambda.function_name", functionName,
                    "lambda.request_id", execution.requestId,
                    "measurement.point", "end"
                )
            );
            
            // Métricas de memoria detalladas
            recordDetailedMemoryMetrics(execution.requestId);
            
            // Counter de éxito/error
            if (success) {
                metricsService.incrementCounter(
                    "lambda.invocations.success.total", 
                    "Successful Lambda invocations", 
                    1L, 
                    endAttributes
                );
            } else {
                metricsService.incrementCounter(
                    "lambda.invocations.errors.total", 
                    "Failed Lambda invocations", 
                    1L, 
                    endAttributes
                );
                
                // Métricas específicas de error
                if (error != null) {
                    recordErrorMetrics(execution, error);
                }
            }
            
            // Métricas de timeout si aplica
            recordTimeoutMetrics(context, execution, executionDuration);
            
            logger.debug("Completed Lambda execution metrics for function: {} requestId: {} duration: {}ms success: {}", 
                         functionName, execution.requestId, executionDuration.toMillis(), success);
            
        } finally {
            currentExecution.remove();
        }
    }
    
    /**
     * Registra métricas de timeout/tiempo restante
     */
    private void recordTimeoutMetrics(Context context, ExecutionMetrics execution, Duration executionDuration) {
        long remainingTimeMs = context.getRemainingTimeInMillis();
        long totalTimeMs = execution.initialRemainingTime;
        double timeUtilization = (double) executionDuration.toMillis() / totalTimeMs;
        
        // Gauge de utilización de tiempo
        metricsService.recordGauge(
            "lambda.time.utilization_ratio", 
            "Lambda time utilization ratio (0.0 to 1.0)", 
            timeUtilization, 
            Map.of(
                "lambda.function_name", functionName,
                "lambda.request_id", execution.requestId
            )
        );
        
        // Warning si está cerca del timeout
        if (remainingTimeMs < 1000) { // Menos de 1 segundo restante
            metricsService.incrementCounter(
                "lambda.timeout.warnings.total", 
                "Lambda functions close to timeout", 
                1L, 
                Map.of(
                    "lambda.function_name", functionName,
                    "warning.type", "near_timeout"
                )
            );
            
            logger.warn("Lambda function {} near timeout. Remaining: {}ms", functionName, remainingTimeMs);
        }
    }
    
    /**
     * Registra métricas detalladas de memoria
     */
    private void recordDetailedMemoryMetrics(String requestId) {
        Runtime runtime = Runtime.getRuntime();
        
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        
        Map<String, String> memoryAttributes = Map.of(
            "lambda.function_name", functionName,
            "lambda.request_id", requestId
        );
        
        // Gauge de memoria máxima disponible
        metricsService.recordGauge(
            "lambda.memory.max_mb", 
            "Lambda maximum available memory in MB", 
            maxMemory / (1024.0 * 1024.0), 
            memoryAttributes
        );
        
        // Gauge de memoria total asignada
        metricsService.recordGauge(
            "lambda.memory.total_mb", 
            "Lambda total allocated memory in MB", 
            totalMemory / (1024.0 * 1024.0), 
            memoryAttributes
        );
        
        // Gauge de memoria libre
        metricsService.recordGauge(
            "lambda.memory.free_mb", 
            "Lambda free memory in MB", 
            freeMemory / (1024.0 * 1024.0), 
            memoryAttributes
        );
        
        // Ratio de utilización de memoria
        double memoryUtilization = (double) usedMemory / maxMemory;
        metricsService.recordGauge(
            "lambda.memory.utilization_ratio", 
            "Lambda memory utilization ratio (0.0 to 1.0)", 
            memoryUtilization, 
            memoryAttributes
        );
    }
    
    /**
     * Registra métricas específicas de errores
     */
    private void recordErrorMetrics(ExecutionMetrics execution, Throwable error) {
        Map<String, String> errorAttributes = Map.of(
            "lambda.function_name", functionName,
            "lambda.request_id", execution.requestId,
            "error.type", error.getClass().getSimpleName(),
            "error.message", error.getMessage() != null ? error.getMessage() : "null"
        );
        
        // Counter por tipo de error
        metricsService.incrementCounter(
            "lambda.errors.by_type.total", 
            "Lambda errors by type", 
            1L, 
            errorAttributes
        );
        
        // Identificar errores críticos
        if (isTimeoutError(error)) {
            metricsService.incrementCounter(
                "lambda.timeout.actual.total", 
                "Lambda actual timeouts", 
                1L, 
                Map.of("lambda.function_name", functionName)
            );
        } else if (isOutOfMemoryError(error)) {
            metricsService.incrementCounter(
                "lambda.oom.total", 
                "Lambda out of memory errors", 
                1L, 
                Map.of("lambda.function_name", functionName)
            );
        }
    }
    
    /**
     * Detecta si esta es la primera ejecución (cold start)
     */
    private boolean detectColdStart(Context context) {
        String functionKey = functionName + ":" + functionVersion;
        return functionWarmupCache.putIfAbsent(functionKey, true) == null;
    }
    
    /**
     * Determina si el error es relacionado con timeout
     */
    private boolean isTimeoutError(Throwable error) {
        return error instanceof InterruptedException || 
               error.getMessage() != null && error.getMessage().toLowerCase().contains("timeout");
    }
    
    /**
     * Determina si el error es relacionado con memoria
     */
    private boolean isOutOfMemoryError(Throwable error) {
        return error instanceof OutOfMemoryError ||
               (error.getMessage() != null && error.getMessage().toLowerCase().contains("out of memory"));
    }
    
    /**
     * Registra métricas de performance para funciones Lambda específicas
     */
    public void recordCustomLambdaMetric(String metricName, double value, Map<String, String> additionalAttributes) {
        Map<String, String> attributes = new java.util.HashMap<>(additionalAttributes);
        attributes.put("lambda.function_name", functionName);
        attributes.put("lambda.function_version", functionVersion);
        
        ExecutionMetrics execution = currentExecution.get();
        if (execution != null) {
            attributes.put("lambda.request_id", execution.requestId);
            attributes.put("lambda.cold_start", String.valueOf(execution.isColdStart));
        }
        
        metricsService.recordHistogram(
            "lambda.custom." + metricName, 
            "Custom Lambda metric: " + metricName, 
            value, 
            attributes
        );
    }
    
    /**
     * Limpia el cache de cold start (útil para testing)
     */
    public static void clearColdStartCache() {
        functionWarmupCache.clear();
    }
    
    /**
     * Obtiene estadísticas del cache de cold start
     */
    public static Map<String, Integer> getColdStartCacheStats() {
        return Map.of("cached_functions", functionWarmupCache.size());
    }
    
    /**
     * Clase interna para mantener métricas de ejecución actual
     */
    private static class ExecutionMetrics {
        final String requestId;
        final String functionName;
        final String functionVersion;
        final boolean isColdStart;
        final Instant startTime;
        final long initialRemainingTime;
        
        ExecutionMetrics(String requestId, String functionName, String functionVersion, 
                        boolean isColdStart, Instant startTime, long initialRemainingTime) {
            this.requestId = requestId;
            this.functionName = functionName;
            this.functionVersion = functionVersion;
            this.isColdStart = isColdStart;
            this.startTime = startTime;
            this.initialRemainingTime = initialRemainingTime;
        }
    }
}