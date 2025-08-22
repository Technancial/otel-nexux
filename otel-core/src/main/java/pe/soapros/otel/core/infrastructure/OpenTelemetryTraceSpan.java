package pe.soapros.otel.core.infrastructure;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import pe.soapros.otel.core.domain.TraceSpan;

import java.util.Map;

public class OpenTelemetryTraceSpan implements TraceSpan {
    
    private final Span span;
    
    public OpenTelemetryTraceSpan(Span span) {
        this.span = span;
    }
    
    @Override
    public void addAttribute(String key, String value) {
        span.setAttribute(key, value);
    }
    
    @Override
    public void addAttributes(Map<String, String> attributes) {
        if (attributes != null) {
            attributes.forEach(span::setAttribute);
        }
    }
    
    @Override
    public void addEvent(String eventName) {
        span.addEvent(eventName);
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
    public void recordException(Throwable exception) {
        span.recordException(exception);
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
        return span.getSpanContext().getTraceId();
    }
    
    @Override
    public String getSpanId() {
        return span.getSpanContext().getSpanId();
    }
    
    @Override
    public boolean isValid() {
        return span.getSpanContext().isValid();
    }
    
    public Span getSpan() {
        return span;
    }
    
    public Context getContext() {
        return Context.current().with(span);
    }

    @Override
    public void close() throws Exception {

    }
}