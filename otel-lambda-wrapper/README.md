# 📘 `otel-lambda-wrapper`

Submódulo de la librería de observabilidad que proporciona wrappers reutilizables para funciones AWS Lambda. Facilita la instrumentación de trazas (traces) mediante OpenTelemetry, extrayendo y propagando automáticamente el contexto de trazabilidad desde API Gateway (u otros orígenes).

## ✨ Funcionalidades

- Wrapper base para funciones Lambda HTTP (`APIGatewayProxyRequestEvent`).
- Extracción automática del contexto `traceparent` (W3C Trace Context).
- Creación de spans asociados a cada ejecución.
- Manejo de excepciones y cierre adecuado de spans.
- Preparado para extenderse con soporte para eventos SQS, Kafka, etc.

---

## 🏗️ Estructura del submódulo
otel-lambda-wrapper/
└── src/main/java/pe/soapros/otel/lambda/infrastructure/
├── HttpTracingLambdaWrapper.java       # Wrapper abstracto para funciones HTTP
├── SpanManager.java                    # Utilitario para iniciar/cerrar spans
└── TraceContextExtractor.java          # Utilitario para extraer contexto de headers

---

## 🚀 Cómo usar el wrapper

### 1. Crear tu función Lambda extendiendo el wrapper

```java
package com.example.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import pe.soapros.otel.lambda.infrastructure.HttpTracingLambdaWrapper;

public class MyLambdaHandler extends HttpTracingLambdaWrapper {

    @Override
    public APIGatewayProxyResponseEvent handle(APIGatewayProxyRequestEvent event, Context context) {
        Span span = Span.current();
        span.addEvent("Inicio de proceso");

        TracingUtils tracingUtils = new TracingUtils(GlobalOpenTelemetry.getTracer("pe.soapros.otel.lambda"));

        tracingUtils.withSpan("procesar-payload", () -> {
            simulateBusinessLogic();
            Span.current().addEvent("Fin del procesamiento del payload");
        });

        span.addEvent("Listo para devolver respuesta");

        return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withBody("OK");
    }
}
```
### para SQS:

```java
package com.example.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import pe.soapros.otel.lambda.infrastructure.SqsTracingLambdaWrapper;
import pe.soapros.otel.lambda.utils.TracingUtils;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.GlobalOpenTelemetry;

public class MySqsHandler extends SqsTracingLambdaWrapper {

    public MySqsHandler() {
        super(GlobalOpenTelemetry.get());
    }

    @Override
    public void handle(SQSEvent.SQSMessage message, Context context) {
        Span span = Span.current(); // este es el span creado por el wrapper
        span.addEvent("Inicio del procesamiento del mensaje SQS");

        TracingUtils tracingUtils = new TracingUtils(GlobalOpenTelemetry.getTracer("pe.soapros.otel.lambda"));

        tracingUtils.withSpan("procesar-evento-sqs", () -> {
            simulateBusinessLogic();
            Span.current().addEvent("Lógica de negocio finalizada");
        });

        span.addEvent("Fin del procesamiento del mensaje SQS");
    }

    private void simulateBusinessLogic() {
        try {
            Thread.sleep(200); // Simulación
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

### Para Kafka:
```java
package com.example.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import pe.soapros.otel.lambda.infrastructure.KafkaTracingLambdaWrapper;
import pe.soapros.otel.lambda.model.KafkaEventRecord;
import pe.soapros.otel.lambda.utils.TracingUtils;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.GlobalOpenTelemetry;

public class MyKafkaHandler extends KafkaTracingLambdaWrapper {

    public MyKafkaHandler() {
        super(GlobalOpenTelemetry.get());
    }

    @Override
    public void handleRecord(KafkaEventRecord record, Context context) {
        Span span = Span.current();
        span.addEvent("Inicio del procesamiento del mensaje Kafka");

        TracingUtils tracingUtils = new TracingUtils(GlobalOpenTelemetry.getTracer("pe.soapros.otel.lambda"));

        tracingUtils.withSpan("analizar-mensaje-kafka", () -> {
            simulateKafkaProcessing(record);
            Span.current().addEvent("Procesamiento del mensaje finalizado");
        });

        span.addEvent("Fin del procesamiento del mensaje Kafka");
    }

    private void simulateKafkaProcessing(KafkaEventRecord record) {
        System.out.println("Procesando payload: " + record.valueAsString());
    }
}
```
### 2. Variables de entorno esperadas

Tu Lambda debe tener configurada esta variable:

|Variable|Descripción|Ejemplo|
|----|---|---|
|OTEL_EXPORTER_OTLP_ENDPOINT|URL del collector OpenTelemetry|
http://otel-collector:4317|

## ✅ Requisitos
•	Java 17 o superior
•	OpenTelemetry Java SDK 1.52.0
•	AWS Lambda Java Runtime


## 🧪 Pruebas

Para probar el wrapper, puedes usar un entorno local con AWS SAM o Testcontainers. Recomendamos instrumentar también los consumidores (SQS, Kafka, etc.) usando otros wrappers de este submódulo.

📦 Extensiones futuras
•	Soporte para eventos tipo SQS, SNS, Kafka.
•	Wrapper genérico para Lambdas que lean desde streams.
•	Incorporación con métricas y logs.