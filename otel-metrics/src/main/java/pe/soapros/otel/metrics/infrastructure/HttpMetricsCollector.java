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
 * Colector especializado de métricas para operaciones HTTP/REST.
 * Captura métricas de latencia, throughput, errores, y tamaños de request/response.
 */
public class HttpMetricsCollector {
    
    private static final Logger logger = LoggerFactory.getLogger(HttpMetricsCollector.class);
    
    private final MetricsService metricsService;
    private final String serviceName;
    
    // Contadores para rate tracking
    private final ConcurrentHashMap<String, AtomicLong> requestCounts = new ConcurrentHashMap<>();
    private final ThreadLocal<HttpRequestMetrics> currentRequest = new ThreadLocal<>();
    
    public HttpMetricsCollector(MetricsService metricsService, String serviceName) {
        this.metricsService = metricsService;
        this.serviceName = serviceName;
    }
    
    /**
     * Inicia la medición de una request HTTP
     */
    public void startRequest(String method, String route, String userAgent, String clientIp) {
        HttpRequestMetrics request = new HttpRequestMetrics(
            method,
            route,
            userAgent,
            clientIp,
            Instant.now()
        );
        
        currentRequest.set(request);
        
        // Counter de requests iniciadas
        Attributes startAttributes = buildHttpAttributes(method, route, 0, null);
        
        metricsService.incrementCounter(
            "http.requests.started.total", 
            "Total HTTP requests started", 
            1L, 
            startAttributes
        );
        
        // Actualizar rate tracking
        String routeKey = method + ":" + route;
        requestCounts.computeIfAbsent(routeKey, k -> new AtomicLong(0)).incrementAndGet();
        
        logger.debug("Started HTTP request metrics collection for {} {}", method, route);
    }
    
    /**
     * Finaliza la medición de una request HTTP
     */
    public void endRequest(int statusCode, long requestSize, long responseSize, Throwable error) {
        HttpRequestMetrics request = currentRequest.get();
        if (request == null) {
            logger.warn("No HTTP request metrics found for current thread");
            return;
        }
        
        try {
            Duration duration = Duration.between(request.startTime, Instant.now());
            boolean isError = statusCode >= 400 || error != null;
            
            Attributes endAttributes = buildHttpAttributes(
                request.method, 
                request.route, 
                statusCode, 
                error
            );
            
            // Métricas básicas de HTTP
            recordBasicHttpMetrics(request, duration, statusCode, requestSize, responseSize, endAttributes);
            
            // Métricas de errores
            if (isError) {
                recordHttpErrorMetrics(request, statusCode, error, endAttributes);
            }
            
            // Métricas de performance
            recordHttpPerformanceMetrics(request, duration, statusCode, endAttributes);
            
            // Métricas de tamaño
            recordHttpSizeMetrics(request, requestSize, responseSize, endAttributes);
            
            logger.debug("Completed HTTP request metrics for {} {} - {}ms - status: {}", 
                         request.method, request.route, duration.toMillis(), statusCode);
            
        } finally {
            currentRequest.remove();
        }
    }
    
    /**
     * Registra métricas básicas de HTTP
     */
    private void recordBasicHttpMetrics(HttpRequestMetrics request, Duration duration, int statusCode, 
                                      long requestSize, long responseSize, Attributes attributes) {
        
        // Counter de requests completadas
        metricsService.incrementCounter(
            "http.requests.total", 
            "Total HTTP requests", 
            1L, 
            attributes
        );
        
        // Histogram de duración
        metricsService.recordHistogram(
            "http.request.duration", 
            "HTTP request duration in milliseconds", 
            duration.toNanos() / 1_000_000.0, 
            attributes
        );
        
        // Counter por status class
        String statusClass = getStatusClass(statusCode);
        Attributes statusAttributes = attributes.toBuilder()
            .put(AttributeKey.stringKey("http.status_class"), statusClass)
            .build();
            
        metricsService.incrementCounter(
            "http.responses.by_status_class.total", 
            "HTTP responses by status class", 
            1L, 
            statusAttributes
        );
    }
    
    /**
     * Registra métricas de errores HTTP
     */
    private void recordHttpErrorMetrics(HttpRequestMetrics request, int statusCode, Throwable error, Attributes attributes) {
        
        // Counter de errores generales
        metricsService.incrementCounter(
            "http.requests.errors.total", 
            "Total HTTP request errors", 
            1L, 
            attributes
        );
        
        // Counter por tipo de error HTTP
        if (statusCode >= 400 && statusCode < 500) {
            metricsService.incrementCounter(
                "http.requests.client_errors.total", 
                "HTTP 4xx client errors", 
                1L, 
                attributes
            );
        } else if (statusCode >= 500) {
            metricsService.incrementCounter(
                "http.requests.server_errors.total", 
                "HTTP 5xx server errors", 
                1L, 
                attributes
            );
        }
        
        // Métricas de excepciones específicas
        if (error != null) {
            Map<String, String> errorAttributes = Map.of(
                "http.method", request.method,
                "http.route", request.route,
                "error.type", error.getClass().getSimpleName(),
                "service.name", serviceName
            );
            
            metricsService.incrementCounter(
                "http.requests.exceptions.total", 
                "HTTP requests that threw exceptions", 
                1L, 
                errorAttributes
            );
            
            // Métricas por tipo específico de excepción
            recordSpecificExceptionMetrics(error, errorAttributes);
        }
    }
    
    /**
     * Registra métricas de performance HTTP
     */
    private void recordHttpPerformanceMetrics(HttpRequestMetrics request, Duration duration, int statusCode, Attributes attributes) {
        
        // Histogram de latencia por percentiles
        double durationMs = duration.toNanos() / 1_000_000.0;
        
        // Clasificar por rangos de latencia
        String latencyBucket = classifyLatency(durationMs);
        Attributes latencyAttributes = attributes.toBuilder()
            .put(AttributeKey.stringKey("latency.bucket"), latencyBucket)
            .build();
            
        metricsService.incrementCounter(
            "http.requests.by_latency.total", 
            "HTTP requests by latency bucket", 
            1L, 
            latencyAttributes
        );
        
        // Métricas de SLA
        recordSlaMetrics(request, durationMs, statusCode);
        
        // Rate por ruta
        recordRouteRateMetrics(request);
    }
    
    /**
     * Registra métricas de tamaño HTTP
     */
    private void recordHttpSizeMetrics(HttpRequestMetrics request, long requestSize, long responseSize, Attributes attributes) {
        
        if (requestSize > 0) {
            metricsService.recordHistogram(
                "http.request.size_bytes", 
                "HTTP request size in bytes", 
                requestSize, 
                attributes
            );
            
            // Clasificar por tamaño de request
            String sizeClass = classifyRequestSize(requestSize);
            Attributes sizeAttributes = attributes.toBuilder()
                .put(AttributeKey.stringKey("request.size_class"), sizeClass)
                .build();
                
            metricsService.incrementCounter(
                "http.requests.by_size.total", 
                "HTTP requests by size class", 
                1L, 
                sizeAttributes
            );
        }
        
        if (responseSize > 0) {
            metricsService.recordHistogram(
                "http.response.size_bytes", 
                "HTTP response size in bytes", 
                responseSize, 
                attributes
            );
        }
        
        // Throughput total (request + response)
        if (requestSize > 0 || responseSize > 0) {
            metricsService.recordHistogram(
                "http.throughput.bytes_total", 
                "Total HTTP throughput (request + response) in bytes", 
                requestSize + responseSize, 
                attributes
            );
        }
    }
    
    /**
     * Registra métricas específicas por tipo de excepción
     */
    private void recordSpecificExceptionMetrics(Throwable error, Map<String, String> baseAttributes) {
        
        if (isTimeoutException(error)) {
            metricsService.incrementCounter(
                "http.requests.timeout.total", 
                "HTTP requests that timed out", 
                1L, 
                baseAttributes
            );
        } else if (isConnectionException(error)) {
            metricsService.incrementCounter(
                "http.requests.connection_error.total", 
                "HTTP requests with connection errors", 
                1L, 
                baseAttributes
            );
        } else if (isSecurityException(error)) {
            metricsService.incrementCounter(
                "http.requests.security_error.total", 
                "HTTP requests with security errors", 
                1L, 
                baseAttributes
            );
        }
    }
    
    /**
     * Registra métricas de SLA
     */
    private void recordSlaMetrics(HttpRequestMetrics request, double durationMs, int statusCode) {
        // SLA típicos: 95% de requests < 200ms, 99% < 500ms
        boolean withinSla200 = durationMs < 200.0;
        boolean withinSla500 = durationMs < 500.0;
        boolean withinSla1000 = durationMs < 1000.0;
        
        Map<String, String> slaAttributes = Map.of(
            "http.method", request.method,
            "http.route", request.route,
            "service.name", serviceName
        );
        
        if (withinSla200) {
            metricsService.incrementCounter(
                "http.requests.sla_200ms.total", 
                "HTTP requests within 200ms SLA", 
                1L, 
                slaAttributes
            );
        }
        
        if (withinSla500) {
            metricsService.incrementCounter(
                "http.requests.sla_500ms.total", 
                "HTTP requests within 500ms SLA", 
                1L, 
                slaAttributes
            );
        }
        
        if (withinSla1000) {
            metricsService.incrementCounter(
                "http.requests.sla_1000ms.total", 
                "HTTP requests within 1000ms SLA", 
                1L, 
                slaAttributes
            );
        }
        
        // Métricas de éxito combinado (status + latencia)
        boolean isSuccess = statusCode >= 200 && statusCode < 400 && withinSla500;
        if (isSuccess) {
            metricsService.incrementCounter(
                "http.requests.success_with_sla.total", 
                "HTTP requests successful and within SLA", 
                1L, 
                slaAttributes
            );
        }
    }
    
    /**
     * Registra métricas de rate por ruta
     */
    private void recordRouteRateMetrics(HttpRequestMetrics request) {
        String routeKey = request.method + ":" + request.route;
        AtomicLong count = requestCounts.get(routeKey);
        
        if (count != null) {
            metricsService.recordGauge(
                "http.route.request_count", 
                "Current request count for HTTP route", 
                count.get(), 
                Map.of(
                    "http.method", request.method,
                    "http.route", request.route,
                    "service.name", serviceName
                )
            );
        }
    }
    
    // ===== MÉTODOS UTILITARIOS =====
    
    private Attributes buildHttpAttributes(String method, String route, int statusCode, Throwable error) {
        var builder = Attributes.builder()
            .put(AttributeKey.stringKey("http.method"), method)
            .put(AttributeKey.stringKey("http.route"), route)
            .put(AttributeKey.stringKey("service.name"), serviceName);
        
        if (statusCode > 0) {
            builder.put(AttributeKey.longKey("http.status_code"), statusCode);
            builder.put(AttributeKey.stringKey("http.status_class"), getStatusClass(statusCode));
        }
        
        if (error != null) {
            builder.put(AttributeKey.stringKey("error.type"), error.getClass().getSimpleName());
        }
        
        return builder.build();
    }
    
    private String getStatusClass(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) return "2xx";
        if (statusCode >= 300 && statusCode < 400) return "3xx";
        if (statusCode >= 400 && statusCode < 500) return "4xx";
        if (statusCode >= 500) return "5xx";
        return "1xx";
    }
    
    private String classifyLatency(double durationMs) {
        if (durationMs < 10) return "ultra_fast";
        if (durationMs < 50) return "fast";
        if (durationMs < 200) return "normal";
        if (durationMs < 500) return "slow";
        if (durationMs < 1000) return "very_slow";
        return "extremely_slow";
    }
    
    private String classifyRequestSize(long bytes) {
        if (bytes < 1024) return "small";           // < 1KB
        if (bytes < 10240) return "medium";         // < 10KB
        if (bytes < 102400) return "large";         // < 100KB
        if (bytes < 1048576) return "very_large";   // < 1MB
        return "huge";                              // >= 1MB
    }
    
    private boolean isTimeoutException(Throwable error) {
        return error instanceof InterruptedException ||
               (error.getMessage() != null && error.getMessage().toLowerCase().contains("timeout"));
    }
    
    private boolean isConnectionException(Throwable error) {
        String className = error.getClass().getSimpleName().toLowerCase();
        return className.contains("connection") || 
               className.contains("socket") ||
               className.contains("network");
    }
    
    private boolean isSecurityException(Throwable error) {
        String className = error.getClass().getSimpleName().toLowerCase();
        return className.contains("security") || 
               className.contains("auth") ||
               className.contains("permission");
    }
    
    /**
     * Registra métricas personalizadas para endpoints específicos
     */
    public void recordEndpointMetric(String endpointName, String metricName, double value, Map<String, String> additionalAttributes) {
        Map<String, String> attributes = new java.util.HashMap<>(additionalAttributes);
        attributes.put("http.endpoint", endpointName);
        attributes.put("service.name", serviceName);
        
        HttpRequestMetrics request = currentRequest.get();
        if (request != null) {
            attributes.put("http.method", request.method);
            attributes.put("http.route", request.route);
        }
        
        metricsService.recordHistogram(
            "http.endpoint." + metricName, 
            "Custom HTTP endpoint metric: " + metricName, 
            value, 
            attributes
        );
    }
    
    /**
     * Limpia los contadores de rate (útil para testing)
     */
    public void clearRateCounters() {
        requestCounts.clear();
    }
    
    /**
     * Obtiene estadísticas de los contadores de rate
     */
    public Map<String, Long> getRateCounterStats() {
        Map<String, Long> stats = new java.util.HashMap<>();
        requestCounts.forEach((route, count) -> stats.put(route, count.get()));
        return stats;
    }
    
    /**
     * Clase interna para mantener métricas de request actual
     */
    private static class HttpRequestMetrics {
        final String method;
        final String route;
        final String userAgent;
        final String clientIp;
        final Instant startTime;
        
        HttpRequestMetrics(String method, String route, String userAgent, String clientIp, Instant startTime) {
            this.method = method;
            this.route = route;
            this.userAgent = userAgent;
            this.clientIp = clientIp;
            this.startTime = startTime;
        }
    }
}