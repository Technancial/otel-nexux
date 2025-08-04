package pe.soapros.otel.lambda.infrastructure;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import pe.soapros.otel.logs.infrastructure.BusinessContext;
import pe.soapros.otel.logs.infrastructure.BusinessContextLogEnricher;

import java.util.Map;
import java.util.Optional;

public class BusinessAwareObservabilityManager extends ObservabilityManager{

    public static final AttributeKey<String> BUSINESS_ID = AttributeKey.stringKey("business.id");
    public static final AttributeKey<String> TRANSACTION_ID = AttributeKey.stringKey("transaction.id");
    public static final AttributeKey<String> EXECUTION_ID = AttributeKey.stringKey("execution.id");
    public static final AttributeKey<String> OPERATION = AttributeKey.stringKey("business.operation");
    public static final AttributeKey<String> USER_ID = AttributeKey.stringKey("user.id");
    public static final AttributeKey<String> TENANT_ID = AttributeKey.stringKey("tenant.id");

    public BusinessAwareObservabilityManager(OpenTelemetry openTelemetry, String instrumentationName) {
        super(openTelemetry, instrumentationName);
    }

    public void setupBusinessContextFromHeaders(Map<String, String> headers) {
        BusinessContextLogEnricher.enrichMDCFromHeaders(headers);

        // También enriquecer el span actual si existe
        Span currentSpan = Span.current();
        if (currentSpan.getSpanContext().isValid()) {
            enrichCurrentSpanWithBusinessContext();
        }
    }

    public void setupBusinessContext(BusinessContext context) {
        BusinessContextLogEnricher.enrichMDCWithBusiness(context);

        // También enriquecer el span actual si existe
        Span currentSpan = Span.current();
        if (currentSpan.getSpanContext().isValid()) {
            enrichCurrentSpanWithBusinessContext();
        }
    }

    public void setupQuickBusinessContext(String businessId, String userId, String operation) {
        BusinessContext context = BusinessContext.builder()
                .businessId(businessId)
                .userId(userId)
                .operation(operation)
                .build();
        setupBusinessContext(context);
    }

    public void updateBusinessId(String businessId) {
        BusinessContextLogEnricher.updateBusinessId(businessId);
        enrichCurrentSpanWithBusinessContext();
    }

    public void updateOperation(String operation) {
        BusinessContextLogEnricher.updateOperation(operation);
        enrichCurrentSpanWithBusinessContext();
    }

    public Optional<String> getCurrentBusinessId() {
        return BusinessContextLogEnricher.getCurrentBusinessId();
    }

    public Map<String, String> getCompleteContextInfo() {
        return BusinessContextLogEnricher.getCompleteContextInfo();
    }

    @Override
    public void addUserInfo(Span span, String userId, String userRole, String tenantId) {
        // Llamar al método padre
        super.addUserInfo(span, userId, userRole, tenantId);

        // Actualizar business context automáticamente
        BusinessContext current = BusinessContextLogEnricher.getCurrentBusinessContext();
        BusinessContext.BusinessContextBuilder builder = current != null ?
                current.toBuilder() : BusinessContext.builder();

        BusinessContext updated = builder
                .userId(userId)
                .tenantId(tenantId)
                .build();

        BusinessContextLogEnricher.enrichMDCWithBusiness(updated);
        enrichSpanWithBusinessContext(span, updated);
    }

    @Override
    public void addBusinessContext(Span span, String businessOperation, String entityId, String entityType) {
        // Llamar al método padre
        super.addBusinessContext(span, businessOperation, entityId, entityType);

        // Actualizar business context automáticamente
        BusinessContext current = BusinessContextLogEnricher.getCurrentBusinessContext();
        BusinessContext.BusinessContextBuilder builder = current != null ?
                current.toBuilder() : BusinessContext.builder();

        BusinessContext updated = builder
                .operation(businessOperation)
                .businessId(entityId)  // Asumir que entityId es businessId
                .build();

        BusinessContextLogEnricher.enrichMDCWithBusiness(updated);
        enrichSpanWithBusinessContext(span, updated);
    }

    @Override
    public void logLambdaStart(String functionName, String requestId) {
        // El padre ya maneja el logging básico
        super.logLambdaStart(functionName, requestId);

        // El business context ya está en MDC, así que los logs automáticamente lo incluyen
    }

    @Override
    public void logLambdaEnd(String functionName, String requestId, long durationMs) {
        // El padre ya maneja el logging básico
        super.logLambdaEnd(functionName, requestId, durationMs);

        // Limpiar business context al final
        BusinessContextLogEnricher.clearMDC();
    }

    private void enrichCurrentSpanWithBusinessContext() {
        Span currentSpan = Span.current();
        if (currentSpan.getSpanContext().isValid()) {
            BusinessContext context = BusinessContextLogEnricher.getCurrentBusinessContext();
            if (context != null) {
                enrichSpanWithBusinessContext(currentSpan, context);
            }
        }
    }

    private void enrichSpanWithBusinessContext(Span span, BusinessContext context) {
        if (span == null || context == null) return;

        try {
            if (context.businessId() != null) {
                span.setAttribute(BUSINESS_ID, context.businessId());
            }
            if (context.transactionId() != null) {
                span.setAttribute(TRANSACTION_ID, context.transactionId());
            }
            if (context.executionId() != null) {
                span.setAttribute(EXECUTION_ID, context.executionId());
            }
            if (context.userId() != null) {
                span.setAttribute(USER_ID, context.userId());
            }
            if (context.tenantId() != null) {
                span.setAttribute(TENANT_ID, context.tenantId());
            }
            if (context.operation() != null) {
                span.setAttribute(OPERATION, context.operation());
            }
        } catch (Exception e) {
            // Log con el logger del padre
            getLoggerService().debug("Error enriching span with business context: " + e.getMessage(), Map.of());
        }
    }
}
