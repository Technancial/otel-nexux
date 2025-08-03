# 📋 Ejemplos de uso de otel-observability

Este directorio contiene ejemplos prácticos de cómo usar la librería de observabilidad otel-observability en diferentes escenarios de AWS Lambda.

## 🗂️ Estructura de ejemplos

```
examples/
├── src/main/java/pe/soapros/otel/lambda/examples/
│   ├── HttpLambdaExample.java         # Ejemplo básico de Lambda HTTP con API Gateway
│   ├── AdvancedHttpLambdaExample.java # Ejemplo avanzado con spans anidados
│   ├── SqsLambdaExample.java          # Ejemplo de Lambda con SQS
│   └── KafkaLambdaExample.java        # Ejemplo de Lambda con Kafka
└── README.md                          # Este archivo
```

## 🚀 Ejemplos disponibles

### 1. HttpLambdaExample
**Archivo:** `HttpLambdaExample.java`

Ejemplo básico que demuestra el uso fundamental del `HttpTracingLambdaWrapper`.

**Características mostradas:**
- Configuración básica del wrapper HTTP
- Procesamiento simple de requests
- Uso del span principal automático
- Respuestas HTTP simples

### 2. AdvancedHttpLambdaExample
**Archivo:** `AdvancedHttpLambdaExample.java`

Ejemplo avanzado que demuestra cómo enriquecer las trazas con spans anidados y eventos personalizados.

**Características mostradas:**
- Creación de spans anidados para diferentes etapas
- Agregado de eventos y atributos personalizados
- Manejo de errores granular por span
- Correlación entre spans padre e hijos
- Medición de tiempo por operación

**Etapas de procesamiento:**
- `validate-request` - Validación de entrada con span dedicado
- `business-processing` - Lógica de negocio con métricas
- `persist-data` - Persistencia con atributos específicos

### 2. SqsLambdaExample
**Archivo:** `SqsLambdaExample.java`

Demuestra el uso del `SqsTracingLambdaWrapper` para funciones Lambda que procesan mensajes de Amazon SQS.

**Características mostradas:**
- Procesamiento automático de mensajes SQS
- Parsing de mensajes JSON con validación
- Enriquecimiento de spans con metadata de SQS
- Manejo de diferentes tipos de mensajes
- Gestión de errores específicos de colas
- Simulación de operaciones de base de datos

**Tipos de mensajes soportados:**
- `order` - Mensajes de pedidos con validación y persistencia
- `notification` - Mensajes de notificación
- `data-sync` - Mensajes de sincronización de datos
- `error-test` - Mensajes para testing de errores

### 3. KafkaLambdaExample
**Archivo:** `KafkaLambdaExample.java`

Demuestra el uso del `KafkaTracingLambdaWrapper` para funciones Lambda que procesan eventos de Apache Kafka.

**Características mostradas:**
- Procesamiento automático de records de Kafka
- Manejo de headers y metadata de Kafka
- Determinación de tipos de evento basada en topics
- Parsing de diferentes formatos de eventos
- Gestión de errores de streaming
- Correlación entre mensajes

**Tipos de eventos soportados:**
- `user-events` - Eventos de acciones de usuario
- `order-events` - Eventos de ciclo de vida de pedidos
- `inventory-events` - Eventos de inventario
- `analytics-events` - Eventos de analytics

## 🛠️ Cómo usar los ejemplos

### 1. Compilar los ejemplos

```bash
# Desde el directorio raíz del proyecto
mvn clean compile

# Solo compilar el módulo de ejemplos
cd examples
mvn compile
```

### 2. Configurar variables de entorno

Para todos los ejemplos, asegúrate de tener configuradas estas variables:

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT="http://your-collector:4317"
export OTEL_SERVICE_NAME="lambda-examples"
export OTEL_SERVICE_VERSION="1.0.0"
export OTEL_LAMBDA_ENABLE_DETAILED_METRICS="true"
export OTEL_LAMBDA_ENABLE_COLD_START_DETECTION="true"
```

### 3. Usar con AWS SAM

Crea un `template.yaml` en tu proyecto:

```yaml
AWSTemplateFormatVersion: '2010-09-09'
Transform: AWS::Serverless-2016-10-31

Globals:
  Function:
    Runtime: java17
    Timeout: 30
    Environment:
      Variables:
        OTEL_EXPORTER_OTLP_ENDPOINT: "http://your-collector:4317"
        OTEL_SERVICE_NAME: "lambda-examples"
        OTEL_SERVICE_VERSION: "1.0.0"

Resources:
  HttpFunction:
    Type: AWS::Serverless::Function
    Properties:
      CodeUri: examples/target/classes
      Handler: pe.soapros.otel.lambda.examples.HttpLambdaExample::handleRequest
      Events:
        Api:
          Type: Api
          Properties:
            Path: /{operation}
            Method: any

  SqsFunction:
    Type: AWS::Serverless::Function
    Properties:
      CodeUri: examples/target/classes
      Handler: pe.soapros.otel.lambda.examples.SqsLambdaExample::handleRequest
      Events:
        SqsEvent:
          Type: SQS
          Properties:
            Queue: !Ref MyQueue

  KafkaFunction:
    Type: AWS::Serverless::Function
    Properties:
      CodeUri: examples/target/classes
      Handler: pe.soapros.otel.lambda.examples.KafkaLambdaExample::handleRequest
      Events:
        KafkaEvent:
          Type: MSK
          Properties:
            StartingPosition: LATEST
            Stream: !Ref KafkaCluster
            Topics:
              - user-events
              - order-events

  MyQueue:
    Type: AWS::SQS::Queue
    Properties:
      QueueName: example-queue
```

### 4. Testing local

```bash
# Iniciar API local
sam local start-api

# Test HTTP endpoint
curl -X POST http://localhost:3000/calculate \
  -H "Content-Type: application/json" \
  -H "X-User-ID: user123" \
  -d '{"value": 42}'

# Test con diferentes operaciones
curl http://localhost:3000/validate -d '{"data": "test"}'
curl http://localhost:3000/transform -d '{"text": "hello world"}'
curl http://localhost:3000/error  # Para probar manejo de errores

# Test función SQS
sam local invoke SqsFunction --event events/sqs-event.json

# Test función Kafka
sam local invoke KafkaFunction --event events/kafka-event.json
```

### 5. Eventos de ejemplo

Crea archivos de eventos para testing:

**events/sqs-event.json:**
```json
{
  "Records": [
    {
      "messageId": "test-msg-123",
      "receiptHandle": "test-receipt-handle",
      "body": "{\"type\":\"order\",\"data\":{\"id\":\"order-123\"},\"priority\":\"high\"}",
      "attributes": {
        "ApproximateReceiveCount": "1",
        "SentTimestamp": "1640995200000"
      },
      "messageAttributes": {},
      "eventSourceARN": "arn:aws:sqs:us-east-1:123456789012:example-queue"
    }
  ]
}
```

**events/kafka-event.json:**
```json
{
  "eventSource": "aws:kafka",
  "records": {
    "user-events-0": [
      {
        "topic": "user-events",
        "partition": 0,
        "offset": 123,
        "timestamp": 1640995200000,
        "timestampType": "CREATE_TIME",
        "value": "{\"userId\":\"user123\",\"action\":\"login\",\"timestamp\":1640995200000}",
        "headers": {
          "correlationId": "corr-123",
          "messageType": "user-action"
        }
      }
    ]
  }
}
```

## 📊 Observabilidad en los ejemplos

Todos los ejemplos generan automáticamente:

### Métricas
- Contadores de mensajes procesados/errores
- Histogramas de duración de procesamiento  
- Métricas de cold start

### Trazas
- Spans automáticos por mensaje/request
- Sub-spans para operaciones específicas
- Contexto propagado entre componentes
- Atributos semánticos estándar

### Logs estructurados
- Correlación con trazas via trace/span IDs
- Información contextual automática
- Manejo de errores con stack traces

## 🔧 Personalización

Para adaptar los ejemplos a tu caso de uso:

1. **Modifica los tipos de mensaje/evento** según tu dominio
2. **Ajusta la lógica de procesamiento** para tu negocio específico
3. **Configura las métricas** según tus KPIs
4. **Personaliza los atributos de spans** para tu contexto
5. **Adapta el manejo de errores** a tus políticas de retry/DLQ

## 🚨 Consideraciones de producción

- **Configurar apropiadamente el collector** OpenTelemetry
- **Optimizar el sampling** para reducir overhead
- **Configurar timeouts** apropiados para el contexto
- **Implementar circuit breakers** para servicios externos
- **Monitorear el overhead** de instrumentación
- **Configurar alertas** basadas en las métricas generadas