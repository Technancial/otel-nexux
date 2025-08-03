package pe.soapros.otel.traces.infrastructure;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import pe.soapros.otel.core.domain.TraceSpan;
import pe.soapros.otel.core.domain.TracerService;

import java.util.Map;

public class OpenTelemetryTracerService implements TracerService {
    private final Tracer tracer;

    public OpenTelemetryTracerService() {
        this.tracer = GlobalOpenTelemetry.getTracer("pe.soapros.otel.traces");
    }

    @Override
    public TraceSpan startSpan(String name, Map<String, String> attributes) {
        Span span = tracer.spanBuilder(name).startSpan();
        return new OpenTelemetryTraceSpan(span);
    }

    @Override
    public void endSpan() {
        Span.current().end();
    }
}
