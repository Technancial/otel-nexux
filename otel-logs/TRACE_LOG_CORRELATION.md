# 🔗 Correlación Automática de Logs y Traces

Esta documentación explica cómo el módulo `otel-logs` automáticamente inserta **traceId** y **spanId** en todos los logs para crear correlación completa entre logs y traces.

## 🎯 ¿Qué es la Correlación de Logs y Traces?

La correlación permite:
- **Buscar todos los logs** relacionados con un trace específico usando el `traceId`
- **Encontrar logs específicos** de una operación usando el `spanId`
- **Seguir el flujo completo** de una request a través de múltiples servicios
- **Debuggear problemas** más fácilmente correlacionando logs con traces

## 🔧 Cómo Funciona

### 1. **ContextAwareLoggerService** - Correlación Automática

```java
// El ContextAwareLoggerService automáticamente incluye traceId y spanId
ContextAwareLoggerService logger = new ContextAwareLoggerService(loggerProvider, "my-service");

// Este log automáticamente incluirá:
// - trace_id: "4bf92f3577b34da6a3ce929d0e0e4736" 
// - span_id: "00f067aa0ba902b7"
// - trace_sampled: true
logger.info("User operation completed", Map.of(
    "user.id", "12345",
    "operation", "update_profile"
));
```

### 2. **TraceContextLogEnricher** - Para Loggers Tradicionales

```java
// Para usar con SLF4J, Logback, Log4j tradicionales
TraceContextLogEnricher.enrichMDC();

try {
    Logger traditionalLogger = LoggerFactory.getLogger(MyClass.class);
    
    // Este log tendrá traceId y spanId en el MDC
    traditionalLogger.info("Processing user request for userId: {}", userId);
    
} finally {
    TraceContextLogEnricher.clearMDC();
}
```
```java
package pe.soapros.otel.examples.quarkus;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import lombok.Data;
import lombok.Builder;
import pe.soapros.otel.lambda.infrastructure.HttpTracingLambdaWrapper;
import pe.soapros.otel.logs.infrastructure.BusinessContextManager;
import pe.soapros.otel.logs.infrastructure.BusinessContextManager.BusinessContext;
import pe.soapros.otel.traces.infrastructure.config.OpenTelemetryConfiguration;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.util.Map;
import java.util.Optional;

/**
 * Lambda con Quarkus que demuestra:
 * - Uso de Lombok para logging limpio
 * - Metadatos de negocio automáticos (businessId, userId, etc.)
 * - Correlación automática de traces
 * - Inyección de dependencias con Quarkus
 */
@Slf4j  // ← Lombok genera automáticamente el logger
@ApplicationScoped
public class OrderProcessingLambda extends HttpTracingLambdaWrapper {
    
    @Inject
    OrderService orderService;
    
    @Inject
    PaymentService paymentService;
    
    @Inject
    NotificationService notificationService;
    
    public OrderProcessingLambda() {
        super(OpenTelemetryConfiguration.create());
    }

    @Override
    public APIGatewayProxyResponseEvent handle(APIGatewayProxyRequestEvent event, Context context) {
        
        // ===== ESTABLECER CONTEXTO DE NEGOCIO AUTOMÁTICAMENTE =====
        BusinessContextManager.setContextFromHeaders(
            Optional.ofNullable(event.getHeaders()).orElse(Map.of())
        );
        
        // Extraer información adicional del path/body
        String orderId = extractOrderId(event);
        String operation = extractOperation(event);
        
        // Actualizar contexto con información específica
        BusinessContextManager.updateBusinessId(orderId);
        BusinessContextManager.updateOperation(operation);
        
        try {
            // ✨ TODOS estos logs automáticamente incluyen:
            // - traceId, spanId (del trace context)
            // - businessId, userId, tenantId, correlationId, operation (del business context)
            
            log.info("🚀 ORDER PROCESSING STARTED"); // ← Simple y limpio con Lombok
            
            return switch (operation) {
                case "create" -> handleCreateOrder(event, context);
                case "update" -> handleUpdateOrder(event, context, orderId);
                case "cancel" -> handleCancelOrder(event, context, orderId);
                case "status" -> handleGetOrderStatus(event, context, orderId);
                default -> handleInvalidOperation(operation);
            };
            
        } catch (Exception e) {
            // Log de error automáticamente correlacionado
            log.error("❌ ORDER PROCESSING FAILED: {}", e.getMessage(), e);
            return createErrorResponse(500, "Internal server error");
            
        } finally {
            // Limpiar contexto al final
            BusinessContextManager.clearContext();
        }
    }
    
    private APIGatewayProxyResponseEvent handleCreateOrder(APIGatewayProxyRequestEvent event, Context context) {
        
        // Logs con contexto automático - ¡súper limpios con Lombok!
        log.info("📦 CREATING NEW ORDER");
        
        try {
            // Parsear request
            CreateOrderRequest request = parseCreateOrderRequest(event.getBody());
            log.debug("Order request parsed successfully");
            
            // Validar orden
            validateOrder(request);
            log.info("✅ ORDER VALIDATION PASSED");
            
            // Crear orden usando servicio inyectado
            Order order = orderService.createOrder(request);
            log.info("📋 ORDER CREATED - orderNumber: {}", order.getOrderNumber());
            
            // Procesar pago
            PaymentResult paymentResult = paymentService.processPayment(order);
            log.info("💳 PAYMENT PROCESSED - status: {}", paymentResult.getStatus());
            
            if (paymentResult.isSuccessful()) {
                // Confirmar orden
                orderService.confirmOrder(order.getId());
                log.info("✅ ORDER CONFIRMED");
                
                // Enviar notificación
                notificationService.sendOrderConfirmation(order);
                log.info("📧 CONFIRMATION NOTIFICATION SENT");
                
                // Respuesta exitosa
                OrderResponse response = OrderResponse.builder()
                    .orderId(order.getId())
                    .orderNumber(order.getOrderNumber())
                    .status("CONFIRMED")
                    .totalAmount(order.getTotalAmount())
                    .contextInfo(BusinessContextManager.getCurrentContextInfo())
                    .build();
                
                log.info("🎉 ORDER PROCESSING COMPLETED SUCCESSFULLY");
                return createSuccessResponse(response);
                
            } else {
                // Manejar fallo de pago
                log.warn("💳 PAYMENT FAILED - reason: {}", paymentResult.getFailureReason());
                orderService.markOrderAsFailed(order.getId(), paymentResult.getFailureReason());
                
                return createErrorResponse(402, "Payment failed: " + paymentResult.getFailureReason());
            }
            
        } catch (ValidationException e) {
            log.warn("⚠️ ORDER VALIDATION FAILED: {}", e.getMessage());
            return createErrorResponse(400, e.getMessage());
            
        } catch (PaymentException e) {
            log.error("💳 PAYMENT SERVICE ERROR: {}", e.getMessage(), e);
            return createErrorResponse(502, "Payment service unavailable");
            
        } catch (Exception e) {
            log.error("🔥 UNEXPECTED ERROR DURING ORDER CREATION", e);
            return createErrorResponse(500, "Order creation failed");
        }
    }
    
    private APIGatewayProxyResponseEvent handleUpdateOrder(APIGatewayProxyRequestEvent event, 
                                                          Context context, String orderId) {
        
        log.info("✏️ UPDATING ORDER - orderId: {}", orderId);
        
        try {
            // Verificar que existe la orden
            Optional<Order> existingOrder = orderService.findOrder(orderId);
            if (existingOrder.isEmpty()) {
                log.warn("❓ ORDER NOT FOUND - orderId: {}", orderId);
                return createErrorResponse(404, "Order not found");
            }
            
            Order order = existingOrder.get();
            log.debug("Order found - status: {}", order.getStatus());
            
            // Verificar que se puede actualizar
            if (!order.canBeUpdated()) {
                log.warn("🔒 ORDER CANNOT BE UPDATED - status: {}", order.getStatus());
                return createErrorResponse(409, "Order cannot be updated in current status");
            }
            
            // Parsear cambios
            UpdateOrderRequest updateRequest = parseUpdateOrderRequest(event.getBody());
            log.debug("Update request parsed successfully");
            
            // Aplicar cambios
            Order updatedOrder = orderService.updateOrder(orderId, updateRequest);
            log.info("✅ ORDER UPDATED SUCCESSFULLY");
            
            // Si cambió el monto, revalidar pago
            if (updateRequest.getTotalAmount() != null && 
                !updateRequest.getTotalAmount().equals(order.getTotalAmount())) {
                
                log.info("💰 TOTAL AMOUNT CHANGED - revalidating payment");
                PaymentResult revalidation = paymentService.revalidatePayment(updatedOrder);
                
                if (!revalidation.isSuccessful()) {
                    log.warn("💳 PAYMENT REVALIDATION FAILED");
                    return createErrorResponse(402, "Payment revalidation failed");
                }
                
                log.info("✅ PAYMENT REVALIDATED");
            }
            
            OrderResponse response = OrderResponse.builder()
                .orderId(updatedOrder.getId())
                .orderNumber(updatedOrder.getOrderNumber())
                .status(updatedOrder.getStatus())
                .totalAmount(updatedOrder.getTotalAmount())
                .contextInfo(BusinessContextManager.getCurrentContextInfo())
                .build();
            
            log.info("🎉 ORDER UPDATE COMPLETED");
            return createSuccessResponse(response);
            
        } catch (Exception e) {
            log.error("🔥 ERROR UPDATING ORDER - orderId: {}", orderId, e);
            return createErrorResponse(500, "Order update failed");
        }
    }
    
    private APIGatewayProxyResponseEvent handleCancelOrder(APIGatewayProxyRequestEvent event, 
                                                          Context context, String orderId) {
        
        log.info("❌ CANCELLING ORDER - orderId: {}", orderId);
        
        try {
            Optional<Order> order = orderService.findOrder(orderId);
            if (order.isEmpty()) {
                log.warn("❓ ORDER NOT FOUND FOR CANCELLATION - orderId: {}", orderId);
                return createErrorResponse(404, "Order not found");
            }
            
            if (!order.get().canBeCancelled()) {
                log.warn("🔒 ORDER CANNOT BE CANCELLED - status: {}", order.get().getStatus());
                return createErrorResponse(409, "Order cannot be cancelled");
            }
            
            // Procesar cancelación
            orderService.cancelOrder(orderId);
            log.info("✅ ORDER CANCELLED");
            
            // Procesar reembolso si es necesario
            if (order.get().isPaid()) {
                PaymentResult refund = paymentService.processRefund(order.get());
                log.info("💰 REFUND PROCESSED - status: {}", refund.getStatus());
            }
            
            // Notificar cancelación
            notificationService.sendCancellationNotification(order.get());
            log.info("📧 CANCELLATION NOTIFICATION SENT");
            
            log.info("🎉 ORDER CANCELLATION COMPLETED");
            return createSuccessResponse(Map.of(
                "message", "Order cancelled successfully",
                "orderId", orderId,
                "contextInfo", BusinessContextManager.getCurrentContextInfo()
            ));
            
        } catch (Exception e) {
            log.error("🔥 ERROR CANCELLING ORDER - orderId: {}", orderId, e);
            return createErrorResponse(500, "Order cancellation failed");
        }
    }
    
    private APIGatewayProxyResponseEvent handleGetOrderStatus(APIGatewayProxyRequestEvent event, 
                                                             Context context, String orderId) {
        
        log.debug("🔍 RETRIEVING ORDER STATUS - orderId: {}", orderId);
        
        try {
            Optional<Order> order = orderService.findOrder(orderId);
            if (order.isEmpty()) {
                log.debug("❓ ORDER NOT FOUND - orderId: {}", orderId);
                return createErrorResponse(404, "Order not found");
            }
            
            OrderResponse response = OrderResponse.builder()
                .orderId(order.get().getId())
                .orderNumber(order.get().getOrderNumber())
                .status(order.get().getStatus())
                .totalAmount(order.get().getTotalAmount())
                .contextInfo(BusinessContextManager.getCurrentContextInfo())
                .build();
            
            log.debug("✅ ORDER STATUS RETRIEVED");
            return createSuccessResponse(response);
            
        } catch (Exception e) {
            log.error("🔥 ERROR RETRIEVING ORDER STATUS - orderId: {}", orderId, e);
            return createErrorResponse(500, "Could not retrieve order status");
        }
    }
    
    private APIGatewayProxyResponseEvent handleInvalidOperation(String operation) {
        log.warn("❓ INVALID OPERATION REQUESTED - operation: {}", operation);
        return createErrorResponse(400, "Invalid operation: " + operation);
    }
    
    // ===== MÉTODOS UTILITARIOS =====
    
    private String extractOrderId(APIGatewayProxyRequestEvent event) {
        String path = event.getPath();
        if (path != null && path.matches("/orders/[^/]+.*")) {
            return path.split("/")[2];
        }
        return null;
    }
    
    private String extractOperation(APIGatewayProxyRequestEvent event) {
        String method = event.getHttpMethod();
        String path = event.getPath();
        
        if (path.matches("/orders/?$")) {
            return "POST".equals(method) ? "create" : "list";
        } else if (path.matches("/orders/[^/]+/?$")) {
            return switch (method) {
                case "GET" -> "status";
                case "PUT", "PATCH" -> "update";
                case "DELETE" -> "cancel";
                default -> "unknown";
            };
        }
        
        return "unknown";
    }
    
    private CreateOrderRequest parseCreateOrderRequest(String body) {
        // Implementar parsing JSON → CreateOrderRequest
        // En un caso real usarías Jackson o similar
        return CreateOrderRequest.builder().build();
    }
    
    private UpdateOrderRequest parseUpdateOrderRequest(String body) {
        // Implementar parsing JSON → UpdateOrderRequest
        return UpdateOrderRequest.builder().build();
    }
    
    private void validateOrder(CreateOrderRequest request) {
        // Implementar validaciones de negocio
        if (request == null) {
            throw new ValidationException("Request cannot be null");
        }
    }
    
    // ===== CLASES INTERNAS CON LOMBOK =====
    
    @Data
    @Builder
    public static class CreateOrderRequest {
        private String productId;
        private Integer quantity;
        private Double totalAmount;
        private String customerEmail;
    }
    
    @Data
    @Builder
    public static class UpdateOrderRequest {
        private Integer quantity;
        private Double totalAmount;
        private String notes;
    }
    
    @Data
    @Builder
    public static class OrderResponse {
        private String orderId;
        private String orderNumber;
        private String status;
        private Double totalAmount;
        private Map<String, String> contextInfo;
    }
    
    // ===== EXCEPCIONES =====
    
    public static class ValidationException extends RuntimeException {
        public ValidationException(String message) {
            super(message);
        }
    }
    
    public static class PaymentException extends RuntimeException {
        public PaymentException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

// ===== SERVICIOS INYECTABLES CON QUARKUS =====

@ApplicationScoped
@Slf4j
@RequiredArgsConstructor
class OrderService {
    
    public Order createOrder(OrderProcessingLambda.CreateOrderRequest request) {
        log.debug("Creating order in database");
        // Simular creación en DB
        return Order.builder()
            .id("ord_" + System.currentTimeMillis())
            .orderNumber("ORD-" + System.currentTimeMillis())
            .status("PENDING")
            .totalAmount(request.getTotalAmount())
            .build();
    }
    
    public Optional<Order> findOrder(String orderId) {
        log.debug("Finding order by ID: {}", orderId);
        // Simular búsqueda en DB
        return Optional.of(Order.builder()
            .id(orderId)
            .orderNumber("ORD-12345")
            .status("CONFIRMED")
            .totalAmount(99.99)
            .build());
    }
    
    public void confirmOrder(String orderId) {
        log.debug("Confirming order: {}", orderId);
        // Simular actualización en DB
    }
    
    public void markOrderAsFailed(String orderId, String reason) {
        log.debug("Marking order as failed: {} - reason: {}", orderId, reason);
        // Simular actualización en DB
    }
    
    public Order updateOrder(String orderId, OrderProcessingLambda.UpdateOrderRequest request) {
        log.debug("Updating order: {}", orderId);
        // Simular actualización en DB
        return Order.builder()
            .id(orderId)
            .orderNumber("ORD-12345")
            .status("UPDATED")
            .totalAmount(request.getTotalAmount())
            .build();
    }
    
    public void cancelOrder(String orderId) {
        log.debug("Cancelling order: {}", orderId);
        // Simular actualización en DB
    }
}

@ApplicationScoped
@Slf4j
class PaymentService {
    
    public PaymentResult processPayment(Order order) {
        log.debug("Processing payment for order: {}", order.getId());
        // Simular procesamiento de pago
        return PaymentResult.builder()
            .successful(true)
            .transactionId("txn_" + System.currentTimeMillis())
            .status("COMPLETED")
            .build();
    }
    
    public PaymentResult revalidatePayment(Order order) {
        log.debug("Revalidating payment for order: {}", order.getId());
        return PaymentResult.builder()
            .successful(true)
            .status("REVALIDATED")
            .build();
    }
    
    public PaymentResult processRefund(Order order) {
        log.debug("Processing refund for order: {}", order.getId());
        return PaymentResult.builder()
            .successful(true)
            .transactionId("ref_" + System.currentTimeMillis())
            .status("REFUNDED")
            .build();
    }
}

@ApplicationScoped
@Slf4j
class NotificationService {
    
    public void sendOrderConfirmation(Order order) {
        log.debug("Sending order confirmation for: {}", order.getId());
        // Simular envío de notificación
    }
    
    public void sendCancellationNotification(Order order) {
        log.debug("Sending cancellation notification for: {}", order.getId());
        // Simular envío de notificación
    }
}

// ===== MODELOS DE DOMINIO =====

@Data
@Builder(toBuilder = true)
class Order {
    private String id;
    private String orderNumber;
    private String status;
    private Double totalAmount;
    
    public boolean canBeUpdated() {
        return "PENDING".equals(status) || "CONFIRMED".equals(status);
    }
    
    public boolean canBeCancelled() {
        return !"CANCELLED".equals(status) && !"DELIVERED".equals(status);
    }
    
    public boolean isPaid() {
        return "CONFIRMED".equals(status) || "DELIVERED".equals(status);
    }
}

@Data
@Builder
class PaymentResult {
    private boolean successful;
    private String transactionId;
    private String status;
    private String failureReason;
}
```



### 3. **Configuración de Logback con Correlación**

```xml
<!-- logback.xml -->
<appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
        <!-- Pattern que incluye traceId y spanId -->
        <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level [%X{traceId:-},%X{spanId:-}] %logger{36} - %msg%n</pattern>
    </encoder>
</appender>
```

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    
    <!-- ===== APPENDERS ===== -->
    
    <!-- Appender para desarrollo con todos los metadatos visibles -->
    <appender name="CONSOLE_DEV" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <!-- Pattern completo con todos los metadatos de negocio -->
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level [trace:%X{traceId:-},span:%X{spanId:-}] [biz:%X{businessId:-},user:%X{userId:-},tenant:%X{tenantId:-}] [op:%X{operation:-},comp:%X{component:-}] %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <!-- Appender para producción con formato JSON estructurado -->
    <appender name="JSON_PROD" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder">
            <providers>
                <timestamp>
                    <pattern>yyyy-MM-dd'T'HH:mm:ss.SSSZ</pattern>
                </timestamp>
                <logLevel/>
                <loggerName/>
                <message/>
                <mdc/>
                <arguments/>
                <stackTrace/>
                <!-- Metadatos estructurados para observabilidad -->
                <pattern>
                    <pattern>
                        {
                        "observability": {
                            "trace_id": "%X{traceId:-}",
                            "span_id": "%X{spanId:-}",
                            "trace_sampled": "%X{traceSampled:-false}"
                        },
                        "business": {
                            "business_id": "%X{businessId:-}",
                            "user_id": "%X{userId:-}",
                            "tenant_id": "%X{tenantId:-}",
                            "correlation_id": "%X{correlationId:-}",
                            "operation": "%X{operation:-}",
                            "component": "%X{component:-}"
                        },
                        "service": {
                            "name": "${SERVICE_NAME:-order-service}",
                            "version": "${SERVICE_VERSION:-1.0.0}",
                            "environment": "${ENVIRONMENT:-development}",
                            "instance_id": "${HOSTNAME:-localhost}"
                        },
                        "lambda": {
                            "function_name": "${AWS_LAMBDA_FUNCTION_NAME:-}",
                            "function_version": "${AWS_LAMBDA_FUNCTION_VERSION:-}",
                            "request_id": "${AWS_LAMBDA_LOG_STREAM_NAME:-}"
                        }
                        }
                    </pattern>
                </pattern>
            </providers>
        </encoder>
    </appender>
    
    <!-- Appender compacto para producción con campos esenciales -->
    <appender name="CONSOLE_COMPACT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} %-5level [%X{traceId:-},%X{spanId:-}] [%X{businessId:-}:%X{operation:-}] %logger{20} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <!-- Appender para CloudWatch con formato optimizado -->
    <appender name="CLOUDWATCH" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%X{traceId:-}] [%X{businessId:-}] [%X{userId:-}] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <!-- OpenTelemetry appender para envío directo a collector -->
    <appender name="OTEL" class="pe.soapros.otel.logs.infrastructure.logback.OpenTelemetryLogbackAppender">
        <instrumentationName>order-processing-service</instrumentationName>
        <includeBusinessContext>true</includeBusinessContext>
        <includeTraceContext>true</includeTraceContext>
    </appender>
    
    <!-- ===== FILTROS ===== -->
    
    <!-- Filtro para logs de alta frecuencia -->
    <turboFilter class="ch.qos.logback.classic.turbo.DuplicateMessageFilter">
        <allowedRepetitions>3</allowedRepetitions>
        <cacheSize>500</cacheSize>
    </turboFilter>
    
    <!-- ===== CONFIGURACIÓN POR AMBIENTE ===== -->
    
    <!-- Desarrollo: Logs detallados y coloridos -->
    <springProfile name="dev,development,local">
        <appender name="CONSOLE_COLOR" class="ch.qos.logback.core.ConsoleAppender">
            <encoder class="ch.qos.logback.core.encoder.LayoutWrappingEncoder">
                <layout class="ch.qos.logback.classic.PatternLayout">
                    <pattern>%cyan(%d{HH:mm:ss.SSS}) %green([%thread]) %highlight(%-5level) %blue([trace:%X{traceId:-}]) %magenta([biz:%X{businessId:-}]) %yellow([op:%X{operation:-}]) %cyan(%logger{20}) - %msg%n</pattern>
                </layout>
            </encoder>
        </appender>
        
        <root level="DEBUG">
            <appender-ref ref="CONSOLE_COLOR"/>
            <appender-ref ref="OTEL"/>
        </root>
    </springProfile>
    
    <!-- Testing: Logs estructurados sin colores -->
    <springProfile name="test">
        <root level="INFO">
            <appender-ref ref="CONSOLE_DEV"/>
        </root>
    </springProfile>
    
    <!-- Staging: Formato JSON pero con más detalle -->
    <springProfile name="staging">
        <root level="INFO">
            <appender-ref ref="JSON_PROD"/>
            <appender-ref ref="OTEL"/>
        </root>
    </springProfile>
    
    <!-- Producción: Formato optimizado para CloudWatch y collectors -->
    <springProfile name="prod,production">
        <root level="INFO">
            <appender-ref ref="CLOUDWATCH"/>
            <appender-ref ref="OTEL"/>
        </root>
    </springProfile>
    
    <!-- ===== CONFIGURACIÓN POR DEFECTO ===== -->
    <root level="INFO">
        <appender-ref ref="CONSOLE_COMPACT"/>
        <appender-ref ref="OTEL"/>
    </root>
    
    <!-- ===== LOGGERS ESPECÍFICOS ===== -->
    
    <!-- Logger para nuestro dominio con nivel DEBUG -->
    <logger name="pe.soapros.otel" level="DEBUG" additivity="false">
        <appender-ref ref="CONSOLE_DEV"/>
        <appender-ref ref="OTEL"/>
    </logger>
    
    <!-- Logger para servicios de negocio -->
    <logger name="pe.soapros.otel.examples.quarkus" level="DEBUG" additivity="false">
        <appender-ref ref="CONSOLE_DEV"/>
        <appender-ref ref="OTEL"/>
    </logger>
    
    <!-- Reducir verbosidad de frameworks -->
    <logger name="io.quarkus" level="INFO"/>
    <logger name="org.jboss" level="WARN"/>
    <logger name="io.netty" level="WARN"/>
    <logger name="io.opentelemetry" level="INFO"/>
    
    <!-- Logger específico para auditoría (siempre activo) -->
    <logger name="AUDIT" level="INFO" additivity="false">
        <appender-ref ref="JSON_PROD"/>
        <appender-ref ref="OTEL"/>
    </logger>
    
    <!-- Logger para métricas de performance -->
    <logger name="PERFORMANCE" level="INFO" additivity="false">
        <appender-ref ref="JSON_PROD"/>
    </logger>
    
    <!-- Logger para errores críticos de negocio -->
    <logger name="BUSINESS_ERROR" level="ERROR" additivity="false">
        <appender-ref ref="JSON_PROD"/>
        <appender-ref ref="OTEL"/>
    </logger>
    
</configuration>
```

```properties
# ===== CONFIGURACIÓN DE QUARKUS PARA LAMBDA =====

# Configuración básica de Quarkus
quarkus.application.name=order-processing-service
quarkus.application.version=1.0.0

# Lambda configuration
quarkus.lambda.handler=handler
quarkus.lambda.enable-polling-jvm-mode=true

# ===== OPENTELEMETRY CONFIGURATION =====

# Habilitación automática de OpenTelemetry
quarkus.otel.enabled=true
quarkus.otel.traces.enabled=true
quarkus.otel.metrics.enabled=true
quarkus.otel.logs.enabled=true

# Configuración del servicio
quarkus.otel.service.name=${SERVICE_NAME:order-processing-service}
quarkus.otel.service.version=${SERVICE_VERSION:1.0.0}

# Configuración del exportador OTLP
quarkus.otel.exporter.otlp.endpoint=${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4317}
quarkus.otel.exporter.otlp.headers=${OTEL_EXPORTER_OTLP_HEADERS:}

# Configuración de sampling
quarkus.otel.traces.sampler=${OTEL_TRACES_SAMPLER:traceidratio}
quarkus.otel.traces.sampler.arg=${OTEL_TRACES_SAMPLER_ARG:1.0}

# Configuración de recursos
quarkus.otel.resource.attributes=deployment.environment=${ENVIRONMENT:development},service.instance.id=${HOSTNAME:localhost}

# ===== LOGGING CONFIGURATION =====

# Configurar SLF4J como proveedor de logging
quarkus.log.handler.console.format=%d{yyyy-MM-dd HH:mm:ss,SSS} %-5p [%c{2.}] (%t) %s%e%n
quarkus.log.level=INFO
quarkus.log.category."pe.soapros.otel".level=DEBUG

# MDC support para correlación
quarkus.log.console.format=%d{yyyy-MM-dd HH:mm:ss.SSS} [%X{traceId:-},%X{spanId:-}] [%X{businessId:-}] %-5p %c{2.} - %m%n

# Configuración específica por ambiente
%dev.quarkus.log.level=DEBUG
%dev.quarkus.log.console.color=true
%dev.quarkus.otel.traces.sampler.arg=1.0

%test.quarkus.log.level=INFO
%test.quarkus.otel.enabled=false

%prod.quarkus.log.level=INFO
%prod.quarkus.log.console.json=true
%prod.quarkus.otel.traces.sampler.arg=0.1

# ===== CONFIGURACIÓN DE PERFORMANCE =====

# Optimizaciones para Lambda
quarkus.lambda.context-timeout=30s
quarkus.lambda.timeout=29s

# Native compilation optimizations
quarkus.native.enable-https-url-handler=true
quarkus.native.enable-all-security-services=true

# GraalVM optimizations para OpenTelemetry
quarkus.native.additional-build-args=--initialize-at-run-time=io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder

# ===== CONFIGURACIÓN DE DEPENDENCIAS =====

# Jackson configuration para JSON
quarkus.jackson.write-dates-as-timestamps=false
quarkus.jackson.serialization-inclusion=non_null

# Health checks
quarkus.health.enabled=true
quarkus.health.openapi.included=true

# ===== CONFIGURACIÓN ESPECÍFICA DE NEGOCIO =====

# Configuración de timeouts
app.order.processing.timeout=25s
app.payment.timeout=10s
app.notification.timeout=5s

# Configuración de retry
app.payment.retry.max-attempts=3
app.payment.retry.delay=1s

# Configuración de feature flags
app.features.payment-validation.enabled=true
app.features.async-notifications.enabled=true
app.features.enhanced-logging.enabled=true

# ===== CONFIGURACIÓN DE SERVICIOS EXTERNOS =====

# Payment service
payment.service.url=${PAYMENT_SERVICE_URL:https://api.payment.example.com}
payment.service.timeout=10s
payment.service.api-key=${PAYMENT_API_KEY:}

# Notification service
notification.service.url=${NOTIFICATION_SERVICE_URL:https://api.notification.example.com}
notification.service.timeout=5s

# Database (si se usa)
%dev.quarkus.datasource.db-kind=h2
%dev.quarkus.datasource.jdbc.url=jdbc:h2:mem:test
%dev.quarkus.hibernate-orm.database.generation=drop-and-create

%prod.quarkus.datasource.db-kind=postgresql
%prod.quarkus.datasource.jdbc.url=${DATABASE_URL}
%prod.quarkus.datasource.username=${DATABASE_USER}
%prod.quarkus.datasource.password=${DATABASE_PASSWORD}

# ===== CONFIGURACIÓN DE OBSERVABILIDAD AVANZADA =====

# Métricas personalizadas
quarkus.micrometer.enabled=true
quarkus.micrometer.export.prometheus.enabled=true
quarkus.micrometer.binder.http-client.enabled=true
quarkus.micrometer.binder.http-server.enabled=true
quarkus.micrometer.binder.jvm.enabled=true

# Configuración de batch para traces
quarkus.otel.exporter.otlp.traces.batch.max-export-batch-size=512
quarkus.otel.exporter.otlp.traces.batch.export-timeout=2s
quarkus.otel.exporter.otlp.traces.batch.schedule-delay=500ms

# Configuración de batch para métricas
quarkus.otel.exporter.otlp.metrics.export-interval=30s
quarkus.otel.exporter.otlp.metrics.batch.max-export-batch-size=512

# ===== CONFIGURACIÓN DE SEGURIDAD =====

# CORS (si es necesario)
quarkus.http.cors=true
quarkus.http.cors.origins=${CORS_ORIGINS:*}

# Rate limiting (si se implementa)
app.rate-limit.requests-per-minute=100
app.rate-limit.burst-capacity=50

# ===== CONFIGURACIÓN DE DESARROLLO =====

# Hot reload
%dev.quarkus.live-reload.instrumentation=true
%dev.quarkus.log.console.color=true

# Dev Services
%dev.quarkus.devservices.enabled=true

# ===== VARIABLES DE ENTORNO REQUERIDAS =====
# Las siguientes variables deben estar configuradas en el entorno:
# 
# OBLIGATORIAS:
# - OTEL_EXPORTER_OTLP_ENDPOINT: Endpoint del collector OpenTelemetry
# - SERVICE_NAME: Nombre del servicio (por defecto: order-processing-service)
# 
# OPCIONALES:
# - SERVICE_VERSION: Versión del servicio (por defecto: 1.0.0)
# - ENVIRONMENT: Ambiente (development/staging/production)
# - OTEL_EXPORTER_OTLP_HEADERS: Headers adicionales para el exporter
# - DATABASE_URL: URL de la base de datos (solo en producción)
# - PAYMENT_SERVICE_URL: URL del servicio de pagos
# - NOTIFICATION_SERVICE_URL: URL del servicio de notificaciones
```
## 📋 Ejemplo de Logs Correlacionados

### **Salida de Logs con Correlación:**

```
2025-08-03 16:30:15.123 [main] INFO  [4bf92f3577b34da6a3ce929d0e0e4736,00f067aa0ba902b7] com.example.UserService - 🚀 STARTING REQUEST PROCESSING
2025-08-03 16:30:15.145 [main] INFO  [4bf92f3577b34da6a3ce929d0e0e4736,00f067aa0ba902b7] com.example.UserService - 👤 RETRIEVING USER PROFILE
2025-08-03 16:30:15.200 [main] DEBUG [4bf92f3577b34da6a3ce929d0e0e4736,00f067aa0ba902b7] com.example.UserService - 🗄️ DATABASE OPERATION STARTED
2025-08-03 16:30:15.400 [main] DEBUG [4bf92f3577b34da6a3ce929d0e0e4736,00f067aa0ba902b7] com.example.UserService - ✅ DATABASE OPERATION COMPLETED
2025-08-03 16:30:15.405 [main] INFO  [4bf92f3577b34da6a3ce929d0e0e4736,b3ce929d0e0e4736] com.example.PermissionService - 🔐 VALIDATING USER PERMISSIONS
2025-08-03 16:30:15.505 [main] INFO  [4bf92f3577b34da6a3ce929d0e0e4736,00f067aa0ba902b7] com.example.UserService - ✅ USER PROFILE RETRIEVED SUCCESSFULLY
```

**Notar que:**
- Todos los logs tienen el **mismo traceId**: `4bf92f3577b34da6a3ce929d0e0e4736`
- Los logs del span principal tienen **spanId**: `00f067aa0ba902b7`
- Los logs del span hijo (permission check) tienen **spanId diferente**: `b3ce929d0e0e4736`

## 🏗️ Integración en Lambda Wrappers

### **Automática en HttpTracingLambdaWrapper:**

```java
public class MyLambda extends HttpTracingLambdaWrapper {
    
    @Override
    public APIGatewayProxyResponseEvent handle(APIGatewayProxyRequestEvent event, Context context) {
        
        // ✅ ESTOS LOGS AUTOMÁTICAMENTE INCLUYEN traceId y spanId
        logInfo("Processing user request", Map.of(
            "user.id", extractUserId(event),
            "operation", "process_user"
        ));
        
        try {
            // Lógica de negocio...
            
            logInfo("Request processed successfully", Map.of(
                "processing.duration_ms", String.valueOf(duration)
            ));
            
        } catch (Exception e) {
            // Error logs también incluyen correlación automática
            logError("Request processing failed", Map.of(
                "error.type", e.getClass().getSimpleName()
            ));
        }
    }
}
```

## 📊 Formatos de Logs Estructurados

### **JSON Output con Correlación:**

```json
{
  "timestamp": "2025-08-03T21:30:15.123Z",
  "level": "INFO",
  "logger": "com.example.UserService",
  "message": "User operation completed",
  "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
  "span_id": "00f067aa0ba902b7",
  "trace_sampled": true,
  "service_name": "user-service",
  "environment": "production",
  "user_id": "12345",
  "operation": "update_profile"
}
```

### **Logback JSON Encoder:**

```xml
<appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder">
        <providers>
            <timestamp/>
            <logLevel/>
            <loggerName/>
            <message/>
            <mdc/>
            <pattern>
                <pattern>
                    {
                    "trace_id": "%X{traceId:-}",
                    "span_id": "%X{spanId:-}",
                    "service_name": "my-service"
                    }
                </pattern>
            </pattern>
        </providers>
    </encoder>
</appender>
```

## 🔍 Queries de Búsqueda en Observabilidad

### **En Jaeger/Zipkin:**
```
trace_id:"4bf92f3577b34da6a3ce929d0e0e4736"
```

### **En ElasticSearch/OpenSearch:**
```json
{
  "query": {
    "term": {
      "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736"
    }
  }
}
```

### **En CloudWatch Logs:**
```
fields @timestamp, @message, trace_id, span_id
| filter trace_id = "4bf92f3577b34da6a3ce929d0e0e4736"
| sort @timestamp asc
```

## 🚀 Configuración Recomendada

### **1. Para Desarrollo:**
```xml
<pattern>%d{HH:mm:ss.SSS} [%thread] %-5level [%X{traceId:-},%X{spanId:-}] %logger{36} - %msg%n</pattern>
```

### **2. Para Producción:**
```xml
<!-- JSON estructurado con todos los campos de correlación -->
<encoder class="net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder">
    <providers>
        <timestamp/>
        <logLevel/>
        <loggerName/>
        <message/>
        <mdc/>
        <stackTrace/>
    </providers>
</encoder>
```

### **3. Variables de Entorno:**
```bash
# Para configurar el servicio
OTEL_SERVICE_NAME=my-service
OTEL_SERVICE_VERSION=1.0.0
ENVIRONMENT=production

# Para configurar exportación
OTEL_EXPORTER_OTLP_ENDPOINT=https://api.honeycomb.io
OTEL_EXPORTER_OTLP_HEADERS=x-honeycomb-team=your-api-key
```

## 💡 Beneficios

1. **🔍 Debugging Mejorado**: Encuentra todos los logs relacionados con un trace
2. **📊 Observabilidad Completa**: Correlación automática entre logs, traces y métricas  
3. **🚀 Zero Configuration**: Funciona automáticamente sin configuración adicional
4. **🔧 Flexibilidad**: Compatible con loggers tradicionales y OpenTelemetry nativo
5. **📈 Performance**: Mínimo overhead, máximo beneficio

## 🎯 Casos de Uso

- **Debugging de Errores**: Seguir todo el flujo de una request fallida
- **Performance Analysis**: Correlacionar logs de timing con traces
- **Security Auditing**: Rastrear operaciones sensibles por trace
- **Business Intelligence**: Analizar patrones de uso por trace completo

¡La correlación automática hace que debugging y observabilidad sean mucho más poderosos! 🚀