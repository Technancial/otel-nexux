# 📊 OpenTelemetry Metrics Module (otel-metrics)

Este módulo proporciona una implementación completa de métricas OpenTelemetry para microservicios y funciones Lambda, incluyendo colectores especializados para diferentes tipos de operaciones.

## 🎯 Características Principales

- **✅ Métricas Básicas**: Counters, Gauges, Histograms con OpenTelemetry
- **🚀 Lambda Metrics**: Cold starts, duración, memoria, timeouts
- **🌐 HTTP Metrics**: Latencia, throughput, errores, SLA
- **🗄️ Database Metrics**: Query performance, conexiones, errores
- **💼 Business Metrics**: KPIs, transacciones, conversión, retención
- **🏭 Factory Pattern**: Configuración centralizada y reutilizable

## 📦 Componentes

### 1. **MetricsService** - Interfaz Principal
```java
MetricsService metrics = new OpenTelemetryMetricsService(meter, "my-service");

// Counters
metrics.incrementCounter("requests.total", "Total requests", Map.of("method", "GET"));

// Gauges  
metrics.recordGauge("cpu.usage", "CPU usage", 75.5, Map.of("host", "server1"));

// Histograms
metrics.recordHistogram("request.duration", "Request duration", 125.0, Map.of());
```

### 2. **LambdaMetricsCollector** - Para AWS Lambda
```java
LambdaMetricsCollector lambdaMetrics = factory.getLambdaMetricsCollector();

// En el handler Lambda
lambdaMetrics.startExecution(context);
try {
    // Lógica de negocio...
    lambdaMetrics.endExecution(context, true, null);
} catch (Exception e) {
    lambdaMetrics.endExecution(context, false, e);
}
```

**Métricas Capturadas:**
- `lambda.invocations.total` - Total de invocaciones
- `lambda.cold_starts.total` - Cold starts detectados
- `lambda.execution.duration` - Duración de ejecución
- `lambda.memory.used_mb` - Memoria utilizada
- `lambda.timeout.warnings.total` - Warnings de timeout

### 3. **HttpMetricsCollector** - Para APIs REST
```java
HttpMetricsCollector httpMetrics = factory.getHttpMetricsCollector();

// En interceptor HTTP
httpMetrics.startRequest("GET", "/api/users", userAgent, clientIp);
try {
    // Procesar request...
    httpMetrics.endRequest(200, requestSize, responseSize, null);
} catch (Exception e) {
    httpMetrics.endRequest(500, requestSize, 0, e);
}
```

**Métricas Capturadas:**
- `http.requests.total` - Total de requests HTTP
- `http.request.duration` - Latencia de requests
- `http.requests.errors.total` - Errores HTTP
- `http.request.size_bytes` - Tamaño de requests
- `http.requests.sla_200ms.total` - Requests dentro de SLA

### 4. **DatabaseMetricsCollector** - Para Bases de Datos
```java
DatabaseMetricsCollector dbMetrics = factory.getDatabaseMetricsCollector();

// En operaciones de DB
dbMetrics.startDatabaseOperation("SELECT", "users", "myapp_db", "main_pool");
try {
    // Ejecutar query...
    dbMetrics.endDatabaseOperation(true, recordsFound, null);
} catch (SQLException e) {
    dbMetrics.endDatabaseOperation(false, 0, e);
}

// Métricas de conexión
dbMetrics.recordConnectionMetrics("myapp_db", "main_pool", 15, 5, 20);
```

**Métricas Capturadas:**
- `db.operations.total` - Total de operaciones
- `db.operation.duration` - Duración de queries
- `db.connections.active` - Conexiones activas
- `db.connections.pool_utilization` - Utilización del pool
- `db.operations.errors.total` - Errores de base de datos

### 5. **BusinessMetricsCollector** - Para KPIs de Negocio
```java
BusinessMetricsCollector businessMetrics = factory.getBusinessMetricsCollector();

// Actividad de usuario
businessMetrics.recordUserActivity("user123", "login", "authentication", 
    Map.of("platform", "web"));

// Transacciones
businessMetrics.startBusinessTransaction("txn_456", "purchase", "user123", 99.99, "USD");
businessMetrics.endBusinessTransaction("success", null);

// KPIs personalizados
businessMetrics.recordKPI("Daily Active Users", 1250, "users", "daily", 
    Map.of("platform", "mobile"));

// Métricas de conversión
businessMetrics.recordConversionRate("signup_funnel", "email_verification", 
    1000, 750, "google_ads");
```

**Métricas Capturadas:**
- `business.user.activity.total` - Actividades de usuario
- `business.transactions.total` - Transacciones de negocio
- `business.revenue.accumulated` - Revenue acumulado
- `business.conversion.rate` - Tasas de conversión
- `business.retention.rate` - Retención de usuarios

## 🚀 Uso Rápido

### 1. **Configuración Básica**
```java
// Configurar OpenTelemetry
OpenTelemetry openTelemetry = OpenTelemetryConfiguration.create();

// Crear factory de métricas
MetricsFactory factory = MetricsFactory.create(openTelemetry, "my-service", "1.0.0");

// Obtener colectores
LambdaMetricsCollector lambdaMetrics = factory.getLambdaMetricsCollector();
HttpMetricsCollector httpMetrics = factory.getHttpMetricsCollector();
DatabaseMetricsCollector dbMetrics = factory.getDatabaseMetricsCollector();
BusinessMetricsCollector businessMetrics = factory.getBusinessMetricsCollector();
```

### 2. **En Lambda Wrapper**
```java
public class MyLambda extends HttpTracingLambdaWrapper {
    private final LambdaMetricsCollector metrics;
    
    public MyLambda() {
        super(OpenTelemetryConfiguration.create());
        MetricsFactory factory = MetricsFactory.create(openTelemetry, "my-lambda");
        this.metrics = factory.getLambdaMetricsCollector();
    }
    
    @Override
    public APIGatewayProxyResponseEvent handle(APIGatewayProxyRequestEvent event, Context context) {
        metrics.startExecution(context);
        
        try {
            // Lógica de negocio...
            metrics.endExecution(context, true, null);
            return createSuccessResponse("OK");
        } catch (Exception e) {
            metrics.endExecution(context, false, e);
            return createErrorResponse(500, "Error");
        }
    }
}
```

### 3. **En Microservicio Spring Boot**
```java
@RestController
public class UserController {
    private final HttpMetricsCollector httpMetrics;
    private final BusinessMetricsCollector businessMetrics;
    
    @GetMapping("/users/{id}")
    public ResponseEntity<User> getUser(@PathVariable String id, HttpServletRequest request) {
        httpMetrics.startRequest("GET", "/users/{id}", 
            request.getHeader("User-Agent"), request.getRemoteAddr());
        
        try {
            User user = userService.findById(id);
            
            // Métricas de negocio
            businessMetrics.recordUserActivity(id, "profile_view", "user_management", 
                Map.of("source", "api"));
            
            httpMetrics.endRequest(200, 0, user.toString().length(), null);
            return ResponseEntity.ok(user);
            
        } catch (Exception e) {
            httpMetrics.endRequest(500, 0, 0, e);
            throw e;
        }
    }
}
```

## 📊 Métricas Disponibles

### **Lambda Metrics**
| Métrica | Tipo | Descripción |
|---------|------|-------------|
| `lambda.invocations.total` | Counter | Total de invocaciones |
| `lambda.cold_starts.total` | Counter | Cold starts detectados |
| `lambda.execution.duration` | Histogram | Duración de ejecución (ms) |
| `lambda.memory.used_mb` | Gauge | Memoria utilizada (MB) |
| `lambda.timeout.warnings.total` | Counter | Warnings de timeout |

### **HTTP Metrics**
| Métrica | Tipo | Descripción |
|---------|------|-------------|
| `http.requests.total` | Counter | Total de requests HTTP |
| `http.request.duration` | Histogram | Latencia de requests (ms) |
| `http.requests.errors.total` | Counter | Errores HTTP |
| `http.request.size_bytes` | Histogram | Tamaño de requests |
| `http.requests.sla_200ms.total` | Counter | Requests < 200ms |

### **Database Metrics**
| Métrica | Tipo | Descripción |
|---------|------|-------------|
| `db.operations.total` | Counter | Total de operaciones |
| `db.operation.duration` | Histogram | Duración de queries (ms) |
| `db.connections.active` | Gauge | Conexiones activas |
| `db.connections.pool_utilization` | Gauge | Utilización del pool |
| `db.operations.errors.total` | Counter | Errores de DB |

### **Business Metrics**
| Métrica | Tipo | Descripción |
|---------|------|-------------|
| `business.user.activity.total` | Counter | Actividades de usuario |
| `business.transactions.total` | Counter | Transacciones |
| `business.revenue.accumulated` | Gauge | Revenue acumulado |
| `business.conversion.rate` | Gauge | Tasa de conversión |
| `business.retention.rate` | Gauge | Retención de usuarios |

## 🔧 Configuración Avanzada

### **Variables de Entorno**
```bash
# Configuración del servicio
OTEL_SERVICE_NAME=my-microservice
OTEL_SERVICE_VERSION=1.2.3
ENVIRONMENT=production

# Configuración de exportación
OTEL_EXPORTER_OTLP_ENDPOINT=https://api.honeycomb.io
OTEL_EXPORTER_OTLP_HEADERS=x-honeycomb-team=your-api-key

# Configuración de sampling
OTEL_TRACES_SAMPLER=traceidratio
OTEL_TRACES_SAMPLER_ARG=0.1
```

### **Factory Personalizada**
```java
// Crear múltiples colectores para diferentes servicios
MetricsFactory mainFactory = MetricsFactory.create(openTelemetry, "main-service");
MetricsFactory authFactory = MetricsFactory.create(openTelemetry, "auth-service");

// Colectores especializados
LambdaMetricsCollector orderLambda = mainFactory.createLambdaMetricsCollector(
    "order-processor", "2.1.0");
HttpMetricsCollector publicApi = mainFactory.createHttpMetricsCollector("public-api");
```

### **Métricas Personalizadas**
```java
// Métricas Lambda personalizadas
lambdaMetrics.recordCustomLambdaMetric("processing_items", itemCount, 
    Map.of("category", "orders"));

// Métricas HTTP personalizadas  
httpMetrics.recordEndpointMetric("user_search", "search_results", resultCount,
    Map.of("query_type", "fuzzy"));

// Métricas de negocio personalizadas
businessMetrics.recordCustomBusinessMetric("ecommerce", "cart_abandonment_rate", 
    abandonmentRate, "percentage", Map.of("channel", "mobile"));
```

## 🎯 Casos de Uso

### **1. Monitoreo de Performance Lambda**
```java
// Detectar cold starts excesivos
// Query: lambda.cold_starts.total / lambda.invocations.total > 0.1

// Alertas de memoria
// Query: lambda.memory.utilization_ratio > 0.9

// SLA de duración
// Query: lambda.execution.duration > 5000
```

### **2. Monitoreo de APIs**
```java
// SLA de latencia por endpoint
// Query: http.requests.sla_500ms.total / http.requests.total < 0.95

// Rate de errores
// Query: http.requests.errors.total / http.requests.total > 0.05

// Throughput por minuto
// Query: rate(http.requests.total[1m])
```

### **3. Health Check de Base de Datos**
```java
// Pool saturation
// Query: db.connections.pool_utilization > 0.8

// Query performance
// Query: db.operation.duration{operation="SELECT"} > 100

// Error rate por operación
// Query: db.operations.errors.total / db.operations.total > 0.01
```

### **4. KPIs de Negocio**
```java
// Daily Active Users
// Query: business.user.activity.total{activity="login"}

// Revenue Growth
// Query: increase(business.revenue.accumulated[24h])

// Conversion Funnel
// Query: business.conversion.rate{funnel="signup"}
```

## 🧪 Testing

```java
@Test
public void testLambdaMetrics() {
    MetricsFactory factory = MetricsFactory.create(mockOpenTelemetry, "test-service");
    LambdaMetricsCollector collector = factory.getLambdaMetricsCollector();
    
    collector.startExecution(mockContext);
    collector.endExecution(mockContext, true, null);
    
    // Verificar métricas registradas
    verify(metricsService).incrementCounter(eq("lambda.invocations.total"), any(), any(), any());
}
```

## 📈 Mejores Prácticas

1. **📊 Usa el Factory Pattern**: Centraliza la configuración de métricas
2. **🏷️ Etiquetas Consistentes**: Usa service.name, version, environment
3. **📉 Evita Cardinalidad Alta**: Limita valores únicos en etiquetas
4. **⚡ Performance**: Los colectores están optimizados para bajo overhead
5. **🔄 Limpieza**: Usa métodos clear() en tests para evitar interferencias
6. **📋 Monitoreo**: Configura alertas basadas en SLAs de negocio

## 🔗 Integración con otros módulos

- **otel-traces**: Las métricas incluyen automáticamente trace context
- **otel-logs**: Los errores se correlacionan con logs estructurados  
- **otel-lambda-wrapper**: Integración automática con Lambda wrappers

¡El módulo otel-metrics está listo para proporcionar observabilidad completa a tus microservicios y funciones Lambda! 🚀