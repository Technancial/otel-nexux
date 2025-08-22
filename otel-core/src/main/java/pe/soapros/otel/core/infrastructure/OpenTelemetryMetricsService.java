package pe.soapros.otel.core.infrastructure;

import io.opentelemetry.api.metrics.DoubleCounter;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import pe.soapros.otel.core.domain.MetricsService;

import java.util.Map;

public class OpenTelemetryMetricsService implements MetricsService {
    
    private final Meter meter;
    
    public OpenTelemetryMetricsService(Meter meter) {
        this.meter = meter;
    }
    
    @Override
    public void incrementCounter(String name, Map<String, String> attributes) {
        LongCounter counter = meter.counterBuilder(name).build();
        counter.add(1, toAttributes(attributes));
    }
    
    @Override
    public void incrementCounter(String name, long value, Map<String, String> attributes) {
        LongCounter counter = meter.counterBuilder(name).build();
        counter.add(value, toAttributes(attributes));
    }
    
    @Override
    public void recordValue(String name, double value, Map<String, String> attributes) {
        DoubleHistogram histogram = meter.histogramBuilder(name).build();
        histogram.record(value, toAttributes(attributes));
    }
    
    @Override
    public void recordDuration(String name, long durationMs, Map<String, String> attributes) {
        LongHistogram histogram = meter.histogramBuilder(name).ofLongs().build();
        histogram.record(durationMs, toAttributes(attributes));
    }
    
    private io.opentelemetry.api.common.Attributes toAttributes(Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return io.opentelemetry.api.common.Attributes.empty();
        }
        
        var builder = io.opentelemetry.api.common.Attributes.builder();
        attributes.forEach(builder::put);
        return builder.build();
    }
    
    public Meter getMeter() {
        return meter;
    }

}