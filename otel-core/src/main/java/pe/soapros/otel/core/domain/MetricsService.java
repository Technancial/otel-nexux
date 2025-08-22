package pe.soapros.otel.core.domain;

import java.util.Map;

public interface MetricsService {
    void incrementCounter(String name, Map<String, String> attributes);
    void incrementCounter(String name, long value, Map<String, String> attributes);
    void recordValue(String name, double value, Map<String, String> attributes);
    void recordDuration(String name, long durationMs, Map<String, String> attributes);
}
