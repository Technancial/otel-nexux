# 📘 `otel-lambda-wrapper`

Submódulo de la librería de observabilidad que proporciona wrappers reutilizables para funciones AWS Lambda. Facilita la instrumentación completa de trazas (traces) y métricas mediante OpenTelemetry, extrayendo y propagando automáticamente el contexto de trazabilidad desde diferentes orígenes de eventos.

## ✨ Funcionalidades

- **Wrappers especializados** para HTTP (API Gateway), SQS y Kafka
- **Extracción automática** del contexto `traceparent` (W3C Trace Context)
- **Métricas automáticas** con contadores, histogramas y detección de cold starts
- **Enriquecimiento de spans** con atributos semánticos estándar
- **Configuración flexible** mediante variables de entorno
- **Manejo robusto de errores** con spans y métricas apropiadas
- **Thread-safe** con inicialización lazy de recursos

---

## 🏗️ Estructura del submódulo
```
otel-lambda-wrapper/
└── src/main/java/pe/soapros/otel/lambda/infrastructure/
    ├── HttpTracingLambdaWrapper.java    # Wrapper para HTTP (API Gateway)
    ├── SqsTracingLambdaWrapper.java     # Wrapper para SQS
    ├── KafkaTracingLambdaWrapper.java   # Wrapper para Kafka
    ├── SpanManager.java                 # Utilitario para gestión de spans
    └── TraceContextExtractor.java       # Extractor de contexto de trazas
```

---

## 🚀 Cómo usar el wrapper

### 1. Wrapper para HTTP (API Gateway)

```java
package com.example.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import pe.soapros.otel.lambda.infrastructure.HttpTracingLambdaWrapper;
import pe.soapros.otel.traces.infrastructure.config.OpenTelemetryConfiguration;
import io.opentelemetry.api.trace.Span;

public class MyHttpLambdaHandler extends HttpTracingLambdaWrapper {

    public MyHttpLambdaHandler() {
        super(OpenTelemetryConfiguration.getOpenTelemetry());
    }

    @Override
    protected APIGatewayProxyResponseEvent handleHttpRequest(
            APIGatewayProxyRequestEvent event, Context context) {
        
        // El wrapper ya ha creado el span y configurado las métricas
        Span span = Span.current();
        span.addEvent("Procesando request HTTP");

        // Tu lógica de negocio aquí
        String result = processBusinessLogic(event.getBody());
        
        span.addEvent("Request HTTP procesado exitosamente");

        // Usar el método helper para crear respuesta exitosa
        return createSuccessResponse(Map.of("result", result, "timestamp", Instant.now().toString()));
    }

    private String processBusinessLogic(String body) {
        // Simular procesamiento
        return "Processed: " + (body != null ? body.length() + " chars" : "empty");
    }
}
```
### 2. Wrapper para SQS

```java
package com.example.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import pe.soapros.otel.lambda.infrastructure.SqsTracingLambdaWrapper;
import pe.soapros.otel.traces.infrastructure.config.OpenTelemetryConfiguration;
import io.opentelemetry.api.trace.Span;

public class MySqsLambdaHandler extends SqsTracingLambdaWrapper {

    public MySqsLambdaHandler() {
        super(OpenTelemetryConfiguration.getOpenTelemetry());
    }

    @Override
    public void handle(SQSEvent.SQSMessage message, Context context) {
        // El wrapper ya ha creado el span y configurado las métricas automáticamente
        Span span = Span.current();
        span.addEvent("Procesando mensaje SQS");

        // Tu lógica de negocio aquí
        processMessage(message);
        
        span.addEvent("Mensaje SQS procesado exitosamente");
    }

    private void processMessage(SQSEvent.SQSMessage message) {
        // Lógica específica del mensaje
        String body = message.getBody();
        String messageId = message.getMessageId();
        
        // Simular procesamiento
        System.out.println("Procesando mensaje: " + messageId + " con body: " + body);
        
        // Aquí iría tu lógica real de procesamiento
        // Por ejemplo: guardar en base de datos, llamar a otra API, etc.
    }
}
```

### 3. Wrapper para Kafka

```java
package com.example.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.KafkaEvent;
import pe.soapros.otel.lambda.infrastructure.KafkaTracingLambdaWrapper;
import pe.soapros.otel.traces.infrastructure.config.OpenTelemetryConfiguration;
import io.opentelemetry.api.trace.Span;

public class MyKafkaLambdaHandler extends KafkaTracingLambdaWrapper {

    public MyKafkaLambdaHandler() {
        super(OpenTelemetryConfiguration.getOpenTelemetry());
    }

    @Override
    protected void handleRecord(KafkaEvent.KafkaEventRecord record, Context context) {
        // El wrapper ya ha creado el span y configurado las métricas automáticamente
        Span span = Span.current();
        span.addEvent("Procesando mensaje Kafka");

        // Tu lógica de negocio aquí
        processKafkaMessage(record);
        
        span.addEvent("Mensaje Kafka procesado exitosamente");
    }

    private void processKafkaMessage(KafkaEvent.KafkaEventRecord record) {
        // Obtener información del mensaje
        String topic = record.getTopic();
        String value = record.getValue();
        long offset = record.getOffset();
        int partition = record.getPartition();
        
        // Simular procesamiento
        System.out.printf("Procesando mensaje de topic '%s', partition %d, offset %d: %s%n", 
                         topic, partition, offset, value);
        
        // Aquí iría tu lógica real de procesamiento
        // Por ejemplo: transformar datos, enviar a otra cola, etc.
    }
}
```
## 🔧 Configuración

### Variables de entorno esperadas

Tu Lambda debe tener configuradas estas variables:

| Variable | Descripción | Ejemplo | Requerido |
|----------|-------------|---------|-----------|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | URL del collector OpenTelemetry | `http://otel-collector:4317` | ✅ |
| `OTEL_SERVICE_NAME` | Nombre del servicio para las trazas | `my-lambda-service` | ⚠️ |
| `OTEL_SERVICE_VERSION` | Versión del servicio | `1.0.0` | ⚠️ |
| `OTEL_LAMBDA_ENABLE_DETAILED_METRICS` | Habilitar métricas detalladas | `true` | ❌ (default: true) |
| `OTEL_LAMBDA_ENABLE_COLD_START_DETECTION` | Habilitar detección de cold start | `true` | ❌ (default: true) |
| `KAFKA_CONSUMER_GROUP` | Grupo de consumidor Kafka (solo para Kafka) | `my-consumer-group` | ❌ |

### Métricas automáticas generadas

Los wrappers generan automáticamente las siguientes métricas:

**HTTP Wrapper:**
- `http_requests_total` - Total de requests HTTP procesados
- `http_request_errors_total` - Total de errores en requests HTTP
- `http_request_duration_seconds` - Duración de procesamiento de requests HTTP
- `http_lambda_cold_starts_total` - Total de cold starts para HTTP

**SQS Wrapper:**
- `sqs_messages_processed_total` - Total de mensajes SQS procesados
- `sqs_messages_errors_total` - Total de errores en mensajes SQS
- `sqs_message_processing_duration_seconds` - Duración de procesamiento de mensajes SQS
- `sqs_lambda_cold_starts_total` - Total de cold starts para SQS

**Kafka Wrapper:**
- `kafka_messages_processed_total` - Total de mensajes Kafka procesados
- `kafka_messages_errors_total` - Total de errores en mensajes Kafka
- `kafka_message_processing_duration_seconds` - Duración de procesamiento de mensajes Kafka
- `kafka_lambda_cold_starts_total` - Total de cold starts para Kafka

## ✅ Requisitos

- **Java 17** o superior
- **OpenTelemetry Java SDK 1.52.0** o superior
- **AWS Lambda Java Runtime**
- **Dependencias de la librería otel-observability**

## 🧪 Pruebas

Para probar los wrappers, puedes usar:

1. **AWS SAM Local** - Para testing local con eventos simulados
2. **Testcontainers** - Para integration testing con collector real
3. **Unit Tests** - Para verificar la lógica de negocio

```bash
# Ejemplo de evento SQS para testing
sam local invoke MySqsFunction --event events/sqs-event.json

# Ejemplo de evento HTTP para testing  
sam local start-api
curl -X POST http://localhost:3000/api/endpoint -d '{"test": "data"}'
```

## 📊 Observabilidad

Los wrappers proporcionan observabilidad completa:

- **Trazas (Traces)**: Spans automáticos con contexto propagado
- **Métricas**: Contadores, histogramas y métricas de rendimiento
- **Atributos**: Metadatos semánticos estándar de OpenTelemetry
- **Correlación**: IDs de correlación y contexto de usuario
- **Cold Start Detection**: Detección y medición de arranques en frío

## 🚨 Manejo de errores

Los wrappers manejan automáticamente:

- **Excepciones capturadas** con spans marcados como error
- **Métricas de error** con etiquetas apropiadas
- **Propagación de excepciones** sin interferir con la lógica
- **Status codes** apropiados en spans

## 🎯 Enriquecimiento avanzado de trazas

Los wrappers proporcionan un span principal automático, pero puedes agregar spans anidados y eventos para mayor granularidad:

### Agregar eventos al span principal

```java
import io.opentelemetry.api.trace.Span;

@Override
public APIGatewayProxyResponseEvent handleHttpRequest(
        APIGatewayProxyRequestEvent event, Context context) {
    
    Span currentSpan = Span.current();
    
    // Agregar eventos con timestamps automáticos
    currentSpan.addEvent("Iniciando validación de entrada");
    
    // Agregar eventos con atributos
    currentSpan.addEvent("Usuario autenticado", 
        Attributes.of(AttributeKey.stringKey("user.id"), "12345"));
    
    // Agregar atributos personalizados al span principal
    currentSpan.setAttribute("business.operation", "calculate-price");
    currentSpan.setAttribute("request.size", event.getBody().length());
    
    // Tu lógica aquí...
    
    currentSpan.addEvent("Procesamiento completado");
    
    return createSuccessResponse(result);
}
```

### Crear spans anidados para operaciones específicas

```java
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;

public class MyAdvancedHttpHandler extends HttpTracingLambdaWrapper {
    
    private final Tracer tracer = GlobalOpenTelemetry.get()
            .getTracer("my.application.business");

    @Override
    public APIGatewayProxyResponseEvent handleHttpRequest(
            APIGatewayProxyRequestEvent event, Context context) {
        
        // El span principal ya está activo
        Span.current().addEvent("Iniciando procesamiento de pedido");
        
        // Crear span para validación
        Span validationSpan = tracer.spanBuilder("validate-order")
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();
        
        try (Scope scope = validationSpan.makeCurrent()) {
            validationSpan.addEvent("Validando datos de entrada");
            validateOrder(event.getBody());
            validationSpan.setStatus(StatusCode.OK);
        } catch (ValidationException e) {
            validationSpan.recordException(e);
            validationSpan.setStatus(StatusCode.ERROR, "Validation failed");
            throw e;
        } finally {
            validationSpan.end();
        }
        
        // Crear span para llamada a base de datos
        Span dbSpan = tracer.spanBuilder("save-order")
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();
        
        try (Scope scope = dbSpan.makeCurrent()) {
            dbSpan.setAttribute("db.system", "postgresql");
            dbSpan.setAttribute("db.table", "orders");
            dbSpan.addEvent("Iniciando transacción");
            
            String orderId = saveOrderToDatabase(event.getBody());
            
            dbSpan.setAttribute("order.id", orderId);
            dbSpan.addEvent("Orden guardada exitosamente");
            dbSpan.setStatus(StatusCode.OK);
            
            return createSuccessResponse(Map.of("orderId", orderId));
            
        } catch (DatabaseException e) {
            dbSpan.recordException(e);
            dbSpan.setStatus(StatusCode.ERROR, "Database operation failed");
            throw e;
        } finally {
            dbSpan.end();
        }
    }
}
```

### Propagar contexto a servicios externos

```java
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;

public class MyServiceIntegrationHandler extends HttpTracingLambdaWrapper {
    
    private final HttpClient httpClient = HttpClient.newBuilder().build();
    private final Tracer tracer = GlobalOpenTelemetry.get()
            .getTracer("my.application.external");

    @Override
    public APIGatewayProxyResponseEvent handleHttpRequest(
            APIGatewayProxyRequestEvent event, Context context) {
        
        // Crear span para llamada externa
        Span httpSpan = tracer.spanBuilder("call-external-service")
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();
        
        try (Scope scope = httpSpan.makeCurrent()) {
            // Configurar atributos HTTP
            httpSpan.setAttribute("http.method", "POST");
            httpSpan.setAttribute("http.url", "https://api.external-service.com/process");
            
            // Crear headers con contexto de traza
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");
            
            // Inyectar contexto de traza en headers HTTP
            GlobalOpenTelemetry.get().getPropagators().getTextMapPropagator()
                .inject(Context.current(), headers, new TextMapSetter<Map<String, String>>() {
                    @Override
                    public void set(Map<String, String> carrier, String key, String value) {
                        carrier.put(key, value);
                    }
                });
            
            httpSpan.addEvent("Enviando request a servicio externo");
            
            // Hacer la llamada HTTP
            String response = callExternalService(headers, event.getBody());
            
            httpSpan.setAttribute("http.status_code", 200);
            httpSpan.addEvent("Respuesta recibida del servicio externo");
            httpSpan.setStatus(StatusCode.OK);
            
            return createSuccessResponse(Map.of("external_response", response));
            
        } catch (Exception e) {
            httpSpan.recordException(e);
            httpSpan.setAttribute("http.status_code", 500);
            httpSpan.setStatus(StatusCode.ERROR, "External service call failed");
            throw e;
        } finally {
            httpSpan.end();
        }
    }
}
```

### Correlación con IDs de negocio

```java
public class MyCorrelatedHandler extends SqsTracingLambdaWrapper {
    
    private final Tracer tracer = GlobalOpenTelemetry.get()
            .getTracer("my.application.correlation");

    @Override
    public void handle(SQSEvent.SQSMessage message, Context context) {
        
        Span currentSpan = Span.current();
        
        // Extraer ID de correlación del mensaje
        String correlationId = extractCorrelationId(message);
        String userId = extractUserId(message);
        
        // Agregar IDs de correlación al span principal
        currentSpan.setAttribute("correlation.id", correlationId);
        currentSpan.setAttribute("user.id", userId);
        currentSpan.setAttribute("message.type", "order_processing");
        
        currentSpan.addEvent("Iniciando procesamiento correlacionado",
            Attributes.of(
                AttributeKey.stringKey("correlation.id"), correlationId,
                AttributeKey.stringKey("stage"), "start"
            ));
        
        // Crear span para cada etapa del procesamiento
        processWithStages(message, correlationId, userId);
        
        currentSpan.addEvent("Procesamiento correlacionado completado");
    }
    
    private void processWithStages(SQSEvent.SQSMessage message, String correlationId, String userId) {
        
        // Etapa 1: Validación
        Span validationSpan = tracer.spanBuilder("validate-message")
                .setAttribute("correlation.id", correlationId)
                .setAttribute("user.id", userId)
                .startSpan();
        
        try (Scope scope = validationSpan.makeCurrent()) {
            validationSpan.addEvent("Validando estructura del mensaje");
            validateMessage(message);
            validationSpan.addEvent("Mensaje válido");
        } finally {
            validationSpan.end();
        }
        
        // Etapa 2: Enriquecimiento
        Span enrichmentSpan = tracer.spanBuilder("enrich-message")
                .setAttribute("correlation.id", correlationId)
                .setAttribute("user.id", userId)
                .startSpan();
        
        try (Scope scope = enrichmentSpan.makeCurrent()) {
            enrichmentSpan.addEvent("Enriqueciendo datos del usuario");
            enrichMessage(message, userId);
            enrichmentSpan.addEvent("Datos enriquecidos");
        } finally {
            enrichmentSpan.end();
        }
        
        // Etapa 3: Persistencia
        Span persistenceSpan = tracer.spanBuilder("persist-message")
                .setAttribute("correlation.id", correlationId)
                .setAttribute("user.id", userId)
                .startSpan();
        
        try (Scope scope = persistenceSpan.makeCurrent()) {
            persistenceSpan.addEvent("Guardando en base de datos");
            persistMessage(message);
            persistenceSpan.addEvent("Datos persistidos");
        } finally {
            persistenceSpan.end();
        }
    }
}
```

### Métricas personalizadas en conjunto con trazas

```java
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.DoubleHistogram;

public class MyMetricsHandler extends KafkaTracingLambdaWrapper {
    
    private final Tracer tracer = GlobalOpenTelemetry.get()
            .getTracer("my.application.metrics");
    private final Meter meter = GlobalOpenTelemetry.get()
            .getMeter("my.application.custom.metrics");
    
    // Métricas personalizadas
    private final LongCounter businessOperationsCounter = meter.counterBuilder("business_operations_total")
            .setDescription("Total business operations processed")
            .build();
    
    private final DoubleHistogram businessLatencyHistogram = meter.histogramBuilder("business_operation_duration_seconds")
            .setDescription("Business operation processing time")
            .setUnit("s")
            .build();

    @Override
    protected void handleRecord(KafkaEvent.KafkaEventRecord record, Context context) {
        
        Span currentSpan = Span.current();
        String operationType = extractOperationType(record);
        
        currentSpan.setAttribute("business.operation.type", operationType);
        currentSpan.addEvent("Iniciando operación de negocio");
        
        Instant startTime = Instant.now();
        
        // Crear span para la operación de negocio
        Span businessSpan = tracer.spanBuilder("business-operation-" + operationType)
                .setAttribute("operation.type", operationType)
                .startSpan();
        
        try (Scope scope = businessSpan.makeCurrent()) {
            
            businessSpan.addEvent("Procesando lógica de negocio");
            
            // Ejecutar lógica de negocio
            executeBusinessLogic(record, operationType);
            
            // Medir tiempo y registrar métricas
            Duration duration = Duration.between(startTime, Instant.now());
            double durationSeconds = duration.toNanos() / 1_000_000_000.0;
            
            // Registrar métricas personalizadas
            businessOperationsCounter.add(1, Attributes.of(
                AttributeKey.stringKey("operation.type"), operationType,
                AttributeKey.stringKey("status"), "success"
            ));
            
            businessLatencyHistogram.record(durationSeconds, Attributes.of(
                AttributeKey.stringKey("operation.type"), operationType
            ));
            
            // Agregar métricas como atributos del span
            businessSpan.setAttribute("operation.duration_ms", duration.toMillis());
            businessSpan.addEvent("Operación de negocio completada exitosamente");
            
        } catch (Exception e) {
            
            Duration duration = Duration.between(startTime, Instant.now());
            
            // Registrar métricas de error
            businessOperationsCounter.add(1, Attributes.of(
                AttributeKey.stringKey("operation.type"), operationType,
                AttributeKey.stringKey("status"), "error"
            ));
            
            businessSpan.recordException(e);
            businessSpan.setAttribute("operation.duration_ms", duration.toMillis());
            businessSpan.addEvent("Error en operación de negocio");
            
            throw e;
        } finally {
            businessSpan.end();
        }
    }
}
```

### Mejores prácticas para spans personalizados

1. **Naming Convention**: Usa nombres descriptivos para spans (`validate-order`, `save-customer`)
2. **Span Kinds**: Configura el tipo apropiado:
   - `SpanKind.INTERNAL` - Operaciones internas
   - `SpanKind.CLIENT` - Llamadas a servicios externos
   - `SpanKind.SERVER` - Endpoints que reciben requests
3. **Attributes**: Agrega contexto relevante (`user.id`, `order.amount`)
4. **Events**: Marca hitos importantes en el procesamiento
5. **Error Handling**: Siempre marca spans como error cuando corresponda
6. **Resource Management**: Usa try-with-resources para garantizar que spans se cierren