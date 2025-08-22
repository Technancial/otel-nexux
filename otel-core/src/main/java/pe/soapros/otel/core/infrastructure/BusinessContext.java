package pe.soapros.otel.core.infrastructure;

import java.util.Map;

public record BusinessContext(
    String businessId,
    String userId,
    String tenantId,
    String correlationId,
    String transactionId,
    String executionId,
    String operation,
    String component,
    String sessionId,
    Map<String, String> customAttributes
) {
    
    public static Builder builder() {
        return new Builder();
    }
    
    public Builder toBuilder() {
        return new Builder(this);
    }
    
    public static class Builder {
        private String businessId;
        private String userId;
        private String tenantId;
        private String correlationId;
        private String transactionId;
        private String executionId;
        private String operation;
        private String component;
        private String sessionId;
        private Map<String, String> customAttributes;
        
        private Builder() {}
        
        private Builder(BusinessContext context) {
            this.businessId = context.businessId;
            this.userId = context.userId;
            this.tenantId = context.tenantId;
            this.correlationId = context.correlationId;
            this.transactionId = context.transactionId;
            this.executionId = context.executionId;
            this.operation = context.operation;
            this.component = context.component;
            this.sessionId = context.sessionId;
            this.customAttributes = context.customAttributes;
        }
        
        public Builder businessId(String businessId) {
            this.businessId = businessId;
            return this;
        }
        
        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }
        
        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }
        
        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }
        
        public Builder transactionId(String transactionId) {
            this.transactionId = transactionId;
            return this;
        }
        
        public Builder executionId(String executionId) {
            this.executionId = executionId;
            return this;
        }
        
        public Builder operation(String operation) {
            this.operation = operation;
            return this;
        }
        
        public Builder component(String component) {
            this.component = component;
            return this;
        }
        
        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }
        
        public Builder customAttributes(Map<String, String> customAttributes) {
            this.customAttributes = customAttributes;
            return this;
        }
        
        public BusinessContext build() {
            return new BusinessContext(
                businessId, userId, tenantId, correlationId, 
                transactionId, executionId, operation, component, 
                sessionId, customAttributes
            );
        }
    }
}