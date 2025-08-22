package pe.soapros.otel.core.domain;

import java.util.Map;

public interface TracerService {
    TraceSpan startSpan(String name, Map<String, String> attributes);

    void endSpan();

    TraceSpan startSpan(String spanName);
    TraceSpan startSpan(String spanName, TraceSpan parent);
}
