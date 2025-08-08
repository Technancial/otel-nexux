package pe.soapros.otel.lambda.infrastructure;

import io.opentelemetry.api.common.AttributeKey;

/**
 * Constantes compartidas para observabilidad en funciones Lambda.
 * Centraliza todas las AttributeKeys utilizadas en el sistema.
 */
public final class ObservabilityConstants {

    // Error and Exception Attributes
    public static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");
    public static final AttributeKey<String> ERROR_MESSAGE = AttributeKey.stringKey("error.message");
    public static final AttributeKey<String> EXCEPTION_STACKTRACE = AttributeKey.stringKey("exception.stacktrace");
    public static final AttributeKey<Boolean> EXCEPTION_ESCAPED = AttributeKey.booleanKey("exception.escaped");
    
    // Business Context Attributes
    public static final AttributeKey<String> BUSINESS_ID = AttributeKey.stringKey("business.id");
    public static final AttributeKey<String> TRANSACTION_ID = AttributeKey.stringKey("transaction.id");
    public static final AttributeKey<String> EXECUTION_ID = AttributeKey.stringKey("execution.id");
    public static final AttributeKey<String> OPERATION = AttributeKey.stringKey("business.operation");
    
    // User and Tenant Attributes
    public static final AttributeKey<String> USER_ID = AttributeKey.stringKey("user.id");
    public static final AttributeKey<String> TENANT_ID = AttributeKey.stringKey("tenant.id");
    public static final AttributeKey<String> SESSION_ID = AttributeKey.stringKey("session.id");
    
    // Correlation Attributes
    public static final AttributeKey<String> CORRELATION_ID = AttributeKey.stringKey("correlation.id");
    
    // Performance Attributes
    public static final AttributeKey<Long> OPERATION_DURATION_MS = AttributeKey.longKey("operation.duration_ms");
    public static final AttributeKey<String> OPERATION_NAME = AttributeKey.stringKey("operation.name");
    
    // Size Attributes
    public static final AttributeKey<Long> REQUEST_SIZE_BYTES = AttributeKey.longKey("request.size_bytes");
    public static final AttributeKey<Long> RESPONSE_SIZE_BYTES = AttributeKey.longKey("response.size_bytes");
    
    // Lambda-specific Attributes
    public static final AttributeKey<String> LAMBDA_FUNCTION_NAME = AttributeKey.stringKey("lambda.function_name");
    public static final AttributeKey<String> LAMBDA_REQUEST_ID = AttributeKey.stringKey("lambda.request_id");
    public static final AttributeKey<Long> LAMBDA_DURATION_MS = AttributeKey.longKey("lambda.duration_ms");
    public static final AttributeKey<Boolean> LAMBDA_COLD_START = AttributeKey.booleanKey("lambda.cold_start");
    
    // Business Entity Attributes
    public static final AttributeKey<String> BUSINESS_ENTITY_ID = AttributeKey.stringKey("business.entity.id");
    public static final AttributeKey<String> BUSINESS_ENTITY_TYPE = AttributeKey.stringKey("business.entity.type");
    
    // User Role Attribute
    public static final AttributeKey<String> USER_ROLE = AttributeKey.stringKey("user.role");

    // Prevent instantiation
    private ObservabilityConstants() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}