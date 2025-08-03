package pe.soapros.otel.core.domain;

import java.util.Map;

public interface MetricsService {
    void record(String name, double value, Map<String, String> attributes);
}
