package pe.soapros.otel.lambda.infrastructure;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import pe.soapros.otel.logs.infrastructure.BusinessContext;
import pe.soapros.otel.logs.infrastructure.BusinessContextLogEnricher;
import pe.soapros.otel.logs.infrastructure.BusinessContextManager;


import java.util.Map;
import java.util.Optional;

import static pe.soapros.otel.lambda.infrastructure.ObservabilityConstants.*;

/**
 * Extensión del ObservabilityManager que agrega funcionalidades específicas para contexto de negocio.
 * Proporciona métodos convenientes para manejo de contexto empresarial en funciones Lambda.
 */
public class BusinessAwareObservabilityManager extends ObservabilityManager{

    public BusinessAwareObservabilityManager(OpenTelemetry openTelemetry, String instrumentationName) {
        super(openTelemetry, instrumentationName);
    }

    /**
     * Configura rápidamente el contexto de negocio básico
     * Este método facilita la configuración inicial en funciones Lambda
     */
    public void setupQuickBusinessContext(String businessId, String userId, String operation) {
        logDebug("Setting up quick business context", Map.of(
                "business.id", businessId != null ? businessId : "null",
                "user.id", userId != null ? userId : "null",
                "operation", operation != null ? operation : "null"
        ));

        // Usar el BusinessContextManager para configuración centralizada
        BusinessContextManager.setQuickContext(businessId, userId, operation);

        // También enriquecer el span actual si existe
        Span currentSpan = Span.current();
        if (currentSpan.getSpanContext().isValid()) {
            enrichCurrentSpanWithBusinessContext();
        }

        logInfo("Business context configured successfully", Map.of(
                "business.id", businessId != null ? businessId : "not_provided",
                "user.id", userId != null ? userId : "not_provided",
                "operation", operation != null ? operation : "not_provided"
        ));
    }

    /**
     * Configura contexto de negocio desde headers HTTP
     */
    public void setupBusinessContextFromHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            logDebug("No headers provided for business context setup", Map.of());
            return;
        }

        logDebug("Setting up business context from headers", Map.of(
                "headers.count", String.valueOf(headers.size())
        ));

        // Usar BusinessContextManager para configuración desde headers
        BusinessContextManager.setContextFromHeaders(headers);

        // También enriquecer el span actual si existe
        Span currentSpan = Span.current();
        if (currentSpan.getSpanContext().isValid()) {
            enrichCurrentSpanWithBusinessContext();
        }

        // Log con información extraída
        Map<String, String> extractedInfo = BusinessContextManager.getCurrentContextInfo();
        logInfo("Business context configured from headers", extractedInfo);
    }

    /**
     * Configura contexto de negocio completo
     */
    public void setupBusinessContext(BusinessContext context) {
        if (context == null) {
            logWarn("Attempted to setup null business context", Map.of());
            return;
        }

        logDebug("Setting up complete business context", Map.of(
                "business.id", context.businessId() != null ? context.businessId() : "null",
                "user.id", context.userId() != null ? context.userId() : "null",
                "operation", context.operation() != null ? context.operation() : "null"
        ));

        // Usar BusinessContextManager
        BusinessContextManager.setContext(context);

        // También enriquecer el span actual si existe
        Span currentSpan = Span.current();
        if (currentSpan.getSpanContext().isValid()) {
            enrichSpanWithBusinessContext(currentSpan, context);
        }

        logInfo("Complete business context configured", Map.of(
                "context.fields", String.valueOf(countNonNullFields(context))
        ));
    }

    /**
     * Actualiza el business ID en el contexto actual
     */
    public void updateBusinessId(String businessId) {
        logDebug("Updating business ID", Map.of("business.id", businessId));

        BusinessContextManager.updateBusinessId(businessId);
        enrichCurrentSpanWithBusinessContext();

        logInfo("Business ID updated", Map.of("business.id", businessId));
    }


    /**
     * Actualiza la operación en el contexto actual
     */
    public void updateOperation(String operation) {
        logDebug("Updating operation", Map.of("operation", operation));

        BusinessContextManager.updateOperation(operation);
        enrichCurrentSpanWithBusinessContext();

        logInfo("Operation updated", Map.of("operation", operation));
    }

    /**
     * Actualiza el user ID en el contexto actual
     */
    public void updateUserId(String userId) {
        logDebug("Updating user ID", Map.of("user.id", userId));

        BusinessContextManager.updateUserId(userId);
        enrichCurrentSpanWithBusinessContext();

        logInfo("User ID updated", Map.of("user.id", userId));
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

        // Actualizar business context automáticamente si ya existe
        Optional<BusinessContext> currentOpt = BusinessContextManager.getCurrentContext();
        if (currentOpt.isPresent()) {
            BusinessContext current = currentOpt.get();
            BusinessContext updated = current.toBuilder()
                    .userId(userId)
                    .tenantId(tenantId)
                    .build();

            BusinessContextManager.setContext(updated);
            enrichSpanWithBusinessContext(span, updated);
        } else if (userId != null || tenantId != null) {
            // Crear nuevo contexto si no existe
            BusinessContext newContext = BusinessContext.builder()
                    .userId(userId)
                    .tenantId(tenantId)
                    .build();

            BusinessContextManager.setContext(newContext);
            enrichSpanWithBusinessContext(span, newContext);
        }
    }

    @Override
    public void addBusinessContext(Span span, String businessOperation, String entityId, String entityType) {
        // Llamar al método padre
        super.addBusinessContext(span, businessOperation, entityId, entityType);

        // Actualizar business context automáticamente
        Optional<BusinessContext> currentOpt = BusinessContextManager.getCurrentContext();
        if (currentOpt.isPresent()) {
            BusinessContext current = currentOpt.get();
            BusinessContext updated = current.toBuilder()
                    .operation(businessOperation)
                    .businessId(entityId)  // Asumir que entityId es businessId
                    .build();

            BusinessContextManager.setContext(updated);
            enrichSpanWithBusinessContext(span, updated);
        } else {
            // Crear nuevo contexto si no existe
            BusinessContext newContext = BusinessContext.builder()
                    .operation(businessOperation)
                    .businessId(entityId)
                    .build();

            BusinessContextManager.setContext(newContext);
            enrichSpanWithBusinessContext(span, newContext);
        }
    }

    @Override
    public void logLambdaStart(String functionName, String requestId) {
        // El padre ya maneja el logging básico
        super.logLambdaStart(functionName, requestId);

        // Agregar información adicional de contexto si está disponible
        Map<String, String> contextInfo = getCompleteContextInfo();
        if (!contextInfo.isEmpty()) {
            logInfo("Lambda started with business context", contextInfo);
        }
    }

    @Override
    public void logLambdaEnd(String functionName, String requestId, long durationMs) {
        // Log con información de contexto antes de limpiar
        Map<String, String> contextInfo = getCompleteContextInfo();
        if (!contextInfo.isEmpty()) {
            logInfo("Lambda completed with business context", Map.of(
                    "lambda.duration_ms", String.valueOf(durationMs),
                    "context.business_id", contextInfo.getOrDefault("business_id", "not_set"),
                    "context.user_id", contextInfo.getOrDefault("user_id", "not_set"),
                    "context.operation", contextInfo.getOrDefault("operation", "not_set")
            ));
        }

        // El padre ya maneja el logging básico
        super.logLambdaEnd(functionName, requestId, durationMs);

        // Limpiar business context al final (el padre ya limpia MDC)
        BusinessContextManager.clearContext();
    }

    /**
     * Enriquece el span actual con contexto de negocio
     */
    private void enrichCurrentSpanWithBusinessContext() {
        Span currentSpan = Span.current();
        if (currentSpan.getSpanContext().isValid()) {
            Optional<BusinessContext> contextOpt = BusinessContextManager.getCurrentContext();
            if (contextOpt.isPresent()) {
                enrichSpanWithBusinessContext(currentSpan, contextOpt.get());
            }
        }
    }

    /**
     * Enriquece un span específico con contexto de negocio
     */
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
            if (context.sessionId() != null) {
                span.setAttribute(SESSION_ID, context.sessionId());
            }

            // Agregar atributos personalizados
            if (context.customAttributes() != null) {
                context.customAttributes().forEach((key, value) -> {
                    if (value != null) {
                        span.setAttribute(AttributeKey.stringKey("custom." + key), value);
                    }
                });
            }

        } catch (Exception e) {
            // Log con el logger del padre
            logError("Error enriching span with business context: " + e.getMessage(), Map.of(
                    "error.type", e.getClass().getSimpleName()
            ));
        }
    }

    /**
     * Cuenta campos no nulos en BusinessContext para logging
     */
    private int countNonNullFields(BusinessContext context) {
        if (context == null) return 0;

        int count = 0;
        if (context.businessId() != null) count++;
        if (context.userId() != null) count++;
        if (context.tenantId() != null) count++;
        if (context.correlationId() != null) count++;
        if (context.transactionId() != null) count++;
        if (context.executionId() != null) count++;
        if (context.operation() != null) count++;
        if (context.component() != null) count++;
        if (context.sessionId() != null) count++;
        if (context.customAttributes() != null && !context.customAttributes().isEmpty()) count++;

        return count;
    }

    /**
     * Crea un contexto de negocio para operaciones de sistema
     */
    public void setupSystemContext(String operation, String systemComponent) {
        BusinessContext systemContext = BusinessContext.builder()
                .userId("system")
                .operation(operation)
                .component(systemComponent)
                .build();

        setupBusinessContext(systemContext);
    }

    /**
     * Crea un contexto de negocio básico para operaciones anónimas
     */
    public void setupAnonymousContext(String operation) {
        BusinessContext anonymousContext = BusinessContext.builder()
                .userId("anonymous")
                .operation(operation)
                .build();

        setupBusinessContext(anonymousContext);
    }

    /**
     * Verifica si hay contexto de negocio configurado
     */
    public boolean hasBusinessContext() {
        return BusinessContextManager.getCurrentContext().isPresent();
    }

    /**
     * Obtiene un resumen del contexto actual para debugging
     */
    public String getContextSummary() {
        Optional<BusinessContext> contextOpt = BusinessContextManager.getCurrentContext();
        if (contextOpt.isEmpty()) {
            return "No business context configured";
        }

        BusinessContext context = contextOpt.get();
        return String.format("BusinessContext{businessId='%s', userId='%s', operation='%s', tenantId='%s'}",
                context.businessId(),
                context.userId(),
                context.operation(),
                context.tenantId());
    }
}
