package pe.soapros.otel.lambda.infrastructure;

import lombok.Getter;

import java.util.Set;

@Getter
public class LambdaObservabilityConfig {

    private final Set<String> customResponseHeaders;
    private final boolean includeResponseBody;
    private final int maxResponseBodyLength;
    private final boolean verboseLogging;
    private final boolean enableSubSpanEvents;
    private final boolean enableSubSpanMetrics;

    private LambdaObservabilityConfig(Builder builder) {
        this.customResponseHeaders = Set.copyOf(builder.customResponseHeaders);
        this.includeResponseBody = builder.includeResponseBody;
        this.maxResponseBodyLength = builder.maxResponseBodyLength;
        this.verboseLogging = builder.verboseLogging;
        this.enableSubSpanEvents = builder.enableSubSpanEvents;
        this.enableSubSpanMetrics = builder.enableSubSpanMetrics;
    }

    /**
     * Configuración por defecto optimizada para AWS Lambda
     */
    public static LambdaObservabilityConfig defaultForLambda() {
        return builder()
                .includeResponseBody(1024) // 1KB máximo para evitar overhead
                .verboseLogging(false) // Producción
                .enableSubSpanEvents(true) // Útil para debugging
                .enableSubSpanMetrics(true) // Performance tracking
                .customResponseHeaders("x-correlation-id", "x-request-id") // Headers comunes
                .build();
    }

    /**
     * Configuración para desarrollo con logging detallado
     */
    public static LambdaObservabilityConfig developmentConfig() {
        return builder()
                .includeResponseBody(2048) // Más detalle en desarrollo
                .verboseLogging(true) // Logging completo
                .enableSubSpanEvents(true)
                .enableSubSpanMetrics(true)
                .customResponseHeaders("x-correlation-id", "x-request-id", "x-debug-info")
                .build();
    }

    /**
     * Configuración minimalista para producción de alto volumen
     */
    public static LambdaObservabilityConfig performanceOptimized() {
        return builder()
                .includeResponseBody(0) // Sin body para performance
                .verboseLogging(false)
                .enableSubSpanEvents(false) // Menos overhead
                .enableSubSpanMetrics(true) // Solo métricas básicas
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Set<String> customResponseHeaders = Set.of();
        private boolean includeResponseBody = false;
        private int maxResponseBodyLength = 0;
        private boolean verboseLogging = false;
        private boolean enableSubSpanEvents = true;
        private boolean enableSubSpanMetrics = true;

        public Builder customResponseHeaders(String... headers) {
            this.customResponseHeaders = Set.of(headers);
            return this;
        }

        public Builder includeResponseBody(int maxLength) {
            this.includeResponseBody = true;
            this.maxResponseBodyLength = maxLength;
            return this;
        }

        public Builder verboseLogging(boolean enabled) {
            this.verboseLogging = enabled;
            return this;
        }

        public Builder enableSubSpanEvents(boolean enabled) {
            this.enableSubSpanEvents = enabled;
            return this;
        }

        public Builder enableSubSpanMetrics(boolean enabled) {
            this.enableSubSpanMetrics = enabled;
            return this;
        }

        public Builder disableAllOptionalFeatures() {
            this.includeResponseBody = false;
            this.verboseLogging = false;
            this.enableSubSpanEvents = false;
            this.enableSubSpanMetrics = false;
            return this;
        }

        public LambdaObservabilityConfig build() {
            return new LambdaObservabilityConfig(this);
        }
    }
}
