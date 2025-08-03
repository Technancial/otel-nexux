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