package pe.soapros.otel.lambda.examples;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.GlobalOpenTelemetry;
import pe.soapros.otel.lambda.infrastructure.HttpTracingLambdaWrapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Ejemplo práctico de API de productos usando HttpTracingLambdaWrapper.
 * Demuestra un caso de uso real de e-commerce con:
 * - CRUD completo de productos
 * - Validaciones de negocio
 * - Búsqueda y filtrado
 * - Manejo de inventario
 * - Contexto de negocio por tenant/usuario
 * - Observabilidad completa
 */
public class ProductApiLambdaExample extends HttpTracingLambdaWrapper {

    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // Simulamos una base de datos en memoria para el ejemplo
    private static final Map<String, Product> PRODUCTS_DB = new HashMap<>();
    
    static {
        // Datos de ejemplo
        PRODUCTS_DB.put("1", new Product("1", "Laptop Gaming", "Alta performance", 
                new BigDecimal("1299.99"), "Electronics", 15, true));
        PRODUCTS_DB.put("2", new Product("2", "Mouse Wireless", "Ergonómico", 
                new BigDecimal("29.99"), "Electronics", 50, true));
        PRODUCTS_DB.put("3", new Product("3", "Teclado Mecánico", "RGB backlight", 
                new BigDecimal("89.99"), "Electronics", 0, false));
    }

    public ProductApiLambdaExample() {
        super(GlobalOpenTelemetry.get());
    }

    @Override
    public APIGatewayProxyResponseEvent handle(APIGatewayProxyRequestEvent event, Context context) {
        
        // Configurar contexto específico del dominio de productos
        setupProductDomainContext(event);
        
        String method = event.getHttpMethod();
        String path = event.getPath();
        
        logInfo("Processing product API request", Map.of(
                "method", method,
                "path", path,
                "tenant", getCurrentBusinessId().orElse("default")
        ));

        try {
            return switch (method.toUpperCase()) {
                case "GET" -> handleGetProducts(event);
                case "POST" -> handleCreateProduct(event);
                case "PUT" -> handleUpdateProduct(event);
                case "DELETE" -> handleDeleteProduct(event);
                default -> createErrorResponse(405, "Method not allowed");
            };
            
        } catch (ProductNotFoundException e) {
            logWarn("Product not found", Map.of(
                    "product.id", e.getProductId(),
                    "operation", getCurrentContextInfo().getOrDefault("operation", "unknown")
            ));
            return createErrorResponse(404, e.getMessage());
            
        } catch (InsufficientStockException e) {
            logWarn("Insufficient stock", Map.of(
                    "product.id", e.getProductId(),
                    "requested", String.valueOf(e.getRequestedQuantity()),
                    "available", String.valueOf(e.getAvailableStock())
            ));
            return createErrorResponse(409, e.getMessage());
            
        } catch (ValidationException e) {
            logWarn("Validation error", Map.of(
                    "validation.field", e.getField(),
                    "validation.error", e.getMessage()
            ));
            return createErrorResponse(400, e.getMessage());
            
        } catch (Exception e) {
            logError("Unexpected error in product API", Map.of(
                    "error.type", e.getClass().getSimpleName(),
                    "error.message", e.getMessage() != null ? e.getMessage() : "Unknown error"
            ));
            return createErrorResponse(500, "Internal server error");
        }
    }

    private void setupProductDomainContext(APIGatewayProxyRequestEvent event) {
        // El wrapper ya configuró el contexto básico desde headers
        // Aquí agregamos contexto específico del dominio de productos
        
        String tenantId = extractTenantFromPath(event.getPath());
        if (tenantId != null) {
            getObservabilityManager().updateBusinessId(tenantId);
        }
        
        // Determinar la operación según el path y método
        String operation = determineProductOperation(event.getHttpMethod(), event.getPath());
        getObservabilityManager().updateOperation(operation);
    }

    private APIGatewayProxyResponseEvent handleGetProducts(APIGatewayProxyRequestEvent event) {
        String path = event.getPath();
        Map<String, String> queryParams = event.getQueryStringParameters();
        
        if (path.matches(".*/products/\\d+$")) {
            // GET /products/{id}
            return handleGetSingleProduct(event);
        } else if (path.endsWith("/products")) {
            // GET /products con filtros opcionales
            return handleGetAllProducts(queryParams);
        } else if (path.endsWith("/products/search")) {
            // GET /products/search
            return handleSearchProducts(queryParams);
        } else {
            return createErrorResponse(404, "Endpoint not found");
        }
    }

    private APIGatewayProxyResponseEvent handleGetSingleProduct(APIGatewayProxyRequestEvent event) {
        String productId = extractProductIdFromPath(event.getPath());
        
        logDebug("Retrieving single product", Map.of("product.id", productId));
        
        Product product = PRODUCTS_DB.get(productId);
        if (product == null) {
            throw new ProductNotFoundException(productId);
        }

        // Registrar métricas de negocio
        recordBusinessMetric("product.view", Map.of(
                "product.id", productId,
                "product.category", product.getCategory()
        ));

        return createSuccessResponse(Map.of(
                "product", product.toMap(),
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    private APIGatewayProxyResponseEvent handleGetAllProducts(Map<String, String> queryParams) {
        logDebug("Retrieving all products", Map.of(
                "filters.count", queryParams != null ? String.valueOf(queryParams.size()) : "0"
        ));
        
        List<Product> products = new ArrayList<>(PRODUCTS_DB.values());
        
        // Aplicar filtros si existen
        if (queryParams != null) {
            if (queryParams.containsKey("category")) {
                String category = queryParams.get("category");
                products = products.stream()
                        .filter(p -> category.equalsIgnoreCase(p.getCategory()))
                        .toList();
                        
                logDebug("Applied category filter", Map.of("category", category, "results", String.valueOf(products.size())));
            }
            
            if (queryParams.containsKey("available")) {
                boolean onlyAvailable = Boolean.parseBoolean(queryParams.get("available"));
                if (onlyAvailable) {
                    products = products.stream()
                            .filter(Product::isAvailable)
                            .toList();
                            
                    logDebug("Applied availability filter", Map.of("results", String.valueOf(products.size())));
                }
            }
        }

        List<Map<String, Object>> productMaps = products.stream()
                .map(Product::toMap)
                .toList();

        return createSuccessResponse(Map.of(
                "products", productMaps,
                "total", products.size(),
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    private APIGatewayProxyResponseEvent handleSearchProducts(Map<String, String> queryParams) {
        if (queryParams == null || !queryParams.containsKey("q")) {
            return createErrorResponse(400, "Query parameter 'q' is required for search");
        }
        
        String searchTerm = queryParams.get("q").toLowerCase();
        
        logDebug("Searching products", Map.of("search.term", searchTerm));
        
        List<Product> matchingProducts = PRODUCTS_DB.values().stream()
                .filter(p -> p.getName().toLowerCase().contains(searchTerm) || 
                           p.getDescription().toLowerCase().contains(searchTerm))
                .toList();

        logInfo("Product search completed", Map.of(
                "search.term", searchTerm,
                "results.count", String.valueOf(matchingProducts.size())
        ));

        List<Map<String, Object>> productMaps = matchingProducts.stream()
                .map(Product::toMap)
                .toList();

        return createSuccessResponse(Map.of(
                "products", productMaps,
                "total", matchingProducts.size(),
                "searchTerm", searchTerm,
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    private APIGatewayProxyResponseEvent handleCreateProduct(APIGatewayProxyRequestEvent event) throws JsonProcessingException {
        try {
            String body = event.getBody();
            if (body == null || body.trim().isEmpty()) {
                throw new ValidationException("body", "Request body is required");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> productData = objectMapper.readValue(body, Map.class);
            
            // Validar datos del producto
            validateProductData(productData, false);
            
            // Crear nuevo producto
            String newId = UUID.randomUUID().toString();
            Product product = Product.fromMap(newId, productData);
            
            PRODUCTS_DB.put(newId, product);
            
            logInfo("Product created successfully", Map.of(
                    "product.id", newId,
                    "product.name", product.getName(),
                    "product.category", product.getCategory()
            ));

            recordBusinessMetric("product.created", Map.of(
                    "product.id", newId,
                    "product.category", product.getCategory()
            ));

            APIGatewayProxyResponseEvent response = createSuccessResponse(Map.of(
                    "message", "Product created successfully",
                    "product", product.toMap()
            ));
            response.setStatusCode(201); // Created
            return response;
            
        } catch (Exception e) {
            if (e instanceof ValidationException) {
                throw e; // Re-lanzar errores de validación
            }
            throw new RuntimeException("Error creating product: " + e.getMessage(), e);
        }
    }

    private APIGatewayProxyResponseEvent handleUpdateProduct(APIGatewayProxyRequestEvent event) throws JsonProcessingException {
        String productId = extractProductIdFromPath(event.getPath());
        
        if (!PRODUCTS_DB.containsKey(productId)) {
            throw new ProductNotFoundException(productId);
        }

        try {
            String body = event.getBody();
            if (body == null || body.trim().isEmpty()) {
                throw new ValidationException("body", "Request body is required");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> updateData = objectMapper.readValue(body, Map.class);
            
            validateProductData(updateData, true);
            
            Product existingProduct = PRODUCTS_DB.get(productId);
            Product updatedProduct = existingProduct.updateFrom(updateData);
            
            PRODUCTS_DB.put(productId, updatedProduct);
            
            logInfo("Product updated successfully", Map.of(
                    "product.id", productId,
                    "product.name", updatedProduct.getName()
            ));

            recordBusinessMetric("product.updated", Map.of(
                    "product.id", productId,
                    "product.category", updatedProduct.getCategory()
            ));

            return createSuccessResponse(Map.of(
                    "message", "Product updated successfully",
                    "product", updatedProduct.toMap()
            ));
            
        } catch (Exception e) {
            if (e instanceof ValidationException) {
                throw e;
            }
            throw new RuntimeException("Error updating product: " + e.getMessage(), e);
        }
    }

    private APIGatewayProxyResponseEvent handleDeleteProduct(APIGatewayProxyRequestEvent event) {
        String productId = extractProductIdFromPath(event.getPath());
        
        Product product = PRODUCTS_DB.remove(productId);
        if (product == null) {
            throw new ProductNotFoundException(productId);
        }

        logInfo("Product deleted successfully", Map.of(
                "product.id", productId,
                "product.name", product.getName()
        ));

        recordBusinessMetric("product.deleted", Map.of(
                "product.id", productId,
                "product.category", product.getCategory()
        ));

        return createSuccessResponse(Map.of(
                "message", "Product deleted successfully",
                "productId", productId,
                "deletedAt", LocalDateTime.now().toString()
        ));
    }

    // Métodos utilitarios

    private String extractProductIdFromPath(String path) {
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("products".equals(parts[i]) && i + 1 < parts.length) {
                return parts[i + 1];
            }
        }
        throw new ValidationException("path", "Invalid product ID in path");
    }

    private String extractTenantFromPath(String path) {
        // Formato esperado: /tenant/{tenantId}/products/...
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("tenant".equals(parts[i]) && i + 1 < parts.length) {
                return parts[i + 1];
            }
        }
        return null; // No hay tenant específico
    }

    private String determineProductOperation(String method, String path) {
        return switch (method.toUpperCase()) {
            case "GET" -> path.matches(".*/products/\\d+$") ? "get-product" : 
                         path.endsWith("/search") ? "search-products" : "list-products";
            case "POST" -> "create-product";
            case "PUT" -> "update-product";
            case "DELETE" -> "delete-product";
            default -> "unknown-product-operation";
        };
    }

    private void validateProductData(Map<String, Object> data, boolean isUpdate) {
        if (!isUpdate && !data.containsKey("name")) {
            throw new ValidationException("name", "Product name is required");
        }
        
        if (data.containsKey("name") && (data.get("name") == null || data.get("name").toString().trim().isEmpty())) {
            throw new ValidationException("name", "Product name cannot be empty");
        }
        
        if (data.containsKey("price")) {
            try {
                BigDecimal price = new BigDecimal(data.get("price").toString());
                if (price.compareTo(BigDecimal.ZERO) < 0) {
                    throw new ValidationException("price", "Product price cannot be negative");
                }
            } catch (NumberFormatException e) {
                throw new ValidationException("price", "Invalid price format");
            }
        }
        
        if (data.containsKey("stock")) {
            try {
                int stock = Integer.parseInt(data.get("stock").toString());
                if (stock < 0) {
                    throw new ValidationException("stock", "Stock cannot be negative");
                }
            } catch (NumberFormatException e) {
                throw new ValidationException("stock", "Invalid stock format");
            }
        }
    }

    private void recordBusinessMetric(String metricName, Map<String, String> attributes) {
        // Aquí registrarías métricas de negocio específicas
        // El wrapper ya maneja métricas técnicas automáticamente
        logDebug("Business metric recorded", Map.of(
                "metric.name", metricName,
                "attributes.count", String.valueOf(attributes.size())
        ));
    }

    // Clases de dominio y excepciones

    private static class Product {
        private final String id;
        private final String name;
        private final String description;
        private final BigDecimal price;
        private final String category;
        private final int stock;
        private final boolean available;
        private final LocalDateTime createdAt;
        private final LocalDateTime updatedAt;

        public Product(String id, String name, String description, BigDecimal price, 
                      String category, int stock, boolean available) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.price = price;
            this.category = category;
            this.stock = stock;
            this.available = available;
            this.createdAt = LocalDateTime.now();
            this.updatedAt = LocalDateTime.now();
        }

        private Product(String id, String name, String description, BigDecimal price, 
                       String category, int stock, boolean available, 
                       LocalDateTime createdAt, LocalDateTime updatedAt) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.price = price;
            this.category = category;
            this.stock = stock;
            this.available = available;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }

        public static Product fromMap(String id, Map<String, Object> data) {
            String name = (String) data.get("name");
            String description = (String) data.getOrDefault("description", "");
            BigDecimal price = new BigDecimal(data.get("price").toString());
            String category = (String) data.getOrDefault("category", "General");
            int stock = data.containsKey("stock") ? Integer.parseInt(data.get("stock").toString()) : 0;
            boolean available = data.containsKey("available") ? (Boolean) data.get("available") : stock > 0;
            
            return new Product(id, name, description, price, category, stock, available);
        }

        public Product updateFrom(Map<String, Object> data) {
            String newName = data.containsKey("name") ? (String) data.get("name") : this.name;
            String newDescription = data.containsKey("description") ? (String) data.get("description") : this.description;
            BigDecimal newPrice = data.containsKey("price") ? new BigDecimal(data.get("price").toString()) : this.price;
            String newCategory = data.containsKey("category") ? (String) data.get("category") : this.category;
            int newStock = data.containsKey("stock") ? Integer.parseInt(data.get("stock").toString()) : this.stock;
            boolean newAvailable = data.containsKey("available") ? (Boolean) data.get("available") : this.available;
            
            return new Product(this.id, newName, newDescription, newPrice, newCategory, 
                             newStock, newAvailable, this.createdAt, LocalDateTime.now());
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("id", id);
            map.put("name", name);
            map.put("description", description);
            map.put("price", price);
            map.put("category", category);
            map.put("stock", stock);
            map.put("available", available);
            map.put("createdAt", createdAt.toString());
            map.put("updatedAt", updatedAt.toString());
            return map;
        }

        // Getters
        public String getId() { return id; }
        public String getName() { return name; }
        public String getDescription() { return description; }
        public BigDecimal getPrice() { return price; }
        public String getCategory() { return category; }
        public int getStock() { return stock; }
        public boolean isAvailable() { return available; }
    }

    // Excepciones específicas del dominio
    private static class ProductNotFoundException extends RuntimeException {
        private final String productId;
        
        public ProductNotFoundException(String productId) {
            super("Product not found with ID: " + productId);
            this.productId = productId;
        }
        
        public String getProductId() { return productId; }
    }

    private static class InsufficientStockException extends RuntimeException {
        private final String productId;
        private final int requestedQuantity;
        private final int availableStock;
        
        public InsufficientStockException(String productId, int requestedQuantity, int availableStock) {
            super(String.format("Insufficient stock for product %s. Requested: %d, Available: %d", 
                               productId, requestedQuantity, availableStock));
            this.productId = productId;
            this.requestedQuantity = requestedQuantity;
            this.availableStock = availableStock;
        }
        
        public String getProductId() { return productId; }
        public int getRequestedQuantity() { return requestedQuantity; }
        public int getAvailableStock() { return availableStock; }
    }

    private static class ValidationException extends RuntimeException {
        private final String field;
        
        public ValidationException(String field, String message) {
            super(message);
            this.field = field;
        }
        
        public String getField() { return field; }
    }
}