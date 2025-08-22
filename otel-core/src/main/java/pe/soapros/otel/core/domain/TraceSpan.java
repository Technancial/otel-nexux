package pe.soapros.otel.core.domain;

import io.opentelemetry.api.trace.Span;

import java.util.Map;

public interface TraceSpan extends AutoCloseable{
    void addAttribute(String key, String value);
    void addAttributes(Map<String, String> attributes);
    void addEvent(String eventName);
    void addEvent(String eventName, Map<String, String> attributes);
    void recordException(Throwable exception);
    void setStatus(boolean success, String description);
    void end();
    String getTraceId();
    String getSpanId();
    boolean isValid();
}
