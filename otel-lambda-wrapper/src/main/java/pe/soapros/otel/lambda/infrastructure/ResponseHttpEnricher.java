package pe.soapros.otel.lambda.infrastructure;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import pe.soapros.otel.core.domain.TraceSpan;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class ResponseHttpEnricher {
    // Headers importantes para tracing
    private static final Set<String> IMPORTANT_RESPONSE_HEADERS = Set.of(
            "content-type", "content-length", "cache-control",
            "x-correlation-id", "x-request-id", "x-trace-id",
            "location", "etag", "last-modified"
    );

    // Headers personalizados a incluir (configurable)
    private final Set<String> customHeadersToInclude;
    private final boolean includeResponseBody;
    private final int maxBodyLength;

    public ResponseHttpEnricher() {
        this(Set.of(), false, 0);
    }

    public ResponseHttpEnricher(Set<String> customHeadersToInclude,
                                boolean includeResponseBody,
                                int maxBodyLength) {
        this.customHeadersToInclude = Set.copyOf(customHeadersToInclude);
        this.includeResponseBody = includeResponseBody;
        this.maxBodyLength = maxBodyLength;
    }

    /**
     * MÉTODO PRINCIPAL: Enriquecer span con respuesta de API Gateway
     */
    public void enrichWithApiGatewayResponse(TraceSpan span, APIGatewayProxyResponseEvent response) {
        if (span == null || response == null) {
            return;
        }

        try {
            // Enriquecer con información básica HTTP
            enrichWithHttpStatus(span, response);

            // Enriquecer con headers de respuesta
            enrichWithResponseHeaders(span, response);

            // Enriquecer con información del body (si está habilitado)
            if (includeResponseBody) {
                enrichWithResponseBody(span, response);
            }

            // Categorizar la respuesta
            categorizeResponse(span, response);

            // Establecer status del span basado en HTTP status
            setSpanStatusFromHttpResponse(span, response);

        } catch (Exception e) {
            // No fallar el span principal por errores de enriquecimiento
            System.err.println("⚠️ Error enriching span with response: " + e.getMessage());
        }
    }

    /**
     * Enriquecer con código de estado HTTP y métricas básicas
     */
    private void enrichWithHttpStatus(TraceSpan span, APIGatewayProxyResponseEvent response) {
        int statusCode = Optional.ofNullable(response.getStatusCode()).orElse(500);

        // Atributos estándar OpenTelemetry para HTTP
        span.addAttribute("http.status_code", String.valueOf(statusCode));
        span.addAttribute("http.response.status_code", String.valueOf(statusCode));

        // Tamaño de la respuesta
        String body = response.getBody();
        if (body != null) {
            span.addAttribute("http.response.body.size", String.valueOf(body.length()));
        }

        // Agregar evento de respuesta generada
        span.addEvent("http.response.generated", Map.of(
                "http.status_code", String.valueOf(statusCode),
                "response.timestamp", String.valueOf(System.currentTimeMillis())
        ));
    }

    /**
     * Enriquecer con headers importantes de la respuesta
     */
    private void enrichWithResponseHeaders(TraceSpan span, APIGatewayProxyResponseEvent response) {
        Map<String, String> headers = response.getHeaders();
        if (headers == null || headers.isEmpty()) {
            return;
        }

        headers.entrySet().stream()
                .filter(entry -> shouldIncludeHeader(entry.getKey()))
                .forEach(entry -> {
                    String headerKey = "http.response.header." + normalizeHeaderName(entry.getKey());
                    span.addAttribute(headerKey, entry.getValue());
                });

        // Agregar conteo total de headers
        span.addAttribute("http.response.headers.count", String.valueOf(headers.size()));
    }

    /**
     * 📄 Enriquecer con información del body de respuesta
     */
    private void enrichWithResponseBody(TraceSpan span, APIGatewayProxyResponseEvent response) {
        String body = response.getBody();
        if (body == null) {
            span.addAttribute("http.response.body.present", "false");
            return;
        }

        span.addAttribute("http.response.body.present", "true");
        span.addAttribute("http.response.body.size", String.valueOf(body.length()));

        // Incluir snippet del body si está habilitado y es pequeño
        if (maxBodyLength > 0 && body.length() <= maxBodyLength) {
            span.addAttribute("http.response.body.snippet", body);
        } else if (maxBodyLength > 0) {
            span.addAttribute("http.response.body.snippet",
                    body.substring(0, Math.min(body.length(), maxBodyLength)) + "...");
        }

        // Detectar tipo de contenido del body
        detectContentType(span, body);
    }

    /**
     * 🏷️ Categorizar la respuesta para análisis
     */
    private void categorizeResponse(TraceSpan span, APIGatewayProxyResponseEvent response) {
        int statusCode = Optional.ofNullable(response.getStatusCode()).orElse(500);

        String category = categorizeStatusCode(statusCode);
        span.addAttribute("http.response.category", category);

        // Información adicional por categoría
        switch (category) {
            case "success" -> span.addAttribute("response.outcome", "success");
            case "client_error" -> {
                span.addAttribute("response.outcome", "client_error");
                span.addAttribute("error.client_side", "true");
            }
            case "server_error" -> {
                span.addAttribute("response.outcome", "server_error");
                span.addAttribute("error.server_side", "true");
            }
            case "redirect" -> span.addAttribute("response.outcome", "redirect");
        }
    }

    /**
     * ✅ Establecer status del span basado en respuesta HTTP
     */
    private void setSpanStatusFromHttpResponse(TraceSpan span, APIGatewayProxyResponseEvent response) {
        int statusCode = Optional.ofNullable(response.getStatusCode()).orElse(500);

        if (statusCode >= 200 && statusCode < 400) {
            // Success y redirect son considerados exitosos
            span.setStatus(true, "HTTP " + statusCode);
        } else if (statusCode >= 400 && statusCode < 500) {
            // Client errors - marcar como error pero con menos severidad
            span.setStatus(false, "Client Error: HTTP " + statusCode);
            span.addAttribute("error.type", "client_error");
        } else {
            // Server errors - marcar como error crítico
            span.setStatus(false, "Server Error: HTTP " + statusCode);
            span.addAttribute("error.type", "server_error");
        }
    }

    // =============== MÉTODOS UTILITARIOS ===============

    /**
     * 🔍 Determinar si incluir un header específico
     */
    private boolean shouldIncludeHeader(String headerName) {
        if (headerName == null) return false;

        String lowerName = headerName.toLowerCase();

        // Incluir headers importantes estándar
        if (IMPORTANT_RESPONSE_HEADERS.contains(lowerName)) {
            return true;
        }

        // Incluir headers personalizados configurados
        if (customHeadersToInclude.contains(lowerName)) {
            return true;
        }

        // Incluir headers que empiecen con x-custom-
        return lowerName.startsWith("x-custom-") || lowerName.startsWith("x-app-");
    }

    /**
     * 🔧 Normalizar nombre de header para atributo
     */
    private String normalizeHeaderName(String headerName) {
        return headerName.toLowerCase().replace("-", "_");
    }

    /**
     * 🏷️ Categorizar código de estado HTTP
     */
    private String categorizeStatusCode(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) return "success";
        if (statusCode >= 300 && statusCode < 400) return "redirect";
        if (statusCode >= 400 && statusCode < 500) return "client_error";
        if (statusCode >= 500) return "server_error";
        return "informational";
    }

    /**
     * 🔍 Detectar tipo de contenido del body
     */
    private void detectContentType(TraceSpan span, String body) {
        if (body.trim().startsWith("{") || body.trim().startsWith("[")) {
            span.addAttribute("http.response.body.format", "json");
        } else if (body.trim().startsWith("<")) {
            span.addAttribute("http.response.body.format", "xml_or_html");
        } else {
            span.addAttribute("http.response.body.format", "text");
        }
    }

    // =============== BUILDERS Y FACTORY METHODS ===============

    /**
     * 🏗️ Builder para configuración personalizada
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 🏭 Factory method para configuración estándar
     */
    public static ResponseHttpEnricher standard() {
        return new ResponseHttpEnricher();
    }

    /**
     * 🏭 Factory method para configuración con body incluido
     */
    public static ResponseHttpEnricher withBodySnippet(int maxBodyLength) {
        return new ResponseHttpEnricher(Set.of(), true, maxBodyLength);
    }

    public static class Builder {
        private Set<String> customHeaders = Set.of();
        private boolean includeBody = false;
        private int maxBodyLength = 0;

        public Builder includeCustomHeaders(String... headers) {
            this.customHeaders = Set.of(headers);
            return this;
        }

        public Builder includeResponseBody(int maxLength) {
            this.includeBody = true;
            this.maxBodyLength = maxLength;
            return this;
        }

        public Builder includeFullResponseBody() {
            this.includeBody = true;
            this.maxBodyLength = Integer.MAX_VALUE;
            return this;
        }

        public ResponseHttpEnricher build() {
            return new ResponseHttpEnricher(customHeaders, includeBody, maxBodyLength);
        }
    }
}
