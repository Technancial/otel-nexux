# 📋 Ejemplos de uso de otel-observability

Este directorio contiene ejemplos prácticos de cómo usar la librería de observabilidad otel-observability en diferentes escenarios de AWS Lambda.

## 🗂️ Estructura de ejemplos

```
examples/
├── src/main/java/pe/soapros/otel/lambda/examples/
│   ├── HttpLambdaExample.java                # Ejemplo básico de Lambda HTTP con API Gateway
│   ├── ComprehensiveHttpLambdaExample.java   # Ejemplo completo con múltiples endpoints
│   ├── ProductApiLambdaExample.java          # Ejemplo práctico de API de productos
│   ├── AdvancedHttpLambdaExample.java        # Ejemplo avanzado con spans anidados
│   ├── SqsLambdaExample.java                 # Ejemplo de Lambda con SQS
│   ├── KafkaLambdaExample.java               # Ejemplo de Lambda con Kafka
│   └── TraceLogCorrelationExample.java       # Ejemplo de correlación trazas-logs
└── README.md                                 # Este archivo
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

### 2. ComprehensiveHttpLambdaExample
**Archivo:** `ComprehensiveHttpLambdaExample.java`

Ejemplo completo que demuestra el uso avanzado del `HttpTracingLambdaWrapper` con múltiples endpoints y funcionalidades.

**Características mostradas:**
- Manejo de múltiples métodos HTTP (GET, POST, PUT, DELETE, OPTIONS)
- Enrutamiento de requests basado en path y método
- Configuración automática de contexto de negocio desde headers
- Logging estructurado con correlación
- Manejo robusto de errores
- Respuestas JSON bien estructuradas
- Headers CORS automáticos

**Endpoints implementados:**
- `GET /users/{id}` - Obtener información de usuario
- `GET /health` - Health check del servicio
- `GET /metrics` - Obtener métricas del servicio
- `POST /resources` - Crear nuevos recursos
- `PUT /users/{id}` - Actualizar usuarios
- `DELETE /users/{id}` - Eliminar usuarios
- `OPTIONS /*` - Soporte CORS

### 3. ProductApiLambdaExample
**Archivo:** `ProductApiLambdaExample.java`

Ejemplo práctico de una API REST completa para gestión de productos en un contexto de e-commerce.

**Características mostradas:**
- CRUD completo de productos con validaciones
- Contexto de negocio específico por tenant/dominio
- Búsqueda y filtrado de productos
- Validaciones de negocio robustas
- Excepciones específicas del dominio
- Métricas de negocio personalizadas
- Manejo de inventario y disponibilidad

**Endpoints implementados:**
- `GET /products` - Listar productos con filtros opcionales
- `GET /products/{id}` - Obtener producto específico  
- `GET /products/search?q={term}` - Buscar productos
- `POST /products` - Crear nuevo producto
- `PUT /products/{id}` - Actualizar producto
- `DELETE /products/{id}` - Eliminar producto
- `GET /tenant/{tenantId}/products` - Productos por tenant

**Validaciones incluidas:**
- Validación de precios (no negativos)
- Validación de stock (no negativos)  
- Validación de campos requeridos
- Validación de formatos de datos

### 4. AdvancedHttpLambdaExample
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

# Test ComprehensiveHttpLambdaExample  
curl http://localhost:3000/health
curl http://localhost:3000/metrics  
curl http://localhost:3000/users/123 -H "X-User-ID: user456" -H "X-Correlation-ID: test-123"
curl -X POST http://localhost:3000/resources -H "Content-Type: application/json" -d '{"name": "Test Resource", "type": "example"}'

# Test ProductApiLambdaExample
curl http://localhost:3000/products  # Listar todos los productos
curl "http://localhost:3000/products?category=Electronics&available=true"  # Con filtros
curl http://localhost:3000/products/1  # Producto específico
curl "http://localhost:3000/products/search?q=laptop"  # Buscar productos
curl -X POST http://localhost:3000/products \
  -H "Content-Type: application/json" \
  -H "X-Business-ID: tenant-123" \
  -d '{"name": "Nuevo Producto", "price": 99.99, "category": "Electronics", "stock": 10}'

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