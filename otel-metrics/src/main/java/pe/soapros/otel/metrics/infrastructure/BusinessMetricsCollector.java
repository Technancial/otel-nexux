package pe.soapros.otel.metrics.infrastructure;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import pe.soapros.otel.metrics.domain.MetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Colector especializado de métricas de negocio.
 * Captura métricas relacionadas con KPIs, transacciones, usuarios,
 * y otros indicadores importantes para el negocio.
 */
public class BusinessMetricsCollector {
    
    private static final Logger logger = LoggerFactory.getLogger(BusinessMetricsCollector.class);
    
    private final MetricsService metricsService;
    private final String serviceName;
    
    // Accumuladores para métricas de negocio
    private final ConcurrentHashMap<String, AtomicLong> businessCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DoubleAdder> businessValues = new ConcurrentHashMap<>();
    private final ThreadLocal<BusinessTransactionMetrics> currentTransaction = new ThreadLocal<>();
    
    public BusinessMetricsCollector(MetricsService metricsService, String serviceName) {
        this.metricsService = metricsService;
        this.serviceName = serviceName;
    }
    
    // ==================== MÉTRICAS DE USUARIOS ====================
    
    /**
     * Registra actividad de usuario
     */
    public void recordUserActivity(String userId, String activityType, String feature, Map<String, String> context) {
        Attributes userAttributes = Attributes.builder()
            .put(AttributeKey.stringKey("user.id"), userId != null ? userId : "anonymous")
            .put(AttributeKey.stringKey("user.activity"), activityType)
            .put(AttributeKey.stringKey("feature.name"), feature)
            .put(AttributeKey.stringKey("service.name"), serviceName)
            .build();
        
        // Counter de actividades de usuario
        metricsService.incrementCounter(
            "business.user.activity.total", 
            "Total user activities", 
            1L, 
            userAttributes
        );
        
        // Counter por tipo de actividad
        metricsService.incrementCounter(
            "business.user.activity_by_type.total", 
            "User activities by type", 
            1L, 
            userAttributes
        );
        
        // Counter por feature
        metricsService.incrementCounter(
            "business.feature.usage.total", 
            "Feature usage count", 
            1L, 
            userAttributes
        );
        
        // Actualizar contadores internos
        String activityKey = activityType + ":" + feature;
        businessCounters.computeIfAbsent(activityKey, k -> new AtomicLong(0)).incrementAndGet();
        
        logger.debug("Recorded user activity: {} - {} on feature: {}", userId, activityType, feature);
    }
    
    /**
     * Registra login/logout de usuarios
     */
    public void recordUserSession(String userId, String sessionAction, String userType, Duration sessionDuration) {
        Map<String, String> sessionAttributes = Map.of(
            "user.id", userId != null ? userId : "anonymous",
            "session.action", sessionAction,
            "user.type", userType,
            "service.name", serviceName
        );
        
        // Counter de sesiones
        metricsService.incrementCounter(
            "business.user.sessions.total", 
            "Total user sessions", 
            1L, 
            sessionAttributes
        );
        
        // Si es logout, registrar duración de sesión
        if ("logout".equals(sessionAction) && sessionDuration != null) {
            metricsService.recordHistogram(
                "business.user.session.duration", 
                "User session duration in minutes", 
                sessionDuration.toMinutes(), 
                sessionAttributes
            );
        }
        
        // Métricas por tipo de usuario
        metricsService.incrementCounter(
            "business.user.sessions_by_type.total", 
            "User sessions by type", 
            1L, 
            sessionAttributes
        );
    }
    
    /**
     * Registra errores de usuario
     */
    public void recordUserError(String userId, String errorType, String feature, String errorMessage) {
        Map<String, String> errorAttributes = Map.of(
            "user.id", userId != null ? userId : "anonymous",
            "error.type", errorType,
            "feature.name", feature,
            "error.message", errorMessage != null ? errorMessage : "unknown",
            "service.name", serviceName
        );
        
        metricsService.incrementCounter(
            "business.user.errors.total", 
            "Total user errors", 
            1L, 
            errorAttributes
        );
        
        // Counter por tipo de error
        metricsService.incrementCounter(
            "business.user.errors_by_type.total", 
            "User errors by type", 
            1L, 
            errorAttributes
        );
    }
    
    // ==================== MÉTRICAS DE TRANSACCIONES ====================
    
    /**
     * Inicia el tracking de una transacción de negocio
     */
    public void startBusinessTransaction(String transactionId, String transactionType, String userId, 
                                       double amount, String currency) {
        BusinessTransactionMetrics transaction = new BusinessTransactionMetrics(
            transactionId,
            transactionType,
            userId,
            amount,
            currency,
            Instant.now()
        );
        
        currentTransaction.set(transaction);
        
        // Counter de transacciones iniciadas
        Attributes startAttributes = buildTransactionAttributes(transaction, "started", null);
        
        metricsService.incrementCounter(
            "business.transactions.started.total", 
            "Business transactions started", 
            1L, 
            startAttributes
        );
        
        logger.debug("Started business transaction: {} type: {} amount: {} {}", 
                     transactionId, transactionType, amount, currency);
    }
    
    /**
     * Finaliza el tracking de una transacción de negocio
     */
    public void endBusinessTransaction(String status, String failureReason) {
        BusinessTransactionMetrics transaction = currentTransaction.get();
        if (transaction == null) {
            logger.warn("No business transaction found for current thread");
            return;
        }
        
        try {
            Duration duration = Duration.between(transaction.startTime, Instant.now());
            boolean isSuccess = "success".equals(status);
            
            Attributes endAttributes = buildTransactionAttributes(transaction, status, failureReason);
            
            // Métricas básicas de transacción
            recordBasicTransactionMetrics(transaction, duration, status, endAttributes);
            
            // Métricas de valor/revenue
            recordTransactionValueMetrics(transaction, isSuccess);
            
            // Métricas de tiempo/performance
            recordTransactionPerformanceMetrics(transaction, duration, endAttributes);
            
            // Métricas de errores si aplica
            if (!isSuccess) {
                recordTransactionErrorMetrics(transaction, failureReason, endAttributes);
            }
            
            logger.debug("Completed business transaction: {} - {}ms - status: {}", 
                         transaction.transactionId, duration.toMillis(), status);
            
        } finally {
            currentTransaction.remove();
        }
    }
    
    /**
     * Registra una transacción completada directamente (método de conveniencia)
     */
    public void recordCompletedTransaction(String transactionType, String userId, double amount, String currency, 
                                         Duration duration, boolean success) {
        String transactionId = generateTransactionId();
        
        startBusinessTransaction(transactionId, transactionType, userId, amount, currency);
        endBusinessTransaction(success ? "success" : "failed", success ? null : "processing_error");
    }
    
    // ==================== MÉTRICAS DE INVENTARIO/PRODUCTOS ====================
    
    /**
     * Registra métricas de inventario
     */
    public void recordInventoryMetrics(String productId, String productCategory, int stockLevel, 
                                     int reservedStock, double productValue) {
        Map<String, String> inventoryAttributes = Map.of(
            "product.id", productId,
            "product.category", productCategory,
            "service.name", serviceName
        );
        
        // Gauge de nivel de stock
        metricsService.recordGauge(
            "business.inventory.stock_level", 
            "Product stock level", 
            stockLevel, 
            inventoryAttributes
        );
        
        // Gauge de stock reservado
        metricsService.recordGauge(
            "business.inventory.reserved_stock", 
            "Reserved product stock", 
            reservedStock, 
            inventoryAttributes
        );
        
        // Gauge de valor de inventario
        metricsService.recordGauge(
            "business.inventory.product_value", 
            "Product inventory value", 
            productValue, 
            inventoryAttributes
        );
        
        // Alertas de stock bajo
        if (stockLevel < 10) {
            metricsService.incrementCounter(
                "business.inventory.low_stock_alerts.total", 
                "Low stock alerts", 
                1L, 
                inventoryAttributes
            );
        }
        
        // Stock agotado
        if (stockLevel == 0) {
            metricsService.incrementCounter(
                "business.inventory.out_of_stock.total", 
                "Out of stock events", 
                1L, 
                inventoryAttributes
            );
        }
    }
    
    /**
     * Registra ventas de productos
     */
    public void recordProductSale(String productId, String productCategory, int quantity, double unitPrice, 
                                double totalAmount, String channel) {
        Map<String, String> saleAttributes = Map.of(
            "product.id", productId,
            "product.category", productCategory,
            "sale.channel", channel,
            "service.name", serviceName
        );
        
        // Counter de ventas
        metricsService.incrementCounter(
            "business.sales.transactions.total", 
            "Total sales transactions", 
            1L, 
            saleAttributes
        );
        
        // Histogram de cantidad vendida
        metricsService.recordHistogram(
            "business.sales.quantity", 
            "Quantity sold per transaction", 
            quantity, 
            saleAttributes
        );
        
        // Histogram de valor de venta
        metricsService.recordHistogram(
            "business.sales.amount", 
            "Sale amount", 
            totalAmount, 
            saleAttributes
        );
        
        // Revenue acumulado
        String revenueKey = productCategory + ":" + channel;
        businessValues.computeIfAbsent(revenueKey, k -> new DoubleAdder()).add(totalAmount);
        
        // Gauge de revenue acumulado
        metricsService.recordGauge(
            "business.sales.revenue_accumulated", 
            "Accumulated revenue", 
            businessValues.get(revenueKey).sum(), 
            saleAttributes
        );
    }
    
    // ==================== MÉTRICAS DE PERFORMANCE DE NEGOCIO ====================
    
    /**
     * Registra KPIs personalizados
     */
    public void recordKPI(String kpiName, double value, String unit, String period, Map<String, String> dimensions) {
        Map<String, String> kpiAttributes = new java.util.HashMap<>(dimensions);
        kpiAttributes.put("kpi.name", kpiName);
        kpiAttributes.put("kpi.unit", unit);
        kpiAttributes.put("kpi.period", period);
        kpiAttributes.put("service.name", serviceName);
        kpiAttributes.put("timestamp.period", getCurrentTimePeriod());
        
        // Gauge del KPI actual
        metricsService.recordGauge(
            "business.kpi." + kpiName.toLowerCase().replace(" ", "_"), 
            "Business KPI: " + kpiName, 
            value, 
            kpiAttributes
        );
        
        // Histogram para análisis histórico
        metricsService.recordHistogram(
            "business.kpi.historical." + kpiName.toLowerCase().replace(" ", "_"), 
            "Historical Business KPI: " + kpiName, 
            value, 
            kpiAttributes
        );
    }
    
    /**
     * Registra métricas de conversion rate
     */
    public void recordConversionRate(String funnel, String step, long visitors, long conversions, String channel) {
        double conversionRate = visitors > 0 ? (double) conversions / visitors : 0.0;
        
        Map<String, String> conversionAttributes = Map.of(
            "funnel.name", funnel,
            "funnel.step", step,
            "marketing.channel", channel,
            "service.name", serviceName
        );
        
        // Gauge de conversion rate
        metricsService.recordGauge(
            "business.conversion.rate", 
            "Conversion rate", 
            conversionRate, 
            conversionAttributes
        );
        
        // Counter de visitantes
        metricsService.recordHistogram(
            "business.conversion.visitors", 
            "Visitors count", 
            visitors, 
            conversionAttributes
        );
        
        // Counter de conversiones
        metricsService.recordHistogram(
            "business.conversion.conversions", 
            "Conversions count", 
            conversions, 
            conversionAttributes
        );
    }
    
    /**
     * Registra métricas de retención de usuarios
     */
    public void recordUserRetention(String cohort, String period, double retentionRate, int totalUsers, int retainedUsers) {
        Map<String, String> retentionAttributes = Map.of(
            "cohort.name", cohort,
            "retention.period", period,
            "service.name", serviceName
        );
        
        // Gauge de retention rate
        metricsService.recordGauge(
            "business.retention.rate", 
            "User retention rate", 
            retentionRate, 
            retentionAttributes
        );
        
        // Histogram de usuarios retenidos
        metricsService.recordHistogram(
            "business.retention.users_retained", 
            "Users retained", 
            retainedUsers, 
            retentionAttributes
        );
        
        // Histogram de total de usuarios en cohort
        metricsService.recordHistogram(
            "business.retention.users_total", 
            "Total users in cohort", 
            totalUsers, 
            retentionAttributes
        );
    }
    
    // ==================== MÉTODOS PRIVADOS ====================
    
    private void recordBasicTransactionMetrics(BusinessTransactionMetrics transaction, Duration duration, 
                                             String status, Attributes attributes) {
        // Counter de transacciones completadas
        metricsService.incrementCounter(
            "business.transactions.total", 
            "Total business transactions", 
            1L, 
            attributes
        );
        
        // Histogram de duración
        metricsService.recordHistogram(
            "business.transaction.duration", 
            "Business transaction duration in milliseconds", 
            duration.toNanos() / 1_000_000.0, 
            attributes
        );
        
        // Counter por status
        if ("success".equals(status)) {
            metricsService.incrementCounter(
                "business.transactions.success.total", 
                "Successful business transactions", 
                1L, 
                attributes
            );
        } else {
            metricsService.incrementCounter(
                "business.transactions.failed.total", 
                "Failed business transactions", 
                1L, 
                attributes
            );
        }
    }
    
    private void recordTransactionValueMetrics(BusinessTransactionMetrics transaction, boolean isSuccess) {
        if (isSuccess && transaction.amount > 0) {
            Map<String, String> valueAttributes = Map.of(
                "transaction.type", transaction.transactionType,
                "transaction.currency", transaction.currency,
                "service.name", serviceName
            );
            
            // Histogram de montos de transacción
            metricsService.recordHistogram(
                "business.transaction.amount", 
                "Business transaction amount", 
                transaction.amount, 
                valueAttributes
            );
            
            // Revenue acumulado por tipo
            String revenueKey = transaction.transactionType + ":" + transaction.currency;
            businessValues.computeIfAbsent(revenueKey, k -> new DoubleAdder()).add(transaction.amount);
            
            // Gauge de revenue acumulado
            metricsService.recordGauge(
                "business.revenue.accumulated", 
                "Accumulated revenue", 
                businessValues.get(revenueKey).sum(), 
                valueAttributes
            );
        }
    }
    
    private void recordTransactionPerformanceMetrics(BusinessTransactionMetrics transaction, Duration duration, 
                                                   Attributes attributes) {
        double durationMs = duration.toNanos() / 1_000_000.0;
        
        // Clasificar por rangos de tiempo
        String durationBucket = classifyTransactionDuration(durationMs);
        Attributes durationAttributes = attributes.toBuilder()
            .put(AttributeKey.stringKey("duration.bucket"), durationBucket)
            .build();
            
        metricsService.incrementCounter(
            "business.transactions.by_duration.total", 
            "Business transactions by duration bucket", 
            1L, 
            durationAttributes
        );
    }
    
    private void recordTransactionErrorMetrics(BusinessTransactionMetrics transaction, String failureReason, 
                                             Attributes attributes) {
        if (failureReason != null) {
            Map<String, String> errorAttributes = Map.of(
                "transaction.type", transaction.transactionType,
                "failure.reason", failureReason,
                "service.name", serviceName
            );
            
            metricsService.incrementCounter(
                "business.transactions.errors_by_reason.total", 
                "Business transaction errors by reason", 
                1L, 
                errorAttributes
            );
        }
    }
    
    private Attributes buildTransactionAttributes(BusinessTransactionMetrics transaction, String status, String failureReason) {
        var builder = Attributes.builder()
            .put(AttributeKey.stringKey("transaction.id"), transaction.transactionId)
            .put(AttributeKey.stringKey("transaction.type"), transaction.transactionType)
            .put(AttributeKey.stringKey("transaction.currency"), transaction.currency)
            .put(AttributeKey.stringKey("transaction.status"), status)
            .put(AttributeKey.stringKey("service.name"), serviceName);
            
        if (transaction.userId != null) {
            builder.put(AttributeKey.stringKey("user.id"), transaction.userId);
        }
        
        if (failureReason != null) {
            builder.put(AttributeKey.stringKey("failure.reason"), failureReason);
        }
        
        return builder.build();
    }
    
    private String classifyTransactionDuration(double durationMs) {
        if (durationMs < 100) return "instant";
        if (durationMs < 500) return "fast";
        if (durationMs < 2000) return "normal";
        if (durationMs < 5000) return "slow";
        return "very_slow";
    }
    
    private String getCurrentTimePeriod() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH"));
    }
    
    private String generateTransactionId() {
        return "txn_" + System.currentTimeMillis() + "_" + Thread.currentThread().getId();
    }
    
    /**
     * Registra métricas personalizadas de negocio
     */
    public void recordCustomBusinessMetric(String domain, String metricName, double value, String unit, 
                                         Map<String, String> additionalAttributes) {
        Map<String, String> attributes = new java.util.HashMap<>(additionalAttributes);
        attributes.put("business.domain", domain);
        attributes.put("metric.unit", unit);
        attributes.put("service.name", serviceName);
        
        BusinessTransactionMetrics transaction = currentTransaction.get();
        if (transaction != null) {
            attributes.put("transaction.id", transaction.transactionId);
            attributes.put("transaction.type", transaction.transactionType);
        }
        
        metricsService.recordHistogram(
            "business." + domain.toLowerCase() + "." + metricName.toLowerCase().replace(" ", "_"), 
            "Custom business metric: " + metricName, 
            value, 
            attributes
        );
    }
    
    /**
     * Limpia los acumuladores (útil para testing)
     */
    public void clearAccumulators() {
        businessCounters.clear();
        businessValues.clear();
    }
    
    /**
     * Obtiene estadísticas de los acumuladores
     */
    public Map<String, Object> getAccumulatorStats() {
        Map<String, Object> stats = new java.util.HashMap<>();
        
        Map<String, Long> counters = new java.util.HashMap<>();
        businessCounters.forEach((key, count) -> counters.put(key, count.get()));
        stats.put("counters", counters);
        
        Map<String, Double> values = new java.util.HashMap<>();
        businessValues.forEach((key, value) -> values.put(key, value.sum()));
        stats.put("values", values);
        
        return stats;
    }
    
    /**
     * Clase interna para mantener métricas de transacción actual
     */
    private static class BusinessTransactionMetrics {
        final String transactionId;
        final String transactionType;
        final String userId;
        final double amount;
        final String currency;
        final Instant startTime;
        
        BusinessTransactionMetrics(String transactionId, String transactionType, String userId, 
                                 double amount, String currency, Instant startTime) {
            this.transactionId = transactionId;
            this.transactionType = transactionType;
            this.userId = userId;
            this.amount = amount;
            this.currency = currency;
            this.startTime = startTime;
        }
    }
}