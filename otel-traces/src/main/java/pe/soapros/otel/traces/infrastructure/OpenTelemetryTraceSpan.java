package pe.soapros.otel.traces.infrastructure;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import pe.soapros.otel.core.domain.TraceSpan;

import java.util.Map;

@Deprecated
public class OpenTelemetryTraceSpan implements TraceSpan {
    private final Span span;

    public OpenTelemetryTraceSpan(Span span) {
        this.span = span;
    }

    @Override
    public void addAttribute(String key, String value) {
        this.span.setAttribute(key, value);
    }

    @Override
    public void addAttributes(Map<String, String> attributes) {
        attributes.forEach(this.span::setAttribute);
    }

    @Override
    public void addEvent(String event) {
        span.addEvent(event);
    }

    @Override
    public void addEvent(String eventName, Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            span.addEvent(eventName);
        } else {
            var otelAttributes = io.opentelemetry.api.common.Attributes.builder();
            attributes.forEach(otelAttributes::put);
            span.addEvent(eventName, otelAttributes.build());
        }
    }

    @Override
    public void recordException(Throwable throwable) {
        span.recordException(throwable);
    }

    @Override
    public void setStatus(boolean success, String description) {
        StatusCode statusCode = success ? StatusCode.OK : StatusCode.ERROR;
        span.setStatus(statusCode, description);
    }

    @Override
    public void end() {
        span.end();
    }

    @Override
    public String getTraceId() {
        return "";
    }

    @Override
    public String getSpanId() {
        return "";
    }

    @Override
    public boolean isValid() {
        return false;
    }

    @Override
    public void close() throws Exception {
        this.end();
    }
}
