package pe.soapros.otel.lambda.examples;

import pe.soapros.otel.core.infrastructure.ObservabilityConfig;
import pe.soapros.otel.core.infrastructure.OpenTelemetryManager;
import pe.soapros.otel.lambda.infrastructure.ObservabilityManager;
import pe.soapros.otel.metrics.infrastructure.MetricsFactory;
import pe.soapros.otel.traces.infrastructure.TracingFactory;
import pe.soapros.otel.logs.infrastructure.SimplifiedLoggingFactory;

import java.util.Map;

public class RefactoredObservabilityExample {
    
    public static void main(String[] args) {
        // 1. Inicializar OpenTelemetryManager una sola vez
        ObservabilityConfig config = ObservabilityConfig.builder("my-lambda-service")
                .serviceVersion("2.0.0")
                .environment(ObservabilityConfig.Environment.DEVELOPMENT)
                .customAttributes(Map.of(
                    "team", "platform",
                    "region", "us-east-1"
                ))
                .build();
        
        OpenTelemetryManager.initialize(config);
        
        // 2. Usar servicios centralizados - todos comparten la misma configuración
        
        // Tracing usando la configuración centralizada
        var tracerService = TracingFactory.getDefaultTracerService();
        var span = tracerService.startSpan("lambda-execution");
        
        try {
            // ObservabilityManager usando la configuración centralizada - configurar contexto primero
            var observabilityManager = ObservabilityManager.fromManagerWithDefaults();
            observabilityManager.setupQuickBusinessContext("business-123", "user-456", "process-payment");
            
            // Logging usando SimplifiedLoggingFactory con contexto de negocio automático
            var businessAwareLogger = SimplifiedLoggingFactory.createBusinessAwareLogger();
            
            // Este log incluirá automáticamente: traceId, spanId, business.id, user.id, operation
            businessAwareLogger.info("Lambda started", Map.of("requestId", "123456"));
            
            // Ejemplo de logging sin atributos adicionales - aún incluye automáticamente contexto de negocio
            businessAwareLogger.debug("Processing business logic");
            
            // Metrics usando la configuración centralizada
            var metricsFactory = MetricsFactory.fromManager();
            var metricsService = metricsFactory.getMetricsService();
            //metricsService.incrementCounter("lambda.invocations", Map.of("function", "my-lambda"));
            
            // Simular trabajo y más logging
            businessAwareLogger.info("Starting payment processing", Map.of("amount", "100.00", "currency", "USD"));
            Thread.sleep(100);
            
            // Log de éxito con información adicional
            businessAwareLogger.infoWithBusiness("Payment processed successfully", "business-123", "process-payment");
            
            span.setStatus(true, "Completed successfully");
            
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(false, "Failed: " + e.getMessage());
        } finally {
            span.end();
        }
        
        System.out.println("Health status: " + OpenTelemetryManager.getInstance().getHealthStatus());
    }
}